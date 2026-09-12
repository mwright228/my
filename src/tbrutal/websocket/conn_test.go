package websocket

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"testing"
)

func TestClientMasksAndServerUnmasks(t *testing.T) {
	clientRaw, serverRaw := net.Pipe()
	client := New(clientRaw, false)
	server := New(serverRaw, true)
	defer client.Close()
	defer server.Close()

	payload := []byte("TB\x01\x03payload")
	done := make(chan error, 1)
	go func() {
		_, err := client.Write(payload)
		done <- err
	}()

	got := make([]byte, len(payload))
	if _, err := io.ReadFull(server, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("payload mismatch: got %q want %q", got, payload)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestServerRejectsUnmaskedClientFrame(t *testing.T) {
	clientRaw, serverRaw := net.Pipe()
	server := New(serverRaw, true)
	defer clientRaw.Close()
	defer server.Close()

	done := make(chan error, 1)
	go func() {
		_, err := clientRaw.Write([]byte{finBit | opcodeBinary, 1, 'x'})
		done <- err
	}()

	buf := make([]byte, 1)
	if _, err := server.Read(buf); err != ErrProtocol {
		t.Fatalf("expected ErrProtocol, got %v", err)
	}
	_ = <-done
}

func TestServerPongsToPingAndReadsPayload(t *testing.T) {
	clientRaw, serverRaw := net.Pipe()
	client := New(clientRaw, false)
	server := New(serverRaw, true)
	defer client.Close()
	defer server.Close()

	go func() {
		_ = writeMaskedFrame(clientRaw, opcodePing, []byte("hello"))
		_ = writeMaskedFrame(clientRaw, opcodeBinary, []byte("data"))
	}()

	got := make([]byte, 4)
	if _, err := io.ReadFull(server, got); err != nil {
		t.Fatal(err)
	}
	if string(got) != "data" {
		t.Fatalf("got %q, want data", got)
	}

	pongHeader := make([]byte, 2)
	if _, err := io.ReadFull(clientRaw, pongHeader); err != nil {
		t.Fatal(err)
	}
	if pongHeader[0] != finBit|opcodePong || pongHeader[1] != 5 {
		t.Fatalf("unexpected pong header %#v", pongHeader)
	}
	pongPayload := make([]byte, 5)
	if _, err := io.ReadFull(clientRaw, pongPayload); err != nil {
		t.Fatal(err)
	}
	if string(pongPayload) != "hello" {
		t.Fatalf("got pong %q, want hello", pongPayload)
	}
}

func TestReadFragmentedBinaryMessage(t *testing.T) {
	clientRaw, serverRaw := net.Pipe()
	client := New(clientRaw, false)
	server := New(serverRaw, true)
	defer client.Close()
	defer server.Close()

	go func() {
		_ = writeMaskedFragment(clientRaw, opcodeBinary, false, []byte("hel"))
		_ = writeMaskedFragment(clientRaw, opcodeContinuation, true, []byte("lo"))
	}()

	buf := make([]byte, 5)
	if _, err := io.ReadFull(server, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != "hello" {
		t.Fatalf("got %q, want hello", buf)
	}
}

func writeMaskedFrame(w io.Writer, opcode byte, payload []byte) error {
	return writeMaskedFragment(w, opcode, true, payload)
}

func writeMaskedFragment(w io.Writer, opcode byte, fin bool, payload []byte) error {
	if len(payload) >= 126 {
		return io.ErrShortBuffer
	}
	first := opcode
	if fin {
		first |= finBit
	}
	mask := [4]byte{1, 2, 3, 4}
	if _, err := w.Write([]byte{first, maskBit | byte(len(payload))}); err != nil {
		return err
	}
	if _, err := w.Write(mask[:]); err != nil {
		return err
	}
	masked := make([]byte, len(payload))
	for i, b := range payload {
		masked[i] = b ^ mask[i&3]
	}
	_, err := w.Write(masked)
	return err
}

func TestExtendedLengthRoundTrip(t *testing.T) {
	clientRaw, serverRaw := net.Pipe()
	client := New(clientRaw, false)
	server := New(serverRaw, true)
	defer client.Close()
	defer server.Close()

	payload := bytes.Repeat([]byte{'x'}, 130)
	go func() { _, _ = client.Write(payload) }()

	got := make([]byte, len(payload))
	if _, err := io.ReadFull(server, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("extended payload mismatch")
	}

	var scratch [10]byte
	binary.BigEndian.PutUint64(scratch[2:], uint64(130))
}
