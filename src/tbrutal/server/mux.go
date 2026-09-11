package server

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"github.com/mwright228/my/src/tbrutal/auth"
	"github.com/mwright228/my/src/tbrutal/pacer"
	"github.com/mwright228/my/src/tbrutal/protocol"
)

const maxStreamsPerSession = 128

var ActiveConns atomic.Int32

type Stream struct {
	id         uint32
	targetConn net.Conn
	closed     atomic.Bool
}

type Session struct {
	conn       net.Conn
	authStore  *auth.Store
	pacer      *pacer.Pacer
	allowPrivateTargets bool
	writeMu    sync.Mutex
	streamsMu  sync.RWMutex
	streams    map[uint32]*Stream
	closed     atomic.Bool
	closeChan  chan struct{}
}

func NewSession(conn net.Conn, authStore *auth.Store, p *pacer.Pacer) *Session {
	return &Session{conn: conn, authStore: authStore, pacer: p, streams: make(map[uint32]*Stream), closeChan: make(chan struct{})}
}

func newTestSession(conn net.Conn, authStore *auth.Store, p *pacer.Pacer) *Session {
	s := NewSession(conn, authStore, p)
	s.allowPrivateTargets = true
	return s
}

func (s *Session) Close() {
	if !s.closed.CompareAndSwap(false, true) {
		return
	}
	close(s.closeChan)
	_ = s.conn.Close()

	s.streamsMu.Lock()
	removed := 0
	for id, st := range s.streams {
		if st.closed.CompareAndSwap(false, true) {
			removed++
		}
		if st.targetConn != nil {
			_ = st.targetConn.Close()
		}
		delete(s.streams, id)
	}
	s.streamsMu.Unlock()
	if removed > 0 {
		ActiveConns.Add(int32(-removed))
	}
}

func (s *Session) sendFrame(f *protocol.Frame) error {
	if s.closed.Load() {
		return io.ErrClosedPipe
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	if s.closed.Load() {
		return io.ErrClosedPipe
	}
	return protocol.WriteFrame(s.conn, f)
}

func (s *Session) Handle() {
	defer s.Close()
	for {
		frame, err := protocol.ReadFrame(s.conn)
		if err != nil {
			log.Printf("[!] T-Brutal session read ended: %v", err)
			return
		}
		switch frame.Cmd {
		case protocol.CmdPing:
			pongFrame, _ := protocol.NewFrame(protocol.CmdPong, frame.StreamID, frame.Payload)
			_ = s.sendFrame(pongFrame)
		case protocol.CmdConnect:
			go s.handleConnect(frame)
		case protocol.CmdData:
			s.handleData(frame)
		case protocol.CmdClose:
			s.handleClose(frame.StreamID)
		}
	}
}

func isBlockedIP(ip net.IP) bool {
	addr, err := netip.ParseAddr(ip.String())
	if err != nil {
		return true
	}
	if addr.IsLoopback() || addr.IsPrivate() || addr.IsLinkLocalUnicast() || addr.IsLinkLocalMulticast() || addr.IsMulticast() || addr.IsUnspecified() {
		return true
	}
	if addr.Is4() {
		a := addr.As4()
		if a[0] == 0 || a[0] >= 240 { return true }
		if a[0] == 100 && a[1] >= 64 && a[1] <= 127 { return true }
		if a[0] == 192 && a[1] == 0 && a[2] == 0 { return true }
		if a[0] == 192 && a[1] == 0 && a[2] == 2 { return true }
		if a[0] == 198 && (a[1] == 18 || a[1] == 19) { return true }
		if a[0] == 198 && a[1] == 51 && a[2] == 100 { return true }
		if a[0] == 203 && a[1] == 0 && a[2] == 113 { return true }
	}
	return false
}

func resolvePublicTarget(host string, port uint16) (string, error) {
	if host == "" || port == 0 { return "", fmt.Errorf("invalid target") }
	if ip := net.ParseIP(host); ip != nil {
		if isBlockedIP(ip) { return "", fmt.Errorf("target address is not allowed") }
		return net.JoinHostPort(ip.String(), fmt.Sprintf("%d", port)), nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	ips, err := net.DefaultResolver.LookupIP(ctx, "ip", host)
	if err != nil || len(ips) == 0 { return "", fmt.Errorf("target resolution failed") }
	for _, ip := range ips {
		if !isBlockedIP(ip) { return net.JoinHostPort(ip.String(), fmt.Sprintf("%d", port)), nil }
	}
	return "", fmt.Errorf("target address is not allowed")
}

func resolveTestTarget(host string, port uint16) (string, error) {
	if host == "" || port == 0 { return "", fmt.Errorf("invalid target") }
	return net.JoinHostPort(host, fmt.Sprintf("%d", port)), nil
}

func (s *Session) resolveTarget(host string, port uint16) (string, error) {
	if s.allowPrivateTargets { return resolveTestTarget(host, port) }
	return resolvePublicTarget(host, port)
}

func (s *Session) rejectConnect(respCode byte, streamID uint32) {
	resp, _ := protocol.NewFrame(protocol.CmdConnectResp, streamID, []byte{respCode})
	_ = s.sendFrame(resp)
}

func (s *Session) handleConnect(frame *protocol.Frame) {
	token, addrType, host, port, err := protocol.DecodeConnectPayload(frame.Payload)
	if err != nil { s.rejectConnect(protocol.RespDialFailed, frame.StreamID); return }
	_ = addrType
	if s.closed.Load() { return }

	ok, _ := s.authStore.Authenticate(token)
	if !ok { s.rejectConnect(protocol.RespAuthFailed, frame.StreamID); return }

	s.streamsMu.Lock()
	if s.closed.Load() || len(s.streams) >= maxStreamsPerSession {
		s.streamsMu.Unlock(); s.rejectConnect(protocol.RespDialFailed, frame.StreamID); return
	}
	if existing, exists := s.streams[frame.StreamID]; exists {
		existing.closed.Store(true)
		if existing.targetConn != nil { _ = existing.targetConn.Close() }
		delete(s.streams, frame.StreamID)
		ActiveConns.Add(-1)
	}
	s.streamsMu.Unlock()

	target, err := s.resolveTarget(host, port)
	if err != nil { s.rejectConnect(protocol.RespDialFailed, frame.StreamID); return }
	targetConn, err := net.DialTimeout("tcp", target, 10*time.Second)
	if err != nil { s.rejectConnect(protocol.RespDialFailed, frame.StreamID); return }
	if tc, ok := targetConn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true); _ = tc.SetKeepAlive(true); _ = tc.SetKeepAlivePeriod(30*time.Second)
	}

	st := &Stream{id: frame.StreamID, targetConn: targetConn}
	s.streamsMu.Lock()
	if s.closed.Load() || len(s.streams) >= maxStreamsPerSession {
		s.streamsMu.Unlock(); _ = targetConn.Close(); s.rejectConnect(protocol.RespDialFailed, frame.StreamID); return
	}
	if existing, exists := s.streams[frame.StreamID]; exists {
		existing.closed.Store(true)
		if existing.targetConn != nil { _ = existing.targetConn.Close() }
		delete(s.streams, frame.StreamID)
		ActiveConns.Add(-1)
	}
	s.streams[frame.StreamID] = st
	ActiveConns.Add(1)
	s.streamsMu.Unlock()

	resp, _ := protocol.NewFrame(protocol.CmdConnectResp, frame.StreamID, []byte{protocol.RespSuccess})
	if err := s.sendFrame(resp); err != nil { s.handleClose(frame.StreamID); return }
	go s.pipeTargetToClient(st)
}

func (s *Session) pipeTargetToClient(st *Stream) {
	defer s.handleClose(st.id)
	buf := make([]byte, 32*1024)
	for {
		if st.closed.Load() || s.closed.Load() { return }
		n, err := st.targetConn.Read(buf)
		if n > 0 {
			s.pacer.Wait(n)
			dataFrame, fErr := protocol.NewFrame(protocol.CmdData, st.id, buf[:n])
			if fErr != nil { return }
			if err := s.sendFrame(dataFrame); err != nil { return }
		}
		if err != nil {
			closeFrame, _ := protocol.NewFrame(protocol.CmdClose, st.id, nil)
			_ = s.sendFrame(closeFrame)
			return
		}
	}
}

func (s *Session) handleData(frame *protocol.Frame) {
	s.streamsMu.RLock(); st, ok := s.streams[frame.StreamID]; s.streamsMu.RUnlock()
	if !ok || st.closed.Load() || s.closed.Load() { return }
	_, _ = st.targetConn.Write(frame.Payload)
}

func (s *Session) handleClose(streamID uint32) {
	s.streamsMu.Lock(); st, ok := s.streams[streamID]; if ok { delete(s.streams, streamID) }; s.streamsMu.Unlock()
	if ok && st.closed.CompareAndSwap(false, true) {
		if st.targetConn != nil { _ = st.targetConn.Close() }
		ActiveConns.Add(-1)
	}
}
