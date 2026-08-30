package main

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"
)

const pid = "0123456789abcdef0123456789abcdef" // a valid 128-bit hex routing id

func newTestServer(t *testing.T) *httptest.Server {
	t.Helper()
	store, err := OpenStore(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	ts := httptest.NewServer(NewServer(store, 8192, false).Handler())
	t.Cleanup(func() {
		ts.Close()
		store.Close()
	})
	return ts
}

func req(t *testing.T, ts *httptest.Server, method, path, token string, headers map[string]string, body []byte) *http.Response {
	t.Helper()
	r, err := http.NewRequest(method, ts.URL+path, bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	for k, v := range headers {
		r.Header.Set(k, v)
	}
	resp, err := ts.Client().Do(r)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func readBody(t *testing.T, resp *http.Response) []byte {
	t.Helper()
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestCreateAndGet(t *testing.T) {
	ts := newTestServer(t)

	resp := req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-None-Match": "*"}, []byte("hello"))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("create: got %d", resp.StatusCode)
	}
	if got := resp.Header.Get("ETag"); got != `"1"` {
		t.Fatalf("create ETag: got %q", got)
	}
	if resp.Header.Get("X-Server-Time") == "" {
		t.Fatal("missing X-Server-Time on create")
	}
	resp.Body.Close()

	resp = req(t, ts, "GET", "/v1/blob/"+pid+"/charge", "tok", nil, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("get: got %d", resp.StatusCode)
	}
	if got := resp.Header.Get("ETag"); got != `"1"` {
		t.Fatalf("get ETag: got %q", got)
	}
	if body := readBody(t, resp); string(body) != "hello" {
		t.Fatalf("get body: got %q", body)
	}
}

func TestGetMissingProfileIs404(t *testing.T) {
	ts := newTestServer(t)
	resp := req(t, ts, "GET", "/v1/blob/"+pid+"/charge", "tok", nil, nil)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("got %d, want 404", resp.StatusCode)
	}
}

func TestCasConflictReturnsCurrent(t *testing.T) {
	ts := newTestServer(t)
	req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-None-Match": "*"}, []byte("v1")).Body.Close()

	resp := req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-Match": `"1"`}, []byte("v2"))
	if resp.StatusCode != http.StatusOK || resp.Header.Get("ETag") != `"2"` {
		t.Fatalf("cas update: got %d etag %q", resp.StatusCode, resp.Header.Get("ETag"))
	}
	resp.Body.Close()

	// Stale CAS (still thinks version is 1) must lose and get the current doc back.
	resp = req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-Match": `"1"`}, []byte("v3"))
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("stale cas: got %d, want 409", resp.StatusCode)
	}
	if resp.Header.Get("ETag") != `"2"` {
		t.Fatalf("conflict ETag: got %q", resp.Header.Get("ETag"))
	}
	if body := readBody(t, resp); string(body) != "v2" {
		t.Fatalf("conflict body: got %q, want v2", body)
	}
}

func TestCreateOnlyOnExistingIs412(t *testing.T) {
	ts := newTestServer(t)
	req(t, ts, "PUT", "/v1/blob/"+pid+"/settings", "tok", map[string]string{"If-None-Match": "*"}, []byte("a")).Body.Close()

	resp := req(t, ts, "PUT", "/v1/blob/"+pid+"/settings", "tok", map[string]string{"If-None-Match": "*"}, []byte("b"))
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusPreconditionFailed {
		t.Fatalf("got %d, want 412", resp.StatusCode)
	}
	if resp.Header.Get("ETag") != `"1"` {
		t.Fatalf("412 ETag: got %q", resp.Header.Get("ETag"))
	}
}

func TestOversizeIs413(t *testing.T) {
	ts := newTestServer(t)
	big := bytes.Repeat([]byte("x"), 9000)
	resp := req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-None-Match": "*"}, big)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("got %d, want 413", resp.StatusCode)
	}
}

func TestWrongTokenIsForbidden(t *testing.T) {
	ts := newTestServer(t)
	req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "owner", map[string]string{"If-None-Match": "*"}, []byte("v1")).Body.Close()

	resp := req(t, ts, "GET", "/v1/blob/"+pid+"/charge", "intruder", nil, nil)
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("get wrong token: got %d, want 403", resp.StatusCode)
	}
	resp.Body.Close()

	resp = req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "intruder", map[string]string{"If-Match": `"1"`}, []byte("v2"))
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("put wrong token: got %d, want 403", resp.StatusCode)
	}
}

func TestRateLimit(t *testing.T) {
	ts := newTestServer(t)
	req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-None-Match": "*"}, []byte("v1")).Body.Close()

	got429 := false
	for i := 0; i < 80; i++ {
		resp := req(t, ts, "GET", "/v1/blob/"+pid+"/charge", "tok", nil, nil)
		resp.Body.Close()
		if resp.StatusCode == http.StatusTooManyRequests {
			got429 = true
			break
		}
	}
	if !got429 {
		t.Fatal("expected a 429 after hammering one profile")
	}
}

func TestTimeEndpoint(t *testing.T) {
	ts := newTestServer(t)
	resp := req(t, ts, "GET", "/v1/time", "", nil, nil)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("got %d", resp.StatusCode)
	}
	if resp.Header.Get("X-Server-Time") == "" {
		t.Fatal("missing X-Server-Time")
	}
	if body := readBody(t, resp); !bytes.Contains(body, []byte(`"now"`)) {
		t.Fatalf("time body: got %q", body)
	}
}

func TestConditionalGetIs304(t *testing.T) {
	ts := newTestServer(t)
	req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-None-Match": "*"}, []byte("v1")).Body.Close()

	resp := req(t, ts, "GET", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-None-Match": `"1"`}, nil)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotModified {
		t.Fatalf("got %d, want 304", resp.StatusCode)
	}
	if resp.Header.Get("ETag") != `"1"` {
		t.Fatalf("304 ETag: got %q", resp.Header.Get("ETag"))
	}

	// A stale If-None-Match still gets the full doc.
	resp = req(t, ts, "GET", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-None-Match": `"0"`}, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("stale conditional get: got %d, want 200", resp.StatusCode)
	}
	if b := readBody(t, resp); string(b) != "v1" {
		t.Fatalf("stale conditional body: got %q", b)
	}
}

// A parked watch must wake the moment a PUT lands and hand back the new doc.
func TestWatchWakesOnPut(t *testing.T) {
	ts := newTestServer(t)
	req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-None-Match": "*"}, []byte("v1")).Body.Close()

	type result struct {
		status int
		body   []byte
		etag   string
	}
	done := make(chan result, 1)
	go func() {
		resp := req(t, ts, "GET", "/v1/blob/"+pid+"/charge?wait=10", "tok", map[string]string{"If-None-Match": `"1"`}, nil)
		done <- result{resp.StatusCode, readBody(t, resp), resp.Header.Get("ETag")}
	}()

	// Give the watcher a moment to park, then write.
	time.Sleep(150 * time.Millisecond)
	req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-Match": `"1"`}, []byte("v2")).Body.Close()

	select {
	case r := <-done:
		if r.status != http.StatusOK || string(r.body) != "v2" || r.etag != `"2"` {
			t.Fatalf("watch result: %d %q %q, want 200 v2 \"2\"", r.status, r.body, r.etag)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("watch never woke after PUT")
	}
}

// A watch that sees no change answers 304 when the wait runs out, with a fresh
// time stamp (the request-start stamp would be a full wait stale).
func TestWatchTimesOutTo304(t *testing.T) {
	ts := newTestServer(t)
	req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-None-Match": "*"}, []byte("v1")).Body.Close()

	before := time.Now().UnixMilli()
	resp := req(t, ts, "GET", "/v1/blob/"+pid+"/charge?wait=1", "tok", map[string]string{"If-None-Match": `"1"`}, nil)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotModified {
		t.Fatalf("got %d, want 304", resp.StatusCode)
	}
	st, err := strconv.ParseInt(resp.Header.Get("X-Server-Time"), 10, 64)
	if err != nil {
		t.Fatalf("bad X-Server-Time: %v", err)
	}
	if st < before+900 {
		t.Fatalf("X-Server-Time not restamped after the wait: %d vs request start %d", st, before)
	}
}

// TestHandoff mirrors two devices sharing one battery: A writes, B reads then updates,
// and A's now-stale write loses and receives B's value to adopt.
func TestHandoff(t *testing.T) {
	ts := newTestServer(t)

	// Device A seeds the charge doc.
	req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-None-Match": "*"}, []byte("a1")).Body.Close()

	// Device B reads it.
	resp := req(t, ts, "GET", "/v1/blob/"+pid+"/charge", "tok", nil, nil)
	if v := resp.Header.Get("ETag"); v != `"1"` {
		t.Fatalf("B read version: %q", v)
	}
	if b := readBody(t, resp); string(b) != "a1" {
		t.Fatalf("B read body: %q", b)
	}

	// Device B takes over and writes v2.
	resp = req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-Match": `"1"`}, []byte("b2"))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("B update: got %d", resp.StatusCode)
	}
	resp.Body.Close()

	// Device A, still on version 1, loses and gets B's current value.
	resp = req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-Match": `"1"`}, []byte("a2"))
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("A stale update: got %d, want 409", resp.StatusCode)
	}
	if b := readBody(t, resp); string(b) != "b2" {
		t.Fatalf("A conflict body: got %q, want b2", b)
	}
}

func TestClientIPHonorsForwardedForOnlyWhenTrusted(t *testing.T) {
	r := httptest.NewRequest("GET", "/v1/time", nil)
	r.RemoteAddr = "10.0.0.1:1234"
	r.Header.Set("X-Forwarded-For", "6.6.6.6, 7.7.7.7")

	s := &Server{}
	if got := s.clientIP(r); got != "10.0.0.1" {
		t.Fatalf("untrusted: got %q, want the connection address", got)
	}
	s.trustProxy = true
	if got := s.clientIP(r); got != "7.7.7.7" {
		t.Fatalf("trusted: got %q, want the last hop (the proxy's own append)", got)
	}
}

// The limiter's gc must reclaim a bucket that has sat idle long enough to refill.
func TestLimiterGCReclaimsIdleBuckets(t *testing.T) {
	l := NewLimiter(1, 20)
	if !l.Allow("k") {
		t.Fatal("first request should pass")
	}
	l.gc(time.Now().Add(30 * time.Minute))
	if len(l.seen) != 0 {
		t.Fatalf("idle bucket not reclaimed: %d left", len(l.seen))
	}
}

// A wrong token must not spend the profile's rate budget, or anyone holding just
// the routing id could 429 starve the real device.
func TestWrongTokenDoesNotSpendProfileBudget(t *testing.T) {
	ts := newTestServer(t)
	req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "owner", map[string]string{"If-None-Match": "*"}, []byte("v1")).Body.Close()

	// Hammer with a bad token well past the per profile burst of 20.
	for i := 0; i < 40; i++ {
		resp := req(t, ts, "GET", "/v1/blob/"+pid+"/charge", "intruder", nil, nil)
		resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Fatalf("bad token request %d: got %d, want 403", i, resp.StatusCode)
		}
	}

	resp := req(t, ts, "GET", "/v1/blob/"+pid+"/charge", "owner", nil, nil)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("owner after bad token spray: got %d, want 200", resp.StatusCode)
	}
}

// Concurrent CAS writes against the same version: exactly one wins, the rest get
// 409 with the winner's doc. Exercises the writer transaction under the race detector.
func TestConcurrentCasHasOneWinner(t *testing.T) {
	ts := newTestServer(t)
	req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-None-Match": "*"}, []byte("v1")).Body.Close()

	const n = 8
	codes := make(chan int, n)
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			resp := req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", map[string]string{"If-Match": `"1"`}, []byte("v2"))
			resp.Body.Close()
			codes <- resp.StatusCode
		}()
	}
	wg.Wait()
	close(codes)

	wins, conflicts := 0, 0
	for c := range codes {
		switch c {
		case http.StatusOK:
			wins++
		case http.StatusConflict:
			conflicts++
		default:
			t.Fatalf("unexpected status %d", c)
		}
	}
	if wins != 1 || conflicts != n-1 {
		t.Fatalf("got %d wins and %d conflicts, want 1 and %d", wins, conflicts, n-1)
	}
}

func TestTimeEndpointIsRateLimited(t *testing.T) {
	ts := newTestServer(t)
	got429 := false
	for i := 0; i < 200; i++ {
		resp := req(t, ts, "GET", "/v1/time", "", nil, nil)
		resp.Body.Close()
		if resp.StatusCode == http.StatusTooManyRequests {
			got429 = true
			break
		}
	}
	if !got429 {
		t.Fatal("expected a 429 after hammering /v1/time")
	}
}

func TestPutWithoutPreconditionIs428(t *testing.T) {
	ts := newTestServer(t)
	resp := req(t, ts, "PUT", "/v1/blob/"+pid+"/charge", "tok", nil, []byte("v1"))
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusPreconditionRequired {
		t.Fatalf("got %d, want 428", resp.StatusCode)
	}
}
