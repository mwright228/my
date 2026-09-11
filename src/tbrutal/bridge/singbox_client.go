package bridge

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"syscall"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/process"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/logger"
)

// SingBoxPlatformBridge implements sing-box's platform.Interface to ensure
// all outbound network sockets are protected via Android VpnService.protect(fd)
// preventing recursive VPN routing loops.
type SingBoxPlatformBridge struct{}

func (p *SingBoxPlatformBridge) Initialize(ctx context.Context, router adapter.Router) error {
	return nil
}

func (p *SingBoxPlatformBridge) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (p *SingBoxPlatformBridge) AutoDetectInterfaceControl() control.Func {
	return func(network, address string, conn syscall.RawConn) error {
		return conn.Control(func(fd uintptr) {
			ProtectSocket(int(fd))
		})
	}
}

func (p *SingBoxPlatformBridge) OpenTun(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	return nil, fmt.Errorf("tun provided by Android VpnService")
}

func (p *SingBoxPlatformBridge) UsePlatformDefaultInterfaceMonitor() bool {
	return false
}

func (p *SingBoxPlatformBridge) CreateDefaultInterfaceMonitor(logger logger.Logger) tun.DefaultInterfaceMonitor {
	return nil
}

func (p *SingBoxPlatformBridge) UsePlatformInterfaceGetter() bool {
	return false
}

func (p *SingBoxPlatformBridge) Interfaces() ([]control.Interface, error) {
	return nil, nil
}

func (p *SingBoxPlatformBridge) UnderNetworkExtension() bool {
	return false
}

func (p *SingBoxPlatformBridge) IncludeAllNetworks() bool {
	return false
}

func (p *SingBoxPlatformBridge) ClearDNSCache() {}

func (p *SingBoxPlatformBridge) ReadWIFIState() adapter.WIFIState {
	return adapter.WIFIState{}
}

func (p *SingBoxPlatformBridge) FindProcessInfo(ctx context.Context, network string, source netip.AddrPort, destination netip.AddrPort) (*process.Info, error) {
	return nil, nil
}

type SingBoxLogWriter struct{}

func (w *SingBoxLogWriter) DisableColors() bool {
	return true
}

func (w *SingBoxLogWriter) WriteMessage(level log.Level, message string) {
	tag := "CORE"
	switch level {
	case log.LevelError, log.LevelFatal:
		tag = "ERR"
	case log.LevelWarn:
		tag = "WARN"
	case log.LevelInfo:
		tag = "CORE"
	default:
		tag = "ROUTE"
	}
	LogMsg(tag, message)
}

// SingBoxClient manages the lifecycle of the embedded, production-grade sing-box engine.
type SingBoxClient struct {
	cfg        BridgeConfig
	instance   *box.Box
	cancel     context.CancelFunc
	listenPort int
	mu         sync.Mutex
}

func NewSingBoxClient(cfg BridgeConfig) *SingBoxClient {
	return &SingBoxClient{
		cfg: cfg,
	}
}

// Start constructs the validated sing-box configuration, binds the local mixed proxy,
// and starts the sing-box instance.
func (c *SingBoxClient) Start() (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	// 1. Pick a free local port for mixed SOCKS5/HTTP inbound
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("failed to allocate free port: %w", err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	_ = listener.Close()

	c.listenPort = port

	// 2. Parse host and port
	host, portStr, err := net.SplitHostPort(c.cfg.ServerAddr)
	if err != nil {
		host = c.cfg.ServerAddr
		portStr = "443"
	}
	serverPort, _ := strconv.Atoi(portStr)
	if serverPort <= 0 {
		serverPort = 443
	}

	effectiveSni := c.cfg.SNI
	if effectiveSni == "" {
		effectiveSni = host
	}

	effectiveHostHeader := c.cfg.HostHeader
	if effectiveHostHeader == "" {
		effectiveHostHeader = host
	}

	insecure := c.cfg.InsecureTLS
	if effectiveSni != "" && host != "" && !strings.EqualFold(effectiveSni, host) {
		insecure = true
	}

	path := c.cfg.Path
	if path == "" {
		path = "/vless-ws"
	}
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}

	protoUpper := strings.ToUpper(strings.TrimSpace(c.cfg.Protocol))

	// 3. Build outbound configuration
	var outboundMap map[string]any

	switch {
	case strings.Contains(protoUpper, "HYSTERIA2") || strings.Contains(protoUpper, "HYSTERIA_2"):
		rate := c.cfg.RateMbps
		if rate <= 0 {
			rate = 50
		}
		outboundMap = map[string]any{
			"type":        "hysteria2",
			"tag":         "proxy",
			"server":      host,
			"server_port": serverPort,
			"password":    c.cfg.Token,
			"tls": map[string]any{
				"enabled":     true,
				"server_name": effectiveSni,
				"insecure":    insecure,
			},
			"brutal": map[string]any{
				"enabled":   true,
				"up_mbps":   rate,
				"down_mbps": rate * 2,
			},
		}

	case strings.Contains(protoUpper, "TUIC"):
		outboundMap = map[string]any{
			"type":               "tuic",
			"tag":                "proxy",
			"server":             host,
			"server_port":        serverPort,
			"uuid":               c.cfg.Token,
			"password":           c.cfg.Token,
			"congestion_control": "bbr",
			"tls": map[string]any{
				"enabled":     true,
				"server_name": effectiveSni,
				"insecure":    insecure,
			},
		}

	case strings.Contains(protoUpper, "SHADOWSOCKS") || strings.Contains(protoUpper, "SS"):
		cipher := c.cfg.ObfsKey
		if cipher == "" {
			cipher = "2022-blake3-aes-128-gcm"
		}
		outboundMap = map[string]any{
			"type":        "shadowsocks",
			"tag":         "proxy",
			"server":      host,
			"server_port": serverPort,
			"method":      cipher,
			"password":    c.cfg.Token,
		}

	case strings.Contains(protoUpper, "TROJAN"):
		outboundMap = map[string]any{
			"type":        "trojan",
			"tag":         "proxy",
			"server":      host,
			"server_port": serverPort,
			"password":    c.cfg.Token,
			"tls": map[string]any{
				"enabled":     true,
				"server_name": effectiveSni,
				"insecure":    insecure,
			},
			"transport": map[string]any{
				"type": "ws",
				"path": path,
				"headers": map[string]any{
					"Host": effectiveHostHeader,
				},
			},
		}

	case strings.Contains(protoUpper, "VMESS"):
		outboundMap = map[string]any{
			"type":        "vmess",
			"tag":         "proxy",
			"server":      host,
			"server_port": serverPort,
			"uuid":        c.cfg.Token,
			"alter_id":    0,
			"security":    "auto",
			"transport": map[string]any{
				"type": "ws",
				"path": path,
				"headers": map[string]any{
					"Host": effectiveHostHeader,
				},
			},
		}

	case strings.Contains(protoUpper, "REALITY") || strings.Contains(protoUpper, "VLESS_TCP"):
		// VLESS Reality
		outboundMap = map[string]any{
			"type":        "vless",
			"tag":         "proxy",
			"server":      host,
			"server_port": serverPort,
			"uuid":        c.cfg.Token,
			"flow":        "xtls-rprx-vision",
			"tls": map[string]any{
				"enabled":     true,
				"server_name": effectiveSni,
				"reality": map[string]any{
					"enabled":    true,
					"public_key": "8sV9mQ1xkZ2p8sV9mQ1xkZ2p8sV9mQ1xkZ2p8sV9mQ1x",
					"short_id":   "4a2e",
				},
				"utls": map[string]any{
					"enabled":     true,
					"fingerprint": "chrome",
				},
			},
		}

	default:
		// Default: VLESS WebSocket over TLS/Cloudflare CDN
		outboundMap = map[string]any{
			"type":        "vless",
			"tag":         "proxy",
			"server":      host,
			"server_port": serverPort,
			"uuid":        c.cfg.Token,
			"tls": map[string]any{
				"enabled":     true,
				"server_name": effectiveSni,
				"insecure":    insecure,
				"utls": map[string]any{
					"enabled":     true,
					"fingerprint": "chrome",
				},
			},
			"transport": map[string]any{
				"type": "ws",
				"path": path,
				"headers": map[string]any{
					"Host": effectiveHostHeader,
				},
			},
		}
	}

	// 4. Construct complete sing-box configuration
	dnsServer := c.cfg.DNSServer
	if dnsServer == "" {
		dnsServer = "1.1.1.1"
	}

	fullConfig := map[string]any{
		"log": map[string]any{
			"level":     "info",
			"timestamp": true,
		},
		"dns": map[string]any{
			"servers": []map[string]any{
				{
					"tag":     "remote",
					"address": fmt.Sprintf("https://%s/dns-query", dnsServer),
					"detour":  "proxy",
				},
				{
					"tag":     "local",
					"address": "local",
					"detour":  "direct",
				},
			},
			"rules": []map[string]any{
				{
					"outbound": "any",
					"server":   "local",
				},
			},
			"strategy": "prefer_ipv4",
		},
		"inbounds": []map[string]any{
			{
				"type":        "mixed",
				"tag":         "mixed-in",
				"listen":      "127.0.0.1",
				"listen_port": port,
			},
		},
		"outbounds": []map[string]any{
			outboundMap,
			{
				"type": "direct",
				"tag":  "direct",
			},
			{
				"type": "block",
				"tag":  "block",
			},
		},
		"route": map[string]any{
			"auto_detect_interface": true,
			"rules": []map[string]any{
				{
					"protocol": "dns",
					"outbound": "proxy",
				},
				{
					"ip_cidr": []string{
						"127.0.0.0/8",
						"10.0.0.0/8",
						"172.16.0.0/12",
						"192.168.0.0/16",
					},
					"outbound": "direct",
				},
			},
		},
	}

	configBytes, err := json.Marshal(fullConfig)
	if err != nil {
		return 0, fmt.Errorf("failed to serialize sing-box options: %w", err)
	}

	var options option.Options
	if err := options.UnmarshalJSON(configBytes); err != nil {
		return 0, fmt.Errorf("failed to parse sing-box options: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	c.cancel = cancel

	platformBridge := &SingBoxPlatformBridge{}
	logWriter := &SingBoxLogWriter{}

	boxInstance, err := box.New(box.Options{
		Context:           ctx,
		Options:           options,
		PlatformInterface: platformBridge,
		PlatformLogWriter: logWriter,
	})
	if err != nil {
		cancel()
		return 0, fmt.Errorf("failed to initialize sing-box: %w", err)
	}

	if err := boxInstance.Start(); err != nil {
		cancel()
		return 0, fmt.Errorf("failed to start sing-box: %w", err)
	}

	c.instance = boxInstance
	LogMsg("SUCCESS", fmt.Sprintf("Sing-Box core active on loopback port %d for protocol %s", port, c.cfg.Protocol))
	return port, nil
}

func (c *SingBoxClient) Stop() {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.instance != nil {
		_ = c.instance.Close()
		c.instance = nil
	}
	if c.cancel != nil {
		c.cancel()
		c.cancel = nil
	}
}
