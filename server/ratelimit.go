package main

import (
	"sync"
	"time"
)

// Limiter is a token-bucket rate limiter keyed by an arbitrary string (a profile id or
// an IP). Each key accrues `refill` tokens per second up to `burst`; a request costs
// one token. All in memory, so limits are per instance.
type Limiter struct {
	mu     sync.Mutex
	refill float64
	burst  float64
	seen   map[string]*bucket
	lastGC time.Time
}

type bucket struct {
	tokens float64
	ts     time.Time
}

func NewLimiter(refillPerSec, burst float64) *Limiter {
	return &Limiter{refill: refillPerSec, burst: burst, seen: make(map[string]*bucket)}
}

func (l *Limiter) Allow(key string) bool {
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()

	b := l.seen[key]
	if b == nil {
		b = &bucket{tokens: l.burst, ts: now}
		l.seen[key] = b
	}
	b.tokens += now.Sub(b.ts).Seconds() * l.refill
	if b.tokens > l.burst {
		b.tokens = l.burst
	}
	b.ts = now

	if len(l.seen) > 100000 && now.Sub(l.lastGC) > time.Minute {
		l.gc(now)
		l.lastGC = now
	}

	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// gc forgets idle buckets to bound memory. Stored tokens sit below burst (Allow
// spends one after refilling), so the check projects the refill forward: a bucket
// that would be full again by now has nothing left to remember.
func (l *Limiter) gc(now time.Time) {
	for k, b := range l.seen {
		if b.tokens+now.Sub(b.ts).Seconds()*l.refill >= l.burst && now.Sub(b.ts) > 10*time.Minute {
			delete(l.seen, k)
		}
	}
}

// DailyCap counts events per key within a UTC day and refuses past a limit. Used to cap
// how many new profiles one IP can create per day.
type DailyCap struct {
	mu    sync.Mutex
	limit int
	day   int64
	count map[string]int
}

func NewDailyCap(limit int) *DailyCap {
	return &DailyCap{limit: limit, count: make(map[string]int)}
}

func (d *DailyCap) Allow(key string) bool {
	day := time.Now().Unix() / 86400
	d.mu.Lock()
	defer d.mu.Unlock()
	if day != d.day {
		d.day = day
		d.count = make(map[string]int)
	}
	if d.count[key] >= d.limit {
		return false
	}
	d.count[key]++
	return true
}
