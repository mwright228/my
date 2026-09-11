package bridge

import (
	"context"
	"encoding/json"
	"errors"
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

type SingBoxPlatformBridge struct{}
func (p *SingBoxPlatformBridge) Initialize(ctx context.Context, router adapter.Router) error { return nil }
func (p *SingBoxPlatformBridge) UsePlatformAutoDetectInterfaceControl() bool { return true }
func (p *SingBoxPlatformBridge) AutoDetectInterfaceControl() control.Func {
	return func(network,address string,conn syscall.RawConn) error {
		var protectErr error
		if err:=conn.Control(func(fd uintptr){if !ProtectSocket(int(fd)){protectErr=errors.New("failed to protect outbound socket from VPN routing loop")}});err!=nil{return err}
		return protectErr
	}
}
func (p *SingBoxPlatformBridge) OpenTun(options *tun.Options, platformOptions option.TunPlatformOptions)(tun.Tun,error){return nil,fmt.Errorf("tun provided by Android VpnService")}
func (p *SingBoxPlatformBridge) UsePlatformDefaultInterfaceMonitor() bool{return false}
func (p *SingBoxPlatformBridge) CreateDefaultInterfaceMonitor(logger logger.Logger) tun.DefaultInterfaceMonitor{return nil}
func (p *SingBoxPlatformBridge) UsePlatformInterfaceGetter() bool{return false}
func (p *SingBoxPlatformBridge) Interfaces()([]control.Interface,error){return nil,nil}
func (p *SingBoxPlatformBridge) UnderNetworkExtension() bool{return false}
func (p *SingBoxPlatformBridge) IncludeAllNetworks() bool{return false}
func (p *SingBoxPlatformBridge) ClearDNSCache(){}
func (p *SingBoxPlatformBridge) ReadWIFIState() adapter.WIFIState{return adapter.WIFIState{}}
func (p *SingBoxPlatformBridge) FindProcessInfo(ctx context.Context,network string,source,destination netip.AddrPort)(*process.Info,error){return nil,nil}

type SingBoxLogWriter struct{}
func (w *SingBoxLogWriter) DisableColors() bool{return true}
func (w *SingBoxLogWriter) WriteMessage(level log.Level,message string){tag:="CORE";switch level{case log.LevelError,log.LevelFatal:tag="ERR";case log.LevelWarn:tag="WARN";case log.LevelInfo:tag="CORE";default:tag="ROUTE"};LogMsg(tag,message)}

type SingBoxClient struct{cfg BridgeConfig;instance *box.Box;cancel context.CancelFunc;listenPort int;mu sync.Mutex}
func NewSingBoxClient(cfg BridgeConfig)*SingBoxClient{return &SingBoxClient{cfg:cfg}}

func (c *SingBoxClient) Start()(int,error){
	c.mu.Lock();defer c.mu.Unlock()
	listener,err:=net.Listen("tcp","127.0.0.1:0");if err!=nil{return 0,fmt.Errorf("failed to allocate free port: %w",err)}
	port:=listener.Addr().(*net.TCPAddr).Port;_ = listener.Close();c.listenPort=port
	host,portStr,err:=net.SplitHostPort(c.cfg.ServerAddr);if err!=nil{host=c.cfg.ServerAddr;portStr="443"};serverPort,err:=strconv.Atoi(portStr);if err!=nil||serverPort<=0{serverPort=443}
	effectiveSni:=c.cfg.SNI;if effectiveSni==""{effectiveSni=host};effectiveHostHeader:=c.cfg.HostHeader;if effectiveHostHeader==""{effectiveHostHeader=host};insecure:=c.cfg.InsecureTLS
	path:=c.cfg.Path;if path==""&&c.cfg.CustomPayload!=""&&strings.HasPrefix(c.cfg.CustomPayload,"/"){path=c.cfg.CustomPayload};if path==""{path="/vless-ws"};if !strings.HasPrefix(path,"/"){path="/"+path}
	protoUpper:=strings.ToUpper(strings.TrimSpace(c.cfg.Protocol));var outboundsList []map[string]any
	switch {
	case strings.Contains(protoUpper,"SHADOWTLS")||strings.Contains(protoUpper,"STLS"):
		stlsPass:=c.cfg.ObfsKey;if stlsPass==""{stlsPass=c.cfg.Token};cipher:="2022-blake3-aes-256-gcm";if c.cfg.CustomPayload!=""&&!strings.HasPrefix(c.cfg.CustomPayload,"/"){cipher=c.cfg.CustomPayload}
		if stlsPass==""||c.cfg.Token==""{return 0,errors.New("ShadowTLS requires password and Shadowsocks credential")}
		outboundsList=[]map[string]any{{"type":"shadowtls","tag":"shadowtls-out","server":host,"server_port":serverPort,"version":3,"password":stlsPass,"tls":map[string]any{"enabled":true,"server_name":effectiveSni,"insecure":insecure}},{"type":"shadowsocks","tag":"proxy","server":host,"server_port":serverPort,"method":cipher,"password":c.cfg.Token,"detour":"shadowtls-out"}}
	case strings.Contains(protoUpper,"HYSTERIA2")||strings.Contains(protoUpper,"HYSTERIA_2"):
		if c.cfg.Token==""{return 0,errors.New("Hysteria 2 requires a password")};rate:=c.cfg.RateMbps;if rate<=0{rate=50};outboundsList=[]map[string]any{{"type":"hysteria2","tag":"proxy","server":host,"server_port":serverPort,"password":c.cfg.Token,"tls":map[string]any{"enabled":true,"server_name":effectiveSni,"insecure":insecure},"brutal":map[string]any{"enabled":true,"up_mbps":rate,"down_mbps":rate*2}}}
	case strings.Contains(protoUpper,"TUIC"):
		if c.cfg.Token==""||c.cfg.ObfsKey==""{return 0,errors.New("TUIC requires UUID and password")}
		outboundsList=[]map[string]any{{"type":"tuic","tag":"proxy","server":host,"server_port":serverPort,"uuid":c.cfg.Token,"password":c.cfg.ObfsKey,"congestion_control":"bbr","tls":map[string]any{"enabled":true,"server_name":effectiveSni,"insecure":insecure}}}
	case protoUpper=="SHADOWSOCKS"||protoUpper=="SS"||strings.HasPrefix(protoUpper,"SS_")||strings.HasPrefix(protoUpper,"SHADOWSOCKS"):
		cipher:=c.cfg.ObfsKey;if cipher==""{cipher="2022-blake3-aes-128-gcm"};if c.cfg.Token==""{return 0,errors.New("Shadowsocks requires a password")};outboundsList=[]map[string]any{{"type":"shadowsocks","tag":"proxy","server":host,"server_port":serverPort,"method":cipher,"password":c.cfg.Token}}
	case strings.Contains(protoUpper,"TROJAN"):
		if c.cfg.Token==""{return 0,errors.New("Trojan requires a password")};outboundsList=[]map[string]any{{"type":"trojan","tag":"proxy","server":host,"server_port":serverPort,"password":c.cfg.Token,"tls":map[string]any{"enabled":true,"server_name":effectiveSni,"insecure":insecure},"transport":map[string]any{"type":"ws","path":path,"headers":map[string]any{"Host":effectiveHostHeader}}}}
	case strings.Contains(protoUpper,"VMESS"):
		if c.cfg.Token==""{return 0,errors.New("VMess requires a UUID")};outboundsList=[]map[string]any{{"type":"vmess","tag":"proxy","server":host,"server_port":serverPort,"uuid":c.cfg.Token,"alter_id":0,"security":"auto","transport":map[string]any{"type":"ws","path":path,"headers":map[string]any{"Host":effectiveHostHeader}}}}
	case protoUpper=="VLESS_REALITY"||strings.Contains(protoUpper,"REALITY"):
		if c.cfg.Token==""||strings.TrimSpace(c.cfg.ObfsKey)==""||strings.TrimSpace(c.cfg.PortHopRange)==""{return 0,errors.New("VLESS Reality requires UUID public key and short ID")};outboundsList=[]map[string]any{{"type":"vless","tag":"proxy","server":host,"server_port":serverPort,"uuid":c.cfg.Token,"flow":"xtls-rprx-vision","tls":map[string]any{"enabled":true,"server_name":effectiveSni,"insecure":insecure,"reality":map[string]any{"enabled":true,"public_key":strings.TrimSpace(c.cfg.ObfsKey),"short_id":strings.TrimSpace(c.cfg.PortHopRange)},"utls":map[string]any{"enabled":true,"fingerprint":"chrome"}}}}
	case protoUpper=="VLESS_TCP":
		if c.cfg.Token==""{return 0,errors.New("VLESS requires a UUID")};outboundsList=[]map[string]any{{"type":"vless","tag":"proxy","server":host,"server_port":serverPort,"uuid":c.cfg.Token,"tls":map[string]any{"enabled":true,"server_name":effectiveSni,"insecure":insecure,"utls":map[string]any{"enabled":true,"fingerprint":"chrome"}}}}
	case protoUpper=="VLESS_WS":
		if c.cfg.Token==""{return 0,errors.New("VLESS requires a UUID")};outboundsList=[]map[string]any{{"type":"vless","tag":"proxy","server":host,"server_port":serverPort,"uuid":c.cfg.Token,"tls":map[string]any{"enabled":true,"server_name":effectiveSni,"insecure":insecure,"utls":map[string]any{"enabled":true,"fingerprint":"chrome"}},"transport":map[string]any{"type":"ws","path":path,"headers":map[string]any{"Host":effectiveHostHeader}}}}
	default:return 0,fmt.Errorf("unsupported sing-box protocol %q",c.cfg.Protocol)
	}
	fullOutbounds:=make([]map[string]any,0,len(outboundsList)+2);fullOutbounds=append(fullOutbounds,outboundsList...);fullOutbounds=append(fullOutbounds,map[string]any{"type":"direct","tag":"direct"},map[string]any{"type":"block","tag":"block"})
	fullConfig:=map[string]any{"log":map[string]any{"level":"info","output":"stdout","timestamp":true},"dns":map[string]any{"servers":[]map[string]any{{"tag":"local","address":"local","detour":"direct"}},"strategy":"prefer_ipv4","independent_cache":true},"inbounds":[]map[string]any{{"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":port}},"outbounds":fullOutbounds,"route":map[string]any{"auto_detect_interface":true,"final":"proxy","rules":[]map[string]any{{"ip_cidr":[]string{"127.0.0.0/8","10.0.0.0/8","172.16.0.0/12","192.168.0.0/16"},"outbound":"direct"}}}}
	configBytes,err:=json.Marshal(fullConfig);if err!=nil{return 0,fmt.Errorf("failed to serialize sing-box options: %w",err)}
	var options option.Options;if err:=options.UnmarshalJSON(configBytes);err!=nil{return 0,fmt.Errorf("failed to parse sing-box options: %w",err)}
	ctx,cancel:=context.WithCancel(context.Background());c.cancel=cancel;platformBridge:=&SingBoxPlatformBridge{};boxInstance,err:=box.New(box.Options{Context:ctx,Options:options,PlatformInterface:platformBridge,PlatformLogWriter:nil});if err!=nil{cancel();return 0,fmt.Errorf("failed to initialize sing-box: %w",err)}
	if err:=boxInstance.Start();err!=nil{cancel();return 0,fmt.Errorf("failed to start sing-box: %w",err)};c.instance=boxInstance;LogMsg("SUCCESS",fmt.Sprintf("Sing-Box core active on loopback port %d for protocol %s",port,c.cfg.Protocol));return port,nil
}

func (c *SingBoxClient) Stop(){c.mu.Lock();defer c.mu.Unlock();if c.instance!=nil{_=c.instance.Close();c.instance=nil};if c.cancel!=nil{c.cancel();c.cancel=nil}}
