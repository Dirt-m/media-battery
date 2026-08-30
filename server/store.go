package main

import (
	"crypto/subtle"
	"database/sql"
	"errors"

	_ "modernc.org/sqlite"
)

// Store is the SQLite-backed blob store. State is a profile row (holding the auth
// token hash) plus up to two blob rows per profile ("charge" and "settings"), each
// with an integer version for compare-and-swap.
//
// Two handles onto the same file: a single-connection writer (writes serialize, no
// SQLITE_BUSY) and a small reader pool. Under WAL readers run alongside the writer,
// so GETs never queue behind PUTs.
type Store struct {
	writer *sql.DB
	reader *sql.DB
}

// Doc is a stored ciphertext blob with its CAS version and last-write time.
type Doc struct {
	Blob      []byte
	Version   int64
	UpdatedAt int64
}

// PutResult reports the outcome of a write. Exactly one of OK / Forbidden / Exists /
// Conflict is set. Current carries the stored doc when a write loses (so the client
// can merge and retry without a follow-up read).
type PutResult struct {
	OK         bool
	NewVersion int64
	Forbidden  bool  // auth token did not match the profile's stored token
	Exists     bool  // create-only write but the doc already exists (-> 412)
	Conflict   bool  // compare-and-swap version mismatch (-> 409)
	Current    *Doc  // the stored doc, when Exists or Conflict
}

func OpenStore(path string) (*Store, error) {
	// Pragmas ride in the DSN so they apply to every pooled connection, not just
	// whichever one happens to serve an Exec.
	dsn := "file:" + path +
		"?_pragma=journal_mode(WAL)" +
		"&_pragma=synchronous(NORMAL)" +
		"&_pragma=busy_timeout(5000)"

	writer, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	writer.SetMaxOpenConns(1)

	reader, err := sql.Open("sqlite", dsn)
	if err != nil {
		writer.Close()
		return nil, err
	}
	reader.SetMaxOpenConns(4)

	db := writer // schema setup goes through the writer

	const schema = `
CREATE TABLE IF NOT EXISTS profiles (
  profile_id TEXT PRIMARY KEY,
  token_hash BLOB NOT NULL,
  created_at INTEGER NOT NULL,
  created_ip TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS blobs (
  profile_id TEXT NOT NULL,
  doc        TEXT NOT NULL CHECK (doc IN ('charge','settings')),
  version    INTEGER NOT NULL,
  blob       BLOB NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (profile_id, doc)
);`
	if _, err := db.Exec(schema); err != nil {
		writer.Close()
		reader.Close()
		return nil, err
	}
	return &Store{writer: writer, reader: reader}, nil
}

func (s *Store) Close() error {
	err := s.reader.Close()
	if werr := s.writer.Close(); werr != nil {
		return werr
	}
	return err
}

// ProfileHash returns the stored auth token hash for a profile, if it exists.
func (s *Store) ProfileHash(id string) ([]byte, bool, error) {
	var h []byte
	err := s.reader.QueryRow(`SELECT token_hash FROM profiles WHERE profile_id=?`, id).Scan(&h)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	return h, true, nil
}

// GetDoc returns a stored doc, if it exists.
func (s *Store) GetDoc(id, doc string) (*Doc, bool, error) {
	var d Doc
	err := s.reader.QueryRow(
		`SELECT blob, version, updated_at FROM blobs WHERE profile_id=? AND doc=?`,
		id, doc).Scan(&d.Blob, &d.Version, &d.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	return &d, true, nil
}

// Put creates or compare-and-swaps a doc in one transaction. It also establishes the
// profile on first write (trust on first use): whoever writes first sets the auth
// token, and every later read or write must present a matching token.
//
// createOnly (from If-None-Match: *) inserts the doc at version 1 and fails with
// Exists if it is already there. Otherwise the write is a CAS against expectVersion
// (from If-Match) and fails with Conflict unless the stored version matches.
func (s *Store) Put(id, doc string, blob, tokenHash []byte, createOnly bool, expectVersion, now int64, ip string) (PutResult, error) {
	var res PutResult

	tx, err := s.writer.Begin()
	if err != nil {
		return res, err
	}
	defer tx.Rollback()

	var storedHash []byte
	err = tx.QueryRow(`SELECT token_hash FROM profiles WHERE profile_id=?`, id).Scan(&storedHash)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		// First ever write to this profile: it owns the token from here on.
		if _, err := tx.Exec(
			`INSERT INTO profiles (profile_id, token_hash, created_at, created_ip) VALUES (?,?,?,?)`,
			id, tokenHash, now, ip); err != nil {
			return res, err
		}
	case err != nil:
		return res, err
	default:
		if subtle.ConstantTimeCompare(storedHash, tokenHash) != 1 {
			res.Forbidden = true
			return res, nil
		}
	}

	var cur Doc
	docErr := tx.QueryRow(
		`SELECT blob, version, updated_at FROM blobs WHERE profile_id=? AND doc=?`,
		id, doc).Scan(&cur.Blob, &cur.Version, &cur.UpdatedAt)
	exists := docErr == nil
	if docErr != nil && !errors.Is(docErr, sql.ErrNoRows) {
		return res, docErr
	}

	if createOnly {
		if exists {
			c := cur
			res.Exists = true
			res.Current = &c
			return res, nil
		}
		if _, err := tx.Exec(
			`INSERT INTO blobs (profile_id, doc, version, blob, updated_at) VALUES (?,?,1,?,?)`,
			id, doc, blob, now); err != nil {
			return res, err
		}
		res.OK = true
		res.NewVersion = 1
	} else {
		if !exists || cur.Version != expectVersion {
			res.Conflict = true
			if exists {
				c := cur
				res.Current = &c
			}
			return res, nil
		}
		res.NewVersion = cur.Version + 1
		if _, err := tx.Exec(
			`UPDATE blobs SET blob=?, version=?, updated_at=? WHERE profile_id=? AND doc=?`,
			blob, res.NewVersion, now, id, doc); err != nil {
			return res, err
		}
		res.OK = true
	}

	if err := tx.Commit(); err != nil {
		return PutResult{}, err
	}
	return res, nil
}

// GC drops profiles (and their blobs) whose most recent write is older than `before`.
// Both deletes ride one transaction so a failure between them cannot leave orphaned
// blob rows behind.
func (s *Store) GC(before int64) (int64, error) {
	tx, err := s.writer.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()

	res, err := tx.Exec(`
DELETE FROM profiles WHERE profile_id IN (
  SELECT p.profile_id FROM profiles p
  LEFT JOIN blobs b ON b.profile_id = p.profile_id
  GROUP BY p.profile_id
  HAVING COALESCE(MAX(b.updated_at), p.created_at) < ?
)`, before)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	if _, err := tx.Exec(`DELETE FROM blobs WHERE profile_id NOT IN (SELECT profile_id FROM profiles)`); err != nil {
		return 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return n, nil
}
