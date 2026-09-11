package bridge

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/mwright228/my/src/tbrutal/server"
)

func TestProbeBugHostDirect(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = fmt.Fprintln(w, "OK")
	}))
	defer ts.Close()

	res := ProbeBugHost(ts.URL, "", 2000)
	if res.StatusCode != 200 { t.Fatalf("expected status 200, got %d (err: %s)", res.StatusCode, res.ErrorMsg) }
	if res.LatencyMs < 0 { t.Errorf("expected non-negative latency, got %d", res.LatencyMs) }
}

func TestProbeBugHostTLSCertificate(t *testing.T) {
	ts := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusOK) }))
	defer ts.Close()

	res := ProbeBugHost(ts.URL, "example.com", 2000)
	if res.StatusCode != 200 { t.Fatalf("expected status 200, got %d (err: %s)", res.StatusCode, res.ErrorMsg) }
	if res.CertCN == "" && len(res.CertSANs) == 0 { t.Logf("TLS server cert had empty CN/SANs") }
}

func TestStartAndStopTunnel(t *testing.T) {
	SetSocketProtector(func(int) bool { return true })
	defer SetSocketProtector(nil)

	tmpDir := t.TempDir()
	usersFile := filepath.Join(tmpDir, "users.json")
	usersJSON := `[{"name":"testuser","uuid":"valid-secret-token-123","status":"active","protocols":["all"]}]`
	if err := os.WriteFile(usersFile, []byte(usersJSON), 0600); err != nil { t.Fatalf("failed to write users.json: %v", err) }

	serverLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil { t.Fatalf("failed to listen for server: %v", err) }
	defer serverLn.Close()

	srv := server.NewServer(server.Config{
		ListenAddr: serverLn.Addr().String(),
		UsersFile: usersFile,
		RateMbps: 0,
		AllowPrivateTargetsForTests: true,
	})
	go func() { _ = srv.Serve(serverLn) }()
	defer func() { ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second); defer cancel(); _ = srv.Stop(ctx) }()

	cfg := BridgeConfig{
		Protocol: "T_BRUTAL",
		ServerAddr: serverLn.Addr().String(),
		SNI: "test.example.com",
		HostHeader: "test.example.com",
		Path: "/tbrutal",
		Token: "valid-secret-token-123",
		PoolSize: 1,
		RateMbps: 50,
		UseTLS: false,
		RawMode: false,
		SocksListenAddr: "127.0.0.1:0",
	}

	port, err := StartTunnel(cfg)
	if err != nil { t.Fatalf("StartTunnel failed: %v", err) }
	if port <= 0 { t.Fatalf("expected valid port, got %d", port) }
	_, err = StartTunnel(cfg)
	if err == nil { t.Errorf("expected duplicate StartTunnel to fail") }

	StopTunnel()

	port2, err := StartTunnel(cfg)
	if err != nil { t.Fatalf("StartTunnel after stop failed: %v", err) }
	if port2 <= 0 { t.Fatalf("expected valid port after restart, got %d", port2) }
	StopTunnel()
}
