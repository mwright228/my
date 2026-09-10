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
	activeClient  *client.Client
	activeMu      sync.Mutex
	runningStatus atomic.Bool
	socksPort     int
)

type BridgeConfig struct {
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
	SocksListenAddr string
}

type BugHostResult struct {
	StatusCode int
	LatencyMs  int64
	CertCN     string
	CertSANs   []string
	ErrorMsg   string
}

// StartTunnel initializes the T-Brutal rate pacer, connection pool, and local SOCKS5 engine.
func StartTunnel(cfg BridgeConfig) (int, error) {
	activeMu.Lock()
	defer activeMu.Unlock()

	if runningStatus.Load() {
		return socksPort, errors.New("t-brutal tunnel is already running")
	}

	if cfg.PoolSize <= 0 {
		cfg.PoolSize = 1
	}
	if cfg.Path == "" && !cfg.RawMode {
		cfg.Path = "/tbrutal"
	}
	if cfg.SocksListenAddr == "" {
		cfg.SocksListenAddr = "127.0.0.1:0" // Dynamic ephemeral port
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

// StopTunnel cleanly tears down all connections and listeners.
func StopTunnel() {
	activeMu.Lock()
	defer activeMu.Unlock()

	if !runningStatus.Load() {
		return
	}

	if activeClient != nil {
		activeClient.Stop()
		activeClient = nil
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
