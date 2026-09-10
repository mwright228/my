package protocol

import (
	"bytes"
	"testing"
)

func TestFrameEncodeDecode(t *testing.T) {
	payload := []byte("hello world t-brutal")
	f, err := NewFrame(CmdData, 42, payload)
	if err != nil {
		t.Fatalf("NewFrame failed: %v", err)
	}

	encoded := f.Encode()
	buf := bytes.NewReader(encoded)

	decoded, err := ReadFrame(buf)
	if err != nil {
		t.Fatalf("ReadFrame failed: %v", err)
	}

	if decoded.Cmd != CmdData {
		t.Errorf("expected Cmd %d, got %d", CmdData, decoded.Cmd)
	}
	if decoded.StreamID != 42 {
		t.Errorf("expected StreamID 42, got %d", decoded.StreamID)
	}
	if !bytes.Equal(decoded.Payload, payload) {
		t.Errorf("payload mismatch: expected %s, got %s", payload, decoded.Payload)
	}
}

func TestConnectPayloadEncodeDecode(t *testing.T) {
	tests := []struct {
		name     string
		token    string
		addrType byte
		host     string
		port     uint16
	}{
		{"IPv4", "uuid-1234-abcd", AddrTypeIPv4, "1.2.3.4", 8080},
		{"Domain", "uuid-admin-root", AddrTypeDomain, "example.org", 443},
		{"IPv6", "token-ipv6-test", AddrTypeIPv6, "2001:db8::1", 53},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			payload, err := EncodeConnectPayload(tt.token, tt.addrType, tt.host, tt.port)
			if err != nil {
				t.Fatalf("EncodeConnectPayload failed: %v", err)
			}

			token, addrType, host, port, err := DecodeConnectPayload(payload)
			if err != nil {
				t.Fatalf("DecodeConnectPayload failed: %v", err)
			}

			if token != tt.token {
				t.Errorf("expected token %s, got %s", tt.token, token)
			}
			if addrType != tt.addrType {
				t.Errorf("expected addrType %d, got %d", tt.addrType, addrType)
			}
			if host != tt.host {
				t.Errorf("expected host %s, got %s", tt.host, host)
			}
			if port != tt.port {
				t.Errorf("expected port %d, got %d", tt.port, port)
			}
		})
	}
}

func TestInvalidMagic(t *testing.T) {
	data := []byte{0x00, 0x00, 0x01, 0x01, 0, 0, 0, 1, 0, 0}
	_, err := ReadFrame(bytes.NewReader(data))
	if err != ErrInvalidMagic {
		t.Errorf("expected ErrInvalidMagic, got %v", err)
	}
}
