package bridge

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"time"
)

var (
	activeRouter   *TunRouter
	activeRouterMu sync.Mutex
)

// TunRouter manages Layer 3 IP packet pump between Android TUN and SOCKS5 proxy.
type TunRouter struct {
	tunFile   *os.File
	socksAddr string
	running   atomic.Bool
	stopChan  chan struct{}
	wg        sync.WaitGroup

	// Active TCP sessions: key -> *TcpSession
	sessions sync.Map
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
}

// StartTunRouter starts reading raw IP packets from the TUN file descriptor
// and demultiplexes them to the local SOCKS proxy.
func StartTunRouter(fd int, socksPort int) error {
	activeRouterMu.Lock()
	defer activeRouterMu.Unlock()

	if activeRouter != nil && activeRouter.running.Load() {
		return errors.New("tun router is already active")
	}

	if fd < 0 {
		return errors.New("invalid tun file descriptor")
	}

	tunFile := os.NewFile(uintptr(fd), "mubx-tun")
	if tunFile == nil {
		return errors.New("failed to wrap tun file descriptor")
	}

	socksAddr := fmt.Sprintf("127.0.0.1:%d", socksPort)

	router := &TunRouter{
		tunFile:   tunFile,
		socksAddr: socksAddr,
		stopChan:  make(chan struct{}),
	}
	router.running.Store(true)

	router.wg.Add(2)
	go router.readLoop()
	go router.reaperLoop()

	activeRouter = router
	return nil
}

// StopTunRouter gracefully closes the TUN router and tears down all sessions.
func StopTunRouter() {
	activeRouterMu.Lock()
	defer activeRouterMu.Unlock()

	if activeRouter == nil {
		return
	}

	activeRouter.running.Store(false)
	close(activeRouter.stopChan)
	_ = activeRouter.tunFile.Close()

	// Close all active sessions
	activeRouter.sessions.Range(func(key, val interface{}) bool {
		if sess, ok := val.(*TcpSession); ok {
			sess.closed.Store(true)
			if sess.socksConn != nil {
				_ = sess.socksConn.Close()
			}
		}
		return true
	})

	activeRouter.wg.Wait()
	activeRouter = nil
}

func (r *TunRouter) reaperLoop() {
	defer r.wg.Done()
	ticker := time.NewTicker(30 * time.Second)
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
						if sess.socksConn != nil {
							_ = sess.socksConn.Close()
						}
						r.sessions.Delete(key)
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

		n, err := r.tunFile.Read(buf)
		if err != nil {
			if r.running.Load() {
				// Unexpected error or closed
				return
			}
			return
		}

		if n < 20 {
			continue // Less than minimum IPv4 header
		}

		// Check IPv4
		version := buf[0] >> 4
		if version != 4 {
			continue // Currently handle IPv4
		}

		packet := make([]byte, n)
		copy(packet, buf[:n])

		go r.handlePacket(packet)
	}
}

func (r *TunRouter) handlePacket(packet []byte) {
	ihl := int(packet[0]&0x0F) * 4
	if len(packet) < ihl {
		return
	}

	protocol := packet[9]
	srcIP := net.IP(packet[12:16])
	dstIP := net.IP(packet[16:20])

	switch protocol {
	case 1: // ICMP
		r.handleICMP(packet, ihl, srcIP, dstIP)
	case 17: // UDP
		r.handleUDP(packet, ihl, srcIP, dstIP)
	case 6: // TCP
		r.handleTCP(packet, ihl, srcIP, dstIP)
	}
}

// handleICMP replies to Echo Request (ping) packets to maintain active latency indicators.
func (r *TunRouter) handleICMP(packet []byte, ihl int, srcIP, dstIP net.IP) {
	if len(packet) < ihl+8 {
		return
	}
	icmpPayload := packet[ihl:]
	if icmpPayload[0] != 8 { // Not Echo Request
		return
	}

	// Craft Echo Reply (Type 0)
	reply := make([]byte, len(packet))
	copy(reply, packet)

	// Swap IP
	copy(reply[12:16], dstIP)
	copy(reply[16:20], srcIP)

	// Set Type 0, Code 0
	reply[ihl] = 0
	reply[ihl+1] = 0

	// Recompute ICMP Checksum
	reply[ihl+2] = 0
	reply[ihl+3] = 0
	csum := computeChecksum(reply[ihl:])
	binary.BigEndian.PutUint16(reply[ihl+2:ihl+4], csum)

	// Recompute IPv4 Header Checksum
	reply[10] = 0
	reply[11] = 0
	ipCsum := computeChecksum(reply[:ihl])
	binary.BigEndian.PutUint16(reply[10:12], ipCsum)

	_, _ = r.tunFile.Write(reply)
}

// handleUDP forwards DNS and UDP packets to local resolvers.
func (r *TunRouter) handleUDP(packet []byte, ihl int, srcIP, dstIP net.IP) {
	if len(packet) < ihl+8 {
		return
	}
	udpHeader := packet[ihl : ihl+8]
	srcPort := binary.BigEndian.Uint16(udpHeader[0:2])
	dstPort := binary.BigEndian.Uint16(udpHeader[2:4])
	udpLen := binary.BigEndian.Uint16(udpHeader[4:6])

	if len(packet) < ihl+int(udpLen) {
		return
	}
	payload := packet[ihl+8 : ihl+int(udpLen)]

	// For DNS queries (port 53), resolve via public DNS resolver
	if dstPort == 53 {
		go func() {
			dnsServer := "1.1.1.1:53"
			rAddr, err := net.ResolveUDPAddr("udp", dnsServer)
			if err != nil {
				return
			}
			conn, err := net.DialUDP("udp", nil, rAddr)
			if err != nil {
				return
			}
			defer conn.Close()

			_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
			if _, err := conn.Write(payload); err != nil {
				return
			}

			respBuf := make([]byte, 4096)
			n, err := conn.Read(respBuf)
			if err != nil || n == 0 {
				return
			}

			// Wrap in IPv4 + UDP response
			reply := craftUDPPacket(dstIP, srcIP, dstPort, srcPort, respBuf[:n])
			_, _ = r.tunFile.Write(reply)
		}()
	}
}

// handleTCP parses TCP segments and bridges them into local SOCKS5 connections.
func (r *TunRouter) handleTCP(packet []byte, ihl int, srcIP, dstIP net.IP) {
	if len(packet) < ihl+20 {
		return
	}
	tcpHeader := packet[ihl:]
	srcPort := binary.BigEndian.Uint16(tcpHeader[0:2])
	dstPort := binary.BigEndian.Uint16(tcpHeader[2:4])
	seq := binary.BigEndian.Uint32(tcpHeader[4:8])
	_ = binary.BigEndian.Uint32(tcpHeader[8:12]) // ack
	dataOffset := int(tcpHeader[12]>>4) * 4
	flags := tcpHeader[13]

	if len(tcpHeader) < dataOffset {
		return
	}
	payload := tcpHeader[dataOffset:]

	key := tcpKey{
		srcIP:   srcIP.String(),
		srcPort: srcPort,
		dstIP:   dstIP.String(),
		dstPort: dstPort,
	}

	val, exists := r.sessions.Load(key)

	// 1. New connection (SYN flag)
	if !exists && (flags&0x02) != 0 {
		session := &TcpSession{
			key:        key,
			clientSeq:  seq + 1,
			serverSeq:  1000,
			lastActive: time.Now(),
		}
		r.sessions.Store(key, session)

		go r.initSocksConnection(session, dstIP.String(), dstPort)

		// Reply with SYN-ACK
		synAck := craftTCPPacket(dstIP, srcIP, dstPort, srcPort, session.serverSeq, session.clientSeq, 0x12, nil)
		session.serverSeq++
		_, _ = r.tunFile.Write(synAck)
		return
	}

	if !exists {
		// Stale packet or reset
		return
	}

	session := val.(*TcpSession)
	session.lastActive = time.Now()

	// 2. Client FIN or RST
	if (flags & 0x01) != 0 { // FIN
		session.closed.Store(true)
		if session.socksConn != nil {
			_ = session.socksConn.Close()
		}
		finAck := craftTCPPacket(dstIP, srcIP, dstPort, srcPort, session.serverSeq, seq+1, 0x11, nil)
		_, _ = r.tunFile.Write(finAck)
		r.sessions.Delete(key)
		return
	}

	if (flags & 0x04) != 0 { // RST
		session.closed.Store(true)
		if session.socksConn != nil {
			_ = session.socksConn.Close()
		}
		r.sessions.Delete(key)
		return
	}

	// 3. Client data transmission
	if len(payload) > 0 {
		session.clientSeq = seq + uint32(len(payload))

		if session.socksConn != nil {
			_, _ = session.socksConn.Write(payload)
		}

		// Acknowledge received data
		ackPacket := craftTCPPacket(dstIP, srcIP, dstPort, srcPort, session.serverSeq, session.clientSeq, 0x10, nil)
		_, _ = r.tunFile.Write(ackPacket)
	}
}

func (r *TunRouter) initSocksConnection(sess *TcpSession, targetHost string, targetPort uint16) {
	conn, err := net.DialTimeout("tcp", r.socksAddr, 4*time.Second)
	if err != nil {
		sess.closed.Store(true)
		r.sessions.Delete(sess.key)
		return
	}
	sess.socksConn = conn

	// SOCKS5 handshake
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		sess.closed.Store(true)
		r.sessions.Delete(sess.key)
		conn.Close()
		return
	}
	var authResp [2]byte
	if _, err := io.ReadFull(conn, authResp[:]); err != nil || authResp[1] != 0x00 {
		sess.closed.Store(true)
		r.sessions.Delete(sess.key)
		conn.Close()
		return
	}

	// CONNECT request (IPv4)
	ip := net.ParseIP(targetHost).To4()
	if ip == nil {
		ip = net.IPv4zero.To4()
	}

	req := []byte{0x05, 0x01, 0x00, 0x01}
	req = append(req, ip...)
	pBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(pBytes, targetPort)
	req = append(req, pBytes...)

	if _, err := conn.Write(req); err != nil {
		sess.closed.Store(true)
		r.sessions.Delete(sess.key)
		conn.Close()
		return
	}

	var resp [10]byte
	if _, err := io.ReadFull(conn, resp[:]); err != nil || resp[1] != 0x00 {
		sess.closed.Store(true)
		r.sessions.Delete(sess.key)
		conn.Close()
		return
	}

	// Pipe SOCKS responses back into TUN
	buf := make([]byte, 16*1024)
	for {
		if sess.closed.Load() {
			return
		}
		n, err := conn.Read(buf)
		if n > 0 {
			sess.lastActive = time.Now()
			srcIP := net.ParseIP(sess.key.dstIP).To4()
			dstIP := net.ParseIP(sess.key.srcIP).To4()
			tcpPkt := craftTCPPacket(srcIP, dstIP, sess.key.dstPort, sess.key.srcPort, sess.serverSeq, sess.clientSeq, 0x18, buf[:n])
			sess.serverSeq += uint32(n)
			_, _ = r.tunFile.Write(tcpPkt)
		}
		if err != nil {
			sess.closed.Store(true)
			r.sessions.Delete(sess.key)
			srcIP := net.ParseIP(sess.key.dstIP).To4()
			dstIP := net.ParseIP(sess.key.srcIP).To4()
			finPkt := craftTCPPacket(srcIP, dstIP, sess.key.dstPort, sess.key.srcPort, sess.serverSeq, sess.clientSeq, 0x11, nil)
			_, _ = r.tunFile.Write(finPkt)
			return
		}
	}
}

func craftUDPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, payload []byte) []byte {
	totalLen := 20 + 8 + len(payload)
	pkt := make([]byte, totalLen)

	// IPv4 Header
	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:4], uint16(totalLen))
	pkt[8] = 64
	pkt[9] = 17 // UDP
	copy(pkt[12:16], srcIP.To4())
	copy(pkt[16:20], dstIP.To4())
	ipCsum := computeChecksum(pkt[:20])
	binary.BigEndian.PutUint16(pkt[10:12], ipCsum)

	// UDP Header
	binary.BigEndian.PutUint16(pkt[20:22], srcPort)
	binary.BigEndian.PutUint16(pkt[22:24], dstPort)
	binary.BigEndian.PutUint16(pkt[24:26], uint16(8+len(payload)))
	copy(pkt[28:], payload)

	return pkt
}

func craftTCPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, seq, ack uint32, flags byte, payload []byte) []byte {
	totalLen := 20 + 20 + len(payload)
	pkt := make([]byte, totalLen)

	// IPv4 Header
	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:4], uint16(totalLen))
	pkt[8] = 64
	pkt[9] = 6 // TCP
	copy(pkt[12:16], srcIP.To4())
	copy(pkt[16:20], dstIP.To4())
	ipCsum := computeChecksum(pkt[:20])
	binary.BigEndian.PutUint16(pkt[10:12], ipCsum)

	// TCP Header
	binary.BigEndian.PutUint16(pkt[20:22], srcPort)
	binary.BigEndian.PutUint16(pkt[22:24], dstPort)
	binary.BigEndian.PutUint32(pkt[24:28], seq)
	binary.BigEndian.PutUint32(pkt[28:32], ack)
	pkt[32] = 0x50 // Data offset (5 * 4 = 20 bytes)
	pkt[33] = flags
	binary.BigEndian.PutUint16(pkt[34:36], 65535) // Window size

	copy(pkt[40:], payload)

	// Pseudo header for TCP Checksum
	tcpCsum := computeTCPChecksum(srcIP.To4(), dstIP.To4(), pkt[20:])
	binary.BigEndian.PutUint16(pkt[36:38], tcpCsum)

	return pkt
}

func computeChecksum(data []byte) uint16 {
	var sum uint32
	for i := 0; i < len(data)-1; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(data[i : i+2]))
	}
	if len(data)%2 == 1 {
		sum += uint32(data[len(data)-1]) << 8
	}
	for sum > 0xFFFF {
		sum = (sum >> 16) + (sum & 0xFFFF)
	}
	return ^uint16(sum)
}

func computeTCPChecksum(srcIP, dstIP []byte, tcpHeaderAndPayload []byte) uint16 {
	var sum uint32
	// Pseudo header: SrcIP (4), DstIP (4), Zero (1), Proto (1), TCP Length (2)
	sum += uint32(binary.BigEndian.Uint16(srcIP[0:2]))
	sum += uint32(binary.BigEndian.Uint16(srcIP[2:4]))
	sum += uint32(binary.BigEndian.Uint16(dstIP[0:2]))
	sum += uint32(binary.BigEndian.Uint16(dstIP[2:4]))
	sum += uint32(6) // TCP Protocol
	sum += uint32(len(tcpHeaderAndPayload))

	for i := 0; i < len(tcpHeaderAndPayload)-1; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(tcpHeaderAndPayload[i : i+2]))
	}
	if len(tcpHeaderAndPayload)%2 == 1 {
		sum += uint32(tcpHeaderAndPayload[len(tcpHeaderAndPayload)-1]) << 8
	}
	for sum > 0xFFFF {
		sum = (sum >> 16) + (sum & 0xFFFF)
	}
	return ^uint16(sum)
}
