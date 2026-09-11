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
	lastMod     time.Time
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
		s.lastMod = time.Time{}
		s.mu.Unlock()
		return
	}

	fi, err := os.Stat(s.filePath)
	if err != nil {
		// Never preserve stale authorization entries after the store disappears.
		s.mu.Lock()
		s.usersByUUID = make(map[string]User)
		s.lastMod = time.Time{}
		s.mu.Unlock()
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if !fi.ModTime().After(s.lastMod) && s.lastMod != (time.Time{}) {
		return
	}

	data, err := os.ReadFile(s.filePath)
	if err != nil {
		s.usersByUUID = make(map[string]User)
		s.lastMod = fi.ModTime()
		return
	}

	var users []User
	if err := json.Unmarshal(data, &users); err != nil {
		s.usersByUUID = make(map[string]User)
		s.lastMod = fi.ModTime()
		return
	}

	newMap := make(map[string]User)
	for _, u := range users {
		if u.UUID != "" {
			newMap[u.UUID] = u
		}
	}

	s.usersByUUID = newMap
	s.lastMod = fi.ModTime()
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
