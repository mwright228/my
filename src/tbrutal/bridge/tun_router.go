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
	socksAddr  string
	dnsServer  string
	running    atomic.Bool
	stopChan   chan struct{}
	wg         sync.WaitGroup
	tunWriteMu sync.Mutex
	packetChan chan []byte
	sessions   sync.Map
}

type tcpKey struct {
	srcIP   string
	srcPort uint16
	dstIP   string
	dstPort uint16
}

func (k tcpKey) String() string {
	return fmt.Sprintf("%s:%d->%s:%d", k.srcIP, k.srcPort, k.dstIP, k.dstPort)
}

type TcpSession struct {
	key        tcpKey
	socksConn  net.Conn
	clientSeq  uint32
	serverSeq  uint32
	closed     atomic.Bool
	lastActive time.Time
	mu         sync.Mutex
	ready      bool
	pending    [][]byte
}

func StartTunRouter(fd int, socksPort int) error {
	return StartTunRouterWithDNS(fd, socksPort, "1.1.1.1:53")
}

func StartTunRouterWithDNS(fd int, socksPort int, dnsServer string) error {
	activeRouterMu.Lock()
	defer activeRouterMu.Unlock()

	if activeRouter != nil && activeRouter.running.Load() {
		return errors.New("tun router is already active")
	}
	if fd < 0 {
		return errors.New("invalid tun file descriptor")
	}

	// Android owns the original ParcelFileDescriptor. The native router owns
	// only this duplicate, which can safely be closed during shutdown.
	dupFD, err := unix.Dup(fd)
	if err != nil {
		return fmt.Errorf("duplicate tun file descriptor: %w", err)
	}
	tunFile := os.NewFile(uintptr(dupFD), "mubx-tun")
	if tunFile == nil {
		_ = unix.Close(dupFD)
		return errors.New("failed to wrap duplicated tun file descriptor")
	}

	socksAddr := fmt.Sprintf("127.0.0.1:%d", socksPort)
	if dnsServer == "" {
		dnsServer = "1.1.1.1:53"
	}
	if !hasPort(dnsServer) {
		dnsServer = net.JoinHostPort(dnsServer, "53")
	}

	router := &TunRouter{
		tunFile:    tunFile,
		socksAddr:  socksAddr,
		dnsServer:  dnsServer,
		stopChan:   make(chan struct{}),
		packetChan: make(chan []byte, 1024),
	}
	router.running.Store(true)

	router.wg.Add(2)
	go router.readLoop()
	go router.reaperLoop()

	const numWorkers = 8
	for i := 0; i < numWorkers; i++ {
		router.wg.Add(1)
		go router.workerLoop()
	}

	activeRouter = router
	LogMsg("ROUTER", fmt.Sprintf("TunRouter started (SOCKS: %s, DNS: %s, Workers: %d)", socksAddr, dnsServer, numWorkers))
	return nil
}

func hasPort(s string) bool {
	_, _, err := net.SplitHostPort(s)
	return err == nil
}

func StopTunRouter() {
	activeRouterMu.Lock()
	defer activeRouterMu.Unlock()

	if activeRouter == nil || !activeRouter.running.Load() {
		return
	}

	router := activeRouter
	router.running.Store(false)
	close(router.stopChan)

	router.sessions.Range(func(key, val interface{}) bool {
		if sess, ok := val.(*TcpSession); ok {
			sess.closed.Store(true)
			sess.mu.Lock()
			if sess.socksConn != nil {
				_ = sess.socksConn.Close()
			}
			sess.mu.Unlock()
		}
		return true
	})

	// Closing the duplicated descriptor unblocks an in-flight reader without
	// closing Android's original ParcelFileDescriptor.
	if router.tunFile != nil {
		_ = router.tunFile.Close()
		router.tunFile = nil
	}

	done := make(chan struct{})
	go func() {
		router.wg.Wait()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(400 * time.Millisecond):
		LogMsg("ROUTER", "TunRouter wait timed out after closing owned descriptor")
	}

	activeRouter = nil
	LogMsg("ROUTER", "TunRouter stopped")
}

func (r *TunRouter) writeTun(pkt []byte) (int, error) {
	if !r.running.Load() || r.tunFile == nil {
		return 0, io.ErrClosedPipe
	}
	r.tunWriteMu.Lock()
	defer r.tunWriteMu.Unlock()
	n, err := r.tunFile.Write(pkt)
	if err == nil && n > 0 {
		TotalTxBytes.Add(uint64(n))
	}
	return n, err
}

func (r *TunRouter) reaperLoop() {
	defer r.wg.Done()
	ticker := time.NewTicker(20 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-r.stopChan:
			return
		case <-ticker.C:
			now := time.Now()
			r.sessions.Range(func(key, val interface{}) bool {
				if sess, ok := val.(*TcpSession); ok {
					if sess.closed.Load() || now.Sub(sess.lastActive) > 90*time.Second {
						sess.closed.Store(true)
						sess.mu.Lock()
						if sess.socksConn != nil {
							_ = sess.socksConn.Close()
						}
						sess.mu.Unlock()
						r.sessions.Delete(key)
						ActiveConns.Add(-1)
					}
				}
				return true
			})
		}
	}
}

func (r *TunRouter) readLoop() {
	defer r.wg.Done()
	buf := make([]byte, 65535)
	for {
		select {
		case <-r.stopChan:
			return
		default:
		}
		if r.tunFile == nil {
			return
		}
		n, err := r.tunFile.Read(buf)
		if err != nil {
			return
		}
		if n < 20 {
			continue
		}
		TotalRxBytes.Add(uint64(n))
		if buf[0]>>4 != 4 {
			continue
		}
		packet := make([]byte, n)
		copy(packet, buf[:n])
		select {
		case r.packetChan <- packet:
		default:
		}
	}
}

func (r *TunRouter) workerLoop() {
	defer r.wg.Done()
	for {
		select {
		case <-r.stopChan:
			return
		case pkt, ok := <-r.packetChan:
			if !ok {
				return
			}
			r.handlePacket(pkt)
		}
	}
}

func (r *TunRouter) handlePacket(packet []byte) {
	if len(packet) < 20 || packet[0]>>4 != 4 {
		return
	}
	ihl := int(packet[0]&0x0F) * 4
	if ihl < 20 || len(packet) < ihl {
		return
	}
	protocol := packet[9]
	srcIP := net.IP(packet[12:16])
	dstIP := net.IP(packet[16:20])
	switch protocol {
	case 1:
		r.handleICMP(packet, ihl, srcIP, dstIP)
	case 17:
		r.handleUDP(packet, ihl, srcIP, dstIP)
	case 6:
		r.handleTCP(packet, ihl, srcIP, dstIP)
	}
}

func (r *TunRouter) handleICMP(packet []byte, ihl int, srcIP, dstIP net.IP) {
	if len(packet) < ihl+8 || packet[ihl] != 8 {
		return
	}
	reply := make([]byte, len(packet))
	copy(reply, packet)
	copy(reply[12:16], dstIP)
	copy(reply[16:20], srcIP)
	reply[ihl], reply[ihl+1] = 0, 0
	reply[ihl+2], reply[ihl+3] = 0, 0
	binary.BigEndian.PutUint16(reply[ihl+2:ihl+4], computeChecksum(reply[ihl:]))
	reply[10], reply[11] = 0, 0
	binary.BigEndian.PutUint16(reply[10:12], computeChecksum(reply[:ihl]))
	_, _ = r.writeTun(reply)
}

func (r *TunRouter) handleUDP(packet []byte, ihl int, srcIP, dstIP net.IP) {
	if len(packet) < ihl+8 {
		return
	}
	udpHeader := packet[ihl : ihl+8]
	srcPort := binary.BigEndian.Uint16(udpHeader[0:2])
	dstPort := binary.BigEndian.Uint16(udpHeader[2:4])
	udpLen := binary.BigEndian.Uint16(udpHeader[4:6])
	if udpLen < 8 || len(packet) < ihl+int(udpLen) || dstPort != 53 {
		return
	}
	payload := packet[ihl+8 : ihl+int(udpLen)]
	go func(dnsQuery []byte, cSrcPort, cDstPort uint16, cSrcIP, cDstIP net.IP) {
		resp, err := r.resolveDNS(dnsQuery)
		if err != nil || len(resp) == 0 {
			return
		}
		reply := craftUDPPacket(cDstIP, cSrcIP, cDstPort, cSrcPort, resp)
		_, _ = r.writeTun(reply)
	}(payload, srcPort, dstPort, srcIP, dstIP)
}

func (r *TunRouter) resolveDNS(query []byte) ([]byte, error) {
	targetDNS := r.dnsServer
	if targetDNS == "" {
		targetDNS = "1.1.1.1:53"
	}
	if !hasPort(targetDNS) {
		targetDNS = net.JoinHostPort(targetDNS, "53")
	}
	if resp, err := queryProtectedUDP(targetDNS, query); err == nil && len(resp) > 0 {
		return resp, nil
	}
	if !strings.HasPrefix(targetDNS, "1.1.1.1") {
		if resp, err := queryProtectedUDP("1.1.1.1:53", query); err == nil && len(resp) > 0 {
			return resp, nil
		}
	}
	if !strings.HasPrefix(targetDNS, "8.8.8.8") {
		if resp, err := queryProtectedUDP("8.8.8.8:53", query); err == nil && len(resp) > 0 {
			return resp, nil
		}
	}
	if r.socksAddr != "" {
		if resp, err := r.queryDNSOverSocks(query); err == nil && len(resp) > 0 {
			return resp, nil
		}
	}
	return nil, errors.New("dns resolution failed on all upstream resolvers")
}

func queryProtectedUDP(dnsServer string, query []byte) ([]byte, error) {
	rAddr, err := net.ResolveUDPAddr("udp4", dnsServer)
	if err != nil {
		return nil, err
	}
	conn, err := net.ListenUDP("udp4", nil)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	if raw, err := conn.SyscallConn(); err == nil {
		var protectErr error
		_ = raw.Control(func(fd uintptr) {
			if !ProtectSocket(int(fd)) {
				protectErr = errors.New("failed to protect DNS UDP socket")
			}
		})
		if protectErr != nil {
			return nil, protectErr
		}
	} else {
		return nil, fmt.Errorf("failed to access DNS socket: %w", err)
	}
	_ = conn.SetDeadline(time.Now().Add(2500 * time.Millisecond))
	if _, err := conn.WriteToUDP(query, rAddr); err != nil {
		return nil, err
	}
	respBuf := make([]byte, 4096)
	n, _, err := conn.ReadFromUDP(respBuf)
	if err != nil || n == 0 {
		return nil, err
	}
	return respBuf[:n], nil
}

func (r *TunRouter) queryDNSOverSocks(query []byte) ([]byte, error) {
	conn, err := net.DialTimeout("tcp", r.socksAddr, 2*time.Second)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		return nil, err
	}
	var authResp [2]byte
	if _, err := io.ReadFull(conn, authResp[:]); err != nil || authResp[1] != 0x00 {
		return nil, errors.New("socks auth failed")
	}
	dnsHost, _, err := net.SplitHostPort(r.dnsServer)
	if err != nil || dnsHost == "" {
		dnsHost = "1.1.1.1"
	}
	dnsIP := net.ParseIP(dnsHost).To4()
	if dnsIP == nil {
		dnsIP = net.ParseIP("1.1.1.1").To4()
	}
	req := []byte{0x05, 0x01, 0x00, 0x01}
	req = append(req, dnsIP...)
	req = append(req, 0x00, 0x35)
	if _, err := conn.Write(req); err != nil {
		return nil, err
	}
	var resp [10]byte
	if _, err := io.ReadFull(conn, resp[:]); err != nil || resp[1] != 0x00 {
		return nil, errors.New("socks connect to dns failed")
	}
	tcpQuery := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(tcpQuery[0:2], uint16(len(query)))
	copy(tcpQuery[2:], query)
	if _, err := conn.Write(tcpQuery); err != nil {
		return nil, err
	}
	var lenBuf [2]byte
	if _, err := io.ReadFull(conn, lenBuf[:]); err != nil {
		return nil, err
	}
	respLen := binary.BigEndian.Uint16(lenBuf[:])
	if respLen == 0 || respLen > 4096 {
		return nil, errors.New("invalid dns response length")
	}
	respData := make([]byte, respLen)
	if _, err := io.ReadFull(conn, respData); err != nil {
		return nil, err
	}
	return respData, nil
}

func (r *TunRouter) handleTCP(packet []byte, ihl int, srcIP, dstIP net.IP) {
	if len(packet) < ihl+20 {
		return
	}
	tcpHeader := packet[ihl:]
	srcPort := binary.BigEndian.Uint16(tcpHeader[0:2])
	dstPort := binary.BigEndian.Uint16(tcpHeader[2:4])
	seq := binary.BigEndian.Uint32(tcpHeader[4:8])
	dataOffset := int(tcpHeader[12]>>4) * 4
	flags := tcpHeader[13]
	if dataOffset < 20 || len(tcpHeader) < dataOffset {
		return
	}
	payload := tcpHeader[dataOffset:]
	key := tcpKey{srcIP: srcIP.String(), srcPort: srcPort, dstIP: dstIP.String(), dstPort: dstPort}
	val, exists := r.sessions.Load(key)
	if !exists && (flags&0x02) != 0 {
		session := &TcpSession{key: key, clientSeq: seq + 1, serverSeq: 1000, lastActive: time.Now()}
		r.sessions.Store(key, session)
		ActiveConns.Add(1)
		go r.initSocksConnection(session, dstIP.String(), dstPort)
		synAck := craftTCPPacket(dstIP, srcIP, dstPort, srcPort, session.serverSeq, session.clientSeq, 0x12, nil)
		session.serverSeq++
		_, _ = r.writeTun(synAck)
		return
	}
	if !exists {
		return
	}
	session := val.(*TcpSession)
	session.lastActive = time.Now()
	if flags&0x01 != 0 {
		session.closed.Store(true)
		session.mu.Lock()
		if session.socksConn != nil { _ = session.socksConn.Close() }
		session.mu.Unlock()
		finAck := craftTCPPacket(dstIP, srcIP, dstPort, srcPort, session.serverSeq, seq+1, 0x11, nil)
		_, _ = r.writeTun(finAck)
		r.sessions.Delete(key)
		ActiveConns.Add(-1)
		return
	}
	if flags&0x04 != 0 {
		session.closed.Store(true)
		session.mu.Lock()
		if session.socksConn != nil { _ = session.socksConn.Close() }
		session.mu.Unlock()
		r.sessions.Delete(key)
		ActiveConns.Add(-1)
		return
	}
	if len(payload) > 0 {
		session.mu.Lock()
		if session.ready && session.socksConn != nil {
			_, _ = session.socksConn.Write(payload)
		} else {
			bufCopy := make([]byte, len(payload))
			copy(bufCopy, payload)
			session.pending = append(session.pending, bufCopy)
		}
		session.clientSeq = seq + uint32(len(payload))
		session.mu.Unlock()
		ackPacket := craftTCPPacket(dstIP, srcIP, dstPort, srcPort, session.serverSeq, session.clientSeq, 0x10, nil)
		_, _ = r.writeTun(ackPacket)
	}
}

func (r *TunRouter) sendReset(key tcpKey, seq, ack uint32) {
	srcIP := net.ParseIP(key.dstIP).To4()
	dstIP := net.ParseIP(key.srcIP).To4()
	if srcIP != nil && dstIP != nil {
		rstPkt := craftTCPPacket(srcIP, dstIP, key.dstPort, key.srcPort, seq, ack, 0x04, nil)
		_, _ = r.writeTun(rstPkt)
	}
}

func (r *TunRouter) initSocksConnection(sess *TcpSession, targetHost string, targetPort uint16) {
	conn, err := net.DialTimeout("tcp", r.socksAddr, 5*time.Second)
	if err != nil {
		sess.closed.Store(true); r.sessions.Delete(sess.key); ActiveConns.Add(-1); r.sendReset(sess.key, sess.serverSeq, sess.clientSeq); return
	}
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil { sess.closed.Store(true); r.sessions.Delete(sess.key); ActiveConns.Add(-1); _ = conn.Close(); r.sendReset(sess.key, sess.serverSeq, sess.clientSeq); return }
	var authResp [2]byte
	if _, err := io.ReadFull(conn, authResp[:]); err != nil || authResp[1] != 0x00 { sess.closed.Store(true); r.sessions.Delete(sess.key); ActiveConns.Add(-1); _ = conn.Close(); r.sendReset(sess.key, sess.serverSeq, sess.clientSeq); return }
	ip := net.ParseIP(targetHost).To4()
	if ip == nil { ip = net.IPv4zero.To4() }
	req := []byte{0x05, 0x01, 0x00, 0x01}
	req = append(req, ip...)
	pBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(pBytes, targetPort)
	req = append(req, pBytes...)
	if _, err := conn.Write(req); err != nil { sess.closed.Store(true); r.sessions.Delete(sess.key); ActiveConns.Add(-1); _ = conn.Close(); r.sendReset(sess.key, sess.serverSeq, sess.clientSeq); return }
	var resp [10]byte
	if _, err := io.ReadFull(conn, resp[:]); err != nil || resp[1] != 0x00 { sess.closed.Store(true); r.sessions.Delete(sess.key); ActiveConns.Add(-1); _ = conn.Close(); r.sendReset(sess.key, sess.serverSeq, sess.clientSeq); return }
	sess.mu.Lock()
	sess.socksConn = conn
	sess.ready = true
	for _, pendingData := range sess.pending { _, _ = conn.Write(pendingData) }
	sess.pending = nil
	sess.mu.Unlock()

	const maxTCPPayload = 1460
	buf := make([]byte, 16*1024)
	for {
		if sess.closed.Load() { _ = conn.Close(); return }
		n, err := conn.Read(buf)
		if n > 0 {
			sess.lastActive = time.Now()
			srcIP := net.ParseIP(sess.key.dstIP).To4()
			dstIP := net.ParseIP(sess.key.srcIP).To4()
			if srcIP != nil && dstIP != nil {
				data := buf[:n]
				for len(data) > 0 {
					chunkSize := len(data)
					flags := byte(0x18)
					if chunkSize > maxTCPPayload { chunkSize = maxTCPPayload; flags = 0x10 }
					tcpPkt := craftTCPPacket(srcIP, dstIP, sess.key.dstPort, sess.key.srcPort, sess.serverSeq, sess.clientSeq, flags, data[:chunkSize])
					sess.serverSeq += uint32(chunkSize)
					if _, wErr := r.writeTun(tcpPkt); wErr != nil { return }
					data = data[chunkSize:]
				}
			}
		}
		if err != nil {
			sess.closed.Store(true); r.sessions.Delete(sess.key); ActiveConns.Add(-1); _ = conn.Close()
			srcIP := net.ParseIP(sess.key.dstIP).To4(); dstIP := net.ParseIP(sess.key.srcIP).To4()
			if srcIP != nil && dstIP != nil { finPkt := craftTCPPacket(srcIP, dstIP, sess.key.dstPort, sess.key.srcPort, sess.serverSeq, sess.clientSeq, 0x11, nil); _, _ = r.writeTun(finPkt) }
			return
		}
	}
}

func craftUDPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, payload []byte) []byte {
	totalLen := 20 + 8 + len(payload)
	pkt := make([]byte, totalLen)
	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:4], uint16(totalLen))
	pkt[8] = 64
	pkt[9] = 17
	copy(pkt[12:16], srcIP.To4())
	copy(pkt[16:20], dstIP.To4())
	binary.BigEndian.PutUint16(pkt[10:12], computeChecksum(pkt[:20]))
	binary.BigEndian.PutUint16(pkt[20:22], srcPort)
	binary.BigEndian.PutUint16(pkt[22:24], dstPort)
	binary.BigEndian.PutUint16(pkt[24:26], uint16(8+len(payload)))
	copy(pkt[28:], payload)
	return pkt
}

func craftTCPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, seq, ack uint32, flags byte, payload []byte) []byte {
	totalLen := 20 + 20 + len(payload)
	pkt := make([]byte, totalLen)
	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:4], uint16(totalLen))
	pkt[8] = 64
	pkt[9] = 6
	copy(pkt[12:16], srcIP.To4())
	copy(pkt[16:20], dstIP.To4())
	binary.BigEndian.PutUint16(pkt[10:12], computeChecksum(pkt[:20]))
	binary.BigEndian.PutUint16(pkt[20:22], srcPort)
	binary.BigEndian.PutUint16(pkt[22:24], dstPort)
	binary.BigEndian.PutUint32(pkt[24:28], seq)
	binary.BigEndian.PutUint32(pkt[28:32], ack)
	pkt[32] = 0x50
	pkt[33] = flags
	binary.BigEndian.PutUint16(pkt[34:36], 65535)
	copy(pkt[40:], payload)
	binary.BigEndian.PutUint16(pkt[36:38], computeTCPChecksum(srcIP.To4(), dstIP.To4(), pkt[20:]))
	return pkt
}

func computeChecksum(data []byte) uint16 {
	var sum uint32
	for i := 0; i+1 < len(data); i += 2 { sum += uint32(binary.BigEndian.Uint16(data[i : i+2])) }
	if len(data)%2 == 1 { sum += uint32(data[len(data)-1]) << 8 }
	for sum > 0xFFFF { sum = (sum >> 16) + (sum & 0xFFFF) }
	return ^uint16(sum)
}

func computeTCPChecksum(srcIP, dstIP []byte, tcpHeaderAndPayload []byte) uint16 {
	var sum uint32
	sum += uint32(binary.BigEndian.Uint16(srcIP[0:2]))
	sum += uint32(binary.BigEndian.Uint16(srcIP[2:4]))
	sum += uint32(binary.BigEndian.Uint16(dstIP[0:2]))
	sum += uint32(binary.BigEndian.Uint16(dstIP[2:4]))
	sum += 6
	sum += uint32(len(tcpHeaderAndPayload))
	for i := 0; i+1 < len(tcpHeaderAndPayload); i += 2 { sum += uint32(binary.BigEndian.Uint16(tcpHeaderAndPayload[i : i+2])) }
	if len(tcpHeaderAndPayload)%2 == 1 { sum += uint32(tcpHeaderAndPayload[len(tcpHeaderAndPayload)-1]) << 8 }
	for sum > 0xFFFF { sum = (sum >> 16) + (sum & 0xFFFF) }
	return ^uint16(sum)
}
