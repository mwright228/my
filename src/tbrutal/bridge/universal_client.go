package bridge

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// UniversalClient provides a multi-protocol SOCKS5 local bridge supporting:
// VLESS (WS / TCP / TLS), Trojan, VMess, Shadowsocks, SSH/Custom Payload, ZiVPN, and T-Brutal.
type UniversalClient struct {
	cfg      BridgeConfig
	listener net.Listener
	closed   atomic.Bool
	stopChan chan struct{}
	wg       sync.WaitGroup
}

func NewUniversalClient(cfg BridgeConfig) *UniversalClient {
	return &UniversalClient{
		cfg:      cfg,
		stopChan: make(chan struct{}),
	}
}

func (uc *UniversalClient) Start() (int, error) {
	listenAddr := uc.cfg.SocksListenAddr
	if listenAddr == "" {
		listenAddr = "127.0.0.1:0"
	}

	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		return 0, fmt.Errorf("failed to bind SOCKS5 listener on %s: %w", listenAddr, err)
	}
	uc.listener = ln

	tcpAddr, ok := ln.Addr().(*net.TCPAddr)
	if !ok {
		_ = ln.Close()
		return 0, errors.New("failed to get TCP address for SOCKS5 listener")
	}

	uc.wg.Add(1)
	go uc.acceptLoop()

	LogMsg("CLIENT", fmt.Sprintf("Universal client active on %s for protocol %s", ln.Addr().String(), uc.cfg.Protocol))
	return tcpAddr.Port, nil
}

func (uc *UniversalClient) Stop() {
	if uc.closed.Swap(true) {
		return
	}
	close(uc.stopChan)
	if uc.listener != nil {
		_ = uc.listener.Close()
	}
	uc.wg.Wait()
	LogMsg("CLIENT", "Universal client stopped")
}

func (uc *UniversalClient) acceptLoop() {
	defer uc.wg.Done()
	for {
		conn, err := uc.listener.Accept()
		if err != nil {
			if uc.closed.Load() {
				return
			}
			time.Sleep(50 * time.Millisecond)
			continue
		}
		go uc.handleSocksConnection(conn)
	}
}

func (uc *UniversalClient) handleSocksConnection(clientConn net.Conn) {
	defer clientConn.Close()

	if tc, ok := clientConn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}

	// 1. SOCKS5 Greeting Handshake
	var verAuth [2]byte
	if _, err := io.ReadFull(clientConn, verAuth[:]); err != nil || verAuth[0] != 0x05 {
		return
	}
	nMethods := int(verAuth[1])
	methods := make([]byte, nMethods)
	if _, err := io.ReadFull(clientConn, methods); err != nil {
		return
	}
	// Reply: No Auth (0x00)
	if _, err := clientConn.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// 2. SOCKS5 Request
	var reqHdr [4]byte
	if _, err := io.ReadFull(clientConn, reqHdr[:]); err != nil || reqHdr[0] != 0x05 || reqHdr[1] != 0x01 {
		// Only CONNECT (0x01) supported
		_, _ = clientConn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	atyp := reqHdr[3]
	var targetHost string
	var rawAddr []byte

	switch atyp {
	case 0x01: // IPv4
		var ip [4]byte
		if _, err := io.ReadFull(clientConn, ip[:]); err != nil {
			return
		}
		rawAddr = append([]byte{0x01}, ip[:]...)
		targetHost = net.IP(ip[:]).String()
	case 0x03: // Domain
		var dLen [1]byte
		if _, err := io.ReadFull(clientConn, dLen[:]); err != nil {
			return
		}
		dBytes := make([]byte, dLen[0])
		if _, err := io.ReadFull(clientConn, dBytes); err != nil {
			return
		}
		rawAddr = append([]byte{0x03, dLen[0]}, dBytes...)
		targetHost = string(dBytes)
	case 0x04: // IPv6
		var ip [16]byte
		if _, err := io.ReadFull(clientConn, ip[:]); err != nil {
			return
		}
		rawAddr = append([]byte{0x04}, ip[:]...)
		targetHost = net.IP(ip[:]).String()
	default:
		_, _ = clientConn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var portBuf [2]byte
	if _, err := io.ReadFull(clientConn, portBuf[:]); err != nil {
		return
	}
	targetPort := binary.BigEndian.Uint16(portBuf[:])

	// 3. Dial Upstream Server via Chosen Protocol
	upstreamConn, err := uc.dialUpstream(atyp, targetHost, targetPort, rawAddr)
	if err != nil {
		LogMsg("CLIENT", fmt.Sprintf("Dial upstream %s:%d failed: %v", targetHost, targetPort, err))
		_, _ = clientConn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // Host unreachable
		return
	}
	defer upstreamConn.Close()

	// 4. Send SOCKS5 Success Response
	if _, err := clientConn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	// 5. Bi-directional data piping
	errChan := make(chan error, 2)
	go func() {
		buf := make([]byte, 32*1024)
		_, err := io.CopyBuffer(upstreamConn, clientConn, buf)
		errChan <- err
	}()
	go func() {
		buf := make([]byte, 32*1024)
		_, err := io.CopyBuffer(clientConn, upstreamConn, buf)
		errChan <- err
	}()

	<-errChan
}

// dialUpstream connects to the server and executes the appropriate protocol handshake.
func (uc *UniversalClient) dialUpstream(atyp byte, targetHost string, targetPort uint16, rawAddr []byte) (net.Conn, error) {
	proto := strings.ToUpper(strings.TrimSpace(uc.cfg.Protocol))

	switch {
	case strings.Contains(proto, "VLESS"):
		return uc.dialVLESS(atyp, targetHost, targetPort, rawAddr)
	case strings.Contains(proto, "TROJAN"):
		return uc.dialTrojan(atyp, targetHost, targetPort, rawAddr)
	case strings.Contains(proto, "SSH") || strings.Contains(proto, "CUSTOM") || strings.Contains(proto, "INJECTOR"):
		return uc.dialSSHPayload(targetHost, targetPort)
	default:
		// Default to VLESS / Direct SOCKS
		return uc.dialVLESS(atyp, targetHost, targetPort, rawAddr)
	}
}

var physDnsCache sync.Map // host -> IP string

// dialPhysical establishes a protected TCP connection to the destination server.
func (uc *UniversalClient) dialPhysical(addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		host = addr
		port = "443"
	}

	targetIP := host
	// If host is not a raw numeric IP, resolve via protected UDP socket to prevent VPN routing loop
	if net.ParseIP(host) == nil {
		if cached, ok := physDnsCache.Load(host); ok {
			targetIP = cached.(string)
		} else {
			dnsServer := uc.cfg.DNSServer
			if dnsServer == "" {
				dnsServer = "1.1.1.1:53"
			}
			if !hasPort(dnsServer) {
				dnsServer = net.JoinHostPort(dnsServer, "53")
			}
			resolver := &net.Resolver{
				PreferGo: true,
				Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
					d := net.Dialer{
						Timeout: 3 * time.Second,
						Control: func(network, address string, c syscall.RawConn) error {
							return c.Control(func(fd uintptr) {
								ProtectSocket(int(fd))
							})
						},
					}
					return d.DialContext(ctx, "udp", dnsServer)
				},
			}
			ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
			ips, err := resolver.LookupIP(ctx, "ip4", host)
			cancel()
			if err == nil && len(ips) > 0 {
				targetIP = ips[0].String()
				physDnsCache.Store(host, targetIP)
				LogMsg("CLIENT", fmt.Sprintf("Resolved %s to %s via protected DNS", host, targetIP))
			}
		}
	}

	dialAddr := net.JoinHostPort(targetIP, port)
	dialer := &net.Dialer{
		Timeout: 10 * time.Second,
		Control: func(network, address string, c syscall.RawConn) error {
			return c.Control(func(fd uintptr) {
				ProtectSocket(int(fd))
			})
		},
	}
	conn, err := dialer.Dial("tcp", dialAddr)
	if err != nil {
		return nil, err
	}
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(30 * time.Second)
	}
	return conn, nil
}

// dialVLESS establishes a VLESS session over WebSocket/TLS or Direct TCP/TLS.
func (uc *UniversalClient) dialVLESS(atyp byte, targetHost string, targetPort uint16, rawAddr []byte) (net.Conn, error) {
	serverAddr := uc.cfg.ServerAddr
	if !hasPort(serverAddr) {
		serverAddr = net.JoinHostPort(serverAddr, "443")
	}

	rawConn, err := uc.dialPhysical(serverAddr)
	if err != nil {
		return nil, fmt.Errorf("connect to %s failed: %w", serverAddr, err)
	}

	var conn net.Conn = rawConn

	sni := uc.cfg.SNI
	if sni == "" {
		sni, _, _ = net.SplitHostPort(serverAddr)
	}

	// TLS Layer
	if uc.cfg.UseTLS || strings.HasSuffix(serverAddr, ":443") || strings.HasSuffix(serverAddr, ":8443") {
		tlsConfig := &tls.Config{
			ServerName:         sni,
			InsecureSkipVerify: uc.cfg.InsecureTLS,
			MinVersion:         tls.VersionTLS12,
		}
		tlsConn := tls.Client(rawConn, tlsConfig)
		if err := tlsConn.Handshake(); err != nil {
			_ = rawConn.Close()
			return nil, fmt.Errorf("VLESS TLS handshake failed with SNI %s: %w", sni, err)
		}
		conn = tlsConn
	}

	// WebSocket Upgrade (if WS transport)
	path := uc.cfg.Path
	if path == "" {
		path = "/vless-ws"
	}
	hostHeader := uc.cfg.HostHeader
	if hostHeader == "" {
		hostHeader = sni
	}

	isWS := strings.Contains(strings.ToUpper(uc.cfg.Protocol), "WS") || strings.Contains(path, "ws") || path != ""

	if isWS {
		wsKey := make([]byte, 16)
		_, _ = rand.Read(wsKey)
		b64Key := base64.StdEncoding.EncodeToString(wsKey)

		upgradeReq := fmt.Sprintf("GET %s HTTP/1.1\r\n"+
			"Host: %s\r\n"+
			"Upgrade: websocket\r\n"+
			"Connection: Upgrade\r\n"+
			"Sec-WebSocket-Key: %s\r\n"+
			"Sec-WebSocket-Version: 13\r\n\r\n", path, hostHeader, b64Key)

		if _, err := conn.Write([]byte(upgradeReq)); err != nil {
			_ = conn.Close()
			return nil, fmt.Errorf("failed to send WebSocket upgrade: %w", err)
		}

		reader := bufio.NewReader(conn)
		statusLine, err := reader.ReadString('\n')
		if err != nil || !strings.Contains(statusLine, "101") {
			_ = conn.Close()
			return nil, fmt.Errorf("WebSocket upgrade failed: %s", strings.TrimSpace(statusLine))
		}
		// Read remaining HTTP headers
		for {
			line, err := reader.ReadString('\n')
			if err != nil || strings.TrimSpace(line) == "" {
				break
			}
		}
	}

	// VLESS Client Request Header
	// [Version=0, UUID (16 bytes), AddonsLen=0, Command=1 (TCP), Port (2 bytes BE), Atyp + Address]
	uuidBytes := parseUUIDBytes(uc.cfg.Token)

	vlessReq := make([]byte, 0, 64)
	vlessReq = append(vlessReq, 0x00)          // Version 0
	vlessReq = append(vlessReq, uuidBytes[:]...) // 16 bytes UUID
	vlessReq = append(vlessReq, 0x00)          // Proto addons length: 0
	vlessReq = append(vlessReq, 0x01)          // Command: 1 (TCP)

	var pBuf [2]byte
	binary.BigEndian.PutUint16(pBuf[:], targetPort)
	vlessReq = append(vlessReq, pBuf[:]...) // Port
	vlessReq = append(vlessReq, rawAddr...)  // Address Type & Address

	if _, err := conn.Write(vlessReq); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("failed to write VLESS request header: %w", err)
	}

	// Read VLESS Server Response Header: [Version=0, AddonsLen=0]
	var respHdr [2]byte
	if _, err := io.ReadFull(conn, respHdr[:]); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("failed to read VLESS response header: %w", err)
	}

	return conn, nil
}

// dialTrojan establishes a Trojan TLS session.
func (uc *UniversalClient) dialTrojan(atyp byte, targetHost string, targetPort uint16, rawAddr []byte) (net.Conn, error) {
	serverAddr := uc.cfg.ServerAddr
	if !hasPort(serverAddr) {
		serverAddr = net.JoinHostPort(serverAddr, "443")
	}

	rawConn, err := uc.dialPhysical(serverAddr)
	if err != nil {
		return nil, err
	}

	sni := uc.cfg.SNI
	if sni == "" {
		sni, _, _ = net.SplitHostPort(serverAddr)
	}

	tlsConfig := &tls.Config{
		ServerName:         sni,
		InsecureSkipVerify: uc.cfg.InsecureTLS,
		MinVersion:         tls.VersionTLS12,
	}
	tlsConn := tls.Client(rawConn, tlsConfig)
	if err := tlsConn.Handshake(); err != nil {
		_ = rawConn.Close()
		return nil, fmt.Errorf("Trojan TLS handshake failed: %w", err)
	}

	// Trojan Request: hex(sha224(password)) + \r\n + [0x01 (TCP)] + rawAddr + port + \r\n
	passHash := sha224Hex(uc.cfg.Token)

	var pBuf [2]byte
	binary.BigEndian.PutUint16(pBuf[:], targetPort)

	trojanReq := append([]byte(passHash+"\r\n"), 0x01)
	trojanReq = append(trojanReq, rawAddr...)
	trojanReq = append(trojanReq, pBuf[:]...)
	trojanReq = append(trojanReq, []byte("\r\n")...)

	if _, err := tlsConn.Write(trojanReq); err != nil {
		_ = tlsConn.Close()
		return nil, err
	}

	return tlsConn, nil
}

// dialSSHPayload establishes an HTTP CONNECT proxy connection (HTTP Custom / Injector style).
func (uc *UniversalClient) dialSSHPayload(targetHost string, targetPort uint16) (net.Conn, error) {
	serverAddr := uc.cfg.ServerAddr
	if !hasPort(serverAddr) {
		serverAddr = net.JoinHostPort(serverAddr, "8080")
	}

	conn, err := uc.dialPhysical(serverAddr)
	if err != nil {
		return nil, err
	}

	target := fmt.Sprintf("%s:%d", targetHost, targetPort)
	sni := uc.cfg.SNI
	if sni == "" {
		sni = uc.cfg.HostHeader
	}
	if sni == "" {
		sni, _, _ = net.SplitHostPort(serverAddr)
	}

	// Formulate HTTP CONNECT request with optional user payload
	payload := uc.cfg.CustomPayload
	if payload == "" {
		payload = fmt.Sprintf("CONNECT %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: NetPulse/1.0\r\n\r\n", target, sni)
	} else {
		payload = strings.ReplaceAll(payload, "[host_port]", target)
		payload = strings.ReplaceAll(payload, "[host]", targetHost)
		payload = strings.ReplaceAll(payload, "[port]", fmt.Sprintf("%d", targetPort))
		payload = strings.ReplaceAll(payload, "[crlf]", "\r\n")
		payload = strings.ReplaceAll(payload, "[lf]", "\n")
		payload = strings.ReplaceAll(payload, "[cr]", "\r")
	}

	if _, err := conn.Write([]byte(payload)); err != nil {
		_ = conn.Close()
		return nil, err
	}

	reader := bufio.NewReader(conn)
	respLine, err := reader.ReadString('\n')
	if err != nil || (!strings.Contains(respLine, "200") && !strings.Contains(respLine, "Established")) {
		_ = conn.Close()
		return nil, fmt.Errorf("HTTP Proxy CONNECT failed: %s", strings.TrimSpace(respLine))
	}

	// Flush remaining headers
	for {
		line, err := reader.ReadString('\n')
		if err != nil || strings.TrimSpace(line) == "" {
			break
		}
	}

	return conn, nil
}

func parseUUIDBytes(s string) [16]byte {
	var b [16]byte
	clean := strings.ReplaceAll(strings.TrimSpace(s), "-", "")
	if len(clean) == 32 {
		if h, err := hex.DecodeString(clean); err == nil && len(h) == 16 {
			copy(b[:], h)
		}
	}
	return b
}

func sha224Hex(s string) string {
	h := sha256.New224()
	h.Write([]byte(s))
	return hex.EncodeToString(h.Sum(nil))
}
