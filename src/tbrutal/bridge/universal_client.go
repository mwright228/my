package bridge

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/sha1"
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

	"golang.org/x/crypto/ssh"
)

// UniversalClient provides a multi-protocol SOCKS5 local bridge.
type UniversalClient struct {
	cfg         BridgeConfig
	listener    net.Listener
	closed      atomic.Bool
	stopChan    chan struct{}
	wg          sync.WaitGroup
	sshClient   *ssh.Client
	sshClientMu sync.Mutex
}

func NewUniversalClient(cfg BridgeConfig) *UniversalClient { return &UniversalClient{cfg: cfg, stopChan: make(chan struct{})} }

func (uc *UniversalClient) Start() (int, error) {
	listenAddr := uc.cfg.SocksListenAddr
	if listenAddr == "" { listenAddr = "127.0.0.1:0" }
	ln, err := net.Listen("tcp", listenAddr)
	if err != nil { return 0, fmt.Errorf("failed to bind SOCKS5 listener on %s: %w", listenAddr, err) }
	uc.listener = ln
	tcpAddr, ok := ln.Addr().(*net.TCPAddr)
	if !ok { _ = ln.Close(); return 0, errors.New("failed to get TCP address for SOCKS5 listener") }
	uc.wg.Add(1)
	go uc.acceptLoop()
	LogMsg("CLIENT", fmt.Sprintf("Universal client active on %s for protocol %s", ln.Addr().String(), uc.cfg.Protocol))
	return tcpAddr.Port, nil
}

func (uc *UniversalClient) Stop() {
	if uc.closed.Swap(true) { return }
	close(uc.stopChan)
	if uc.listener != nil { _ = uc.listener.Close() }
	uc.sshClientMu.Lock()
	if uc.sshClient != nil { _ = uc.sshClient.Close(); uc.sshClient = nil }
	uc.sshClientMu.Unlock()
	uc.wg.Wait()
	LogMsg("CLIENT", "Universal client stopped")
}

func (uc *UniversalClient) acceptLoop() {
	defer uc.wg.Done()
	for {
		conn, err := uc.listener.Accept()
		if err != nil {
			if uc.closed.Load() { return }
			time.Sleep(50 * time.Millisecond)
			continue
		}
		go uc.handleSocksConnection(conn)
	}
}

func (uc *UniversalClient) handleSocksConnection(clientConn net.Conn) {
	defer clientConn.Close()
	_ = clientConn.SetDeadline(time.Now().Add(15 * time.Second))
	if tc, ok := clientConn.(*net.TCPConn); ok { _ = tc.SetNoDelay(true) }

	var verAuth [2]byte
	if _, err := io.ReadFull(clientConn, verAuth[:]); err != nil || verAuth[0] != 0x05 { return }
	methods := make([]byte, int(verAuth[1]))
	if _, err := io.ReadFull(clientConn, methods); err != nil { return }
	if _, err := clientConn.Write([]byte{0x05, 0x00}); err != nil { return }

	var reqHdr [4]byte
	if _, err := io.ReadFull(clientConn, reqHdr[:]); err != nil || reqHdr[0] != 0x05 || reqHdr[1] != 0x01 {
		_, _ = clientConn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	atyp := reqHdr[3]
	var targetHost string
	var rawAddr []byte
	switch atyp {
	case 0x01:
		var ip [4]byte
		if _, err := io.ReadFull(clientConn, ip[:]); err != nil { return }
		rawAddr = append([]byte{0x01}, ip[:]...); targetHost = net.IP(ip[:]).String()
	case 0x03:
		var dLen [1]byte
		if _, err := io.ReadFull(clientConn, dLen[:]); err != nil || dLen[0] == 0 { return }
		dBytes := make([]byte, dLen[0])
		if _, err := io.ReadFull(clientConn, dBytes); err != nil { return }
		rawAddr = append([]byte{0x03, dLen[0]}, dBytes...); targetHost = string(dBytes)
	case 0x04:
		var ip [16]byte
		if _, err := io.ReadFull(clientConn, ip[:]); err != nil { return }
		rawAddr = append([]byte{0x04}, ip[:]...); targetHost = net.IP(ip[:]).String()
	default:
		_, _ = clientConn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); return
	}

	var portBuf [2]byte
	if _, err := io.ReadFull(clientConn, portBuf[:]); err != nil { return }
	targetPort := binary.BigEndian.Uint16(portBuf[:])
	_ = clientConn.SetDeadline(time.Time{})

	upstreamConn, err := uc.dialUpstream(atyp, targetHost, targetPort, rawAddr)
	if err != nil {
		LogMsg("CLIENT", fmt.Sprintf("Dial upstream %s:%d failed: %v", targetHost, targetPort, err))
		_, _ = clientConn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer upstreamConn.Close()
	if _, err := clientConn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil { return }

	errChan := make(chan error, 2)
	go func() { _, e := io.CopyBuffer(upstreamConn, clientConn, make([]byte, 32*1024)); errChan <- e }()
	go func() { _, e := io.CopyBuffer(clientConn, upstreamConn, make([]byte, 32*1024)); errChan <- e }()
	<-errChan
}

func (uc *UniversalClient) dialUpstream(atyp byte, targetHost string, targetPort uint16, rawAddr []byte) (net.Conn, error) {
	proto := strings.ToUpper(strings.TrimSpace(uc.cfg.Protocol))
	switch proto {
	case "VLESS_WS":
		return uc.dialVLESS(atyp, targetHost, targetPort, rawAddr)
	case "TROJAN_WS", "TROJAN":
		return uc.dialTrojan(atyp, targetHost, targetPort, rawAddr)
	case "SSH_PAYLOAD", "SSH", "CUSTOM", "INJECTOR", "HTTP_INJECTOR":
		return uc.dialSSH(targetHost, targetPort)
	default:
		return nil, fmt.Errorf("unsupported universal protocol %q", uc.cfg.Protocol)
	}
}

var physDnsCache sync.Map

func isBlockedIP(ip net.IP) bool {
	if ip == nil { return true }
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified()
}

func hasPort(s string) bool {
	_, _, err := net.SplitHostPort(s)
	return err == nil
}

func (uc *UniversalClient) protectedControl() func(network, address string, c syscall.RawConn) error {
	return func(network, address string, c syscall.RawConn) error {
		var protectErr error
		err := c.Control(func(fd uintptr) {
			if !ProtectSocket(int(fd)) { protectErr = errors.New("failed to protect outbound socket from VPN routing loop") }
		})
		if err != nil { return err }
		return protectErr
	}
}

func (uc *UniversalClient) dialPhysical(addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil { host, port = addr, "443" }
	if host == "" { return nil, errors.New("empty physical dial host") }

	targetIP := host
	if net.ParseIP(host) == nil {
		if cached, ok := physDnsCache.Load(host); ok {
			targetIP = cached.(string)
		} else {
			dnsServer := uc.cfg.DNSServer
			if dnsServer == "" { dnsServer = "1.1.1.1:53" }
			if !hasPort(dnsServer) { dnsServer = net.JoinHostPort(dnsServer, "53") }
			resolver := &net.Resolver{
				PreferGo: true,
				Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
					var protectErr error
					d := net.Dialer{Timeout: 3 * time.Second, Control: func(network, address string, c syscall.RawConn) error {
						return c.Control(func(fd uintptr) {
							if !ProtectSocket(int(fd)) { protectErr = errors.New("failed to protect physical DNS socket") }
						})
					}}
					conn, err := d.DialContext(ctx, "udp", dnsServer)
					if protectErr != nil { if conn != nil { _ = conn.Close() }; return nil, protectErr }
					return conn, err
				},
			}
			ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
			ips, lookupErr := resolver.LookupIP(ctx, "ip4", host)
			cancel()
			if lookupErr != nil || len(ips) == 0 { return nil, fmt.Errorf("protected DNS resolution failed for %s", host) }
			for _, ip := range ips {
				if !isBlockedIP(ip) { targetIP = ip.String(); break }
			}
			if net.ParseIP(targetIP) == nil { return nil, errors.New("physical host resolved only to blocked addresses") }
			physDnsCache.Store(host, targetIP)
		}
	}

	dialer := &net.Dialer{Timeout: 10 * time.Second, Control: func(network, address string, c syscall.RawConn) error {
		var protectErr error
		err := c.Control(func(fd uintptr) {
			if !ProtectSocket(int(fd)) { protectErr = errors.New("failed to protect physical TCP socket") }
		})
		if err != nil { return err }
		return protectErr
	}}
	conn, err := dialer.Dial("tcp", net.JoinHostPort(targetIP, port))
	if err != nil { return nil, err }
	if tc, ok := conn.(*net.TCPConn); ok { _ = tc.SetNoDelay(true); _ = tc.SetKeepAlive(true); _ = tc.SetKeepAlivePeriod(30 * time.Second) }
	return conn, nil
}

func (uc *UniversalClient) dialVLESS(atyp byte, targetHost string, targetPort uint16, rawAddr []byte) (net.Conn, error) {
	serverAddr := uc.cfg.ServerAddr
	if !hasPort(serverAddr) { serverAddr = net.JoinHostPort(serverAddr, "443") }
	rawConn, err := uc.dialPhysical(serverAddr)
	if err != nil { return nil, fmt.Errorf("connect to %s failed: %w", serverAddr, err) }
	var conn net.Conn = rawConn

	sni := uc.cfg.SNI
	if sni == "" { sni, _, _ = net.SplitHostPort(serverAddr) }
	if uc.cfg.UseTLS || strings.HasSuffix(serverAddr, ":443") || strings.HasSuffix(serverAddr, ":8443") {
		tlsConn := tls.Client(rawConn, &tls.Config{ServerName: sni, InsecureSkipVerify: uc.cfg.InsecureTLS, MinVersion: tls.VersionTLS12})
		if err := tlsConn.Handshake(); err != nil { _ = rawConn.Close(); return nil, fmt.Errorf("VLESS TLS handshake failed with SNI %s: %w", sni, err) }
		conn = tlsConn
	}

	path := uc.cfg.Path
	if path == "" { path = "/vless-ws" }
	if strings.HasPrefix(path, "%2F") || strings.HasPrefix(path, "%2f") { path = "/" + path[3:] }
	if !strings.HasPrefix(path, "/") { path = "/" + path }
	hostHeader := uc.cfg.HostHeader
	if hostHeader == "" { hostHeader = sni }

	isWS := strings.EqualFold(strings.TrimSpace(uc.cfg.Protocol), "VLESS_WS")
	if isWS {
		wsKey := make([]byte, 16)
		if _, err := rand.Read(wsKey); err != nil { _ = conn.Close(); return nil, fmt.Errorf("generate WebSocket key: %w", err) }
		b64Key := base64.StdEncoding.EncodeToString(wsKey)
		upgradeReq := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: Mozilla/5.0\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nOrigin: https://%s\r\n\r\n", path, hostHeader, b64Key, hostHeader)
		if _, err := conn.Write([]byte(upgradeReq)); err != nil { _ = conn.Close(); return nil, err }
		reader := bufio.NewReader(conn)
		statusLine, err := reader.ReadString('\n')
		if err != nil || !strings.Contains(statusLine, "101") { _ = conn.Close(); return nil, fmt.Errorf("WebSocket upgrade failed: %s", strings.TrimSpace(statusLine)) }
		headers := map[string]string{}
		for {
			line, err := reader.ReadString('\n')
			if err != nil { _ = conn.Close(); return nil, fmt.Errorf("WebSocket response headers failed: %w", err) }
			line = strings.TrimSpace(line)
			if line == "" { break }
			if k, v, ok := strings.Cut(line, ":"); ok { headers[strings.ToLower(strings.TrimSpace(k))] = strings.TrimSpace(v) }
		}
		want := base64.StdEncoding.EncodeToString(func() []byte { h := sha1.Sum([]byte(b64Key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")); return h[:] }())
		if !strings.EqualFold(headers["sec-websocket-accept"], want) { _ = conn.Close(); return nil, errors.New("invalid WebSocket Sec-WebSocket-Accept") }
	}

	uuidBytes := parseUUIDBytes(uc.cfg.Token)
	vlessReq := make([]byte, 0, 64)
	vlessReq = append(vlessReq, 0x00)
	vlessReq = append(vlessReq, uuidBytes[:]...)
	vlessReq = append(vlessReq, 0x00, 0x01)
	var pBuf [2]byte; binary.BigEndian.PutUint16(pBuf[:], targetPort)
	vlessReq = append(vlessReq, pBuf[:]...); vlessReq = append(vlessReq, rawAddr...)
	if _, err := conn.Write(vlessReq); err != nil { _ = conn.Close(); return nil, err }
	var respHdr [2]byte
	if _, err := io.ReadFull(conn, respHdr[:]); err != nil { _ = conn.Close(); return nil, fmt.Errorf("failed to read VLESS response header: %w", err) }
	return conn, nil
}

func (uc *UniversalClient) dialTrojan(atyp byte, targetHost string, targetPort uint16, rawAddr []byte) (net.Conn, error) {
	serverAddr := uc.cfg.ServerAddr
	if !hasPort(serverAddr) { serverAddr = net.JoinHostPort(serverAddr, "443") }
	rawConn, err := uc.dialPhysical(serverAddr)
	if err != nil { return nil, err }
	sni := uc.cfg.SNI
	if sni == "" { sni, _, _ = net.SplitHostPort(serverAddr) }
	tlsConn := tls.Client(rawConn, &tls.Config{ServerName: sni, InsecureSkipVerify: uc.cfg.InsecureTLS, MinVersion: tls.VersionTLS12})
	if err := tlsConn.Handshake(); err != nil { _ = rawConn.Close(); return nil, fmt.Errorf("Trojan TLS handshake failed: %w", err) }
	passHash := sha224Hex(uc.cfg.Token)
	var pBuf [2]byte; binary.BigEndian.PutUint16(pBuf[:], targetPort)
	req := append([]byte(passHash+"\r\n"), 0x01); req = append(req, rawAddr...); req = append(req, pBuf[:]...); req = append(req, []byte("\r\n")...)
	if _, err := tlsConn.Write(req); err != nil { _ = tlsConn.Close(); return nil, err }
	return tlsConn, nil
}

func (uc *UniversalClient) getSSHClient() (*ssh.Client, error) {
	uc.sshClientMu.Lock()
	defer uc.sshClientMu.Unlock()
	if uc.sshClient != nil { return uc.sshClient, nil }
	user, pass := parseSSHUserPass(uc.cfg.Token); if user == "" { user = "root" }
	var authMethods []ssh.AuthMethod; if pass != "" { authMethods = append(authMethods, ssh.Password(pass)) }
	var hostKeyCallback ssh.HostKeyCallback
	if uc.cfg.InsecureTLS {
		hostKeyCallback = ssh.InsecureIgnoreHostKey()
	} else {
		expected := SSHHostKeySHA256()
		if expected == "" { return nil, errors.New("secure SSH requires an SSH host-key SHA256 fingerprint") }
		expected = strings.TrimSpace(expected)
		hostKeyCallback = func(hostname string, remote net.Addr, key ssh.PublicKey) error {
			actual := ssh.FingerprintSHA256(key)
			if actual != expected {
				return fmt.Errorf("SSH host-key fingerprint mismatch for %s: got %s", hostname, actual)
			}
			return nil
		}
	}
	sshConfig := &ssh.ClientConfig{User: user, Auth: authMethods, HostKeyCallback: hostKeyCallback, Timeout: 15 * time.Second}
	serverAddr := uc.cfg.ServerAddr
	if !hasPort(serverAddr) { serverAddr = net.JoinHostPort(serverAddr, "22") }
	sni := uc.cfg.SNI; if sni == "" { sni = uc.cfg.HostHeader }; if sni == "" { sni, _, _ = net.SplitHostPort(serverAddr) }
	payloadUpper := strings.ToUpper(strings.TrimSpace(uc.cfg.CustomPayload))
	isSSL := uc.cfg.UseTLS || strings.HasPrefix(payloadUpper, "SSL") || strings.HasPrefix(payloadUpper, "TLS") || strings.HasSuffix(serverAddr, ":443")
	isDirect := strings.HasPrefix(payloadUpper, "DIRECT") || (uc.cfg.CustomPayload == "" && !isSSL)

	var err error
	var underlyingConn net.Conn
	if isDirect {
		LogMsg("SSH", fmt.Sprintf("Establishing Direct SSH connection to %s as user %s", serverAddr, user))
		underlyingConn, err = uc.dialPhysical(serverAddr)
		if err != nil { return nil, fmt.Errorf("direct ssh dial failed: %w", err) }
	} else if isSSL {
		LogMsg("SSH", fmt.Sprintf("Establishing SSL/TLS SSH tunnel to %s (SNI: %s) as user %s", serverAddr, sni, user))
		rawConn, dErr := uc.dialPhysical(serverAddr); if dErr != nil { return nil, fmt.Errorf("tls ssh dial failed: %w", dErr) }
		tlsConn := tls.Client(rawConn, &tls.Config{ServerName: sni, InsecureSkipVerify: uc.cfg.InsecureTLS, MinVersion: tls.VersionTLS12})
		if hErr := tlsConn.Handshake(); hErr != nil { _ = rawConn.Close(); return nil, fmt.Errorf("tls handshake for ssh failed: %w", hErr) }
		underlyingConn = tlsConn
	} else {
		LogMsg("SSH", fmt.Sprintf("Establishing HTTP Custom SSH tunnel to %s with payload injection", serverAddr))
		rawConn, dErr := uc.dialPhysical(serverAddr); if dErr != nil { return nil, fmt.Errorf("http custom dial failed: %w", dErr) }
		payload := uc.cfg.CustomPayload
		if payload == "" { payload = fmt.Sprintf("CONNECT %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: Mozilla/5.0\r\n\r\n", serverAddr, sni) } else {
			payload = strings.ReplaceAll(payload, "[host_port]", serverAddr); payload = strings.ReplaceAll(payload, "[host]", sni); payload = strings.ReplaceAll(payload, "[port]", "22"); payload = strings.ReplaceAll(payload, "[protocol]", "HTTP/1.1"); payload = strings.ReplaceAll(payload, "[ua]", "Mozilla/5.0"); payload = strings.ReplaceAll(payload, "[raw]", "\r\n"); payload = strings.ReplaceAll(payload, "[crlf]", "\r\n"); payload = strings.ReplaceAll(payload, "[lf]", "\n"); payload = strings.ReplaceAll(payload, "[cr]", "\r")
		}
		if _, wErr := rawConn.Write([]byte(payload)); wErr != nil { _ = rawConn.Close(); return nil, wErr }
		reader := bufio.NewReader(rawConn); respLine, rErr := reader.ReadString('\n')
		if rErr != nil || (!strings.Contains(respLine, "200") && !strings.Contains(respLine, "Established")) { _ = rawConn.Close(); return nil, fmt.Errorf("HTTP Proxy CONNECT failed: %s", strings.TrimSpace(respLine)) }
		for { line, rErr2 := reader.ReadString('\n'); if rErr2 != nil || strings.TrimSpace(line) == "" { break } }
		underlyingConn = rawConn
	}

	c, chans, reqs, err := ssh.NewClientConn(underlyingConn, serverAddr, sshConfig)
	if err != nil { _ = underlyingConn.Close(); return nil, fmt.Errorf("ssh client handshake failed: %w", err) }
	client := ssh.NewClient(c, chans, reqs); uc.sshClient = client
	LogMsg("SSH", fmt.Sprintf("SSH authenticated & tunnel established to %s", serverAddr)); return client, nil
}

func (uc *UniversalClient) dialSSH(targetHost string, targetPort uint16) (net.Conn, error) {
	client, err := uc.getSSHClient(); if err != nil { return nil, err }
	target := net.JoinHostPort(targetHost, fmt.Sprintf("%d", targetPort))
	conn, err := client.Dial("tcp", target)
	if err != nil {
		uc.sshClientMu.Lock(); if uc.sshClient == client { _ = uc.sshClient.Close(); uc.sshClient = nil }; uc.sshClientMu.Unlock()
		return nil, fmt.Errorf("ssh dial to %s failed: %w", target, err)
	}
	return conn, nil
}

func parseSSHUserPass(token string) (string, string) { parts := strings.SplitN(token, ":", 2); if len(parts) == 2 { return parts[0], parts[1] }; return token, "" }
func parseUUIDBytes(s string) [16]byte { var b [16]byte; clean := strings.ReplaceAll(strings.TrimSpace(s), "-", ""); if len(clean) == 32 { if h, err := hex.DecodeString(clean); err == nil && len(h) == 16 { copy(b[:], h) } }; return b }
func sha224Hex(s string) string { h := sha256.New224(); _, _ = h.Write([]byte(s)); return hex.EncodeToString(h.Sum(nil)) }