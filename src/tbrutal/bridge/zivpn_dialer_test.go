package bridge

import (
	"bytes"
	"testing"
)

func TestSalamanderObfuscator(t *testing.T) {
	key := "zivpn-secret-key"
	obfs := NewSalamanderObfuscator(key)

	original := []byte("Hello, this is a secret payload routed over MUB-X ZiVPN UDP.")
	obfuscated := obfs.Obfuscate(original)

	if bytes.Equal(original, obfuscated) {
		t.Fatalf("Obfuscated data should not match original plaintext")
	}

	deobfuscated := obfs.Deobfuscate(obfuscated)
	if !bytes.Equal(original, deobfuscated) {
		t.Fatalf("Deobfuscated data does not match original! got=%s, want=%s", string(deobfuscated), string(original))
	}

	// Empty key fallback test
	fallbackObfs := NewSalamanderObfuscator("")
	if string(fallbackObfs.key) != "zivpn" {
		t.Fatalf("Default password should be 'zivpn', got=%s", string(fallbackObfs.key))
	}
}

func TestParsePortHopRange(t *testing.T) {
	cases := []struct {
		input     string
		wantStart int
		wantEnd   int
		hasRange  bool
	}{
		{"6000:19999", 6000, 19999, true},
		{"6000-19999", 6000, 19999, true},
		{"5667", 5667, 5667, false},
		{"invalid", 5667, 5667, false},
		{"7000:6000", 5667, 5667, false}, // invalid inverted range
	}

	for _, tc := range cases {
		pool := ParsePortHopRange(tc.input)
		if pool.startPort != tc.wantStart || pool.endPort != tc.wantEnd || pool.hasRange != tc.hasRange {
			t.Errorf("ParsePortHopRange(%q) = %+v, want start=%d end=%d range=%v",
				tc.input, pool, tc.wantStart, tc.wantEnd, tc.hasRange)
		}
	}

	// Test PickPort bounds
	hopPool := ParsePortHopRange("6000:19999")
	for i := 0; i < 50; i++ {
		p := hopPool.PickPort()
		if p < 6000 || p > 19999 {
			t.Fatalf("PickPort() out of bounds: %d", p)
		}
	}
}

func TestZiVPNClientLifecycle(t *testing.T) {
	client := NewZiVPNClient("127.0.0.1", "127.0.0.1", "6000:7000", "mubx-test", "127.0.0.1:0")
	if err := client.Start(); err != nil {
		t.Fatalf("Failed to start ZiVPN client: %v", err)
	}

	addr := client.ListenerAddr()
	if addr == "" || addr == "127.0.0.1:0" {
		t.Fatalf("Expected valid listening address, got: %s", addr)
	}

	client.Stop()
}
