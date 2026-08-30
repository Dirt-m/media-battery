// Cross-device sync engine. Loaded before background.js and inert until background
// calls Sync.init(adapter); all battery state goes through that adapter.
//
// Charge is one shared account, written by whichever device you are actively using.
// Followers park a conditional GET (a watch) that the server answers when a write
// lands. Reads project through projection.js, whose bounded drain means a writer that
// vanished mid drain stops mattering DRAIN_HORIZON_MS after its last write. Anchors
// carry a writer id so a device never follows its own echo, and the "I stopped
// draining" push retries until the server acknowledges it. End to end encrypted from
// one secret code: the server stores opaque blobs and does no merging. Timestamps run
// on the server clock so device skew can't corrupt the delta math.

(function () {
  'use strict';

  const DEFAULT_SERVER = 'https://sb.dirt23.site';
  // Wire constant, under the project's old working name. Same for the HKDF info
  // strings below: changing either orphans every existing sync profile.
  const SALT = new TextEncoder().encode('social-battery-sync-v1');
  const PUSH_MS = 10000;      // how often the engaged writer pushes charge
  const WATCH_WAIT_S = 45;    // how long the server may park a watch before answering 304
  const WATCH_PACE_MS = 3000; // floor between watch rounds, so the loop can't spin
  const NUDGE_MS = 5000;      // minimum gap between UI-nudged pulls (the popup polls 1/s)
  const SETTINGS_PULL_MS = 5 * 60 * 1000; // most a phone's settings edit stays unseen while the page lives
  const BACKOFF = [5000, 15000, 30000]; // retry schedule after a failed request

  const enc = (s) => new TextEncoder().encode(s);
  const dec = (b) => new TextDecoder().decode(b);
  const sleep = (ms) => new Promise((res) => setTimeout(res, ms));

  // --- code + crypto (pure, exposed for testing) ------------------------------

  const B32 = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'; // Crockford, no I L O U

  function base32Encode(bytes) {
    let bits = 0, value = 0, out = '';
    for (const b of bytes) {
      value = (value << 8) | b; bits += 8;
      while (bits >= 5) { out += B32[(value >>> (bits - 5)) & 31]; bits -= 5; }
    }
    if (bits > 0) out += B32[(value << (5 - bits)) & 31];
    return out;
  }

  // Tolerant of what people paste: any spacing, lower case, and the substitutions
  // they make (O for 0, I or L for 1).
  function base32Decode(str) {
    const clean = String(str).toUpperCase().replace(/O/g, '0').replace(/[IL]/g, '1')
      .split('').filter((c) => B32.indexOf(c) >= 0);
    let bits = 0, value = 0;
    const out = [];
    for (const c of clean) {
      value = (value << 5) | B32.indexOf(c); bits += 5;
      while (bits >= 8) { out.push((value >>> (bits - 8)) & 255); bits -= 8; }
    }
    return new Uint8Array(out);
  }

  // 128 bits, and 26 characters to type. The code is both the auth token and the
  // encryption key.
  const CODE_BYTES = 16;

  function generateCode() {
    return base32Encode(crypto.getRandomValues(new Uint8Array(CODE_BYTES)));
  }

  // Group into fours for display. The stored code is the ungrouped upper-case string.
  function formatCode(code) {
    return (code.match(/.{1,4}/g) || [code]).join(' ');
  }

  function toHex(buf) {
    return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, '0')).join('');
  }
  function toB64url(buf) {
    let s = '';
    for (const b of new Uint8Array(buf)) s += String.fromCharCode(b);
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  async function deriveKeys(code) {
    const raw = base32Decode(code);
    if (raw.length !== CODE_BYTES) throw new Error('bad code');
    const base = await crypto.subtle.importKey('raw', raw, 'HKDF', false, ['deriveBits', 'deriveKey']);
    const hkdf = (info, bits) => crypto.subtle.deriveBits(
      { name: 'HKDF', hash: 'SHA-256', salt: SALT, info: enc(info) }, base, bits);
    const routing = await hkdf('sb-routing-v1', 128);
    const auth = await hkdf('sb-auth-v1', 256);
    const aesKey = await crypto.subtle.deriveKey(
      { name: 'HKDF', hash: 'SHA-256', salt: SALT, info: enc('sb-enc-v1') },
      base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    return { routingId: toHex(routing), authToken: toB64url(auth), aesKey };
  }

  async function encryptDoc(aesKey, doc, obj) {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ct = new Uint8Array(await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv, additionalData: enc(doc) }, aesKey, enc(JSON.stringify(obj))));
    const out = new Uint8Array(12 + ct.length);
    out.set(iv, 0); out.set(ct, 12);
    return out;
  }

  async function decryptDoc(aesKey, doc, bytes) {
    try {
      const pt = await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: bytes.slice(0, 12), additionalData: enc(doc) }, aesKey, bytes.slice(12));
      return JSON.parse(dec(pt));
    } catch (_) {
      return null; // corrupt or from a different code: never applied to local state
    }
  }

  // Per-key last-write-wins over the settings map { key: {value, ts} }.
  function mergeSettings(local, remote) {
    const out = {};
    const keys = new Set([...Object.keys(local || {}), ...Object.keys(remote || {})]);
    for (const k of keys) {
      const a = local && local[k], b = remote && remote[k];
      if (!b) out[k] = a;
      else if (!a) out[k] = b;
      else out[k] = (b.ts > a.ts) ? b : a;
    }
    return out;
  }

  // --- engine -----------------------------------------------------------------

  const SYNCED = 'synced', SYNCING = 'syncing', OFFLINE = 'offline', OFF = 'off';

  const S = {
    adapter: null,
    keys: null,          // { routingId, authToken, aesKey }, cached for the current code
    keysForCode: null,   // the code the cached keys belong to
    versions: {},        // doc -> last known server version (in memory only)
    pushTimer: null,
    watchGen: 0,         // bumping it retires any running watch loop
    watchAbort: null,    // AbortController of the in-flight parked watch
    engaged: false,
    active: false,       // a tab is open (ticker running)
    busyCharge: false, pendingCharge: false,
    busySettings: false, pendingSettings: false,
    dirty: false,        // a charge push has not been acknowledged by the server yet
    retryTimer: null,    // pending retry for an unacknowledged charge push
    settingsRetryTimer: null, // pending retry for a failed settings push
    lastNudgeTs: 0,      // last UI-nudged pull (pullSoon), for the throttle
    lastSettingsPullTs: 0, // last settings pull, for the slower settings cadence
    backoffIdx: 0,
    status: { on: false, state: OFF, lastSyncTs: 0 }
  };

  function cfg() { return S.adapter.getConfig(); }
  function serverBase() { return (cfg().serverUrl || DEFAULT_SERVER).replace(/\/+$/, ''); }
  function hasCode() { return !!cfg().syncCode; }

  async function keys() {
    const code = cfg().syncCode;
    if (!code) throw new Error('no code');
    if (S.keysForCode !== code) { S.keys = await deriveKeys(code); S.keysForCode = code; }
    return S.keys;
  }

  function setStatus(state, extra) {
    S.status = Object.assign({ on: hasCode(), state, lastSyncTs: S.status.lastSyncTs }, extra || {});
    if (S.adapter.setStatus) S.adapter.setStatus(S.status);
  }
  function markSynced() {
    S.backoffIdx = 0;
    S.status.lastSyncTs = S.adapter.sbNow();
    setStatus(SYNCED);
  }

  function parseEtag(h) {
    if (!h) return null;
    const m = String(h).replace(/^W\//, '').replace(/"/g, '');
    const v = parseInt(m, 10);
    return isFinite(v) ? v : null;
  }

  // One round trip. Picks up the server clock offset from every response
  // (X-Server-Time is restamped after a parked watch, so it stays fresh).
  async function http(method, path, opts) {
    opts = opts || {};
    const resp = await fetch(serverBase() + path, {
      method,
      headers: Object.assign({ Authorization: 'Bearer ' + (await keys()).authToken }, opts.headers || {}),
      body: opts.body || null,
      signal: opts.signal || null
    });
    const st = resp.headers.get('X-Server-Time');
    if (st) {
      const t = Number(st);
      if (isFinite(t)) S.adapter.setServerOffset(t - Date.now());
    }
    const wantBody = (method === 'GET' && resp.status === 200) || resp.status === 409;
    const body = wantBody ? new Uint8Array(await resp.arrayBuffer()) : null;
    return { status: resp.status, version: parseEtag(resp.headers.get('ETag')), body };
  }

  function noteOffline(err) {
    setStatus(OFFLINE, { error: (err && err.message) || 'offline' });
    const delay = BACKOFF[Math.min(S.backoffIdx, BACKOFF.length - 1)];
    S.backoffIdx = Math.min(S.backoffIdx + 1, BACKOFF.length - 1);
    return delay;
  }

  // --- charge (lower charge always wins) --------------------------------------

  // Pull the charge anchor. With a known version the GET is conditional and a 304
  // means no body and no decrypt. opts.wait parks the request on the server until a
  // write lands or the wait expires, so a follower mirrors the writer without polling.
  async function pullCharge(opts) {
    opts = opts || {};
    if (!hasCode()) return;
    let path = '/v1/blob/' + (await keys()).routingId + '/charge';
    const headers = {};
    if (S.versions.charge != null) headers['If-None-Match'] = '"' + S.versions.charge + '"';
    if (opts.wait && S.versions.charge != null) path += '?wait=' + WATCH_WAIT_S;
    const r = await http('GET', path, { headers, signal: opts.signal || null });
    if (r.status === 404) { S.versions.charge = null; return; }
    if (r.status === 304) { markSynced(); return; } // unchanged since our version
    // Auth won't heal on retry, so show it in the status, but throw anyway: the watch
    // loop backs off in its catch, and a plain return would re-issue the GET every
    // pace round forever. link() reads the throw as an unconfirmed join and rolls back.
    if (r.status === 403) { setStatus(OFFLINE, { error: 'auth' }); throw new Error('auth'); }
    if (r.status !== 200) throw new Error('http ' + r.status); // 429/5xx: let callers back off
    markSynced(); // reached the server; followers rarely push, so mark it here too
    S.versions.charge = r.version;
    const anchor = await decryptDoc((await keys()).aesKey, 'charge', r.body);
    if (!anchor || typeof anchor.charge !== 'number') return;
    // Lower wins, decided in background: it adopts only if the remote implies a charge
    // at or below ours, otherwise it keeps ours and we overwrite the server.
    const res = S.adapter.maybeAdoptAnchor(anchor);
    if (res && res.overwrite) pushCharge();
  }

  // Push the local anchor over the server copy. The engaged writer's job, plus one-off
  // user actions like reserve. On a version clash we take the server's version and
  // re-write our value.
  //
  // Stays dirty until the server acknowledges it, and any failure schedules a retry
  // whether or not this device is still engaged. Losing the "I stopped draining" push
  // used to strand a permanently draining anchor on the server; projection bounds the
  // damage now, but the retry is what clears it.
  async function pushCharge() {
    if (!hasCode()) return;
    if (S.busyCharge) { S.pendingCharge = true; return; }
    S.busyCharge = true;
    S.dirty = true;
    try {
      const k = await keys();
      const path = '/v1/blob/' + k.routingId + '/charge';
      const body = await encryptDoc(k.aesKey, 'charge', S.adapter.getAnchor());
      for (let attempt = 0; attempt < 4; attempt++) {
        let r;
        if (S.versions.charge == null) {
          r = await http('PUT', path, { headers: { 'If-None-Match': '*' }, body });
          if (r.status === 200) { S.versions.charge = 1; S.dirty = false; markSynced(); return; }
          if (r.status === 412) { S.versions.charge = r.version; continue; }
        } else {
          r = await http('PUT', path, { headers: { 'If-Match': '"' + S.versions.charge + '"' }, body });
          if (r.status === 200) { S.versions.charge = (S.versions.charge || 0) + 1; S.dirty = false; markSynced(); return; }
          if (r.status === 409) { S.versions.charge = r.version; continue; } // overwrite theirs
        }
        if (r.status === 403) { setStatus(OFFLINE, { error: 'auth' }); return; } // a retry can't heal auth
        retryChargeSoon(); // unexpected status (5xx): transient, try again later
        return;
      }
      retryChargeSoon(); // the CAS kept clashing; back off and retry
    } catch (err) {
      noteOffline(err);
      retryChargeSoon();
    } finally {
      S.busyCharge = false;
      if (S.pendingCharge) { S.pendingCharge = false; pushCharge(); }
    }
  }

  // Backoff retry for an unacknowledged push. Each attempt re-reads the anchor, so
  // what finally lands is current.
  function retryChargeSoon() {
    if (S.retryTimer) return;
    const delay = BACKOFF[Math.min(S.backoffIdx, BACKOFF.length - 1)];
    S.retryTimer = setTimeout(() => {
      S.retryTimer = null;
      if (S.dirty && hasCode()) pushCharge().catch(() => {});
    }, delay);
  }

  // --- settings (per-key last write wins, merged) -----------------------------

  function localSettingsDoc() {
    const s = S.adapter.getSettings();       // { key: value }
    const meta = cfg().settingsMeta || {};    // { key: ts }
    const doc = {};
    for (const key of Object.keys(s)) doc[key] = { value: s[key], ts: meta[key] || 0 };
    return doc;
  }

  // Adopt a merged settings map: hand the values to background and record the timestamps.
  function adoptSettings(merged) {
    const values = {}, meta = Object.assign({}, cfg().settingsMeta || {});
    for (const key of Object.keys(merged)) {
      values[key] = merged[key].value;
      meta[key] = merged[key].ts;
    }
    S.adapter.saveConfig({ settingsMeta: meta });
    S.adapter.applyRemoteSettings(values);
  }

  function sameDoc(a, b) {
    return JSON.stringify(a) === JSON.stringify(b);
  }

  async function pullSettings() {
    if (!hasCode()) return;
    S.lastSettingsPullTs = Date.now();
    const headers = {};
    if (S.versions.settings != null) headers['If-None-Match'] = '"' + S.versions.settings + '"';
    const r = await http('GET', '/v1/blob/' + (await keys()).routingId + '/settings', { headers });
    if (r.status === 404) { S.versions.settings = null; return; }
    if (r.status === 304) return; // unchanged since our version
    if (r.status !== 200) return;
    S.versions.settings = r.version;
    const remote = await decryptDoc((await keys()).aesKey, 'settings', r.body);
    if (!remote || !remote.keys) return;
    const local = localSettingsDoc();
    const merged = mergeSettings(local, remote.keys);
    if (!sameDoc(merged, local)) adoptSettings(merged);   // remote had newer keys
    if (!sameDoc(merged, remote.keys)) pushSettings();     // local had newer keys, push them
  }

  async function pushSettings() {
    if (!hasCode()) return;
    if (S.busySettings) { S.pendingSettings = true; return; }
    S.busySettings = true;
    try {
      const k = await keys();
      const path = '/v1/blob/' + k.routingId + '/settings';
      for (let attempt = 0; attempt < 4; attempt++) {
        const doc = { keys: localSettingsDoc() };
        const body = await encryptDoc(k.aesKey, 'settings', doc);
        let r;
        if (S.versions.settings == null) {
          r = await http('PUT', path, { headers: { 'If-None-Match': '*' }, body });
          if (r.status === 200) { S.versions.settings = 1; markSynced(); return; }
          if (r.status === 412) { S.versions.settings = r.version; continue; }
        } else {
          r = await http('PUT', path, { headers: { 'If-Match': '"' + S.versions.settings + '"' }, body });
          if (r.status === 200) { S.versions.settings = (S.versions.settings || 0) + 1; markSynced(); return; }
          if (r.status === 409) {
            // Merge the winner's doc in and retry, so no one's change is lost.
            const remote = await decryptDoc(k.aesKey, 'settings', r.body);
            S.versions.settings = r.version;
            if (remote && remote.keys) {
              const merged = mergeSettings(doc.keys, remote.keys);
              if (!sameDoc(merged, doc.keys)) adoptSettings(merged);
            }
            continue;
          }
        }
        if (r.status === 403) { setStatus(OFFLINE, { error: 'auth' }); return; } // a retry can't heal auth
        retrySettingsSoon(); // unexpected status (5xx): transient, try again later
        return;
      }
      retrySettingsSoon(); // the CAS kept clashing; back off and retry
    } catch (err) {
      noteOffline(err);
      retrySettingsSoon();
    } finally {
      S.busySettings = false;
      if (S.pendingSettings) { S.pendingSettings = false; pushSettings(); }
    }
  }

  // Backoff retry for a failed settings push, single flight like the charge one. Each
  // attempt rebuilds the doc.
  function retrySettingsSoon() {
    if (S.settingsRetryTimer) return;
    const delay = BACKOFF[Math.min(S.backoffIdx, BACKOFF.length - 1)];
    S.settingsRetryTimer = setTimeout(() => {
      S.settingsRetryTimer = null;
      if (hasCode()) pushSettings().catch(() => {});
    }, delay);
  }

  // --- timers -----------------------------------------------------------------

  function startPush() {
    if (S.pushTimer) return;
    S.pushTimer = setInterval(() => pushCharge(), PUSH_MS);
    // A minimized window throttles setInterval, so a background drain would stop
    // pushing. The alarm still fires under throttling.
    if (S.adapter.setHeartbeat) S.adapter.setHeartbeat(true);
  }
  function stopPush() {
    if (S.pushTimer) { clearInterval(S.pushTimer); S.pushTimer = null; }
    if (S.adapter.setHeartbeat) S.adapter.setHeartbeat(false);
  }
  // The follower's watch loop, one parked request at a time. Each round is a
  // conditional GET the server holds until a write lands or the wait expires. The pace
  // floor keeps the loop from spinning whatever the server answers.
  function startWatch() {
    stopWatch(); // one loop at a time: abort any parked GET so the old loop exits
    const gen = ++S.watchGen; // retires any previous loop
    (async () => {
      while (gen === S.watchGen && S.active && !S.engaged && hasCode()) {
        const t0 = Date.now();
        const ctrl = new AbortController();
        S.watchAbort = ctrl;
        try {
          await pullCharge({ wait: true, signal: ctrl.signal });
        } catch (err) {
          if (err && err.name === 'AbortError') break; // stopWatch retired us
          const delay = noteOffline(err);
          await sleep(delay);
        } finally {
          // Clear only our own controller; a newer loop may have installed its own.
          if (S.watchAbort === ctrl) S.watchAbort = null;
        }
        const dt = Date.now() - t0;
        if (dt < WATCH_PACE_MS) await sleep(WATCH_PACE_MS - dt);
        // No doc yet (404: fresh code, or a GC'd profile). Nothing to park on, so
        // check back at the watch cadence instead of spinning on immediate GETs.
        if (S.versions.charge == null) await sleep(WATCH_WAIT_S * 1000);
      }
    })();
  }
  function stopWatch() {
    S.watchGen++;
    if (S.watchAbort) { try { S.watchAbort.abort(); } catch (_) {} S.watchAbort = null; }
  }

  // --- lifecycle called by background -----------------------------------------

  const Sync = {
    init(adapter) {
      S.adapter = adapter;
      setStatus(hasCode() ? SYNCED : OFF);
      if (hasCode()) {
        // Catch up on load, then let engagement/poll drive the rest.
        pullCharge().then(pullSettings).catch(() => {});
      }
    },

    // A tab opened (background woke): catch up on settings and start watching. Guarded
    // on the adapter because onConnect can fire before background's async load calls
    // init(), and a throw there aborts the port wiring.
    onActive() {
      if (!S.adapter || !hasCode() || S.active) return;
      S.active = true;
      pullSettings().catch(() => {});
      startWatch();
    },

    // No tabs left: stop watching. (No push here; onEngagementChange already flushed.)
    onIdle() {
      S.active = false;
      stopWatch();
    },

    // The single writer handoff. Becoming engaged: adopt the shared anchor, then own
    // it. Releasing: flush the final charge and stop writing (the flush retries, see
    // pushCharge). Guarded on the adapter: settle() can run off an early idle or window
    // event before init().
    onEngagementChange(engaged) {
      if (!S.adapter || !hasCode()) return;
      if (engaged === S.engaged) return;
      S.engaged = engaged;
      if (engaged) {
        stopWatch(); // the writer owns its state; no parked read should linger
        setStatus(SYNCING);
        pullCharge().then(() => { startPush(); pushCharge(); }).catch(() => { startPush(); });
      } else {
        stopPush();
        pushCharge().catch(() => {});
        if (S.active) startWatch(); // back to following
      }
    },

    // Fired by an alarm, which survives a minimized window's timer throttling. Keeps the
    // active writer pushing so a background drain propagates.
    onHeartbeat() {
      if (!hasCode()) return;
      if (!S.engaged) {
        // The alarm survives an event page restart but S.engaged does not, so a stale
        // alarm would wake the page every minute forever. startPush re-arms it when a
        // device actually becomes engaged.
        if (S.adapter && S.adapter.setHeartbeat) S.adapter.setHeartbeat(false);
        return;
      }
      pushCharge().catch(() => {});
    },

    // A settings key changed locally: stamp it and push.
    onSettingsChanged(changedKeys) {
      if (!hasCode() || !changedKeys || !changedKeys.length) return;
      const meta = Object.assign({}, cfg().settingsMeta || {});
      const now = S.adapter.sbNow();
      for (const key of changedKeys) meta[key] = now;
      S.adapter.saveConfig({ settingsMeta: meta });
      pushSettings().catch(() => {});
    },

    // A discrete charge change while not the active writer (reserve, per-video
    // unblock). Adoption is lower wins, so a drop propagates to the other devices and
    // a raise only updates the server copy.
    pushChargeSoon() {
      if (!hasCode()) return;
      pushCharge().catch(() => {});
    },

    // A UI nudge (the popup polls getState every second): pull, but throttled.
    pullSoon() {
      if (!hasCode() || S.engaged) return;
      if (Date.now() - S.lastNudgeTs < NUDGE_MS) return;
      S.lastNudgeTs = Date.now();
      pullCharge().catch(() => {});
      // Settings too, on a slower cadence. An event page can live for hours with tabs
      // open, so without this the phone's edits land only on the next background load,
      // and a desktop save could restamp a stale copy of the phone's app modes.
      if (Date.now() - S.lastSettingsPullTs >= SETTINGS_PULL_MS) {
        pullSettings().catch(() => {});
      }
    },

    // --- UI actions -------------------------------------------------------------

    // Turn sync on: mint a code, seed the server from this device, return the code.
    async enable() {
      const code = generateCode();
      S.adapter.saveConfig({ syncCode: code });
      S.keysForCode = null; S.versions = {};
      try {
        await this._activate();
        setStatus(SYNCED, { on: true });
      } catch (err) {
        noteOffline(err);
      }
      return { code: formatCode(code), rawCode: code, serverUrl: cfg().serverUrl || DEFAULT_SERVER };
    },

    // Join an existing battery from a pasted code. Any 26 valid characters decode, so
    // a typo would otherwise seed a fresh empty profile and report itself synced. The
    // join only succeeds against a profile the server already has; anything less rolls
    // the sync config back. A charge the pull already adopted stands, and lower wins
    // means it only ever lowered.
    async link(rawInput) {
      const raw = base32Decode(rawInput);
      if (raw.length !== CODE_BYTES) return { ok: false, error: 'badCode' };
      const code = base32Encode(raw);
      const prevCode = cfg().syncCode;
      const rollback = () => {
        S.adapter.saveConfig({ syncCode: prevCode || null });
        S.keysForCode = null; S.versions = {};
        if (prevCode) this._activate().catch(() => {});
        else setStatus(OFF, { on: false });
      };
      S.adapter.saveConfig({ syncCode: code });
      S.keysForCode = null; S.versions = {};
      try {
        setStatus(SYNCING);
        await pullCharge();
        if (S.versions.charge === null) { // a confirmed 404: no such profile
          rollback();
          return { ok: false, error: 'noProfile' };
        }
        if (S.versions.charge === undefined) throw new Error('unconfirmed'); // guard; pullCharge throws on errors itself now
        await pullSettings();
        if (S.versions.settings == null) await pushSettings();
        if (S.active && !S.engaged) startWatch();
        if (S.engaged) startPush();
        setStatus(SYNCED, { on: true });
        return { ok: true };
      } catch (err) {
        rollback();
        return { ok: false, error: 'offline' };
      }
    },

    // Seed the server clock offset, then reconcile both docs: adopt what the server has,
    // or create it from this device if the profile is new.
    async _activate() {
      setStatus(SYNCING);
      try {
        const r = await http('GET', '/v1/time');
        // offset already applied inside http()
        void r;
      } catch (_) { /* time is best effort; the next request re-anchors */ }
      await pullCharge();
      if (S.versions.charge == null) await pushCharge();   // profile was new: seed charge
      await pullSettings();
      if (S.versions.settings == null) await pushSettings(); // seed settings
      if (S.active && !S.engaged) startWatch();
      if (S.engaged) { startPush(); }
    },

    setServer(url) {
      S.adapter.saveConfig({ serverUrl: (url || '').replace(/\/+$/, '') || DEFAULT_SERVER });
      S.versions = {}; // versions are per server
      if (hasCode()) this._activate().catch((err) => noteOffline(err));
    },

    unlink() {
      stopPush(); stopWatch();
      if (S.retryTimer) { clearTimeout(S.retryTimer); S.retryTimer = null; }
      if (S.settingsRetryTimer) { clearTimeout(S.settingsRetryTimer); S.settingsRetryTimer = null; }
      S.dirty = false;
      S.engaged = false; S.active = false; S.versions = {};
      S.keys = null; S.keysForCode = null;
      S.adapter.saveConfig({ syncCode: null, serverOffset: 0, settingsMeta: {} });
      S.status.lastSyncTs = 0;
      setStatus(OFF, { on: false });
    },

    getStatus() { return S.status; },
    defaultServer() { return DEFAULT_SERVER; },

    // Exposed for tests.
    _internals: { deriveKeys, encryptDoc, decryptDoc, mergeSettings, base32Encode, base32Decode, formatCode }
  };

  globalThis.Sync = Sync;
})();
