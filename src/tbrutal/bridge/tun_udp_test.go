package bridge

import (
	"bytes"
	"encoding/binary"
	"net"
	"testing"
)

func TestWriteSocks5UDP(t *testing.T) {
	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()

	relay, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()

	payload := []byte("hello")
	go func() {
		conn, err := net.DialUDP("udp4", nil, relay.LocalAddr().(*net.UDPAddr))
		if err != nil {
			return
		}
		defer conn.Close()
		_ = writeSocks5UDP(conn, net.IPv4(8, 8, 8, 8), 53, payload)
	}()

	buf := make([]byte, 64)
	n, _, err := relay.ReadFromUDP(buf)
	if err != nil {
		t.Fatal(err)
	}
	want := append([]byte{0, 0, 0, 1, 8, 8, 8, 8, 0, 53}, payload...)
	if !bytes.Equal(buf[:n], want) {
		t.Fatalf("SOCKS5 UDP frame = %v, want %v", buf[:n], want)
	}
	_ = server
	_ = client
}

func TestParseSocks5UDP(t *testing.T) {
	packet := append([]byte{0, 0, 0, 1, 1, 1, 1, 1, 0x01, 0xbb}, []byte("reply")...)
	ip, port, payload, err := parseSocks5UDP(packet)
	if err != nil {
		t.Fatal(err)
	}
	if !ip.Equal(net.IPv4(1, 1, 1, 1)) || port != 443 || !bytes.Equal(payload, []byte("reply")) {
		t.Fatalf("got %s:%d %q", ip, port, payload)
	}
}

func TestReadSocks5ReplyAddr(t *testing.T) {
	input := []byte{4}
	input = append(input, net.ParseIP("2001:db8::1").To16()...)
	var gotPort [2]byte
	binary.BigEndian.PutUint16(gotPort[:], 5353)
	input = append(input, gotPort[:]...)
	addr, err := readSocks5ReplyAddr(bytes.NewReader(input[1:]), 4)
	if err != nil {
		t.Fatal(err)
	}
	if addr != "2001:db8::1" {
		t.Fatalf("got %q", addr)
	}
}
