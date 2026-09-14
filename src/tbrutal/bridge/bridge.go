package bridge

import (
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/mwright228/my/src/tbrutal/client"
)

var (
	activeClient    *client.Client
	activeZiVPN     *ZiVPNClient
	activeUniversal *UniversalClient
	activeSingBox   *SingBoxClient
	activeMu        sync.Mutex
	runningStatus   atomic.Bool
	socksPort       int

	protectHook func(fd int) bool
	logHook     func(tag, msg string)

	TotalRxBytes atomic.Uint64
	TotalTxBytes atomic.Uint64
	ActiveConns  atomic.Int32
)

// SetSocketProtector registers the Android VpnService socket protection callback.
func SetSocketProtector(fn func(fd int) bool) {
	protectHook = fn
	client.SetSocketProtector(fn)
}

// ProtectSocket protects an outbound socket descriptor from VPN routing loop.
func ProtectSocket(fd int) bool {
	if protectHook != nil {
		return protectHook(fd)
	}
	return false
}

// SetLogger registers a native log callback.
func SetLogger(fn func(tag, msg string)) {
	logHook = fn
}

// LogMsg dispatches a log message to the registered logger.
func LogMsg(tag, msg string) {
	if logHook != nil {
		logHook(tag, msg)
	}
}

// GetTelemetry returns atomically tracked RX bytes, TX bytes, and active connection count.
func GetTelemetry() (uint64, uint64, int32) {
	return TotalRxBytes.Load(), TotalTxBytes.Load(), ActiveConns.Load()
}

type BridgeConfig struct {
	Protocol        string
	ServerAddr      string
	SNI             string
	HostHeader      string
	Path            string
	Token           string
	PoolSize        int
	RateMbps        int
	UseTLS          bool
	InsecureTLS     bool
	RawMode         bool
	ObfsKey         string
	PortHopRange    string
	SocksListenAddr string
	DNSServer       string
	CustomPayload   string
}

type BugHostResult struct {
	StatusCode int
	LatencyMs  int64
	CertCN     string
	CertSANs   []string
	ErrorMsg   string
}

// StartTunnel brings up the engine that owns cfg.Protocol (T-Brutal, ZiVPN UDP,
// the SSH/HTTP injector, or the sing-box family) plus its local SOCKS5 listener,
// and returns the loopback port that listener bound.
func StartTunnel(cfg BridgeConfig) (int, error) {
	activeMu.Lock()
	defer activeMu.Unlock()

	if runningStatus.Load() {
		return socksPort, errors.New("mubx tunnel is already running")
	}

	client.SetSocketProtector(ProtectSocket)
	LogMsg("TUNNEL", fmt.Sprintf("Initializing %s tunnel to %s...", cfg.Protocol, cfg.ServerAddr))

	if cfg.SocksListenAddr == "" {
		cfg.SocksListenAddr = "127.0.0.1:0" // Dynamic ephemeral port
	}

	engine, err := engineFor(cfg.Protocol)
	if err != nil {
		return 0, err
	}
	switch engine {
	case engineZiVPN:
		return startZiVPN(cfg)
	case engineUniversal:
		return startUniversal(cfg)
	case engineSingBox:
		return startSingBox(cfg)
	}

	// T-Brutal Wire-Speed Paced Mode
	if cfg.PoolSize <= 0 {
		cfg.PoolSize = 1
	}
	if cfg.Path == "" && !cfg.RawMode {
		if cfg.CustomPayload != "" && strings.HasPrefix(cfg.CustomPayload, "/") {
			cfg.Path = cfg.CustomPayload
		} else {
			cfg.Path = "/tbrutal"
		}
	}

	c := client.NewClient(client.Config{
		ServerAddr:     cfg.ServerAddr,
		SNI:            cfg.SNI,
		HostHeader:     cfg.HostHeader,
		Path:           cfg.Path,
		Token:          cfg.Token,
		LocalSocksAddr: cfg.SocksListenAddr,
		NumConns:       cfg.PoolSize,
		RateMbps:       cfg.RateMbps,
		UseTLS:         cfg.UseTLS,
		InsecureTLS:    cfg.InsecureTLS,
		RawMode:        cfg.RawMode,
	})

	if err := c.Start(); err != nil {
		return 0, fmt.Errorf("failed to start T-Brutal client: %w", err)
	}

	addr := c.ListenerAddr()
	tcpAddr, err := net.ResolveTCPAddr("tcp", addr)
	if err != nil {
		c.Stop()
		return 0, fmt.Errorf("failed to resolve socks address: %w", err)
	}

	socksPort = tcpAddr.Port
	activeClient = c
	runningStatus.Store(true)
	return socksPort, nil
}

// engineKind identifies the client engine that speaks a protocol.
type engineKind int

const (
	engineTBrutal engineKind = iota
	engineZiVPN
	engineUniversal
	engineSingBox
)

// engineFor maps a protocol identity to the engine that owns it. It is the
// single place that decides which engine speaks a protocol.
//
// This used to be a chain of strings.Contains checks, which routed
// "SSH_PAYLOAD" into the "SS" branch (it contains the substring "SS") and
// built a VLESS outbound instead of an SSH tunnel, while "AMNEZIA_WG" matched
// no branch at all and silently came up as T-Brutal. The match is exact now,
// and an unrecognized identity is rejected so the wrong engine can never
// manufacture a tunnel for a protocol it does not speak.
func engineFor(protocol string) (engineKind, error) {
	switch strings.ToUpper(strings.TrimSpace(protocol)) {
	case "ZIVPN_UDP", "ZIVPN":
		return engineZiVPN, nil
	case "SSH_PAYLOAD", "SSH", "SSH_INJECTOR", "CUSTOM", "INJECTOR",
		"CHAMELEON_HTTP", "CHAMELEON":
		return engineUniversal, nil
	case "VLESS_WS", "VLESS_TCP", "VLESS_HTTPUPGRADE", "VLESS_XHTTP", "VLESS_GRPC",
		"VMESS_WS", "TROJAN_WS", "TUIC", "HYSTERIA_2", "SHADOWSOCKS_2022", "SHADOWTLS_V3":
		return engineSingBox, nil
	case "T_BRUTAL", "TBRUTAL", "":
		// Empty protocol is the legacy T-Brutal link.
		return engineTBrutal, nil
	default:
		return 0, fmt.Errorf("unsupported protocol %q: no client engine is available", protocol)
	}
}

// startZiVPN brings up the ZiVPN UDP engine and its local SOCKS5 listener.
func startZiVPN(cfg BridgeConfig) (int, error) {
	host, _, err := net.SplitHostPort(cfg.ServerAddr)
	if err != nil {
		host = cfg.ServerAddr
	}
	zc := NewZiVPNClient(host, host, cfg.PortHopRange, cfg.ObfsKey, cfg.SocksListenAddr)
	if err := zc.Start(); err != nil {
		return 0, fmt.Errorf("failed to start ZiVPN client: %w", err)
	}
	tcpAddr, err := net.ResolveTCPAddr("tcp", zc.ListenerAddr())
	if err != nil {
		zc.Stop()
		return 0, fmt.Errorf("failed to resolve socks address: %w", err)
	}
	socksPort = tcpAddr.Port
	activeZiVPN = zc
	runningStatus.Store(true)
	return socksPort, nil
}

// startUniversal brings up the SSH / HTTP-injector engine and its local
// SOCKS5 listener.
func startUniversal(cfg BridgeConfig) (int, error) {
	uc := NewUniversalClient(cfg)
	p, err := uc.Start()
	if err != nil {
		return 0, fmt.Errorf("failed to start %s injector client: %w", cfg.Protocol, err)
	}
	socksPort = p
	activeUniversal = uc
	runningStatus.Store(true)
	return socksPort, nil
}

// startSingBox brings up the embedded sing-box engine, falling back to the
// universal client when sing-box rejects the configuration.
func startSingBox(cfg BridgeConfig) (int, error) {
	sbc := NewSingBoxClient(cfg)
	p, err := sbc.Start()
	if err == nil {
		socksPort = p
		activeSingBox = sbc
		runningStatus.Store(true)
		return socksPort, nil
	}

	LogMsg("WARN", fmt.Sprintf("Sing-Box start note: %v. Falling back to universal client...", err))
	uc := NewUniversalClient(cfg)
	p2, err2 := uc.Start()
	if err2 != nil {
		return 0, fmt.Errorf("failed to start %s client: %w (sing-box: %v)", cfg.Protocol, err2, err)
	}
	socksPort = p2
	activeUniversal = uc
	runningStatus.Store(true)
	return socksPort, nil
}

// StopTunnel cleanly tears down all connections, listeners, and TUN router.
func StopTunnel() {
	activeMu.Lock()
	defer activeMu.Unlock()

	StopTunRouter()

	if !runningStatus.Load() {
		return
	}

	if activeSingBox != nil {
		activeSingBox.Stop()
		activeSingBox = nil
	}
	if activeUniversal != nil {
		activeUniversal.Stop()
		activeUniversal = nil
	}
	if activeClient != nil {
		activeClient.Stop()
		activeClient = nil
	}
	if activeZiVPN != nil {
		activeZiVPN.Stop()
		activeZiVPN = nil
	}
	runningStatus.Store(false)
	socksPort = 0
}

// ProbeBugHost evaluates prospective carrier bug-hosts for latency, HTTP status, and SSL certificates.
func ProbeBugHost(targetURL string, sni string, timeoutMs int) BugHostResult {
	start := time.Now()
	timeout := time.Duration(timeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = 4 * time.Second
	}

	result := BugHostResult{}

	isHTTPS := strings.HasPrefix(strings.ToLower(targetURL), "https://")
	tlsConfig := &tls.Config{
		InsecureSkipVerify: true,
	}
	if sni != "" {
		tlsConfig.ServerName = sni
	}

	transport := &http.Transport{
		TLSClientConfig: tlsConfig,
		DialContext: (&net.Dialer{
			Timeout: timeout,
		}).DialContext,
		ResponseHeaderTimeout: timeout,
	}

	httpClient := &http.Client{
		Transport: transport,
		Timeout:   timeout,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse // Do not follow redirects to see raw 301/302
		},
	}

	req, err := http.NewRequest("GET", targetURL, nil)
	if err != nil {
		result.ErrorMsg = err.Error()
		return result
	}
	req.Header.Set("User-Agent", "MUB-X-Probe/2.5")

	resp, err := httpClient.Do(req)
	result.LatencyMs = time.Since(start).Milliseconds()

	if err != nil {
		result.ErrorMsg = err.Error()
		return result
	}
	defer resp.Body.Close()

	result.StatusCode = resp.StatusCode

	if isHTTPS && resp.TLS != nil && len(resp.TLS.PeerCertificates) > 0 {
		cert := resp.TLS.PeerCertificates[0]
		result.CertCN = cert.Subject.CommonName
		result.CertSANs = cert.DNSNames
	}

	return result
}
