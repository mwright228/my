package protocol

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
)

const (
	Magic0 = 0x54 // 'T'
	Magic1 = 0x42 // 'B'
	Version1 = 0x01

	CmdConnect     byte = 0x01
	CmdConnectResp byte = 0x02
	CmdData        byte = 0x03
	CmdClose       byte = 0x04
	CmdPing        byte = 0x05
	CmdPong        byte = 0x06

	AddrTypeIPv4   byte = 0x01
	AddrTypeDomain byte = 0x03
	AddrTypeIPv6   byte = 0x04

	RespSuccess       byte = 0x00
	RespAuthFailed    byte = 0x01
	RespDialFailed    byte = 0x02
	RespQuotaExceeded byte = 0x03

	HeaderLen = 10
	MaxPayloadLen = 65535
)

var (
	ErrInvalidMagic   = errors.New("invalid protocol magic")
	ErrInvalidVersion = errors.New("unsupported protocol version")
	ErrPayloadTooBig  = errors.New("payload length exceeds maximum allowed")
	ErrMalformedAddr  = errors.New("malformed address in connect request")
)

type Header struct {
	Magic    [2]byte
	Version  byte
	Cmd      byte
	StreamID uint32
	Length   uint16
}

type Frame struct {
	Header
	Payload []byte
}

func NewFrame(cmd byte, streamID uint32, payload []byte) (*Frame, error) {
	if len(payload) > MaxPayloadLen {
		return nil, ErrPayloadTooBig
	}
	return &Frame{
		Header: Header{
			Magic:    [2]byte{Magic0, Magic1},
			Version:  Version1,
			Cmd:      cmd,
			StreamID: streamID,
			Length:   uint16(len(payload)),
		},
		Payload: payload,
	}, nil
}

func ReadFrame(r io.Reader) (*Frame, error) {
	var hdrBuf [HeaderLen]byte
	if _, err := io.ReadFull(r, hdrBuf[:]); err != nil {
		return nil, err
	}

	if hdrBuf[0] != Magic0 || hdrBuf[1] != Magic1 {
		if strings.HasPrefix(string(hdrBuf[:]), "HTTP/") {
			return nil, fmt.Errorf("server returned HTTP response %q (VPS needs 'mubx-update --force' or mubx-tbrutal is not running)", strings.TrimSpace(string(hdrBuf[:])))
		}
		return nil, ErrInvalidMagic
	}
	if hdrBuf[2] != Version1 {
		return nil, ErrInvalidVersion
	}

	cmd := hdrBuf[3]
	streamID := binary.BigEndian.Uint32(hdrBuf[4:8])
	payloadLen := binary.BigEndian.Uint16(hdrBuf[8:10])

	var payload []byte
	if payloadLen > 0 {
		payload = make([]byte, payloadLen)
		if _, err := io.ReadFull(r, payload); err != nil {
			return nil, err
		}
	}

	return &Frame{
		Header: Header{
			Magic:    [2]byte{Magic0, Magic1},
			Version:  Version1,
			Cmd:      cmd,
			StreamID: streamID,
			Length:   payloadLen,
		},
		Payload: payload,
	}, nil
}

func (f *Frame) Encode() []byte {
	buf := make([]byte, HeaderLen+len(f.Payload))
	buf[0] = f.Magic[0]
	buf[1] = f.Magic[1]
	buf[2] = f.Version
	buf[3] = f.Cmd
	binary.BigEndian.PutUint32(buf[4:8], f.StreamID)
	binary.BigEndian.PutUint16(buf[8:10], f.Length)
	if len(f.Payload) > 0 {
		copy(buf[HeaderLen:], f.Payload)
	}
	return buf
}

func WriteFrame(w io.Writer, f *Frame) error {
	_, err := w.Write(f.Encode())
	return err
}

func EncodeConnectPayload(token string, addrType byte, host string, port uint16) ([]byte, error) {
	tokenBytes := []byte(token)
	if len(tokenBytes) > 255 {
		return nil, errors.New("auth token exceeds 255 bytes")
	}

	var addrBytes []byte
	switch addrType {
	case AddrTypeIPv4:
		ip := net.ParseIP(host).To4()
		if ip == nil {
			return nil, fmt.Errorf("invalid IPv4 address: %s", host)
		}
		addrBytes = ip
	case AddrTypeDomain:
		hBytes := []byte(host)
		if len(hBytes) > 255 {
			return nil, errors.New("domain name exceeds 255 bytes")
		}
		addrBytes = append([]byte{byte(len(hBytes))}, hBytes...)
	case AddrTypeIPv6:
		ip := net.ParseIP(host).To16()
		if ip == nil {
			return nil, fmt.Errorf("invalid IPv6 address: %s", host)
		}
		addrBytes = ip
	default:
		return nil, fmt.Errorf("unsupported address type: %d", addrType)
	}

	totalLen := 1 + len(tokenBytes) + 1 + len(addrBytes) + 2
	buf := make([]byte, totalLen)
	offset := 0

	buf[offset] = byte(len(tokenBytes))
	offset++
	copy(buf[offset:], tokenBytes)
	offset += len(tokenBytes)

	buf[offset] = addrType
	offset++
	copy(buf[offset:], addrBytes)
	offset += len(addrBytes)

	binary.BigEndian.PutUint16(buf[offset:offset+2], port)
	return buf, nil
}

func DecodeConnectPayload(payload []byte) (token string, addrType byte, host string, port uint16, err error) {
	if len(payload) < 4 {
		return "", 0, "", 0, ErrMalformedAddr
	}

	tokenLen := int(payload[0])
	offset := 1
	if len(payload) < offset+tokenLen+1 {
		return "", 0, "", 0, ErrMalformedAddr
	}

	token = string(payload[offset : offset+tokenLen])
	offset += tokenLen

	addrType = payload[offset]
	offset++

	switch addrType {
	case AddrTypeIPv4:
		if len(payload) < offset+4+2 {
			return "", 0, "", 0, ErrMalformedAddr
		}
		host = net.IP(payload[offset : offset+4]).String()
		offset += 4
	case AddrTypeDomain:
		if len(payload) < offset+1 {
			return "", 0, "", 0, ErrMalformedAddr
		}
		dLen := int(payload[offset])
		offset++
		if len(payload) < offset+dLen+2 {
			return "", 0, "", 0, ErrMalformedAddr
		}
		host = string(payload[offset : offset+dLen])
		offset += dLen
	case AddrTypeIPv6:
		if len(payload) < offset+16+2 {
			return "", 0, "", 0, ErrMalformedAddr
		}
		host = net.IP(payload[offset : offset+16]).String()
		offset += 16
	default:
		return "", 0, "", 0, fmt.Errorf("unsupported addr type: %d", addrType)
	}

	port = binary.BigEndian.Uint16(payload[offset : offset+2])
	return token, addrType, host, port, nil
}
