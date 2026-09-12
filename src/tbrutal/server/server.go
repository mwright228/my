package server

import (
	"bytes"
	"context"
	"crypto/sha1"
	"encoding/base64"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	"github.com/mwright228/my/src/tbrutal/auth"
	"github.com/mwright228/my/src/tbrutal/pacer"
	"github.com/mwright228/my/src/tbrutal/protocol"
	ws "github.com/mwright228/my/src/tbrutal/websocket"
)

type Config struct {
	ListenAddr string
	UsersFile  string
	RateMbps   int
	// AllowPrivateTargetsForTests must remain false for production servers. It
	// exists only so deterministic local integration tests can relay to a
	// loopback echo server without weakening the production SSRF policy.
	AllowPrivateTargetsForTests bool
}

type prefixConn struct {
	net.Conn
	reader io.Reader
}

func (c *prefixConn) Read(p []byte) (int, error) { return c.reader.Read(p) }

type chanListener struct {
	addr    net.Addr
	conns   chan net.Conn
	closed  atomic.Bool
	closeCh chan struct{}
}

func newChanListener(addr net.Addr) *chanListener {
	return &chanListener{addr: addr, conns: make(chan net.Conn, 256), closeCh: make(chan struct{})}
}

func (l *chanListener) Accept() (net.Conn, error) {
	select {
	case c, ok := <-l.conns:
		if !ok { return nil, net.ErrClosed }
		return c, nil
	case <-l.closeCh:
		return nil, net.ErrClosed
	}
}

func (l *chanListener) Close() error {
	if l.closed.CompareAndSwap(false, true) { close(l.closeCh) }
	return nil
}

func (l *chanListener) Addr() net.Addr {
	if l.addr != nil { return l.addr }
	return &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 18999}
}

func (l *chanListener) Feed(c net.Conn) error {
	if l.closed.Load() { return net.ErrClosed }
	select {
	case l.conns <- c:
		return nil
	case <-l.closeCh:
		return net.ErrClosed
	case <-time.After(3 * time.Second):
		return errors.New("http listener buffer full")
	}
}

type Server struct {
	cfg        Config
	authStore  *auth.Store
	pacer      *pacer.Pacer
	httpServer *http.Server
	listener   net.Listener
	chanLn     *chanListener
	closed     atomic.Bool
}

func NewServer(cfg Config) *Server {
	if cfg.ListenAddr == "" { cfg.ListenAddr = "127.0.0.1:18999" }
	if cfg.UsersFile == "" { cfg.UsersFile = "/etc/mubx/users.json" }

	s := &Server{
		cfg:       cfg,
		authStore: auth.NewStore(cfg.UsersFile),
		pacer:     pacer.NewPacer(cfg.RateMbps),
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/tbrutal", s.handleUpgrade)
	mux.HandleFunc("/ws", s.handleUpgrade)
	mux.HandleFunc("/", s.handleUpgrade)

	s.httpServer = &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    32 << 10,
	}
	return s
}

func websocketAccept(key string) string {
	sum := sha1.Sum([]byte(strings.TrimSpace(key) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
	return base64.StdEncoding.EncodeToString(sum[:])
}

func (s *Server) newSession(conn net.Conn) *Session {
	sess := NewSession(conn, s.authStore, s.pacer)
	sess.allowPrivateTargets = s.cfg.AllowPrivateTargetsForTests
	return sess
}

func (s *Server) handleUpgrade(w http.ResponseWriter, r *http.Request) {
	upg := strings.TrimSpace(strings.ToLower(r.Header.Get("Upgrade")))
	connHdr := strings.ToLower(r.Header.Get("Connection"))
	if (upg != "tbrutal" && upg != "websocket") || !strings.Contains(connHdr, "upgrade") {
		http.Error(w, "Bad Request: T-Brutal Upgrade required", http.StatusBadRequest)
		return
	}

	hj, ok := w.(http.Hijacker)
	if !ok { http.Error(w, "Server does not support hijacking", http.StatusInternalServerError); return }
	conn, buf, err := hj.Hijack()
	if err != nil { return }
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(30 * time.Second)
	}

	accept := ""
	if upg == "websocket" {
		key := strings.TrimSpace(r.Header.Get("Sec-WebSocket-Key"))
		if key == "" { _ = conn.Close(); return }
		accept = websocketAccept(key)
	}

	resp := "HTTP/1.1 101 Switching Protocols\r\n" +
		"Upgrade: " + upg + "\r\n" +
		"Connection: Upgrade\r\n"
	if accept != "" { resp += "Sec-WebSocket-Accept: " + accept + "\r\n" }
	resp += "\r\n"
	if _, err := buf.WriteString(resp); err != nil { _ = conn.Close(); return }
	if err := buf.Flush(); err != nil { _ = conn.Close(); return }

	// Preserve bytes already buffered by net/http while parsing the upgrade.
	baseConn := &prefixConn{Conn: conn, reader: buf}
	var sessionConn net.Conn = baseConn
	if upg == "websocket" {
		// RFC 6455: client frames are masked and server frames are unmasked.
		sessionConn = ws.New(baseConn, true)
	}
	go s.newSession(sessionConn).Handle()
}

func (s *Server) Start() error {
	log.Printf("[*] T-Brutal server listening on %s (Pacer: %d Mbps, Users: %s, Dual: Raw+HTTP)", s.cfg.ListenAddr, s.cfg.RateMbps, s.cfg.UsersFile)
	ln, err := net.Listen("tcp", s.cfg.ListenAddr)
	if err != nil { return err }
	return s.Serve(ln)
}

func (s *Server) Serve(ln net.Listener) error {
	s.listener = ln
	s.chanLn = newChanListener(ln.Addr())
	go func() { _ = s.httpServer.Serve(s.chanLn) }()
	for {
		conn, err := ln.Accept()
		if err != nil {
			if s.closed.Load() { return nil }
			return err
		}
		go s.dispatchConn(conn)
	}
}

func (s *Server) dispatchConn(conn net.Conn) {
	_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	prefix := make([]byte, 2)
	n, err := io.ReadFull(conn, prefix)
	_ = conn.SetReadDeadline(time.Time{})
	if err != nil { _ = conn.Close(); return }

	wrapped := &prefixConn{Conn: conn, reader: io.MultiReader(bytes.NewReader(prefix[:n]), conn)}
	if n >= 2 && prefix[0] == protocol.Magic0 && prefix[1] == protocol.Magic1 {
		if tc, ok := conn.(*net.TCPConn); ok {
			_ = tc.SetNoDelay(true)
			_ = tc.SetKeepAlive(true)
			_ = tc.SetKeepAlivePeriod(30 * time.Second)
		}
		go s.newSession(wrapped).Handle()
		return
	}

	if s.chanLn == nil { _ = wrapped.Close(); return }
	if err := s.chanLn.Feed(wrapped); err != nil { _ = wrapped.Close() }
}

func (s *Server) Stop(ctx context.Context) error {
	s.closed.Store(true)
	if s.listener != nil { _ = s.listener.Close() }
	if s.chanLn != nil { _ = s.chanLn.Close() }
	return s.httpServer.Shutdown(ctx)
}
