package bridge

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"math/big"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// SalamanderObfuscator provides XOR stream obfuscation for UDP datagrams.
type SalamanderObfuscator struct {
	key []byte
}

// NewSalamanderObfuscator initializes an obfuscator with a given passphrase.
func NewSalamanderObfuscator(password string) *SalamanderObfuscator {
	if password == "" {
		password = "zivpn"
	}
	return &SalamanderObfuscator{
		key: []byte(password),
	}
}

// Obfuscate performs in-place or copied XOR masking.
func (s *SalamanderObfuscator) Obfuscate(data []byte) []byte {
	if len(s.key) == 0 || len(data) == 0 {
		return data
	}
	out := make([]byte, len(data))
	kLen := len(s.key)
	for i := 0; i < len(data); i++ {
		out[i] = data[i] ^ s.key[i%kLen]
	}
	return out
}

// Deobfuscate is symmetric with Obfuscate for XOR.
func (s *SalamanderObfuscator) Deobfuscate(data []byte) []byte {
	return s.Obfuscate(data)
}

// PortHopPool manages multi-port hopping ranges (e.g. "6000:19999").
type PortHopPool struct {
	startPort int
	endPort   int
	hasRange  bool
}

// ParsePortHopRange parses range strings like "6000:19999", "6000-19999", or "5667".
func ParsePortHopRange(rangeStr string) PortHopPool {
	rangeStr = strings.TrimSpace(rangeStr)
	sep := ":"
	if strings.Contains(rangeStr, "-") {
		sep = "-"
	} else if strings.Contains(rangeStr, ",") {
		sep = ","
	}

	parts := strings.Split(rangeStr, sep)
	if len(parts) == 2 {
		p1, err1 := strconv.Atoi(strings.TrimSpace(parts[0]))
		p2, err2 := strconv.Atoi(strings.TrimSpace(parts[1]))
		if err1 == nil && err2 == nil && p1 > 0 && p2 >= p1 && p2 <= 65535 {
			return PortHopPool{
				startPort: p1,
				endPort:   p2,
				hasRange:  true,
			}
		}
	} else if len(parts) == 1 {
		p, err := strconv.Atoi(strings.TrimSpace(parts[0]))
		if err == nil && p > 0 && p <= 65535 {
			return PortHopPool{
				startPort: p,
				endPort:   p,
				hasRange:  false,
			}
		}
	}

	// Default fallback: single standard ZiVPN port
	return PortHopPool{
		startPort: 5667,
		endPort:   5667,
		hasRange:  false,
	}
}

// PickPort returns a random port within the hopping range.
func (p PortHopPool) PickPort() int {
	if !p.hasRange || p.startPort == p.endPort {
		return p.startPort
	}
	diff := int64(p.endPort - p.startPort + 1)
	n, err := rand.Int(rand.Reader, big.NewInt(diff))
	if err != nil {
		return p.startPort
	}
	return p.startPort + int(n.Int64())
}

// ZiVPNClient represents a running ZiVPN client engine with Salamander obfuscation.
type ZiVPNClient struct {
	serverHost string
	serverIP   string
	hopPool    PortHopPool
	obfs       *SalamanderObfuscator
	socksAddr  string
	listener   net.Listener
	mu         sync.Mutex
	closed     bool
}

// NewZiVPNClient creates a new ZiVPN client instance.
func NewZiVPNClient(serverHost, serverIP string, hopRange string, obfsPassword string, socksListenAddr string) *ZiVPNClient {
	if socksListenAddr == "" {
		socksListenAddr = "127.0.0.1:0"
	}
	if serverIP == "" {
		serverIP = serverHost
	}

	return &ZiVPNClient{
		serverHost: serverHost,
		serverIP:   serverIP,
		hopPool:    ParsePortHopRange(hopRange),
		obfs:       NewSalamanderObfuscator(obfsPassword),
		socksAddr:  socksListenAddr,
	}
}

// Start launches the local SOCKS5 listener for ZiVPN.
func (zc *ZiVPNClient) Start() error {
	ln, err := net.Listen("tcp", zc.socksAddr)
	if err != nil {
		return fmt.Errorf("zivpn failed to listen on %s: %w", zc.socksAddr, err)
	}
	zc.listener = ln
	zc.socksAddr = ln.Addr().String()

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go zc.handleSocks5(conn)
		}
	}()

	return nil
}

// ListenerAddr returns the local listening address.
func (zc *ZiVPNClient) ListenerAddr() string {
	zc.mu.Lock()
	defer zc.mu.Unlock()
	if zc.listener != nil {
		return zc.listener.Addr().String()
	}
	return zc.socksAddr
}

// Stop cleanly terminates the ZiVPN client.
func (zc *ZiVPNClient) Stop() {
	zc.mu.Lock()
	defer zc.mu.Unlock()
	zc.closed = true
	if zc.listener != nil {
		_ = zc.listener.Close()
		zc.listener = nil
	}
}

func (zc *ZiVPNClient) handleSocks5(conn net.Conn) {
	defer conn.Close()

	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}

	// 1. SOCKS5 Auth Handshake
	var verAuth [2]byte
	if _, err := conn.Read(verAuth[:]); err != nil || verAuth[0] != 0x05 {
		return
	}
	methods := make([]byte, int(verAuth[1]))
	if _, err := conn.Read(methods); err != nil {
		return
	}
	if _, err := conn.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// 2. Read SOCKS5 Request
	var reqHdr [4]byte
	if _, err := conn.Read(reqHdr[:]); err != nil || reqHdr[0] != 0x05 || reqHdr[1] != 0x01 {
		_, _ = conn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var targetHost string
	switch reqHdr[3] {
	case 0x01: // IPv4
		var ip [4]byte
		if _, err := conn.Read(ip[:]); err != nil {
			return
		}
		targetHost = net.IP(ip[:]).String()
	case 0x03: // Domain
		var dLen [1]byte
		if _, err := conn.Read(dLen[:]); err != nil {
			return
		}
		domain := make([]byte, dLen[0])
		if _, err := conn.Read(domain); err != nil {
			return
		}
		targetHost = string(domain)
	case 0x04: // IPv6
		var ip [16]byte
		if _, err := conn.Read(ip[:]); err != nil {
			return
		}
		targetHost = net.IP(ip[:]).String()
	default:
		_, _ = conn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var portBuf [2]byte
	if _, err := conn.Read(portBuf[:]); err != nil {
		return
	}
	targetPort := binary.BigEndian.Uint16(portBuf[:])
	targetAddr := net.JoinHostPort(targetHost, strconv.Itoa(int(targetPort)))

	// 3. Dial ZiVPN UDP Hop Port
	egressPort := zc.hopPool.PickPort()
	remoteUDPAddr := net.JoinHostPort(zc.serverIP, strconv.Itoa(egressPort))

	rAddr, err := net.ResolveUDPAddr("udp", remoteUDPAddr)
	if err != nil {
		_, _ = conn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	udpConn, err := net.DialUDP("udp", nil, rAddr)
	if err != nil {
		_, _ = conn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer udpConn.Close()

	if raw, err := udpConn.SyscallConn(); err == nil {
		_ = raw.Control(func(fd uintptr) {
			ProtectSocket(int(fd))
		})
	}

	// 4. Send Connect Success
	if _, err := conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	// 5. Pipe traffic with Salamander XOR obfuscation
	zc.pipeObfuscated(conn, udpConn, targetAddr)
}

func (zc *ZiVPNClient) pipeObfuscated(tcpConn net.Conn, udpConn *net.UDPConn, targetAddr string) {
	done := make(chan struct{}, 2)

	// TCP -> Obfuscated UDP
	go func() {
		defer func() { done <- struct{}{} }()
		buf := make([]byte, 16*1024)
		for {
			n, err := tcpConn.Read(buf)
			if n > 0 {
				obfsData := zc.obfs.Obfuscate(buf[:n])
				if _, wErr := udpConn.Write(obfsData); wErr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	}()

	// Obfuscated UDP -> Deobfuscated TCP
	go func() {
		defer func() { done <- struct{}{} }()
		buf := make([]byte, 16*1024)
		for {
			_ = udpConn.SetReadDeadline(time.Now().Add(60 * time.Second))
			n, err := udpConn.Read(buf)
			if n > 0 {
				deobfsData := zc.obfs.Deobfuscate(buf[:n])
				if _, wErr := tcpConn.Write(deobfsData); wErr != nil {
					return
				}
			}
			if err != nil {
				var netErr net.Error
				if errors.As(err, &netErr) && netErr.Timeout() {
					return
				}
				return
			}
		}
	}()

	<-done
	_ = tcpConn.Close()
	_ = udpConn.Close()
}
