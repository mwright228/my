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
	activeClient *client.Client
	activeZiVPN *ZiVPNClient
	activeUniversal *UniversalClient
	activeSingBox *SingBoxClient
	activeMu sync.Mutex
	runningStatus atomic.Bool
	socksPort int
	protectHook atomic.Value
	logHook atomic.Value
	TotalRxBytes atomic.Uint64
	TotalTxBytes atomic.Uint64
	ActiveConns atomic.Int32
)

func SetSocketProtector(fn func(fd int) bool) { protectHook.Store(fn); client.SetSocketProtector(fn) }
func ProtectSocket(fd int) bool { v := protectHook.Load(); if v == nil { return false }; return v.(func(int) bool)(fd) }
func SetLogger(fn func(tag,msg string)) { logHook.Store(fn) }
func LogMsg(tag,msg string) { v:=logHook.Load(); if v!=nil { v.(func(string,string))(tag,msg) } }
func GetTelemetry()(uint64,uint64,int32){return TotalRxBytes.Load(),TotalTxBytes.Load(),ActiveConns.Load()}

type BridgeConfig struct {
	Protocol string
	ServerAddr string
	SNI string
	HostHeader string
	Path string
	Token string
	PoolSize int
	RateMbps int
	UseTLS bool
	InsecureTLS bool
	RawMode bool
	ObfsKey string
	PortHopRange string
	SocksListenAddr string
	DNSServer string
	CustomPayload string
	SSHHostKeySHA256 string
}

type BugHostResult struct { StatusCode int; LatencyMs int64; CertCN string; CertSANs []string; ErrorMsg string }

func usesSingBox(proto string) bool {
	p:=strings.ToUpper(strings.TrimSpace(proto))
	return strings.Contains(p,"VLESS")||strings.Contains(p,"REALITY")||strings.Contains(p,"HYSTERIA")||strings.Contains(p,"TUIC")||strings.Contains(p,"TROJAN")||strings.Contains(p,"VMESS")||p=="SHADOWSOCKS"||strings.HasPrefix(p,"SHADOWSOCKS_")||p=="SS"||strings.HasPrefix(p,"SS_")||strings.Contains(p,"SHADOWTLS")||strings.Contains(p,"STLS")
}

func StartTunnel(cfg BridgeConfig)(int,error){
	activeMu.Lock();defer activeMu.Unlock()
	if runningStatus.Load(){return socksPort,errors.New("mubx tunnel is already running")}
	client.SetSocketProtector(ProtectSocket)
	if cfg.SocksListenAddr==""{cfg.SocksListenAddr="127.0.0.1:0"}
	protoUpper:=strings.ToUpper(strings.TrimSpace(cfg.Protocol))
	if strings.EqualFold(cfg.Protocol,"ZIVPN_UDP")||strings.EqualFold(cfg.Protocol,"ZIVPN"){
		host,_,err:=net.SplitHostPort(cfg.ServerAddr);if err!=nil{host=cfg.ServerAddr};zc:=NewZiVPNClient(host,host,cfg.PortHopRange,cfg.ObfsKey,cfg.SocksListenAddr);if err:=zc.Start();err!=nil{return 0,fmt.Errorf("failed to start ZiVPN client: %w",err)};tcpAddr,err:=net.ResolveTCPAddr("tcp",zc.ListenerAddr());if err!=nil{zc.Stop();return 0,fmt.Errorf("failed to resolve socks address: %w",err)};socksPort=tcpAddr.Port;activeZiVPN=zc;runningStatus.Store(true);return socksPort,nil
	}
	if usesSingBox(cfg.Protocol){
		sbc:=NewSingBoxClient(cfg);p,err:=sbc.Start();if err!=nil{return 0,fmt.Errorf("failed to start %s client: %w",cfg.Protocol,err)};socksPort=p;activeSingBox=sbc;runningStatus.Store(true);return socksPort,nil
	}
	if strings.Contains(protoUpper,"SSH")||strings.Contains(protoUpper,"CUSTOM")||strings.Contains(protoUpper,"INJECTOR"){
		uc:=NewUniversalClient(cfg);p,err:=uc.Start();if err!=nil{return 0,fmt.Errorf("failed to start %s injector client: %w",cfg.Protocol,err)};socksPort=p;activeUniversal=uc;runningStatus.Store(true);return socksPort,nil
	}
	if protoUpper!="T_BRUTAL"{return 0,fmt.Errorf("unsupported tunnel protocol %q",cfg.Protocol)}
	if cfg.PoolSize<=0{cfg.PoolSize=1};if cfg.Path==""&&!cfg.RawMode{if cfg.CustomPayload!=""&&strings.HasPrefix(cfg.CustomPayload,"/"){cfg.Path=cfg.CustomPayload}else{cfg.Path="/tbrutal"}}
	c:=client.NewClient(client.Config{ServerAddr:cfg.ServerAddr,SNI:cfg.SNI,HostHeader:cfg.HostHeader,Path:cfg.Path,Token:cfg.Token,LocalSocksAddr:cfg.SocksListenAddr,NumConns:cfg.PoolSize,RateMbps:cfg.RateMbps,UseTLS:cfg.UseTLS,InsecureTLS:cfg.InsecureTLS,RawMode:cfg.RawMode})
	if err:=c.Start();err!=nil{return 0,fmt.Errorf("failed to start T-Brutal client: %w",err)};tcpAddr,err:=net.ResolveTCPAddr("tcp",c.ListenerAddr());if err!=nil{c.Stop();return 0,fmt.Errorf("failed to resolve socks address: %w",err)};socksPort=tcpAddr.Port;activeClient=c;runningStatus.Store(true);return socksPort,nil
}

func StopTunnel(){activeMu.Lock();defer activeMu.Unlock();StopTunRouter();if !runningStatus.Load(){return};if activeSingBox!=nil{activeSingBox.Stop();activeSingBox=nil};if activeUniversal!=nil{activeUniversal.Stop();activeUniversal=nil};if activeClient!=nil{activeClient.Stop();activeClient=nil};if activeZiVPN!=nil{activeZiVPN.Stop();activeZiVPN=nil};runningStatus.Store(false);socksPort=0}

func ProbeBugHost(targetURL,sni string,timeoutMs int)BugHostResult{start:=time.Now();timeout:=time.Duration(timeoutMs)*time.Millisecond;if timeout<=0{timeout=4*time.Second};result:=BugHostResult{};isHTTPS:=strings.HasPrefix(strings.ToLower(targetURL),"https://");tlsConfig:=&tls.Config{InsecureSkipVerify:true};if sni!=""{tlsConfig.ServerName=sni};transport:=&http.Transport{TLSClientConfig:tlsConfig,DialContext:(&net.Dialer{Timeout:timeout}).DialContext,ResponseHeaderTimeout:timeout};httpClient:=&http.Client{Transport:transport,Timeout:timeout,CheckRedirect:func(req *http.Request,via []*http.Request)error{return http.ErrUseLastResponse}};req,err:=http.NewRequest("GET",targetURL,nil);if err!=nil{result.ErrorMsg=err.Error();return result};req.Header.Set("User-Agent","MUB-X-Probe/2.5");resp,err:=httpClient.Do(req);result.LatencyMs=time.Since(start).Milliseconds();if err!=nil{result.ErrorMsg=err.Error();return result};defer resp.Body.Close();result.StatusCode=resp.StatusCode;if isHTTPS&&resp.TLS!=nil&&len(resp.TLS.PeerCertificates)>0{cert:=resp.TLS.PeerCertificates[0];result.CertCN=cert.Subject.CommonName;result.CertSANs=cert.DNSNames};return result}
