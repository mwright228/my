package bridge

import (
	"bufio"
	"encoding/base64"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

// fakeChameleonProxy stands in for the server's mubx-chameleon universal
// payload proxy: it parses a CONNECT request, records the request line and
// Proxy-Authorization header, answers 200, then echoes the tunnel.
type fakeChameleonProxy struct {
	ln        net.Listener
	requests  chan string
	authz     chan string
	statusOut string
}

func newFakeChameleonProxy(t *testing.T, status string) *fakeChameleonProxy {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("fake chameleon listen: %v", err)
	}
	p := &fakeChameleonProxy{
		ln:        ln,
		requests:  make(chan string, 1),
		authz:     make(chan string, 1),
		statusOut: status,
	}
	go p.serve()
	return p
}

func (p *fakeChameleonProxy) serve() {
	conn, err := p.ln.Accept()
	if err != nil {
		return
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))

	br := bufio.NewReader(conn)
	requestLine, err := br.ReadString('\n')
	if err != nil {
		return
	}
	p.requests <- strings.TrimSpace(requestLine)

	auth := ""
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			return
		}
		if strings.TrimSpace(line) == "" {
			break
		}
		if strings.HasPrefix(strings.ToLower(line), "proxy-authorization:") {
			auth = strings.TrimSpace(line[len("proxy-authorization:"):])
		}
	}
	p.authz <- auth

	if _, err := io.WriteString(conn, p.statusOut); err != nil {
		return
	}
	if !strings.Contains(p.statusOut, "200") {
		return
	}
	_, _ = io.Copy(conn, br)
}

func (p *fakeChameleonProxy) Close() { _ = p.ln.Close() }

// socksRoundTrip drives one SOCKS5 CONNECT through a local UniversalClient and
// returns the echoed payload plus the bytes the CONNECT tunnel carried.
func socksRoundTrip(t *testing.T, uc *UniversalClient, port int, target string, payload string) string {
	t.Helper()
	conn, err := net.DialTimeout("tcp", "127.0.0.1:"+itoa(port), 5*time.Second)
	if err != nil {
		t.Fatalf("dial local SOCKS5: %v", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))

	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		t.Fatalf("socks greeting: %v", err)
	}
	var greeting [2]byte
	if _, err := io.ReadFull(conn, greeting[:]); err != nil {
		t.Fatalf("socks greeting reply: %v", err)
	}
	if greeting[0] != 0x05 || greeting[1] != 0x00 {
		t.Fatalf("unexpected greeting reply %v", greeting)
	}

	host, portStr, err := net.SplitHostPort(target)
	if err != nil {
		t.Fatalf("bad target %q: %v", target, err)
	}
	p, err := net.LookupPort("tcp", portStr)
	if err != nil {
		t.Fatalf("bad target port %q: %v", portStr, err)
	}
	req := []byte{0x05, 0x01, 0x00, 0x03, byte(len(host))}
	req = append(req, host...)
	req = append(req, byte(p>>8), byte(p))
	if _, err := conn.Write(req); err != nil {
		t.Fatalf("socks connect: %v", err)
	}

	var resp [10]byte
	if _, err := io.ReadFull(conn, resp[:]); err != nil {
		t.Fatalf("socks connect reply: %v", err)
	}
	if resp[1] != 0x00 {
		t.Fatalf("socks connect rejected with status %d", resp[1])
	}

	if _, err := conn.Write([]byte(payload)); err != nil {
		t.Fatalf("tunnel write: %v", err)
	}
	echo := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, echo); err != nil {
		t.Fatalf("tunnel read: %v", err)
	}
	return string(echo)
}

func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	var b [8]byte
	pos := len(b)
	for i > 0 {
		pos--
		b[pos] = byte('0' + i%10)
		i /= 10
	}
	return string(b[pos:])
}

// TestChameleonDialSendsAuthenticatedConnect is the end-to-end test for the
// Chameleon engine: the proxy must see a CONNECT to the SOCKS5 target and a
// Basic credential the server's user store can validate.
func TestChameleonDialSendsAuthenticatedConnect(t *testing.T) {
	const (
		user = "alice"
		uuid = "22222222-3333-4444-5555-666666666666"
	)
	proxy := newFakeChameleonProxy(t, "HTTP/1.1 200 Connection established\r\n\r\n")
	defer proxy.Close()

	uc := NewUniversalClient(BridgeConfig{
		Protocol:        "CHAMELEON_HTTP",
		ServerAddr:      proxy.ln.Addr().String(),
		SNI:             "bug-host.example",
		Token:           user + ":" + uuid,
		SocksListenAddr: "127.0.0.1:0",
	})
	port, err := uc.Start()
	if err != nil {
		t.Fatalf("start universal client: %v", err)
	}
	defer uc.Stop()

	echo := socksRoundTrip(t, uc, port, "example.com:80", "HELLO-CHAMELEON")

	if echo != "HELLO-CHAMELEON" {
		t.Fatalf("tunnel echo mismatch: got %q", echo)
	}

	select {
	case line := <-proxy.requests:
		if line != "CONNECT example.com:80 HTTP/1.1" {
			t.Errorf("proxy saw request line %q", line)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("proxy never received a CONNECT request")
	}

	select {
	case header := <-proxy.authz:
		if !strings.HasPrefix(strings.ToLower(header), "basic ") {
			t.Fatalf("expected Basic authorization, got %q", header)
		}
		decoded, err := base64.StdEncoding.DecodeString(strings.TrimSpace(header[len("Basic "):]))
		if err != nil {
			t.Fatalf("decode proxy-authorization: %v", err)
		}
		if got := string(decoded); got != user+":"+uuid {
			t.Errorf("proxy-authorization decoded to %q, want %q", got, user+":"+uuid)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("proxy never received a Proxy-Authorization header")
	}
}

// TestChameleonRejectsNon200 makes sure a refusing proxy surfaces as a SOCKS
// failure instead of a silently broken tunnel.
func TestChameleonRejectsNon200(t *testing.T) {
	proxy := newFakeChameleonProxy(t, "HTTP/1.1 407 Proxy Authentication Required\r\n\r\n")
	defer proxy.Close()

	uc := NewUniversalClient(BridgeConfig{
		Protocol:        "CHAMELEON_HTTP",
		ServerAddr:      proxy.ln.Addr().String(),
		Token:           "alice:22222222-3333-4444-5555-666666666666",
		SocksListenAddr: "127.0.0.1:0",
	})
	port, err := uc.Start()
	if err != nil {
		t.Fatalf("start universal client: %v", err)
	}
	defer uc.Stop()

	conn, err := net.DialTimeout("tcp", "127.0.0.1:"+itoa(port), 5*time.Second)
	if err != nil {
		t.Fatalf("dial local SOCKS5: %v", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))

	_, _ = conn.Write([]byte{0x05, 0x01, 0x00})
	var greeting [2]byte
	if _, err := io.ReadFull(conn, greeting[:]); err != nil {
		t.Fatalf("socks greeting reply: %v", err)
	}
	req := append([]byte{0x05, 0x01, 0x00, 0x03, byte(len("example.com"))}, []byte("example.com")...)
	req = append(req, 0x00, 0x50)
	_, _ = conn.Write(req)

	var resp [10]byte
	if _, err := io.ReadFull(conn, resp[:]); err != nil {
		t.Fatalf("socks connect reply: %v", err)
	}
	if resp[1] == 0x00 {
		t.Fatal("expected the 407 to surface as a SOCKS failure, got success")
	}
}
