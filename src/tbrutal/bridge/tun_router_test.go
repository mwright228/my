package bridge

import (
	"bytes"
	"context"
	"encoding/binary"
	"net"
	"net/netip"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

func TestSocks5AddressEncoding(t *testing.T) {
	tests := []struct {
		name string
		dst  M.Socksaddr
		want []byte
	}{
		{
			name: "ipv4",
			dst:  M.Socksaddr{Addr: netip.MustParseAddr("1.1.1.1"), Port: 443},
			want: []byte{1, 1, 1, 1, 1, 1, 0xbb},
		},
		{
			name: "ipv6",
			dst:  M.Socksaddr{Addr: netip.MustParseAddr("2001:db8::1"), Port: 443},
			want: append([]byte{4}, append(netip.MustParseAddr("2001:db8::1").AsSlice(), 0x01, 0xbb)...),
		},
		{
			name: "domain",
			dst:  M.Socksaddr{Fqdn: "example.com", Port: 80},
			want: []byte{3, 11, 'e', 'x', 'a', 'm', 'p', 'l', 'e', '.', 'c', 'o', 'm', 0, 80},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := socks5Address(tt.dst)
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(got, tt.want) {
				t.Fatalf("encoded address = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestSocks5AddressRejectsInvalidDomain(t *testing.T) {
	_, err := socks5Address(M.Socksaddr{Fqdn: "bad\nname", Port: 443})
	if err == nil {
		t.Fatal("expected invalid domain to be rejected")
	}
}

func TestSocks5TCPConnectPreservesDomain(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()

	serverErr := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			serverErr <- err
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(time.Second))
		greeting := make([]byte, 3)
		if _, err := ioReadFull(conn, greeting); err != nil {
			serverErr <- err
			return
		}
		if !bytes.Equal(greeting, []byte{5, 1, 0}) {
			serverErr <- &testError{"bad SOCKS5 greeting"}
			return
		}
		if _, err := conn.Write([]byte{5, 0}); err != nil {
			serverErr <- err
			return
		}
		head := make([]byte, 4)
		if _, err := ioReadFull(conn, head); err != nil {
			serverErr <- err
			return
		}
		if !bytes.Equal(head, []byte{5, 1, 0, 3}) {
			serverErr <- &testError{"expected domain CONNECT request"}
			return
		}
		length := []byte{0}
		if _, err := ioReadFull(conn, length); err != nil {
			serverErr <- err
			return
		}
		domain := make([]byte, length[0])
		if _, err := ioReadFull(conn, domain); err != nil {
			serverErr <- err
			return
		}
		port := make([]byte, 2)
		if _, err := ioReadFull(conn, port); err != nil {
			serverErr <- err
			return
		}
		if string(domain) != "example.com" || binary.BigEndian.Uint16(port) != 443 {
			serverErr <- &testError{"SOCKS5 destination was not preserved"}
			return
		}
		_, err = conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 1})
		serverErr <- err
	}()

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	dst := M.Socksaddr{Fqdn: "example.com", Port: 443}
	conn, err := dialSocks5TCP(ctx, listener.Addr().String(), dst)
	if err != nil {
		t.Fatal(err)
	}
	_ = conn.Close()
	if err := <-serverErr; err != nil {
		t.Fatal(err)
	}
}

func TestParseSocks5UDPDatagram(t *testing.T) {
	payload := []byte("reply")
	packet := append([]byte{0, 0, 0, 4}, append(netip.MustParseAddr("2001:db8::1").AsSlice(), append([]byte{0x01, 0xbb}, payload...)...)...)
	got, err := parseSocks5UDPDatagram(packet)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("payload = %q, want %q", got, payload)
	}
}

func TestTunRouterInvalidFD(t *testing.T) {
	if err := StartTunRouter(-1, 10808); err == nil {
		t.Fatal("expected invalid fd to fail closed")
	}
}

type testError struct{ msg string }
func (e *testError) Error() string { return e.msg }

func ioReadFull(conn net.Conn, dst []byte) (int, error) {
	count := 0
	for count < len(dst) {
		n, err := conn.Read(dst[count:])
		count += n
		if err != nil {
			return count, err
		}
		if n == 0 {
			return count, context.Canceled
		}
	}
	return count, nil
}
