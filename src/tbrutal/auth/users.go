package auth

import (
	"encoding/json"
	"os"
	"sync"
	"time"
)

type User struct {
	Name      string   `json:"name"`
	UUID      string   `json:"uuid"`
	Status    string   `json:"status"`
	Protocols []string `json:"protocols"`
	Expiry    string   `json:"expiry"`
	QuotaGB   float64  `json:"quota_gb"`
}

type Store struct {
	mu          sync.RWMutex
	filePath    string
	usersByUUID map[string]User
}

func NewStore(filePath string) *Store {
	s := &Store{
		filePath:    filePath,
		usersByUUID: make(map[string]User),
	}
	s.reload()
	return s
}

func (s *Store) reload() {
	if s.filePath == "" {
		s.mu.Lock()
		s.usersByUUID = make(map[string]User)
		s.mu.Unlock()
		return
	}

	data, err := os.ReadFile(s.filePath)
	if err != nil {
		// Never preserve stale authorization entries after the store disappears
		// or becomes unreadable.
		s.mu.Lock()
		s.usersByUUID = make(map[string]User)
		s.mu.Unlock()
		return
	}

	var users []User
	if err := json.Unmarshal(data, &users); err != nil {
		s.mu.Lock()
		s.usersByUUID = make(map[string]User)
		s.mu.Unlock()
		return
	}

	newMap := make(map[string]User)
	for _, u := range users {
		if u.UUID != "" {
			newMap[u.UUID] = u
		}
	}

	s.mu.Lock()
	s.usersByUUID = newMap
	s.mu.Unlock()
}

func isExpired(expiry string, now time.Time) bool {
	if expiry == "" || expiry == "never" {
		return false
	}

	// mubx-users stores YYYY-MM-DD dates. Treat malformed non-empty values as
	// invalid/expired rather than accidentally granting access.
	expiryTime, err := time.Parse("2006-01-02", expiry)
	if err != nil {
		return true
	}
	return !now.Before(expiryTime.Add(24 * time.Hour))
}

func (s *Store) Authenticate(token string) (bool, string) {
	if token == "" || s.filePath == "" {
		return false, ""
	}

	// Reload on every authorization decision. Provisioning/quota jobs rewrite
	// the store atomically; relying on filesystem mtime can leave stale access
	// when multiple writes happen inside one timestamp-resolution window.
	s.reload()

	s.mu.RLock()
	defer s.mu.RUnlock()

	u, ok := s.usersByUUID[token]
	if !ok {
		return false, ""
	}

	if u.Status == "frozen" || isExpired(u.Expiry, time.Now()) {
		return false, ""
	}

	if len(u.Protocols) == 0 {
		return true, u.Name
	}

	for _, p := range u.Protocols {
		if p == "all" || p == "tbrutal" || p == "antidpi" || p == "stealth" {
			return true, u.Name
		}
	}

	return false, ""
}
