package main_test

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/mwright228/my/src/tbrutal/client"
	"github.com/mwright228/my/src/tbrutal/server"
)

func TestEndToEndProxy(t *testing.T) {
	targetLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil { t.Fatalf("failed to listen on target: %v", err) }
	defer targetLn.Close()

	go func() {
		for {
			conn, err := targetLn.Accept()
			if err != nil { return }
			go func(c net.Conn) { defer c.Close(); _, _ = io.Copy(c, c) }(conn)
		}
	}()

	_, targetPortStr, _ := net.SplitHostPort(targetLn.Addr().String())
	var targetPort uint16
	fmt.Sscanf(targetPortStr, "%d", &targetPort)

	tmpDir := t.TempDir()
	usersFile := filepath.Join(tmpDir, "users.json")
	usersJSON := `[{"name":"testuser","uuid":"valid-secret-token-123","status":"active","protocols":["all"]}]`
	if err := os.WriteFile(usersFile, []byte(usersJSON), 0600); err != nil { t.Fatalf("failed to write users.json: %v", err) }

	serverLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil { t.Fatalf("failed to listen on server: %v", err) }
	defer serverLn.Close()

	srv := server.NewServer(server.Config{
		ListenAddr: serverLn.Addr().String(), UsersFile: usersFile, RateMbps: 0,
		AllowPrivateTargetsForTests: true,
	})
	go func() { _ = srv.Serve(serverLn) }()
	defer func() { ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second); defer cancel(); _ = srv.Stop(ctx) }()

	cli := client.NewClient(client.Config{
		ServerAddr: serverLn.Addr().String(), SNI: "downloads.vodafone.co.uk", Path: "/tbrutal",
		Token: "valid-secret-token-123", LocalSocksAddr: "127.0.0.1:0", NumConns: 2,
		RateMbps: 0, UseTLS: false, InsecureTLS: true,
	})
	if err := cli.Start(); err != nil { t.Fatalf("failed to start client: %v", err) }
	defer cli.Stop()

	socksConn, err := net.DialTimeout("tcp", cli.ListenerAddr(), 5*time.Second)
	if err != nil { t.Fatalf("failed to dial local socks5: %v", err) }
	defer socksConn.Close()

	if _, err = socksConn.Write([]byte{0x05, 0x01, 0x00}); err != nil { t.Fatalf("socks handshake write failed: %v", err) }
	var authResp [2]byte
	if _, err := io.ReadFull(socksConn, authResp[:]); err != nil { t.Fatalf("socks handshake read failed: %v", err) }
	if authResp[0] != 0x05 || authResp[1] != 0x00 { t.Fatalf("unexpected socks auth response: %v", authResp) }

	var reqBuf bytes.Buffer
	reqBuf.Write([]byte{0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1})
	var portBytes [2]byte
	binary.BigEndian.PutUint16(portBytes[:], targetPort)
	reqBuf.Write(portBytes[:])
	if _, err := socksConn.Write(reqBuf.Bytes()); err != nil { t.Fatalf("socks connect write failed: %v", err) }

	var connResp [10]byte
	if _, err := io.ReadFull(socksConn, connResp[:]); err != nil { t.Fatalf("socks connect read failed: %v", err) }
	if connResp[1] != 0x00 { t.Fatalf("socks connect failed with status: %d", connResp[1]) }

	testMsg := []byte("HELLO-T-BRUTAL-CARRIER-TEST")
	if _, err := socksConn.Write(testMsg); err != nil { t.Fatalf("write to socks connection failed: %v", err) }
	echoBuf := make([]byte, len(testMsg))
	if _, err := io.ReadFull(socksConn, echoBuf); err != nil { t.Fatalf("read from socks connection failed: %v", err) }
	if !bytes.Equal(testMsg, echoBuf) { t.Fatalf("echo mismatch: sent %s, got %s", testMsg, echoBuf) }
}

func TestEndToEndProxyIPv6(t *testing.T) {
	targetLn, err := net.Listen("tcp6", "[::1]:0")
	if err != nil { t.Skipf("IPv6 loopback unavailable: %v", err) }
	defer targetLn.Close()

	go func() {
		for {
			conn, err := targetLn.Accept()
			if err != nil { return }
			go func(c net.Conn) { defer c.Close(); _, _ = io.Copy(c, c) }(conn)
		}
	}()

	_, targetPortStr, _ := net.SplitHostPort(targetLn.Addr().String())
	var targetPort uint16
	fmt.Sscanf(targetPortStr, "%d", &targetPort)

	tmpDir := t.TempDir()
	usersFile := filepath.Join(tmpDir, "users.json")
	usersJSON := `[{"name":"testuser","uuid":"valid-secret-token-123","status":"active","protocols":["all"]}]`
	if err := os.WriteFile(usersFile, []byte(usersJSON), 0600); err != nil { t.Fatalf("failed to write users.json: %v", err) }

	serverLn, err := net.Listen("tcp6", "[::1]:0")
	if err != nil { t.Skipf("IPv6 loopback server unavailable: %v", err) }
	defer serverLn.Close()

	srv := server.NewServer(server.Config{
		ListenAddr: serverLn.Addr().String(), UsersFile: usersFile, RateMbps: 0,
		AllowPrivateTargetsForTests: true,
	})
	go func() { _ = srv.Serve(serverLn) }()
	defer func() { ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second); defer cancel(); _ = srv.Stop(ctx) }()

	cli := client.NewClient(client.Config{
		ServerAddr: serverLn.Addr().String(), SNI: "ipv6.test.invalid", Path: "",
		Token: "valid-secret-token-123", LocalSocksAddr: "127.0.0.1:0", NumConns: 2,
		RateMbps: 0, UseTLS: false, InsecureTLS: true, RawMode: true,
	})
	if err := cli.Start(); err != nil { t.Fatalf("failed to start IPv6 client: %v", err) }
	defer cli.Stop()

	socksConn, err := net.DialTimeout("tcp", cli.ListenerAddr(), 5*time.Second)
	if err != nil { t.Fatalf("failed to dial local socks5: %v", err) }
	defer socksConn.Close()
	_ = socksConn.SetDeadline(time.Now().Add(15 * time.Second))

	if _, err = socksConn.Write([]byte{0x05, 0x01, 0x00}); err != nil { t.Fatalf("socks handshake write failed: %v", err) }
	var authResp [2]byte
	if _, err := io.ReadFull(socksConn, authResp[:]); err != nil { t.Fatalf("socks handshake read failed: %v", err) }
	if authResp[0] != 0x05 || authResp[1] != 0x00 { t.Fatalf("unexpected socks auth response: %v", authResp) }

	ip := net.ParseIP("::1")
	var req bytes.Buffer
	req.Write([]byte{0x05, 0x01, 0x00, 0x04})
	req.Write(ip.To16())
	var portBytes [2]byte
	binary.BigEndian.PutUint16(portBytes[:], targetPort)
	req.Write(portBytes[:])
	if _, err := socksConn.Write(req.Bytes()); err != nil { t.Fatalf("IPv6 socks connect write failed: %v", err) }

	var connResp [10]byte
	if _, err := io.ReadFull(socksConn, connResp[:]); err != nil { t.Fatalf("IPv6 socks connect read failed: %v", err) }
	if connResp[1] != 0x00 { t.Fatalf("IPv6 socks connect failed with status: %d", connResp[1]) }

	testMsg := []byte("HELLO-T-BRUTAL-IPV6-CARRIER-TEST")
	if _, err := socksConn.Write(testMsg); err != nil { t.Fatalf("IPv6 write failed: %v", err) }
	echoBuf := make([]byte, len(testMsg))
	if _, err := io.ReadFull(socksConn, echoBuf); err != nil { t.Fatalf("IPv6 read failed: %v", err) }
	if !bytes.Equal(testMsg, echoBuf) { t.Fatalf("IPv6 echo mismatch: sent %s, got %s", testMsg, echoBuf) }
}

func TestEndToEndProxyRawSNI(t *testing.T) {
	targetLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil { t.Fatalf("failed to listen on target: %v", err) }
	defer targetLn.Close()

	go func() {
		for {
			conn, err := targetLn.Accept()
			if err != nil { return }
			go func(c net.Conn) { defer c.Close(); _, _ = io.Copy(c, c) }(conn)
		}
	}()

	_, targetPortStr, _ := net.SplitHostPort(targetLn.Addr().String())
	var targetPort uint16
	fmt.Sscanf(targetPortStr, "%d", &targetPort)

	tmpDir := t.TempDir()
	usersFile := filepath.Join(tmpDir, "users.json")
	usersJSON := `[{"name":"testuser","uuid":"valid-secret-token-123","status":"active","protocols":["all"]}]`
	if err := os.WriteFile(usersFile, []byte(usersJSON), 0600); err != nil { t.Fatalf("failed to write users.json: %v", err) }

	serverLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil { t.Fatalf("failed to listen on server: %v", err) }
	defer serverLn.Close()

	srv := server.NewServer(server.Config{
		ListenAddr: serverLn.Addr().String(), UsersFile: usersFile, RateMbps: 0,
		AllowPrivateTargetsForTests: true,
	})
	go func() { _ = srv.Serve(serverLn) }()
	defer func() { ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second); defer cancel(); _ = srv.Stop(ctx) }()

	cli := client.NewClient(client.Config{
		ServerAddr: serverLn.Addr().String(), SNI: "images.vodafone.co.uk", Token: "valid-secret-token-123",
		LocalSocksAddr: "127.0.0.1:0", NumConns: 2, RateMbps: 0, UseTLS: false,
		InsecureTLS: true, RawMode: true,
	})
	if err := cli.Start(); err != nil { t.Fatalf("failed to start raw client: %v", err) }
	defer cli.Stop()

	socksConn, err := net.DialTimeout("tcp", cli.ListenerAddr(), 5*time.Second)
	if err != nil { t.Fatalf("failed to dial local socks5: %v", err) }
	defer socksConn.Close()

	if _, err = socksConn.Write([]byte{0x05, 0x01, 0x00}); err != nil { t.Fatalf("socks handshake write failed: %v", err) }
	var authResp [2]byte
	if _, err := io.ReadFull(socksConn, authResp[:]); err != nil { t.Fatalf("socks handshake read failed: %v", err) }
	if authResp[0] != 0x05 || authResp[1] != 0x00 { t.Fatalf("unexpected socks auth response: %v", authResp) }

	var reqBuf bytes.Buffer
	reqBuf.Write([]byte{0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1})
	var portBytes [2]byte
	binary.BigEndian.PutUint16(portBytes[:], targetPort)
	reqBuf.Write(portBytes[:])
	if _, err := socksConn.Write(reqBuf.Bytes()); err != nil { t.Fatalf("socks connect write failed: %v", err) }

	var connResp [10]byte
	if _, err := io.ReadFull(socksConn, connResp[:]); err != nil { t.Fatalf("socks connect read failed: %v", err) }
	if connResp[1] != 0x00 { t.Fatalf("socks connect failed with status: %d", connResp[1]) }

	testMsg := []byte("HELLO-T-BRUTAL-RAW-SNI-TEST")
	if _, err := socksConn.Write(testMsg); err != nil { t.Fatalf("write to socks connection failed: %v", err) }
	echoBuf := make([]byte, len(testMsg))
	if _, err := io.ReadFull(socksConn, echoBuf); err != nil { t.Fatalf("read from socks connection failed: %v", err) }
	if !bytes.Equal(testMsg, echoBuf) { t.Fatalf("echo mismatch: sent %s, got %s", testMsg, echoBuf) }
}
