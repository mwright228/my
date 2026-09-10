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
	mu         sync.RWMutex
	filePath   string
	lastMod    time.Time
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
		return
	}

	fi, err := os.Stat(s.filePath)
	if err != nil {
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if !fi.ModTime().After(s.lastMod) && len(s.usersByUUID) > 0 {
		return
	}

	data, err := os.ReadFile(s.filePath)
	if err != nil {
		return
	}

	var users []User
	if err := json.Unmarshal(data, &users); err != nil {
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

func (s *Store) Authenticate(token string) (bool, string) {
	if token == "" {
		return false, ""
	}

	s.reload()

	s.mu.RLock()
	defer s.mu.RUnlock()

	// If no users configured in file, allow default admin token if file missing
	if len(s.usersByUUID) == 0 {
		if _, err := os.Stat(s.filePath); os.IsNotExist(err) {
			return true, "default"
		}
		return false, ""
	}

	u, ok := s.usersByUUID[token]
	if !ok {
		return false, ""
	}

	if u.Status == "frozen" {
		return false, ""
	}

	// Check protocols
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
