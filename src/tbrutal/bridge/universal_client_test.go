package bridge

import (
	"io"
	"net"
	"testing"
)

func TestUniversalClientLifecycle(t *testing.T) {
	cfg := BridgeConfig{
		Protocol:        "VLESS_WS",
		ServerAddr:      "127.0.0.1:443",
		SNI:             "example.com",
		Token:           "d3b07384-d113-4f05-b1a9-3d122e2a77f2",
		SocksListenAddr: "127.0.0.1:0",
	}

	uc := NewUniversalClient(cfg)
	port, err := uc.Start()
	if err != nil {
		t.Fatalf("Failed to start UniversalClient: %v", err)
	}
	if port <= 0 {
		t.Fatalf("Expected valid listening port, got %d", port)
	}

	// Test SOCKS5 Greeting to the local listener
	conn, err := net.Dial("tcp", uc.listener.Addr().String())
	if err != nil {
		t.Fatalf("Failed to dial local SOCKS5 listener: %v", err)
	}
	defer conn.Close()

	// Send SOCKS5 Greeting: [0x05, 0x01, 0x00]
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		t.Fatalf("Failed to send SOCKS5 greeting: %v", err)
	}
	var resp [2]byte
	if _, err := io.ReadFull(conn, resp[:]); err != nil {
		t.Fatalf("Failed to read SOCKS5 greeting response: %v", err)
	}
	if resp[0] != 0x05 || resp[1] != 0x00 {
		t.Fatalf("Unexpected SOCKS5 greeting response: %v", resp)
	}

	uc.Stop()
}
