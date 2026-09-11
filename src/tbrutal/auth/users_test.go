package auth

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestAuthStore(t *testing.T) {
	tmpDir := t.TempDir()
	usersFile := filepath.Join(tmpDir, "users.json")

	content := `[
		{"name":"alice","uuid":"token-alice","status":"active","protocols":["all"]},
		{"name":"bob","uuid":"token-bob","status":"frozen","protocols":["all"]},
		{"name":"carol","uuid":"token-carol","status":"active","protocols":["ssh"]},
		{"name":"dave","uuid":"token-dave","status":"active","protocols":["tbrutal"]}
	]`
	if err := os.WriteFile(usersFile, []byte(content), 0600); err != nil {
		t.Fatalf("failed to write users file: %v", err)
	}

	store := NewStore(usersFile)

	if ok, name := store.Authenticate("token-alice"); !ok || name != "alice" {
		t.Errorf("expected alice authenticated, got ok=%v, name=%s", ok, name)
	}
	if ok, _ := store.Authenticate("token-bob"); ok {
		t.Errorf("frozen user bob should fail authentication")
	}
	if ok, _ := store.Authenticate("token-carol"); ok {
		t.Errorf("user carol with only ssh should fail tbrutal authentication")
	}
	if ok, name := store.Authenticate("token-dave"); !ok || name != "dave" {
		t.Errorf("expected dave authenticated, got ok=%v, name=%s", ok, name)
	}
	if ok, _ := store.Authenticate("token-unknown"); ok {
		t.Errorf("unknown token should fail authentication")
	}
}

func TestAuthenticateExpiry(t *testing.T) {
	tmpDir := t.TempDir()
	usersFile := filepath.Join(tmpDir, "users.json")
	content := `[
		{"name":"expired","uuid":"token-expired","status":"active","expiry":"2020-01-01","protocols":["all"]},
		{"name":"today","uuid":"token-today","status":"active","expiry":"2026-09-11","protocols":["all"]},
		{"name":"future","uuid":"token-future","status":"active","expiry":"2099-01-01","protocols":["all"]},
		{"name":"never","uuid":"token-never","status":"active","expiry":"never","protocols":["all"]},
		{"name":"malformed","uuid":"token-malformed","status":"active","expiry":"not-a-date","protocols":["all"]}
	]`
	if err := os.WriteFile(usersFile, []byte(content), 0600); err != nil {
		t.Fatal(err)
	}

	store := NewStore(usersFile)
	if ok, _ := store.Authenticate("token-expired"); ok {
		t.Fatal("expired user authenticated")
	}
	if ok, _ := store.Authenticate("token-future"); !ok {
		t.Fatal("future user was rejected")
	}
	if ok, _ := store.Authenticate("token-never"); !ok {
		t.Fatal("never-expiring user was rejected")
	}
	if ok, _ := store.Authenticate("token-malformed"); ok {
		t.Fatal("malformed expiry authenticated")
	}

	// The store uses the current day boundary for YYYY-MM-DD expiry; make the
	// helper-level expectation explicit without coupling the test to local TZ.
	if !isExpired("2020-01-01", time.Now()) {
		t.Fatal("known past expiry should be expired")
	}
}

func TestAuthStoreFailsClosedWhenFileDisappearsOrBreaks(t *testing.T) {
	tmpDir := t.TempDir()
	usersFile := filepath.Join(tmpDir, "users.json")
	good := `[{"name":"alice","uuid":"token-alice","status":"active","protocols":["all"]}]`
	if err := os.WriteFile(usersFile, []byte(good), 0600); err != nil {
		t.Fatal(err)
	}

	store := NewStore(usersFile)
	if ok, _ := store.Authenticate("token-alice"); !ok {
		t.Fatal("valid user should authenticate")
	}

	if err := os.Remove(usersFile); err != nil {
		t.Fatal(err)
	}
	if ok, _ := store.Authenticate("token-alice"); ok {
		t.Fatal("authentication must fail closed when user store disappears")
	}

	if err := os.WriteFile(usersFile, []byte("not-json"), 0600); err != nil {
		t.Fatal(err)
	}
	if ok, _ := store.Authenticate("token-alice"); ok {
		t.Fatal("authentication must fail closed on malformed user store")
	}
}
