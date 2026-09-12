package server

import (
	"net"
	"testing"
)

func TestIsBlockedIPSpecialUse(t *testing.T) {
	tests := []struct {
		name string
		ip   string
		want bool
	}{
		{name: "loopback v4", ip: "127.0.0.1", want: true},
		{name: "private v4", ip: "10.0.0.1", want: true},
		{name: "carrier grade nat", ip: "100.64.0.1", want: true},
		{name: "documentation v4", ip: "192.0.2.1", want: true},
		{name: "benchmark v4", ip: "198.18.0.1", want: true},
		{name: "documentation v4 second", ip: "198.51.100.1", want: true},
		{name: "documentation v4 third", ip: "203.0.113.1", want: true},
		{name: "loopback v6", ip: "::1", want: true},
		{name: "ula v6", ip: "fd00::1", want: true},
		{name: "discard-only v6", ip: "100::1", want: true},
		{name: "benchmark v6", ip: "2001:2::1", want: true},
		{name: "orchid v6", ip: "2001:10::1", want: true},
		{name: "documentation v6", ip: "2001:db8::1", want: true},
		{name: "public v4", ip: "1.1.1.1", want: false},
		{name: "public v6", ip: "2606:4700:4700::1111", want: false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ip := net.ParseIP(tt.ip)
			if got := isBlockedIP(ip); got != tt.want {
				t.Fatalf("isBlockedIP(%s) = %v, want %v", tt.ip, got, tt.want)
			}
		})
	}
}

func TestIsBlockedIPRejectsInvalidInput(t *testing.T) {
	if !isBlockedIP(nil) {
		t.Fatal("isBlockedIP(nil) = false, want true")
	}
	if !isBlockedIP(net.ParseIP("not-an-ip")) {
		t.Fatal("invalid IP should be blocked")
	}
}
