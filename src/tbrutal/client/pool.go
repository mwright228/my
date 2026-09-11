package client

import (
	"bufio"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/mwright228/my/src/tbrutal/pacer"
	"github.com/mwright228/my/src/tbrutal/protocol"
)

type StreamEntry struct {
	id         uint32
	clientConn net.Conn
	respChan   chan byte
	pConn      *PooledConn
	closed     atomic.Bool
}

type PooledConn struct {
	pool      *Pool
	index     int
	rawConn   net.Conn
	writeMu   sync.Mutex
	closed    atomic.Bool
	closeChan chan struct{}
}

func (pc *PooledConn) sendFrame(f *protocol.Frame) error {
	if pc.closed.Load() {
		return io.ErrClosedPipe
	}
	pc.writeMu.Lock()
	defer pc.writeMu.Unlock()
	return protocol.WriteFrame(pc.rawConn, f)
}

type Pool struct {
	serverAddr   string
	sni          string
	hostHeader   string
	path         string
	token        string
	numConns     int
	useTLS       bool
	insecureTLS  bool
	rawMode      bool
	pacer        *pacer.Pacer
	conns        []*PooledConn
	connsMu      sync.RWMutex
	rrCounter    uint32
	streamsMu    sync.RWMutex
	streams      map[uint32]*StreamEntry
	streamIDGen  uint32
	closed       atomic.Bool
	stopChan     chan struct{}
}

func NewPool(serverAddr, sni, hostHeader, path, token string, numConns int, useTLS, insecureTLS, rawMode bool, p *pacer.Pacer) *Pool {
	if numConns <= 0 {
		numConns = 1
	}
	if path == "" {
		if !rawMode {
			path = "/tbrutal"
		}
	}
	if sni == "" {
		sni, _, _ = net.SplitHostPort(serverAddr)
	}
	if hostHeader == "" {
		h, _, err := net.SplitHostPort(serverAddr)
		if err == nil && h != "" {
			hostHeader = h
		} else {
			hostHeader = sni
		}
	}

	return &Pool{
		serverAddr:  serverAddr,
		sni:         sni,
		hostHeader:  hostHeader,
		path:        path,
		token:       token,
		numConns:    numConns,
		useTLS:      useTLS,
		insecureTLS: insecureTLS,
		rawMode:     rawMode,
		pacer:       p,
		conns:       make([]*PooledConn, numConns),
		streams:     make(map[uint32]*StreamEntry),
		stopChan:    make(chan struct{}),
	}
}

func (p *Pool) Start() error {
	for i := 0; i < p.numConns; i++ {
		go p.maintainConnection(i)
	}

	// Wait for at least one connection to be established
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		if p.HasHealthyConn() {
			return nil
		}
		time.Sleep(200 * time.Millisecond)
	}
	return errors.New("timed out waiting for initial pooled connection to server")
}

func (p *Pool) HasHealthyConn() bool {
	p.connsMu.RLock()
	defer p.connsMu.RUnlock()
	for _, c := range p.conns {
		if c != nil && !c.closed.Load() {
			return true
		}
	}
	return false
}

func (p *Pool) maintainConnection(index int) {
	for {
		if p.closed.Load() {
			return
		}

		pc, err := p.dialSingle(index)
		if err != nil {
			log.Printf("[!] Pool connection [%d] dial failed: %v, retrying in 3s...", index, err)
			time.Sleep(3 * time.Second)
			continue
		}

		p.connsMu.Lock()
		p.conns[index] = pc
		p.connsMu.Unlock()

		modeStr := "HTTP-Upgrade"
		if p.rawMode {
			modeStr = "Raw-TCP-SNI"
		}
		log.Printf("[*] Pool connection [%d] connected to %s (SNI: %s, Host: %s, Mode: %s)", index, p.serverAddr, p.sni, p.hostHeader, modeStr)

		// Start heartbeat loop
		go p.heartbeat(pc)

		// Frame reader loop
		p.readLoop(pc)

		log.Printf("[!] Pool connection [%d] disconnected, reconnecting...", index)
		time.Sleep(1 * time.Second)
	}
}

func (p *Pool) dialSingle(index int) (*PooledConn, error) {
	dialer := &net.Dialer{
		Timeout: 10 * time.Second,
		Control: func(network, address string, c syscall.RawConn) error {
			return c.Control(func(fd uintptr) {
				if SocketProtector != nil {
					SocketProtector(int(fd))
				}
			})
		},
	}
	rawConn, err := dialer.Dial("tcp", p.serverAddr)
	if err != nil {
		return nil, err
	}

	if tc, ok := rawConn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(30 * time.Second)
	}

	var transportConn net.Conn = rawConn

	if p.useTLS {
		tlsConfig := &tls.Config{
			ServerName:         p.sni,
			InsecureSkipVerify: p.insecureTLS,
			MinVersion:         tls.VersionTLS12,
		}
		if !p.rawMode {
			tlsConfig.NextProtos = []string{"http/1.1"}
		}
		tlsConn := tls.Client(rawConn, tlsConfig)
		tlsConn.SetDeadline(time.Now().Add(10 * time.Second))
		if err := tlsConn.Handshake(); err != nil {
			_ = rawConn.Close()
			return nil, fmt.Errorf("TLS handshake error with SNI %s: %w", p.sni, err)
		}
		tlsConn.SetDeadline(time.Time{})
		transportConn = tlsConn
	}

	if p.rawMode {
		// In raw TCP/TLS SNI mode, send an initial ping frame immediately so upstream multiplexers
		// (like MUB-X Chameleon on loopback 18443) immediately sniff the TB\x01 magic without waiting.
		pingFrame, err := protocol.NewFrame(protocol.CmdPing, 0, nil)
		if err != nil {
			_ = transportConn.Close()
			return nil, err
		}
		if err := protocol.WriteFrame(transportConn, pingFrame); err != nil {
			_ = transportConn.Close()
			return nil, fmt.Errorf("send initial ping frame failed: %w", err)
		}

		// Verify round-trip communication with upstream T-Brutal server
		_ = transportConn.SetReadDeadline(time.Now().Add(5 * time.Second))
		respFrame, err := protocol.ReadFrame(transportConn)
		_ = transportConn.SetReadDeadline(time.Time{})
		if err != nil {
			_ = transportConn.Close()
			return nil, fmt.Errorf("handshake verification failed: %w", err)
		}
		if respFrame.Cmd != protocol.CmdPong {
			_ = transportConn.Close()
			return nil, fmt.Errorf("expected CmdPong from server, got %d", respFrame.Cmd)
		}

		return &PooledConn{
			pool:      p,
			index:     index,
			rawConn:   transportConn,
			closeChan: make(chan struct{}),
		}, nil
	}

	// Send HTTP Upgrade Request (RFC 6455 compliant WebSocket headers to pass through CDNs and carrier DPI)
	req := fmt.Sprintf("GET %s HTTP/1.1\r\n"+
		"Host: %s\r\n"+
		"Upgrade: websocket\r\n"+
		"Connection: Upgrade\r\n"+
		"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
		"Sec-WebSocket-Version: 13\r\n"+
		"User-Agent: Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36\r\n"+
		"\r\n", p.path, p.hostHeader)

	if _, err := transportConn.Write([]byte(req)); err != nil {
		_ = transportConn.Close()
		return nil, fmt.Errorf("send upgrade request failed: %w", err)
	}

	// Read HTTP 101 Switching Protocols response
	reader := bufio.NewReader(transportConn)
	statusLine, err := reader.ReadString('\n')
	if err != nil {
		_ = transportConn.Close()
		return nil, fmt.Errorf("read upgrade response failed: %w", err)
	}

	if !strings.Contains(statusLine, "101") {
		var extra strings.Builder
		for i := 0; i < 8; i++ {
			line, err := reader.ReadString('\n')
			if err != nil || strings.TrimSpace(line) == "" {
				break
			}
			trimmed := strings.TrimSpace(line)
			if strings.HasPrefix(strings.ToLower(trimmed), "server:") ||
				strings.HasPrefix(strings.ToLower(trimmed), "content-type:") ||
				strings.HasPrefix(strings.ToLower(trimmed), "via:") ||
				strings.HasPrefix(strings.ToLower(trimmed), "x-cache:") {
				extra.WriteString(" [" + trimmed + "]")
			}
		}
		_ = transportConn.Close()
		return nil, fmt.Errorf("upgrade failed, expected 101 but got: %s%s", strings.TrimSpace(statusLine), extra.String())
	}

	// Consume remaining headers until empty line
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			_ = transportConn.Close()
			return nil, err
		}
		if strings.TrimSpace(line) == "" {
			break
		}
	}

	return &PooledConn{
		pool:      p,
		index:     index,
		rawConn:   transportConn,
		closeChan: make(chan struct{}),
	}, nil
}

func (p *Pool) heartbeat(pc *PooledConn) {
	ticker := time.NewTicker(12 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			if pc.closed.Load() || p.closed.Load() {
				return
			}
			pingFrame, _ := protocol.NewFrame(protocol.CmdPing, 0, nil)
			if err := pc.sendFrame(pingFrame); err != nil {
				pc.closed.Store(true)
				_ = pc.rawConn.Close()
				return
			}
		case <-pc.closeChan:
			return
		case <-p.stopChan:
			return
		}
	}
}

func (p *Pool) readLoop(pc *PooledConn) {
	defer func() {
		pc.closed.Store(true)
		_ = pc.rawConn.Close()

		// Immediately clean up and reset all active streams attached to this dropped connection
		// so browser / app connections don't hang indefinitely waiting for data.
		p.streamsMu.Lock()
		var orphaned []*StreamEntry
		for id, st := range p.streams {
			if st.pConn == pc {
				orphaned = append(orphaned, st)
				delete(p.streams, id)
			}
		}
		p.streamsMu.Unlock()

		for _, st := range orphaned {
			if st.closed.CompareAndSwap(false, true) {
				_ = st.clientConn.Close()
			}
		}
	}()

	for {
		frame, err := protocol.ReadFrame(pc.rawConn)
		if err != nil {
			if !p.closed.Load() && !pc.closed.Load() {
				log.Printf("[!] Pool connection [%d] read error: %v", pc.index, err)
			}
			return
		}

		switch frame.Cmd {
		case protocol.CmdPong:
			// Heartbeat acknowledged

		case protocol.CmdConnectResp:
			p.streamsMu.RLock()
			st, ok := p.streams[frame.StreamID]
			p.streamsMu.RUnlock()
			if ok && len(frame.Payload) > 0 {
				select {
				case st.respChan <- frame.Payload[0]:
				default:
				}
			}

		case protocol.CmdData:
			p.streamsMu.RLock()
			st, ok := p.streams[frame.StreamID]
			p.streamsMu.RUnlock()
			if ok && !st.closed.Load() {
				_, _ = st.clientConn.Write(frame.Payload)
			}

		case protocol.CmdClose:
			p.streamsMu.Lock()
			st, ok := p.streams[frame.StreamID]
			if ok {
				delete(p.streams, frame.StreamID)
			}
			p.streamsMu.Unlock()
			if ok && st.closed.CompareAndSwap(false, true) {
				_ = st.clientConn.Close()
			}
		}
	}
}

func (p *Pool) NextConn() (*PooledConn, error) {
	p.connsMu.RLock()
	defer p.connsMu.RUnlock()

	total := len(p.conns)
	start := int(atomic.AddUint32(&p.rrCounter, 1))

	for i := 0; i < total; i++ {
		idx := (start + i) % total
		c := p.conns[idx]
		if c != nil && !c.closed.Load() {
			return c, nil
		}
	}

	return nil, errors.New("all pool connections are currently offline")
}

func (p *Pool) RegisterStream(clientConn net.Conn) (*StreamEntry, error) {
	pc, err := p.NextConn()
	if err != nil {
		return nil, err
	}

	id := atomic.AddUint32(&p.streamIDGen, 1)
	st := &StreamEntry{
		id:         id,
		clientConn: clientConn,
		respChan:   make(chan byte, 1),
		pConn:      pc,
	}

	p.streamsMu.Lock()
	p.streams[id] = st
	p.streamsMu.Unlock()

	return st, nil
}

func (p *Pool) UnregisterStream(id uint32) {
	p.streamsMu.Lock()
	st, ok := p.streams[id]
	if ok {
		delete(p.streams, id)
	}
	p.streamsMu.Unlock()

	if ok && st.closed.CompareAndSwap(false, true) {
		_ = st.clientConn.Close()
		closeFrame, _ := protocol.NewFrame(protocol.CmdClose, id, nil)
		_ = st.pConn.sendFrame(closeFrame)
	}
}

func (p *Pool) Close() {
	if p.closed.CompareAndSwap(false, true) {
		close(p.stopChan)
		p.connsMu.Lock()
		for _, c := range p.conns {
			if c != nil {
				c.closed.Store(true)
				_ = c.rawConn.Close()
			}
		}
		p.connsMu.Unlock()
	}
}
