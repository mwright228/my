package server

import (
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/mwright228/my/src/tbrutal/auth"
	"github.com/mwright228/my/src/tbrutal/pacer"
)

type Config struct {
	ListenAddr string
	UsersFile  string
	RateMbps   int
}

type Server struct {
	cfg        Config
	authStore  *auth.Store
	pacer      *pacer.Pacer
	httpServer *http.Server
}

func NewServer(cfg Config) *Server {
	if cfg.ListenAddr == "" {
		cfg.ListenAddr = "127.0.0.1:18999"
	}
	if cfg.UsersFile == "" {
		cfg.UsersFile = "/etc/mubx/users.json"
	}

	authStore := auth.NewStore(cfg.UsersFile)
	p := pacer.NewPacer(cfg.RateMbps)

	s := &Server{
		cfg:       cfg,
		authStore: authStore,
		pacer:     p,
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/tbrutal", s.handleUpgrade)
	mux.HandleFunc("/", s.handleUpgrade)

	s.httpServer = &http.Server{
		Addr:         cfg.ListenAddr,
		Handler:      mux,
		ReadTimeout:  0,
		WriteTimeout: 0,
		IdleTimeout:  120 * time.Second,
	}

	return s
}

func (s *Server) handleUpgrade(w http.ResponseWriter, r *http.Request) {
	upg := strings.ToLower(r.Header.Get("Upgrade"))
	connHdr := strings.ToLower(r.Header.Get("Connection"))

	if upg != "tbrutal" && upg != "websocket" && !strings.Contains(connHdr, "upgrade") {
		http.Error(w, "Bad Request: T-Brutal Upgrade required", http.StatusBadRequest)
		return
	}

	hj, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "Server does not support hijacking", http.StatusInternalServerError)
		return
	}

	conn, buf, err := hj.Hijack()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(30 * time.Second)
	}

	// Send HTTP 101 Switching Protocols
	resp := "HTTP/1.1 101 Switching Protocols\r\n" +
		"Upgrade: tbrutal\r\n" +
		"Connection: Upgrade\r\n" +
		"\r\n"
	if _, err := buf.WriteString(resp); err != nil {
		_ = conn.Close()
		return
	}
	if err := buf.Flush(); err != nil {
		_ = conn.Close()
		return
	}

	session := NewSession(conn, s.authStore, s.pacer)
	go session.Handle()
}

func (s *Server) Start() error {
	log.Printf("[*] T-Brutal server listening on %s (Pacer: %d Mbps, Users: %s)", s.cfg.ListenAddr, s.cfg.RateMbps, s.cfg.UsersFile)
	err := s.httpServer.ListenAndServe()
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func (s *Server) Serve(ln net.Listener) error {
	err := s.httpServer.Serve(ln)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func (s *Server) Stop(ctx context.Context) error {
	return s.httpServer.Shutdown(ctx)
}
