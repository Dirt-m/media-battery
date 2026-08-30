package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Server wires the store to the HTTP API and holds the in-memory rate limiters
// and the long-poll hub.
type Server struct {
	store      *Store
	maxBody    int64
	trustProxy bool // honor X-Forwarded-For (only sane behind a reverse proxy)
	hub        *Hub // wakes parked watch requests when a PUT lands

	perProfile *Limiter  // spread across a single profile's requests
	perIP      *Limiter  // blunts profile-id spraying from one source
	newProfile *DailyCap // caps new profiles created per IP per day
}

// A watch may ask the server to hold the request up to this long before
// answering 304. Kept under common proxy read timeouts (usually 60s).
const maxWaitSeconds = 50

func NewServer(store *Store, maxBody int64, trustProxy bool) *Server {
	return &Server{
		store:      store,
		maxBody:    maxBody,
		trustProxy: trustProxy,
		hub:        NewHub(8192),
		perProfile: NewLimiter(1, 20), // ~60 req/min, burst 20
		// Watchers idle near 1 req/min each, but one CGNAT or campus IP can front
		// dozens of active devices.
		perIP:      NewLimiter(4, 80), // ~240 req/min, burst 80
		newProfile: NewDailyCap(50),
	}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/time", s.handleTime)
	mux.HandleFunc("GET /v1/blob/{profile}/{doc}", s.handleGet)
	mux.HandleFunc("PUT /v1/blob/{profile}/{doc}", s.handlePut)
	mux.HandleFunc("OPTIONS /v1/blob/{profile}/{doc}", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})
	return s.withCommon(mux)
}

// withCommon stamps every response with the server clock, which clients use to anchor
// their time offset, plus permissive CORS. CORS is harmless here (blobs are E2EE) and
// a future web client would need it; browser extensions with host permission do not.
func (s *Server) withCommon(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Server-Time", strconv.FormatInt(nowMS(), 10))
		h.Set("Access-Control-Allow-Origin", "*")
		h.Set("Access-Control-Allow-Methods", "GET, PUT, OPTIONS")
		h.Set("Access-Control-Allow-Headers", "Authorization, Content-Type, If-Match, If-None-Match")
		h.Set("Access-Control-Expose-Headers", "ETag, X-Server-Time")
		next.ServeHTTP(w, r)
	})
}

func (s *Server) handleTime(w http.ResponseWriter, r *http.Request) {
	if !s.perIP.Allow(s.clientIP(r)) {
		tooMany(w)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int64{"now": nowMS()})
}

func (s *Server) handleGet(w http.ResponseWriter, r *http.Request) {
	profile := r.PathValue("profile")
	doc := r.PathValue("doc")
	if !validProfile(profile) || !validDoc(doc) {
		http.NotFound(w, r)
		return
	}
	if !s.perIP.Allow(s.clientIP(r)) {
		tooMany(w)
		return
	}

	token, ok := bearer(r)
	if !ok {
		forbidden(w)
		return
	}
	tokenHash := sha256.Sum256([]byte(token))

	storedHash, exists, err := s.store.ProfileHash(profile)
	if err != nil {
		serverErr(w, err)
		return
	}
	if !exists {
		http.NotFound(w, r) // unknown profile: drives the client's bootstrap
		return
	}
	if subtle.ConstantTimeCompare(storedHash, tokenHash[:]) != 1 {
		forbidden(w)
		return
	}
	// The profile budget is spent only after the token verifies, so someone
	// holding just the routing id cannot 429 starve the real device.
	if !s.perProfile.Allow(profile) {
		tooMany(w)
		return
	}

	d, ok, err := s.store.GetDoc(profile, doc)
	if err != nil {
		serverErr(w, err)
		return
	}
	if !ok {
		http.NotFound(w, r)
		return
	}

	// Conditional GET: an If-None-Match matching the stored version has nothing to send.
	// With ?wait=<seconds> the request parks until a PUT to this doc wakes it or the
	// wait runs out, so a client can watch instead of poll.
	if since, ok := parseETag(r.Header.Get("If-None-Match")); ok && since == d.Version {
		if wait := parseWait(r.URL.Query().Get("wait")); wait > 0 {
			if cur := s.awaitChange(r, profile, doc, d.Version, wait); cur != nil {
				d = cur
			} else {
				stampTime(w) // the parked time made the earlier stamp stale
				w.Header().Set("ETag", etag(d.Version))
				w.WriteHeader(http.StatusNotModified)
				return
			}
		} else {
			w.Header().Set("ETag", etag(d.Version))
			w.WriteHeader(http.StatusNotModified)
			return
		}
	}

	stampTime(w)
	w.Header().Set("ETag", etag(d.Version))
	w.Header().Set("Content-Type", "application/octet-stream")
	w.WriteHeader(http.StatusOK)
	w.Write(d.Blob)
}

// awaitChange parks until the doc moves past sinceVersion, the wait expires, or the
// client goes away. Returns the new doc, or nil if nothing changed. Over the hub's
// caps it returns nil immediately.
func (s *Server) awaitChange(r *http.Request, profile, doc string, sinceVersion int64, wait time.Duration) *Doc {
	ch, cancel, ok := s.hub.Subscribe(profile + "/" + doc)
	if !ok {
		return nil
	}
	defer cancel()

	// Re-check after subscribing: a PUT between our read and the subscription
	// would otherwise be missed and leave us parked past it.
	if d, ok, err := s.store.GetDoc(profile, doc); err == nil && ok && d.Version != sinceVersion {
		return d
	}

	t := time.NewTimer(wait)
	defer t.Stop()
	select {
	case <-ch:
	case <-t.C:
		return nil
	case <-r.Context().Done():
		return nil
	}
	if d, ok, err := s.store.GetDoc(profile, doc); err == nil && ok && d.Version != sinceVersion {
		return d
	}
	return nil
}

func parseWait(q string) time.Duration {
	if q == "" {
		return 0
	}
	v, err := strconv.Atoi(q)
	if err != nil || v <= 0 {
		return 0
	}
	if v > maxWaitSeconds {
		v = maxWaitSeconds
	}
	return time.Duration(v) * time.Second
}

func (s *Server) handlePut(w http.ResponseWriter, r *http.Request) {
	profile := r.PathValue("profile")
	doc := r.PathValue("doc")
	if !validProfile(profile) || !validDoc(doc) {
		http.NotFound(w, r)
		return
	}
	ip := s.clientIP(r)
	if !s.perIP.Allow(ip) {
		tooMany(w)
		return
	}

	token, ok := bearer(r)
	if !ok {
		forbidden(w)
		return
	}
	tokenHash := sha256.Sum256([]byte(token))

	// Every write must state its precondition: create-only, or CAS against a version.
	createOnly := r.Header.Get("If-None-Match") == "*"
	var expect int64 = -1
	if !createOnly {
		v, ok := parseETag(r.Header.Get("If-Match"))
		if !ok {
			http.Error(w, "need If-Match or If-None-Match", http.StatusPreconditionRequired)
			return
		}
		expect = v
	}

	r.Body = http.MaxBytesReader(w, r.Body, s.maxBody)
	blob, err := io.ReadAll(r.Body)
	if err != nil {
		entityTooLarge(w)
		return
	}

	// One read serves the token precheck (a wrong token must not spend the profile's
	// rate budget), the per profile limiter, and the daily new profile cap. store.Put
	// re-checks the token inside its own transaction, so a race here cannot let a
	// write through.
	storedHash, exists, err := s.store.ProfileHash(profile)
	if err != nil {
		serverErr(w, err)
		return
	}
	if exists && subtle.ConstantTimeCompare(storedHash, tokenHash[:]) != 1 {
		forbidden(w)
		return
	}
	if !s.perProfile.Allow(profile) {
		tooMany(w)
		return
	}
	// Cap fresh-profile creation per source. A write to an existing profile is exempt.
	if !exists && !s.newProfile.Allow(ip) {
		tooMany(w)
		return
	}

	res, err := s.store.Put(profile, doc, blob, tokenHash[:], createOnly, expect, nowMS(), ip)
	if err != nil {
		serverErr(w, err)
		return
	}

	switch {
	case res.Forbidden:
		forbidden(w)
	case res.Exists:
		if res.Current != nil {
			w.Header().Set("ETag", etag(res.Current.Version))
		}
		http.Error(w, "already exists", http.StatusPreconditionFailed)
	case res.Conflict:
		// Hand back the current version and body so the client merges without a re-GET.
		if res.Current != nil {
			w.Header().Set("ETag", etag(res.Current.Version))
			w.Header().Set("Content-Type", "application/octet-stream")
			w.WriteHeader(http.StatusConflict)
			w.Write(res.Current.Blob)
		} else {
			http.Error(w, "conflict", http.StatusConflict)
		}
	case res.OK:
		s.hub.Notify(profile + "/" + doc) // wake parked watchers
		w.Header().Set("ETag", etag(res.NewVersion))
		w.WriteHeader(http.StatusOK)
	default:
		serverErr(w, nil)
	}
}

func (s *Server) runGC(retention time.Duration) {
	t := time.NewTicker(6 * time.Hour)
	defer t.Stop()
	for {
		before := time.Now().Add(-retention).UnixMilli()
		if n, err := s.store.GC(before); err != nil {
			log.Printf("gc error: %v", err)
		} else if n > 0 {
			log.Printf("gc: removed %d profiles", n)
		}
		<-t.C
	}
}

// --- helpers ------------------------------------------------------------------

func nowMS() int64 { return time.Now().UnixMilli() }

// stampTime refreshes X-Server-Time just before the response is written. withCommon
// stamps at request start, which on a parked watch is up to a full wait stale and
// would skew the client's clock anchoring.
func stampTime(w http.ResponseWriter) {
	w.Header().Set("X-Server-Time", strconv.FormatInt(nowMS(), 10))
}

func bearer(r *http.Request) (string, bool) {
	const p = "Bearer "
	h := r.Header.Get("Authorization")
	if len(h) > len(p) && strings.EqualFold(h[:len(p)], p) {
		if t := strings.TrimSpace(h[len(p):]); t != "" {
			return t, true
		}
	}
	return "", false
}

func etag(v int64) string { return `"` + strconv.FormatInt(v, 10) + `"` }

func parseETag(s string) (int64, bool) {
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(s, "W/")
	s = strings.Trim(s, `"`)
	if s == "" {
		return 0, false
	}
	v, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return 0, false
	}
	return v, true
}

func validDoc(d string) bool { return d == "charge" || d == "settings" }

// validProfile accepts the client's hex routing id (128 bits = 32 hex chars); the range
// is loose so a future id scheme still passes, but it rejects paths and junk.
func validProfile(p string) bool {
	if len(p) < 8 || len(p) > 64 {
		return false
	}
	for _, c := range p {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
			return false
		}
	}
	return true
}

// clientIP keys the rate limits and the daily profile cap. X-Forwarded-For is client
// supplied, so it counts only behind a trusted reverse proxy (-trust-proxy), and then
// only its last hop, the one that proxy appended. Anything earlier (and the whole
// header when not behind a proxy) is spoofable: one forged header would sidestep
// every per IP limit.
func (s *Server) clientIP(r *http.Request) string {
	if s.trustProxy {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			parts := strings.Split(xff, ",")
			if ip := strings.TrimSpace(parts[len(parts)-1]); ip != "" {
				return ip
			}
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func forbidden(w http.ResponseWriter)       { http.Error(w, "forbidden", http.StatusForbidden) }
func entityTooLarge(w http.ResponseWriter)  { http.Error(w, "too large", http.StatusRequestEntityTooLarge) }
func serverErr(w http.ResponseWriter, err error) {
	if err != nil {
		log.Printf("error: %v", err)
	}
	http.Error(w, "server error", http.StatusInternalServerError)
}

func tooMany(w http.ResponseWriter) {
	w.Header().Set("Retry-After", "5")
	http.Error(w, "rate limited", http.StatusTooManyRequests)
}
