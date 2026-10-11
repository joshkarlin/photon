package main

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestCoalescer(t *testing.T) {
	var c coalescer
	var active, maxActive, runs atomic.Int32
	started := make(chan struct{})
	release := make(chan struct{})
	fn := func() {
		n := active.Add(1)
		if n > maxActive.Load() {
			maxActive.Store(n)
		}
		if runs.Add(1) == 1 {
			close(started)
			<-release
		}
		time.Sleep(time.Millisecond)
		active.Add(-1)
	}

	var wg sync.WaitGroup
	wg.Add(1)
	go func() { defer wg.Done(); c.run(fn) }()
	<-started
	// A burst while the first run is in progress returns at once...
	for i := 0; i < 10; i++ {
		c.run(fn)
	}
	close(release)
	wg.Wait()

	if maxActive.Load() != 1 {
		t.Errorf("runs overlapped (max %d at once)", maxActive.Load())
	}
	// ...and collapses into exactly one follow-up run.
	if got := runs.Load(); got != 2 {
		t.Errorf("runs = %d, want 2", got)
	}
	// A request after everything settled runs again.
	c.run(fn)
	if got := runs.Load(); got != 3 {
		t.Errorf("runs after idle = %d, want 3", got)
	}
}
