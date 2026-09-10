package pacer

import (
	"sync"
	"time"
)

// Pacer implements a token-bucket rate limiter that enforces a fixed or minimum transmission rate.
// This is Brutal Congestion Control in userspace: it refuses to back off when loss occurs,
// pushing packets at a guaranteed target wire speed.
type Pacer struct {
	mu           sync.Mutex
	rateBytesSec int64
	capacity     int64
	tokens       int64
	lastUpdate   time.Time
	enabled      bool
}

// NewPacer creates a Pacer with the specified rate in Megabits per second (Mbps).
// If rateMbps <= 0, pacing is disabled.
func NewPacer(rateMbps int) *Pacer {
	if rateMbps <= 0 {
		return &Pacer{enabled: false}
	}

	bytesPerSec := int64(rateMbps) * 1000 * 1000 / 8
	// Allow burst capacity up to 100ms of data, minimum 128KB
	capacity := bytesPerSec / 10
	if capacity < 128*1024 {
		capacity = 128 * 1024
	}

	return &Pacer{
		rateBytesSec: bytesPerSec,
		capacity:     capacity,
		tokens:       capacity,
		lastUpdate:   time.Now(),
		enabled:      true,
	}
}

// Wait blocks until enough tokens are available to transmit n bytes.
func (p *Pacer) Wait(n int) {
	if !p.enabled || n <= 0 {
		return
	}

	p.mu.Lock()
	now := time.Now()
	elapsed := now.Sub(p.lastUpdate)
	p.lastUpdate = now

	// Replenish tokens based on elapsed time
	addedTokens := int64(elapsed.Seconds() * float64(p.rateBytesSec))
	p.tokens += addedTokens
	if p.tokens > p.capacity {
		p.tokens = p.capacity
	}

	needed := int64(n)
	p.tokens -= needed

	// If tokens are still non-negative, proceed immediately with zero delay
	if p.tokens >= 0 {
		p.mu.Unlock()
		return
	}

	deficit := -p.tokens
	// Only sleep when deficit exceeds minimum scheduling threshold (20ms of bandwidth or 64KB)
	// This prevents OS scheduler quantization micro-stutter (which collapses throughput to 10kb/s on Android)
	minThreshold := p.rateBytesSec / 50
	if minThreshold < 64*1024 {
		minThreshold = 64 * 1024
	}
	if deficit < minThreshold {
		p.mu.Unlock()
		return
	}

	sleepDuration := time.Duration(float64(deficit) / float64(p.rateBytesSec) * float64(time.Second))
	p.tokens = 0
	p.lastUpdate = now.Add(sleepDuration)
	p.mu.Unlock()

	time.Sleep(sleepDuration)
}

// SetRate updates the transmission rate dynamically.
func (p *Pacer) SetRate(rateMbps int) {
	if p == nil {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()

	if rateMbps <= 0 {
		p.enabled = false
		return
	}

	bytesPerSec := int64(rateMbps) * 1000 * 1000 / 8
	capacity := bytesPerSec / 10
	if capacity < 128*1024 {
		capacity = 128 * 1024
	}

	p.rateBytesSec = bytesPerSec
	p.capacity = capacity
	p.tokens = capacity
	p.lastUpdate = time.Now()
	p.enabled = true
}

