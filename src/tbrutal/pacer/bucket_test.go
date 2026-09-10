package pacer

import (
	"testing"
	"time"
)

func TestPacerDisabled(t *testing.T) {
	p := NewPacer(0)
	start := time.Now()
	p.Wait(1024 * 1024)
	if time.Since(start) > 50*time.Millisecond {
		t.Errorf("disabled pacer should not block")
	}
}

func TestPacerBurstAndLimit(t *testing.T) {
	// 8 Mbps = 1 MB/s
	p := NewPacer(8)

	// First burst of 64KB should consume initial capacity without long delay
	start := time.Now()
	p.Wait(64 * 1024)
	if time.Since(start) > 100*time.Millisecond {
		t.Errorf("initial burst exceeded expected latency")
	}

	// Large request exceeding capacity will throttle
	start = time.Now()
	// 500KB at 1MB/s should take around ~300-500ms once tokens are depleted
	p.Wait(500 * 1024)
	elapsed := time.Since(start)
	if elapsed < 200*time.Millisecond {
		t.Errorf("expected throttling delay, got %v", elapsed)
	}
}
