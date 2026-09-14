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

	// rawPath is the path the link actually carried. Shadowsocks WS links put
	// their v2ray-plugin route here (/ss-<user> or /ss22-<user>); raw-SS links
	// carry none and must stay free of any WebSocket transport.
	rawPath := c.cfg.Path
	if rawPath == "" && strings.HasPrefix(c.cfg.CustomPayload, "/") {
		rawPath = c.cfg.CustomPayload
	}
	if rawPath != "" && !strings.HasPrefix(rawPath, "/") {
		rawPath = "/" + rawPath
	}

	// VLESS-family transports need a concrete path; the server's WS inbound
	// answers on /vless-ws.
	path := rawPath
	if path == "" {
		path = "/vless-ws"
	}

	proto := strings.ToUpper(strings.TrimSpace(c.cfg.Protocol))

	// 3. Build outbound configuration
	var outboundsList []map[string]any

	switch proto {
	case "SHADOWTLS_V3":
		stlsPass := c.cfg.ObfsKey
		if stlsPass == "" {
			stlsPass = c.cfg.Token
		}
		ssPass := c.cfg.Token
		cipher := "2022-blake3-aes-256-gcm"
		if c.cfg.CustomPayload != "" && !strings.HasPrefix(c.cfg.CustomPayload, "/") {
			cipher = c.cfg.CustomPayload
		}

		outboundsList = []map[string]any{
			{
				"type":        "shadowtls",
				"tag":         "shadowtls-out",
				"server":      host,
				"server_port": serverPort,
				"version":     3,
				"password":    stlsPass,
				"tls": map[string]any{
					"enabled":     true,
					"server_name": effectiveSni,
					"insecure":    insecure,
				},
			},
			{
				"type":        "shadowsocks",
				"tag":         "proxy",
				"server":      host,
				"server_port": serverPort,
				"method":      cipher,
				"password":    ssPass,
				"detour":      "shadowtls-out",
			},
		}

	case "HYSTERIA_2":
		rate := c.cfg.RateMbps
		if rate <= 0 {
			rate = 50
		}
		outboundsList = []map[string]any{
			{
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
			},
		}

	case "TUIC":
		outboundsList = []map[string]any{
			{
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
			},
		}

	case "SHADOWSOCKS_2022":
		cipher := c.cfg.ObfsKey
		if cipher == "" {
			cipher = "2022-blake3-aes-256-gcm"
		}
		ss := map[string]any{
			"type":        "shadowsocks",
			"tag":         "proxy",
			"server":      host,
			"server_port": serverPort,
			"method":      cipher,
			"password":    c.cfg.Token,
		}
		// A v2ray-plugin path makes this Shadowsocks-over-WebSocket+TLS (the
		// nginx /ss-<user> and /ss22-<user> routes); without one it is the raw
		// Shadowsocks TCP inbound on 443 or 8388+ and carries no transport.
		if rawPath != "" {
			ss["plugin"] = "v2ray-plugin"
			ss["plugin_opts"] = "tls;host=" + effectiveHostHeader + ";path=" + rawPath + ";mux=0"
		}
		outboundsList = []map[string]any{ss}

	case "TROJAN_WS":
		outboundsList = []map[string]any{
			{
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
			},
		}

	case "VMESS_WS":
		outboundsList = []map[string]any{
			{
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
			},
		}

	case "VLESS_WS", "VLESS_TCP", "VLESS_HTTPUPGRADE", "VLESS_XHTTP", "VLESS_GRPC":
		// Every VLESS transport the server renders, selected by exact identity.
		// MUB-X retired Reality, so there is no Reality branch and no placeholder
		// key material in the client any more.
		var transport map[string]any
		switch proto {
		case "VLESS_HTTPUPGRADE":
			transport = map[string]any{
				"type":    "httpupgrade",
				"path":    path,
				"headers": map[string]any{"Host": effectiveHostHeader},
			}
		case "VLESS_XHTTP":
			transport = map[string]any{
				"type":    "xhttp",
				"path":    path,
				"headers": map[string]any{"Host": effectiveHostHeader},
			}
		case "VLESS_GRPC":
			transport = map[string]any{
				"type":         "grpc",
				"service_name": strings.TrimPrefix(path, "/"),
			}
		case "VLESS_TCP":
			transport = map[string]any{"type": "tcp"}
		default:
			transport = map[string]any{
				"type":    "ws",
				"path":    path,
				"headers": map[string]any{"Host": effectiveHostHeader},
			}
		}

		tlsOpts := map[string]any{
			"enabled":     c.cfg.UseTLS,
			"server_name": effectiveSni,
			"insecure":    insecure,
		}
		if c.cfg.UseTLS {
			tlsOpts["utls"] = map[string]any{"enabled": true, "fingerprint": "chrome"}
		}

		outboundsList = []map[string]any{
			{
				"type":        "vless",
				"tag":         "proxy",
				"server":      host,
				"server_port": serverPort,
				"uuid":        c.cfg.Token,
				"tls":         tlsOpts,
				"transport":   transport,
			},
		}

	default:
		return 0, fmt.Errorf("sing-box has no outbound for protocol %q", c.cfg.Protocol)
	}

	// 4. Construct complete sing-box configuration
	fullOutbounds := make([]map[string]any, 0, len(outboundsList)+2)
	fullOutbounds = append(fullOutbounds, outboundsList...)
	fullOutbounds = append(fullOutbounds,
		map[string]any{"type": "direct", "tag": "direct"},
		map[string]any{"type": "block", "tag": "block"},
	)

	fullConfig := map[string]any{
		"log": map[string]any{
			"level":     "debug",
			"output":    "stdout",
			"timestamp": true,
		},
		// DNS: always use local/system resolver. Routing DNS via the proxy
		// outbound before the WebSocket handshake completes causes sing-box to
		// attempt a WS upgrade to the DNS server (e.g. 1.1.1.1:53), which
		// returns 502 and prevents the tunnel from ever starting.
		"dns": map[string]any{
			"servers": []map[string]any{
				{
					"tag":     "local",
					"address": "local",
					"detour":  "direct",
				},
			},
			"strategy":          "prefer_ipv4",
			"independent_cache": true,
		},
		"inbounds": []map[string]any{
			{
				"type":        "mixed",
				"tag":         "mixed-in",
				"listen":      "127.0.0.1",
				"listen_port": port,
			},
		},
		"outbounds": fullOutbounds,
		"route": map[string]any{
			"auto_detect_interface": true,
			"final":                 "proxy",
			"rules": []map[string]any{
				// Private/loopback ranges go direct — never proxy local traffic.
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

	boxInstance, err := box.New(box.Options{
		Context:           ctx,
		Options:           options,
		PlatformInterface: platformBridge,
		PlatformLogWriter: nil,
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
