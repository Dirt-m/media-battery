// sb-sync, the Media Battery sync server.
//
// A versioned, opaque blob store. Each profile holds two documents, "charge" and
// "settings", both end-to-end encrypted by the client. The server never sees plaintext
// and does no merging: it stores ciphertext, hands it back, and enforces
// compare-and-swap so two devices can't silently clobber each other.
//
// One static binary, one SQLite file. See README.md.
package main

import (
	"context"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	addr := flag.String("addr", ":8787", "listen address")
	dbPath := flag.String("db", "sync.db", "sqlite database file")
	maxBody := flag.Int64("max-body", 8192, "max request body size in bytes")
	gcDays := flag.Int("gc-days", 180, "drop profiles untouched for this many days (0 disables)")
	trustProxy := flag.Bool("trust-proxy", false, "trust the last X-Forwarded-For hop for client IPs (only behind a reverse proxy that always appends it)")
	flag.Parse()

	store, err := OpenStore(*dbPath)
	if err != nil {
		log.Fatalf("open store: %v", err)
	}
	defer store.Close()

	srv := NewServer(store, *maxBody, *trustProxy)
	if *gcDays > 0 {
		go srv.runGC(time.Duration(*gcDays) * 24 * time.Hour)
	}

	httpSrv := &http.Server{
		Addr:              *addr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		// Both must outlive a parked watch (maxWaitSeconds), or the timeout would cut
		// every long poll short. They bound everything else.
		ReadTimeout:  75 * time.Second,
		WriteTimeout: 75 * time.Second,
		IdleTimeout:  2 * time.Minute,
	}

	go func() {
		log.Printf("sb-sync listening on %s (db %s, max-body %d)", *addr, *dbPath, *maxBody)
		if err := httpSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("listen: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop

	// Parked watches hold requests up to maxWaitSeconds, so shutdown must outlast
	// them or store.Close would pull the database out from under live handlers.
	ctx, cancel := context.WithTimeout(context.Background(), (maxWaitSeconds+5)*time.Second)
	defer cancel()
	_ = httpSrv.Shutdown(ctx)
	log.Println("stopped")
}
