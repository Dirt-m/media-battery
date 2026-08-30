package main

import "sync"

// Hub lets long-poll watchers wait for a document to change. A successful PUT
// notifies the document's key and every watcher on it wakes at once. In memory and
// per instance, like the rate limiters; the deployment model is a single server.
//
// Subscribe hands out a shared channel that Notify closes, since a closed channel
// wakes every receiver. Entries are reference counted, so a key with no watchers
// and no pending notify holds no memory.
type Hub struct {
	mu      sync.Mutex
	entries map[string]*hubEntry
	total   int // watchers across all keys, for the global cap
	max     int
}

type hubEntry struct {
	ch   chan struct{}
	refs int
}

// perKeyWatchers bounds one profile's parked requests (a profile is a handful of
// devices); maxWatchers bounds parked goroutines server-wide. Past either cap a
// watch degrades to an immediate answer, which the client paces on its own.
const perKeyWatchers = 8

func NewHub(maxWatchers int) *Hub {
	return &Hub{entries: make(map[string]*hubEntry), max: maxWatchers}
}

// Subscribe registers a watcher. ok is false when a cap is hit; otherwise the
// channel closes on the next Notify, and cancel MUST be called when done waiting.
func (h *Hub) Subscribe(key string) (ch <-chan struct{}, cancel func(), ok bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.total >= h.max {
		return nil, nil, false
	}
	e := h.entries[key]
	if e == nil {
		e = &hubEntry{ch: make(chan struct{})}
		h.entries[key] = e
	}
	if e.refs >= perKeyWatchers {
		return nil, nil, false
	}
	e.refs++
	h.total++
	return e.ch, func() {
		h.mu.Lock()
		defer h.mu.Unlock()
		e.refs--
		h.total--
		// Drop the entry once idle, unless Notify already replaced it.
		if e.refs == 0 && h.entries[key] == e {
			delete(h.entries, key)
		}
	}, true
}

// Notify wakes every current watcher of key. Late cancels still decrement the old
// entry harmlessly; the next Subscribe starts a fresh channel.
func (h *Hub) Notify(key string) {
	h.mu.Lock()
	e := h.entries[key]
	if e != nil {
		delete(h.entries, key)
	}
	h.mu.Unlock()
	if e != nil {
		close(e.ch)
	}
}
