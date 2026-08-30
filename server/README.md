# sb-sync

The sync server for Media Battery. It keeps your battery and settings on every device
without an account. You hold one secret code; the server only ever stores encrypted
blobs it cannot read.

If the official server ever goes away, run your own and point the extension at it. That
is the whole reason this is a single small binary.

## What it does

Each profile has two documents, `charge` and `settings`, both encrypted on your device.
The server stores them, versions them, and refuses a write that is based on a stale
version, so two devices never silently overwrite each other. It does no merging and
sees no plaintext.

## Build

Needs Go 1.22 or newer. The one dependency is a pure-Go SQLite driver, so the build is
a static binary with no C toolchain.

```
cd server
go mod tidy          # fetches the SQLite driver and writes go.sum
CGO_ENABLED=0 go build -o sb-sync .
```

## Run

```
./sb-sync -addr :8787 -db /var/lib/sbsync/sync.db
```

Flags:

- `-addr` listen address (default `:8787`)
- `-db` SQLite file path (default `sync.db`)
- `-max-body` largest accepted request body in bytes (default `8192`)
- `-gc-days` drop profiles untouched for this many days (default `180`, `0` disables).
  GC goes by last write: reads keep nothing alive, so a profile that only watched for
  the whole window is dropped and comes back on its next push.
- `-trust-proxy` key rate limits on the last `X-Forwarded-For` hop instead of the
  connection address. Set it only behind a reverse proxy that always appends that
  header; without one the header is client supplied and would let anyone dodge the
  limits.

It serves plain HTTP. Put TLS in front of it. A minimal Caddyfile:

```
sync.example.com {
    reverse_proxy localhost:8787
}
```

Behind a proxy like that, run with `-trust-proxy` so each client is limited by its
own address rather than all of them sharing the proxy's. Then set the server URL in
the extension's sync settings to `https://sync.example.com`.

## API

Every response carries `X-Server-Time` (epoch ms) so clients can correct for clock
skew. Versions are surfaced as a strong `ETag`.

- `GET /v1/time` returns `{"now": <ms>}`.
- `GET /v1/blob/{profile}/{doc}` returns the ciphertext with its `ETag`, or `404` if it
  does not exist yet. Requires `Authorization: Bearer <token>`. With
  `If-None-Match: "<version>"` an unchanged doc answers `304` with no body. Add
  `?wait=<seconds>` (capped at 50) to park the request until a write lands or the wait
  runs out; that is how clients watch a profile instead of polling it. `X-Server-Time`
  is stamped when the response is written, so even a parked response carries fresh
  time.
- `PUT /v1/blob/{profile}/{doc}` stores ciphertext. Send `If-None-Match: *` to create
  (fails `412` if it already exists) or `If-Match: "<version>"` to compare-and-swap
  (fails `409`, returning the current version and body). `413` over the size cap, `403`
  on a token that does not match the profile, `429` when rate limited.

`{doc}` is `charge` or `settings`. The first write to a profile fixes its auth token;
every later request must present a matching token. Lose the code and the data is gone by
design: no accounts, no recovery.

Profile squatting comes with the no account design: whoever writes a routing id first
owns its token. The id is unguessable in practice, but treat it as part of the secret.
A squatter gains denial of service at most; the content stays encrypted.

## Test

```
cd server
go test ./...
```
