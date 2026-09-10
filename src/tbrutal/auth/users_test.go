package auth

import (
	"os"
	"path/filepath"
	"testing"
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

	// Alice: active + all -> OK
	ok, name := store.Authenticate("token-alice")
	if !ok || name != "alice" {
		t.Errorf("expected alice authenticated, got ok=%v, name=%s", ok, name)
	}

	// Bob: frozen -> FAIL
	ok, _ = store.Authenticate("token-bob")
	if ok {
		t.Errorf("frozen user bob should fail authentication")
	}

	// Carol: active + ssh only -> FAIL for tbrutal
	ok, _ = store.Authenticate("token-carol")
	if ok {
		t.Errorf("user carol with only ssh should fail tbrutal authentication")
	}

	// Dave: active + tbrutal -> OK
	ok, name = store.Authenticate("token-dave")
	if !ok || name != "dave" {
		t.Errorf("expected dave authenticated, got ok=%v, name=%s", ok, name)
	}

	// Unknown token -> FAIL
	ok, _ = store.Authenticate("token-unknown")
	if ok {
		t.Errorf("unknown token should fail authentication")
	}
}
