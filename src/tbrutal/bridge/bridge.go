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

// StartTunnel initializes the selected protocol engine (T-Brutal or ZiVPN UDP) and local SOCKS5 engine.
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

	// 1. ZiVPN UDP Protocol Mode
	if strings.EqualFold(cfg.Protocol, "ZIVPN_UDP") || strings.EqualFold(cfg.Protocol, "ZIVPN") {
		host, _, err := net.SplitHostPort(cfg.ServerAddr)
		if err != nil {
			host = cfg.ServerAddr
		}
		zc := NewZiVPNClient(host, host, cfg.PortHopRange, cfg.ObfsKey, cfg.SocksListenAddr)
		if err := zc.Start(); err != nil {
			return 0, fmt.Errorf("failed to start ZiVPN client: %w", err)
		}
		addr := zc.ListenerAddr()
		tcpAddr, err := net.ResolveTCPAddr("tcp", addr)
		if err != nil {
			zc.Stop()
			return 0, fmt.Errorf("failed to resolve socks address: %w", err)
		}
		socksPort = tcpAddr.Port
		activeZiVPN = zc
		runningStatus.Store(true)
		return socksPort, nil
	}

	// 2. Production-Grade Sing-Box Core (VLESS, Reality, Hysteria2, TUIC, Shadowsocks, Trojan, VMess)
	protoUpper := strings.ToUpper(strings.TrimSpace(cfg.Protocol))
	if strings.Contains(protoUpper, "VLESS") ||
		strings.Contains(protoUpper, "REALITY") ||
		strings.Contains(protoUpper, "HYSTERIA") ||
		strings.Contains(protoUpper, "TUIC") ||
		strings.Contains(protoUpper, "TROJAN") ||
		strings.Contains(protoUpper, "VMESS") ||
		strings.Contains(protoUpper, "SHADOWSOCKS") ||
		strings.Contains(protoUpper, "SS") {

		sbc := NewSingBoxClient(cfg)
		p, err := sbc.Start()
		if err != nil {
			LogMsg("WARN", fmt.Sprintf("Sing-Box start note: %v. Falling back to universal client...", err))
			uc := NewUniversalClient(cfg)
			p2, err2 := uc.Start()
			if err2 != nil {
				return 0, fmt.Errorf("failed to start %s client: %w", cfg.Protocol, err2)
			}
			socksPort = p2
			activeUniversal = uc
		} else {
			socksPort = p
			activeSingBox = sbc
		}
		runningStatus.Store(true)
		return socksPort, nil
	}

	// 2b. SSH / HTTP Injector Mode
	if strings.Contains(protoUpper, "SSH") ||
		strings.Contains(protoUpper, "CUSTOM") ||
		strings.Contains(protoUpper, "INJECTOR") {

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

	// 3. Default: T-Brutal Wire-Speed Paced Mode
	if cfg.PoolSize <= 0 {
		cfg.PoolSize = 1
	}
	if cfg.Path == "" && !cfg.RawMode {
		cfg.Path = "/tbrutal"
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
