package bridge

import (
	"bytes"
	"net"
	"net/netip"
	"testing"

	M "github.com/sagernet/sing/common/metadata"
)

func TestWriteSocks5UDPDatagramIPv4(t *testing.T) {
	relay, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()

	payload := []byte("hello")
	conn, err := net.DialUDP("udp4", nil, relay.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if err := writeSocks5UDPDatagram(conn, M.Socksaddr{Addr: netip.MustParseAddr("8.8.8.8"), Port: 53}, payload); err != nil {
		t.Fatal(err)
	}

	buf := make([]byte, 64)
	n, _, err := relay.ReadFromUDP(buf)
	if err != nil {
		t.Fatal(err)
	}
	want := append([]byte{0, 0, 0, 1, 8, 8, 8, 8, 0, 53}, payload...)
	if !bytes.Equal(buf[:n], want) {
		t.Fatalf("SOCKS5 UDP frame = %v, want %v", buf[:n], want)
	}
}

func TestParseSocks5UDPIPv6(t *testing.T) {
	payload := []byte("reply")
	packet := append([]byte{0, 0, 0, 4}, netip.MustParseAddr("2001:db8::1").AsSlice()...)
	packet = append(packet, 0x01, 0xbb)
	packet = append(packet, payload...)
	got, err := parseSocks5UDPDatagram(packet)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("payload = %q, want %q", got, payload)
	}
}
