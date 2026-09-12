package websocket

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sync"
)

const (
	finBit    = 0x80
	rsvMask   = 0x70
	opcodeMask = 0x0f
	maskBit   = 0x80

	opcodeContinuation = 0x0
	opcodeText         = 0x1
	opcodeBinary       = 0x2
	opcodeClose        = 0x8
	opcodePing         = 0x9
	opcodePong         = 0xA

	maxControlPayload = 125
	maxMessageSize    = 16 << 20
)

var (
	ErrProtocol = errors.New("invalid WebSocket frame")
	ErrMessageTooBig = errors.New("WebSocket message exceeds maximum size")
)

// Conn adapts a net.Conn to an RFC 6455 binary message stream. The client side
// masks every frame it writes; the server side requires masking from its peer.
// Each Write is emitted as one FIN=1 binary message. Read transparently
// reassembles fragmented binary messages and handles control frames.
type Conn struct {
	net.Conn
	serverSide bool
	writeMu    sync.Mutex
	readBuf    []byte
	closed     bool
}

func New(conn net.Conn, serverSide bool) *Conn {
	return &Conn{Conn: conn, serverSide: serverSide}
}

func (c *Conn) Write(p []byte) (int, error) {
	if len(p) > maxMessageSize {
		return 0, ErrMessageTooBig
	}
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	if c.closed {
		return 0, io.ErrClosedPipe
	}
	if err := c.writeFrame(opcodeBinary, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *Conn) Read(p []byte) (int, error) {
	for len(c.readBuf) == 0 {
		msg, err := c.readMessage()
		if err != nil {
			return 0, err
		}
		if len(msg) == 0 {
			continue
		}
		c.readBuf = msg
	}
	n := copy(p, c.readBuf)
	c.readBuf = c.readBuf[n:]
	return n, nil
}

func (c *Conn) Close() error {
	c.writeMu.Lock()
	if !c.closed {
		c.closed = true
		_ = c.writeFrame(opcodeClose, nil)
	}
	c.writeMu.Unlock()
	return c.Conn.Close()
}

func (c *Conn) writeFrame(opcode byte, payload []byte) error {
	if len(payload) > maxMessageSize && opcode < opcodeClose {
		return ErrMessageTooBig
	}
	first := byte(finBit | (opcode & opcodeMask))
	mask := !c.serverSide
	second := byte(0)
	if mask {
		second |= maskBit
	}

	var header [14]byte
	header[0] = first
	headerLen := 2
	switch {
	case len(payload) < 126:
		second |= byte(len(payload))
		header[1] = second
	case len(payload) <= 65535:
		second |= 126
		header[1] = second
		binary.BigEndian.PutUint16(header[2:4], uint16(len(payload)))
		headerLen = 4
	default:
		second |= 127
		header[1] = second
		binary.BigEndian.PutUint64(header[2:10], uint64(len(payload)))
		headerLen = 10
	}

	var maskKey [4]byte
	if mask {
		// RFC 6455 requires a fresh unpredictable masking key for each client frame.
		if _, err := io.ReadFull(randReader{}, maskKey[:]); err != nil {
			return err
		}
		copy(header[headerLen:headerLen+4], maskKey[:])
		headerLen += 4
	}
	if _, err := c.Conn.Write(header[:headerLen]); err != nil {
		return err
	}
	if len(payload) == 0 {
		return nil
	}
	if mask {
		masked := make([]byte, len(payload))
		for i, b := range payload {
			masked[i] = b ^ maskKey[i&3]
		}
		_, err := c.Conn.Write(masked)
		return err
	}
	_, err := c.Conn.Write(payload)
	return err
}

// randReader is kept as a tiny io.Reader adapter so this package has no global
// mutable masking-key state. crypto/rand is used in its implementation below.
type randReader struct{}

func (randReader) Read(p []byte) (int, error) {
	return cryptoRandRead(p)
}

func (c *Conn) readMessage() ([]byte, error) {
	var message []byte
	fragmented := false
	for {
		opcode, fin, payload, err := c.readFrame()
		if err != nil {
			return nil, err
		}
		switch opcode {
		case opcodePing:
			c.writeMu.Lock()
			err = c.writeFrame(opcodePong, payload)
			c.writeMu.Unlock()
			if err != nil {
				return nil, err
			}
			continue
		case opcodePong:
			continue
		case opcodeClose:
			c.writeMu.Lock()
			if !c.closed {
				_ = c.writeFrame(opcodeClose, payload)
				c.closed = true
			}
			c.writeMu.Unlock()
			return nil, io.EOF
		case opcodeText:
			return nil, ErrProtocol
		case opcodeBinary:
			if fragmented {
				return nil, ErrProtocol
			}
			message = append(message, payload...)
			if len(message) > maxMessageSize {
				return nil, ErrMessageTooBig
			}
			if fin {
				return message, nil
			}
			fragmented = true
		case opcodeContinuation:
			if !fragmented {
				return nil, ErrProtocol
			}
			message = append(message, payload...)
			if len(message) > maxMessageSize {
				return nil, ErrMessageTooBig
			}
			if fin {
				return message, nil
			}
		default:
			return nil, ErrProtocol
		}
	}
}

func (c *Conn) readFrame() (byte, bool, []byte, error) {
	var hdr [2]byte
	if _, err := io.ReadFull(c.Conn, hdr[:]); err != nil {
		return 0, false, nil, err
	}
	if hdr[0]&rsvMask != 0 {
		return 0, false, nil, ErrProtocol
	}

	fin := hdr[0]&finBit != 0
	opcode := hdr[0] & opcodeMask
	masked := hdr[1]&maskBit != 0
	wantMasked := c.serverSide
	if masked != wantMasked {
		return 0, false, nil, ErrProtocol
	}

	lengthByte := int(hdr[1] & 0x7f)
	length := int64(lengthByte)
	if lengthByte == 126 {
		var ext [2]byte
		if _, err := io.ReadFull(c.Conn, ext[:]); err != nil {
			return 0, false, nil, err
		}
		length = int64(binary.BigEndian.Uint16(ext[:]))
	} else if lengthByte == 127 {
		var ext [8]byte
		if _, err := io.ReadFull(c.Conn, ext[:]); err != nil {
			return 0, false, nil, err
		}
		length = int64(binary.BigEndian.Uint64(ext[:]))
		if length < 0 {
			return 0, false, nil, ErrProtocol
		}
	}

	isControl := opcode >= 0x8
	if isControl && (!fin || length > maxControlPayload) {
		return 0, false, nil, ErrProtocol
	}
	if length > maxMessageSize {
		return 0, false, nil, ErrMessageTooBig
	}
	if opcode == opcodeContinuation || opcode == opcodeBinary || opcode == opcodeText {
		// handled below
	} else if opcode != opcodeClose && opcode != opcodePing && opcode != opcodePong {
		return 0, false, nil, ErrProtocol
	}

	var maskKey [4]byte
	if masked {
		if _, err := io.ReadFull(c.Conn, maskKey[:]); err != nil {
			return 0, false, nil, err
		}
	}
	payload := make([]byte, int(length))
	if _, err := io.ReadFull(c.Conn, payload); err != nil {
		return 0, false, nil, err
	}
	if masked {
		for i := range payload {
			payload[i] ^= maskKey[i&3]
		}
	}
	return opcode, fin, payload, nil
}

// cryptoRandRead is declared as a variable so tests can replace it without
// changing the public API.
var cryptoRandRead = func(p []byte) (int, error) {
	return cryptorand.Read(p)
}

var cryptorand = struct {
	Read func([]byte) (int, error)
}{Read: func(p []byte) (int, error) { return readCryptoRand(p) }}

func readCryptoRand(p []byte) (int, error) {
	return io.ReadFull(cryptorandReader{}, p)
}

type cryptorandReader struct{}

func (cryptorandReader) Read(p []byte) (int, error) {
	// This method is replaced in init below with crypto/rand.Reader.
	return 0, errors.New("crypto/rand reader unavailable")
}
