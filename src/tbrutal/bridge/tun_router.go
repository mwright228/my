package bridge

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/sys/unix"
)

var (
	activeRouter   *TunRouter
	activeRouterMu sync.Mutex
)

type TunRouter struct {
	tunFile    *os.File
	tunMu      sync.RWMutex
	socksAddr  string
	dnsServer  string
	running    atomic.Bool
	stopChan   chan struct{}
	wg         sync.WaitGroup
	tunWriteMu sync.Mutex
	packetChan chan []byte
	dnsSem     chan struct{}
	sessions   sync.Map
}

type tcpKey struct { srcIP string; srcPort uint16; dstIP string; dstPort uint16 }
func (k tcpKey) String() string { return fmt.Sprintf("%s:%d->%s:%d", k.srcIP, k.srcPort, k.dstIP, k.dstPort) }

type TcpSession struct {
	key        tcpKey
	socksConn  net.Conn
	clientSeq  uint32
	serverSeq  uint32
	closed     atomic.Bool
	lastActive atomic.Int64
	mu         sync.Mutex
	ready      bool
	pending    [][]byte
}

func StartTunRouter(fd int, socksPort int) error { return StartTunRouterWithDNS(fd, socksPort, "1.1.1.1:53") }

func StartTunRouterWithDNS(fd int, socksPort int, dnsServer string) error {
	activeRouterMu.Lock(); defer activeRouterMu.Unlock()
	if activeRouter != nil && activeRouter.running.Load() { return errors.New("tun router is already active") }
	if fd < 0 { return errors.New("invalid tun file descriptor") }
	dupFD, err := unix.Dup(fd)
	if err != nil { return fmt.Errorf("duplicate tun file descriptor: %w", err) }
	tunFile := os.NewFile(uintptr(dupFD), "mubx-tun")
	if tunFile == nil { _ = unix.Close(dupFD); return errors.New("failed to wrap duplicated tun file descriptor") }
	if dnsServer == "" { dnsServer = "1.1.1.1:53" }
	if !hasPort(dnsServer) { dnsServer = net.JoinHostPort(dnsServer, "53") }
	r := &TunRouter{tunFile: tunFile, socksAddr: fmt.Sprintf("127.0.0.1:%d", socksPort), dnsServer: dnsServer, stopChan: make(chan struct{}), packetChan: make(chan []byte, 1024), dnsSem: make(chan struct{}, 32)}
	r.running.Store(true)
	r.wg.Add(2); go r.readLoop(); go r.reaperLoop()
	for i := 0; i < 8; i++ { r.wg.Add(1); go r.workerLoop() }
	activeRouter = r
	LogMsg("ROUTER", fmt.Sprintf("TunRouter started (SOCKS: %s, DNS: %s, Workers: 8)", r.socksAddr, dnsServer))
	return nil
}

func hasPort(s string) bool { _, _, err := net.SplitHostPort(s); return err == nil }

func (r *TunRouter) getTunFile() *os.File { r.tunMu.RLock(); defer r.tunMu.RUnlock(); return r.tunFile }
func (r *TunRouter) closeOwnedTun() { r.tunMu.Lock(); defer r.tunMu.Unlock(); if r.tunFile != nil { _ = r.tunFile.Close(); r.tunFile = nil } }

func (r *TunRouter) closeSession(key any, sess *TcpSession) {
	if !sess.closed.CompareAndSwap(false, true) { return }
	sess.mu.Lock(); if sess.socksConn != nil { _ = sess.socksConn.Close() }; sess.mu.Unlock()
	if _, loaded := r.sessions.LoadAndDelete(key); loaded { ActiveConns.Add(-1) }
}

func StopTunRouter() {
	activeRouterMu.Lock(); defer activeRouterMu.Unlock()
	if activeRouter == nil || !activeRouter.running.Load() { return }
	r := activeRouter; r.running.Store(false); close(r.stopChan)
	r.sessions.Range(func(key, val any) bool { if sess, ok := val.(*TcpSession); ok { r.closeSession(key, sess) }; return true })
	r.closeOwnedTun()
	done := make(chan struct{}); go func() { r.wg.Wait(); close(done) }()
	select { case <-done: case <-time.After(400 * time.Millisecond): LogMsg("ROUTER", "TunRouter wait timed out after closing owned descriptor") }
	activeRouter = nil; LogMsg("ROUTER", "TunRouter stopped")
}

func (r *TunRouter) writeTun(pkt []byte) (int, error) {
	if !r.running.Load() { return 0, io.ErrClosedPipe }
	f := r.getTunFile(); if f == nil { return 0, io.ErrClosedPipe }
	r.tunWriteMu.Lock(); defer r.tunWriteMu.Unlock()
	if !r.running.Load() { return 0, io.ErrClosedPipe }
	n, err := f.Write(pkt); if err == nil && n > 0 { TotalTxBytes.Add(uint64(n)) }; return n, err
}

func (r *TunRouter) reaperLoop() {
	defer r.wg.Done(); ticker := time.NewTicker(20 * time.Second); defer ticker.Stop()
	for { select {
	case <-r.stopChan: return
	case <-ticker.C:
		now := time.Now().UnixNano()
		r.sessions.Range(func(key, val any) bool { if sess, ok := val.(*TcpSession); ok { last := sess.lastActive.Load(); if sess.closed.Load() || (last > 0 && now-last > int64(90*time.Second)) { r.closeSession(key, sess) } }; return true })
	} }
}

func (r *TunRouter) readLoop() {
	defer r.wg.Done(); buf := make([]byte, 65535)
	for { select { case <-r.stopChan: return; default: }
		f := r.getTunFile(); if f == nil { return }
		n, err := f.Read(buf); if err != nil { return }
		if n < 20 || buf[0]>>4 != 4 { continue }; TotalRxBytes.Add(uint64(n)); pkt := make([]byte, n); copy(pkt, buf[:n])
		select { case r.packetChan <- pkt: default: }
	}
}

func (r *TunRouter) workerLoop() { defer r.wg.Done(); for { select { case <-r.stopChan: return; case pkt, ok := <-r.packetChan: if !ok { return }; r.handlePacket(pkt) } } }

func (r *TunRouter) handlePacket(packet []byte) {
	if len(packet) < 20 || packet[0]>>4 != 4 { return }; ihl := int(packet[0]&0x0F)*4; if ihl < 20 || len(packet) < ihl { return }
	srcIP, dstIP := net.IP(packet[12:16]), net.IP(packet[16:20]); switch packet[9] { case 1: r.handleICMP(packet, ihl, srcIP, dstIP); case 17: r.handleUDP(packet, ihl, srcIP, dstIP); case 6: r.handleTCP(packet, ihl, srcIP, dstIP) }
}

func (r *TunRouter) handleICMP(packet []byte, ihl int, srcIP, dstIP net.IP) {
	if len(packet) < ihl+8 || packet[ihl] != 8 { return }; reply := make([]byte, len(packet)); copy(reply, packet); copy(reply[12:16], dstIP); copy(reply[16:20], srcIP); reply[ihl], reply[ihl+1], reply[ihl+2], reply[ihl+3] = 0,0,0,0; binary.BigEndian.PutUint16(reply[ihl+2:ihl+4], computeChecksum(reply[ihl:])); reply[10], reply[11] = 0,0; binary.BigEndian.PutUint16(reply[10:12], computeChecksum(reply[:ihl])); _, _ = r.writeTun(reply)
}

func (r *TunRouter) handleUDP(packet []byte, ihl int, srcIP, dstIP net.IP) {
	if len(packet) < ihl+8 { return }; h := packet[ihl:ihl+8]; srcPort,dstPort := binary.BigEndian.Uint16(h[:2]),binary.BigEndian.Uint16(h[2:4]); udpLen:=binary.BigEndian.Uint16(h[4:6]); if udpLen<8 || len(packet)<ihl+int(udpLen) || dstPort!=53 { return }
	payload:=append([]byte(nil),packet[ihl+8:ihl+int(udpLen)]...); select { case r.dnsSem <- struct{}{}: default: return }
	go func(){ defer func(){<-r.dnsSem}(); resp,err:=r.resolveDNS(payload); if err!=nil || len(resp)==0{return}; _,_=r.writeTun(craftUDPPacket(dstIP,srcIP,dstPort,srcPort,resp)) }()
}

func (r *TunRouter) resolveDNS(query []byte) ([]byte,error) { target:=r.dnsServer; if target==""{target="1.1.1.1:53"}; if !hasPort(target){target=net.JoinHostPort(target,"53")}; if resp,err:=queryProtectedUDP(target,query);err==nil&&len(resp)>0{return resp,nil}; if !strings.HasPrefix(target,"1.1.1.1"){if resp,err:=queryProtectedUDP("1.1.1.1:53",query);err==nil&&len(resp)>0{return resp,nil}}; if !strings.HasPrefix(target,"8.8.8.8"){if resp,err:=queryProtectedUDP("8.8.8.8:53",query);err==nil&&len(resp)>0{return resp,nil}}; if r.socksAddr!=""{if resp,err:=r.queryDNSOverSocks(query);err==nil&&len(resp)>0{return resp,nil}}; return nil,errors.New("dns resolution failed on all upstream resolvers") }

func queryProtectedUDP(dnsServer string,query []byte)([]byte,error){ rAddr,err:=net.ResolveUDPAddr("udp4",dnsServer); if err!=nil{return nil,err}; conn,err:=net.ListenUDP("udp4",nil); if err!=nil{return nil,err}; defer conn.Close(); raw,err:=conn.SyscallConn(); if err!=nil{return nil,err}; var protectErr error; if err=raw.Control(func(fd uintptr){if !ProtectSocket(int(fd)){protectErr=errors.New("failed to protect DNS UDP socket")}});err!=nil{return nil,err}; if protectErr!=nil{return nil,protectErr}; _=conn.SetDeadline(time.Now().Add(2500*time.Millisecond)); if _,err:=conn.WriteToUDP(query,rAddr);err!=nil{return nil,err}; buf:=make([]byte,4096); n,_,err:=conn.ReadFromUDP(buf);if err!=nil||n==0{return nil,err};return buf[:n],nil }

func (r *TunRouter) queryDNSOverSocks(query []byte)([]byte,error){ conn,err:=net.DialTimeout("tcp",r.socksAddr,2*time.Second);if err!=nil{return nil,err};defer conn.Close();_=conn.SetDeadline(time.Now().Add(3*time.Second));if _,err:=conn.Write([]byte{5,1,0});err!=nil{return nil,err};var ar [2]byte;if _,err:=io.ReadFull(conn,ar[:]);err!=nil||ar[1]!=0{return nil,errors.New("socks auth failed")}; host,_,err:=net.SplitHostPort(r.dnsServer);if err!=nil||host==""{host="1.1.1.1"};ip:=net.ParseIP(host).To4();if ip==nil{ip=net.ParseIP("1.1.1.1").To4()};req:=[]byte{5,1,0,1};req=append(req,ip...);req=append(req,0,53);if _,err:=conn.Write(req);err!=nil{return nil,err};var rr [10]byte;if _,err:=io.ReadFull(conn,rr[:]);err!=nil||rr[1]!=0{return nil,errors.New("socks connect to dns failed")};q:=make([]byte,2+len(query));binary.BigEndian.PutUint16(q[:2],uint16(len(query)));copy(q[2:],query);if _,err:=conn.Write(q);err!=nil{return nil,err};var lb [2]byte;if _,err:=io.ReadFull(conn,lb[:]);err!=nil{return nil,err};n:=binary.BigEndian.Uint16(lb[:]);if n==0||n>4096{return nil,errors.New("invalid dns response length")};out:=make([]byte,n);if _,err:=io.ReadFull(conn,out);err!=nil{return nil,err};return out,nil }

func (r *TunRouter) handleTCP(packet []byte, ihl int, srcIP,dstIP net.IP){if len(packet)<ihl+20{return};h:=packet[ihl:];sp,dp:=binary.BigEndian.Uint16(h[:2]),binary.BigEndian.Uint16(h[2:4]);seq:=binary.BigEndian.Uint32(h[4:8]);off:=int(h[12]>>4)*4;flags:=h[13];if off<20||len(h)<off{return};payload:=h[off:];key:=tcpKey{srcIP:srcIP.String(),srcPort:sp,dstIP:dstIP.String(),dstPort:dp};v,ok:=r.sessions.Load(key);if !ok&&flags&2!=0{sess:=&TcpSession{key:key,clientSeq:seq+1,serverSeq:1000};sess.lastActive.Store(time.Now().UnixNano());r.sessions.Store(key,sess);ActiveConns.Add(1);go r.initSocksConnection(sess,dstIP.String(),dp);_,_=r.writeTun(craftTCPPacket(dstIP,srcIP,dp,sp,sess.serverSeq,sess.clientSeq,0x12,nil));sess.serverSeq++;return};if !ok{return};sess:=v.(*TcpSession);sess.lastActive.Store(time.Now().UnixNano());if flags&1!=0{sess.closed.Store(true);sess.mu.Lock();if sess.socksConn!=nil{_=sess.socksConn.Close()};sess.mu.Unlock();_,_=r.writeTun(craftTCPPacket(dstIP,srcIP,dp,sp,sess.serverSeq,seq+1,0x11,nil));if _,loaded:=r.sessions.LoadAndDelete(key);loaded{ActiveConns.Add(-1)};return};if flags&4!=0{r.closeSession(key,sess);return};if len(payload)>0{sess.mu.Lock();if sess.ready&&sess.socksConn!=nil{_,_=sess.socksConn.Write(payload)}else{sess.pending=append(sess.pending,append([]byte(nil),payload...))};sess.clientSeq=seq+uint32(len(payload));sess.mu.Unlock();_,_=r.writeTun(craftTCPPacket(dstIP,srcIP,dp,sp,sess.serverSeq,sess.clientSeq,0x10,nil))}}

func (r *TunRouter) sendReset(key tcpKey,seq,ack uint32){s,d:=net.ParseIP(key.dstIP).To4(),net.ParseIP(key.srcIP).To4();if s!=nil&&d!=nil{_,_=r.writeTun(craftTCPPacket(s,d,key.dstPort,key.srcPort,seq,ack,4,nil))}}

func (r *TunRouter) initSocksConnection(sess *TcpSession,targetHost string,targetPort uint16){conn,err:=net.DialTimeout("tcp",r.socksAddr,5*time.Second);if err!=nil{r.closeSession(sess.key,sess);r.sendReset(sess.key,sess.serverSeq,sess.clientSeq);return};fail:=func(){r.closeSession(sess.key,sess);_=conn.Close();r.sendReset(sess.key,sess.serverSeq,sess.clientSeq)};if _,err:=conn.Write([]byte{5,1,0});err!=nil{fail();return};var ar [2]byte;if _,err:=io.ReadFull(conn,ar[:]);err!=nil||ar[1]!=0{fail();return};ip:=net.ParseIP(targetHost).To4();if ip==nil{ip=net.IPv4zero.To4()};req:=[]byte{5,1,0,1};req=append(req,ip...);p:=make([]byte,2);binary.BigEndian.PutUint16(p,targetPort);req=append(req,p...);if _,err:=conn.Write(req);err!=nil{fail();return};var rr [10]byte;if _,err:=io.ReadFull(conn,rr[:]);err!=nil||rr[1]!=0{fail();return};sess.mu.Lock();if sess.closed.Load(){sess.mu.Unlock();_=conn.Close();return};sess.socksConn=conn;sess.ready=true;pending:=sess.pending;sess.pending=nil;sess.mu.Unlock();for _,data:=range pending{if _,err:=conn.Write(data);err!=nil{r.closeSession(sess.key,sess);_=conn.Close();return}};const maxTCPPayload=1460;buf:=make([]byte,16*1024);for{if sess.closed.Load(){_=conn.Close();return};n,err:=conn.Read(buf);if n>0{sess.lastActive.Store(time.Now().UnixNano());s,d:=net.ParseIP(sess.key.dstIP).To4(),net.ParseIP(sess.key.srcIP).To4();if s!=nil&&d!=nil{data:=buf[:n];for len(data)>0{chunk,fl:=len(data),byte(0x18);if chunk>maxTCPPayload{chunk,fl=maxTCPPayload,0x10};pkt:=craftTCPPacket(s,d,sess.key.dstPort,sess.key.srcPort,sess.serverSeq,sess.clientSeq,fl,data[:chunk]);sess.serverSeq+=uint32(chunk);if _,werr:=r.writeTun(pkt);werr!=nil{r.closeSession(sess.key,sess);return};data=data[chunk:]}}};if err!=nil{r.closeSession(sess.key,sess);return}}}

func craftUDPPacket(srcIP,dstIP net.IP,srcPort,dstPort uint16,payload []byte)[]byte{total:=20+8+len(payload);pkt:=make([]byte,total);pkt[0]=0x45;binary.BigEndian.PutUint16(pkt[2:4],uint16(total));pkt[8]=64;pkt[9]=17;copy(pkt[12:16],srcIP.To4());copy(pkt[16:20],dstIP.To4());binary.BigEndian.PutUint16(pkt[10:12],computeChecksum(pkt[:20]));binary.BigEndian.PutUint16(pkt[20:22],srcPort);binary.BigEndian.PutUint16(pkt[22:24],dstPort);binary.BigEndian.PutUint16(pkt[24:26],uint16(8+len(payload)));copy(pkt[28:],payload);return pkt}
func craftTCPPacket(srcIP,dstIP net.IP,srcPort,dstPort uint16,seq,ack uint32,flags byte,payload []byte)[]byte{total:=40+len(payload);pkt:=make([]byte,total);pkt[0]=0x45;binary.BigEndian.PutUint16(pkt[2:4],uint16(total));pkt[8]=64;pkt[9]=6;copy(pkt[12:16],srcIP.To4());copy(pkt[16:20],dstIP.To4());binary.BigEndian.PutUint16(pkt[10:12],computeChecksum(pkt[:20]));binary.BigEndian.PutUint16(pkt[20:22],srcPort);binary.BigEndian.PutUint16(pkt[22:24],dstPort);binary.BigEndian.PutUint32(pkt[24:28],seq);binary.BigEndian.PutUint32(pkt[28:32],ack);pkt[32]=0x50;pkt[33]=flags;binary.BigEndian.PutUint16(pkt[34:36],65535);copy(pkt[40:],payload);binary.BigEndian.PutUint16(pkt[36:38],computeTCPChecksum(srcIP.To4(),dstIP.To4(),pkt[20:]));return pkt}
func computeChecksum(data []byte)uint16{var sum uint32;for i:=0;i+1<len(data);i+=2{sum+=uint32(binary.BigEndian.Uint16(data[i:i+2]))};if len(data)%2==1{sum+=uint32(data[len(data)-1])<<8};for sum>0xFFFF{sum=(sum>>16)+(sum&0xFFFF)};return ^uint16(sum)}
func computeTCPChecksum(srcIP,dstIP,tcp []byte)uint16{var sum uint32;sum+=uint32(binary.BigEndian.Uint16(srcIP[0:2]));sum+=uint32(binary.BigEndian.Uint16(srcIP[2:4]));sum+=uint32(binary.BigEndian.Uint16(dstIP[0:2]));sum+=uint32(binary.BigEndian.Uint16(dstIP[2:4]));sum+=6;sum+=uint32(len(tcp));for i:=0;i+1<len(tcp);i+=2{sum+=uint32(binary.BigEndian.Uint16(tcp[i:i+2]))};if len(tcp)%2==1{sum+=uint32(tcp[len(tcp)-1])<<8};for sum>0xFFFF{sum=(sum>>16)+(sum&0xFFFF)};return ^uint16(sum)}
