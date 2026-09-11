package server

import (
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/mwright228/my/src/tbrutal/auth"
	"github.com/mwright228/my/src/tbrutal/pacer"
	"github.com/mwright228/my/src/tbrutal/protocol"
)

type Stream struct {
	id         uint32
	targetConn net.Conn
	closed     atomic.Bool
}

type Session struct {
	conn       net.Conn
	authStore  *auth.Store
	pacer      *pacer.Pacer
	writeMu    sync.Mutex
	streamsMu  sync.RWMutex
	streams    map[uint32]*Stream
	closed     atomic.Bool
	closeChan  chan struct{}
}

func NewSession(conn net.Conn, authStore *auth.Store, p *pacer.Pacer) *Session {
	return &Session{
		conn:      conn,
		authStore: authStore,
		pacer:     p,
		streams:   make(map[uint32]*Stream),
		closeChan: make(chan struct{}),
	}
}

func (s *Session) Close() {
	if s.closed.CompareAndSwap(false, true) {
		close(s.closeChan)
		_ = s.conn.Close()

		s.streamsMu.Lock()
		for id, st := range s.streams {
			st.closed.Store(true)
			if st.targetConn != nil {
				_ = st.targetConn.Close()
			}
			delete(s.streams, id)
		}
		s.streamsMu.Unlock()
	}
}

func (s *Session) sendFrame(f *protocol.Frame) error {
	if s.closed.Load() {
		return io.ErrClosedPipe
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return protocol.WriteFrame(s.conn, f)
}

func (s *Session) Handle() {
	defer s.Close()

	for {
		frame, err := protocol.ReadFrame(s.conn)
		if err != nil {
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

func (s *Session) handleConnect(frame *protocol.Frame) {
	token, addrType, host, port, err := protocol.DecodeConnectPayload(frame.Payload)
	if err != nil {
		resp, _ := protocol.NewFrame(protocol.CmdConnectResp, frame.StreamID, []byte{protocol.RespDialFailed})
		_ = s.sendFrame(resp)
		return
	}
	_ = addrType

	ok, username := s.authStore.Authenticate(token)
	if !ok {
		resp, _ := protocol.NewFrame(protocol.CmdConnectResp, frame.StreamID, []byte{protocol.RespAuthFailed})
		_ = s.sendFrame(resp)
		return
	}

	_ = username
	target := net.JoinHostPort(host, fmt.Sprintf("%d", port))
	targetConn, err := net.DialTimeout("tcp", target, 10*time.Second)
	if err != nil {
		resp, _ := protocol.NewFrame(protocol.CmdConnectResp, frame.StreamID, []byte{protocol.RespDialFailed})
		_ = s.sendFrame(resp)
		return
	}

	if tc, ok := targetConn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(30 * time.Second)
	}

	st := &Stream{
		id:         frame.StreamID,
		targetConn: targetConn,
	}

	s.streamsMu.Lock()
	s.streams[frame.StreamID] = st
	s.streamsMu.Unlock()

	// Send success response
	resp, _ := protocol.NewFrame(protocol.CmdConnectResp, frame.StreamID, []byte{protocol.RespSuccess})
	if err := s.sendFrame(resp); err != nil {
		s.handleClose(frame.StreamID)
		return
	}

	// Start reading from targetConn and sending back to client
	go s.pipeTargetToClient(st)
}

func (s *Session) pipeTargetToClient(st *Stream) {
	defer s.handleClose(st.id)

	buf := make([]byte, 32*1024)
	for {
		if st.closed.Load() || s.closed.Load() {
			return
		}

		n, err := st.targetConn.Read(buf)
		if n > 0 {
			// Userspace Brutal rate pacing: enforce minimum interval / tokens
			s.pacer.Wait(n)

			dataFrame, fErr := protocol.NewFrame(protocol.CmdData, st.id, buf[:n])
			if fErr != nil {
				return
			}
			if err := s.sendFrame(dataFrame); err != nil {
				return
			}
		}

		if err != nil {
			// Remote target closed, send close frame
			closeFrame, _ := protocol.NewFrame(protocol.CmdClose, st.id, nil)
			_ = s.sendFrame(closeFrame)
			return
		}
	}
}

func (s *Session) handleData(frame *protocol.Frame) {
	s.streamsMu.RLock()
	st, ok := s.streams[frame.StreamID]
	s.streamsMu.RUnlock()

	if !ok || st.closed.Load() {
		return
	}

	_, _ = st.targetConn.Write(frame.Payload)
}

func (s *Session) handleClose(streamID uint32) {
	s.streamsMu.Lock()
	st, ok := s.streams[streamID]
	if ok {
		delete(s.streams, streamID)
	}
	s.streamsMu.Unlock()

	if ok && st.closed.CompareAndSwap(false, true) {
		if st.targetConn != nil {
			_ = st.targetConn.Close()
		}
	}
}
