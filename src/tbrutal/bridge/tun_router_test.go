package bridge

import (
	"encoding/binary"
	"net"
	"os"
	"testing"
)

func TestChecksumComputation(t *testing.T) {
	// Standard test header
	data := []byte{0x45, 0x00, 0x00, 0x3c, 0x1c, 0x46, 0x40, 0x00, 0x40, 0x06, 0x00, 0x00, 0xac, 0x10, 0x0a, 0x63, 0xac, 0x10, 0x0a, 0x0c}
	csum := computeChecksum(data)
	if csum == 0 {
		t.Fatalf("Computed checksum should be non-zero")
	}

	// When checksum is inserted into header, checksum of entire header must be 0
	binary.BigEndian.PutUint16(data[10:12], csum)
	verify := computeChecksum(data)
	if verify != 0 {
		t.Fatalf("Checksum verification failed: got 0x%04x, want 0", verify)
	}
}

func TestCraftPackets(t *testing.T) {
	src := net.ParseIP("172.19.0.2")
	dst := net.ParseIP("1.1.1.1")
	payload := []byte("ping test")

	udpPkt := craftUDPPacket(src, dst, 12345, 53, payload)
	if len(udpPkt) != 20+8+len(payload) {
		t.Fatalf("Unexpected UDP packet length: %d", len(udpPkt))
	}
	if udpPkt[9] != 17 {
		t.Fatalf("Expected protocol 17 (UDP), got %d", udpPkt[9])
	}

	tcpPkt := craftTCPPacket(src, dst, 54321, 443, 100, 200, 0x18, payload)
	if len(tcpPkt) != 20+20+len(payload) {
		t.Fatalf("Unexpected TCP packet length: %d", len(tcpPkt))
	}
	if tcpPkt[9] != 6 {
		t.Fatalf("Expected protocol 6 (TCP), got %d", tcpPkt[9])
	}
}

func TestTunRouterLifecycle(t *testing.T) {
	// Use os.Pipe as a mock /dev/net/tun file descriptor
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatalf("Failed to create pipe: %v", err)
	}
	defer w.Close()

	fd := int(r.Fd())
	err = StartTunRouter(fd, 10808)
	if err != nil {
		t.Fatalf("StartTunRouter failed: %v", err)
	}

	// Should prevent multiple concurrent instances
	errDuplicate := StartTunRouter(fd, 10808)
	if errDuplicate == nil {
		t.Fatalf("Expected error when starting already active router")
	}

	_ = w.Close()
	StopTunRouter()
}
