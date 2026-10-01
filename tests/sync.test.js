// Tests for sync.js's pure internals (code handling, crypto, settings merge).
// The engine itself needs a browser; these cover the parts that don't.
// Run: node --test

const { test } = require('node:test');
const assert = require('node:assert/strict');

require('../sync.js'); // IIFE, installs globalThis.Sync
const I = globalThis.Sync._internals;

test('base32 round-trips 16 random bytes', () => {
  const bytes = new Uint8Array(16).map((_, i) => (i * 37 + 5) % 256);
  const decoded = I.base32Decode(I.base32Encode(bytes));
  assert.deepEqual([...decoded], [...bytes]);
});

test('code entry forgives spacing, case, and ambiguous characters', () => {
  const code = I.base32Encode(new Uint8Array(16).map((_, i) => i * 11 % 256));
  const sloppy = I.formatCode(code).toLowerCase()
    .replace(/0/g, 'o').replace(/1/g, 'l');
  assert.equal(I.base32Encode(I.base32Decode(sloppy)), code);
});

test('keys derive deterministically and differ per purpose', async () => {
  const code = I.base32Encode(new Uint8Array(16).map((_, i) => i));
  const a = await I.deriveKeys(code);
  const b = await I.deriveKeys(code);
  assert.equal(a.routingId, b.routingId);
  assert.equal(a.authToken, b.authToken);
  assert.notEqual(a.routingId, a.authToken);
});

test('encrypt/decrypt round-trips, and a doc name mismatch decrypts to null', async () => {
  const code = I.base32Encode(new Uint8Array(16).map((_, i) => 255 - i));
  const { aesKey } = await I.deriveKeys(code);
  const anchor = { charge: 123, asOf: 456, draining: true, writer: 'ab12' };
  const blob = await I.encryptDoc(aesKey, 'charge', anchor);
  assert.deepEqual(await I.decryptDoc(aesKey, 'charge', blob), anchor);
  // The doc name is bound in as AAD: a charge blob can't be replayed as settings.
  assert.equal(await I.decryptDoc(aesKey, 'settings', blob), null);
});

test('settings merge is per-key last write wins', () => {
  const local = {
    capacity: { value: 1800, ts: 10 },
    rechargePerMin: { value: 5, ts: 30 }
  };
  const remote = {
    capacity: { value: 3600, ts: 20 },
    hideYtSidebar: { value: true, ts: 5 }
  };
  const merged = I.mergeSettings(local, remote);
  assert.equal(merged.capacity.value, 3600);      // remote newer
  assert.equal(merged.rechargePerMin.value, 5);   // local only
  assert.equal(merged.hideYtSidebar.value, true); // remote only
});

test('settings merge ties go to local', () => {
  const merged = I.mergeSettings(
    { capacity: { value: 1, ts: 10 } },
    { capacity: { value: 2, ts: 10 } });
  assert.equal(merged.capacity.value, 1);
});

// --- engine smoke tests (stubbed fetch, fake adapter) ------------------------

function fakeAdapter(overrides) {
  const cfg = { syncCode: I.base32Encode(new Uint8Array(16).map((_, i) => i * 7 % 256)) };
  return Object.assign({
    getConfig: () => cfg,
    saveConfig: (patch) => Object.assign(cfg, patch),
    setStatus: () => {},
    setServerOffset: () => {},
    sbNow: () => Date.now(),
    getSettings: () => ({}),
    getAnchor: () => ({ charge: 0, asOf: 0 }),
    maybeAdoptAnchor: () => null,
    applyRemoteSettings: () => {}
  }, overrides || {});
}

test('a heartbeat while not engaged disarms the alarm', () => {
  const calls = [];
  globalThis.fetch = () => Promise.reject(new Error('offline'));
  globalThis.Sync.init(fakeAdapter({ setHeartbeat: (on) => calls.push(on) }));
  globalThis.Sync.onHeartbeat();
  assert.deepEqual(calls, [false]);
});

test('a 403 pull surfaces broken auth in the status', async () => {
  globalThis.fetch = async () => new Response(null, { status: 403 });
  globalThis.Sync.init(fakeAdapter());
  for (let i = 0; i < 200 && globalThis.Sync.getStatus().state !== 'offline'; i++) {
    await new Promise((res) => setImmediate(res));
  }
  const st = globalThis.Sync.getStatus();
  assert.equal(st.state, 'offline');
  assert.equal(st.error, 'auth');
});

// --- clock anchoring: a held response must not move the clock -----------------
// The offset is server time minus the moment the response is handled. Android freezes a
// backgrounded Firefox, so a response the server sent at night can be handled in the
// morning; read then it would set the clock hours behind and last night's anchor would
// project without its recharge. Only a round trip short enough to vouch for the reading
// counts: the park the request asked for plus a slack.

// Drives Date.now() so the test can stretch a round trip without waiting.
function withFakeClock(fn) {
  const real = Date.now;
  const clock = { now: real() };
  Date.now = () => clock.now;
  return fn(clock).finally(() => { Date.now = real; });
}

function stampedResponse(status, serverTime, extraHeaders) {
  return new Response(null, {
    status,
    headers: Object.assign({ 'X-Server-Time': String(serverTime) }, extraHeaders || {})
  });
}

async function settleFetches(count, fetches) {
  for (let i = 0; i < 500 && fetches.length < count; i++) await new Promise((r) => setImmediate(r));
  for (let i = 0; i < 20; i++) await new Promise((r) => setImmediate(r));
}

test('a response held through a sleep does not move the clock', () => withFakeClock(async (clock) => {
  const offsets = [];
  const fetches = [];
  globalThis.fetch = async (url) => {
    fetches.push(url);
    clock.now += 8 * 3600 * 1000; // the phone slept on the parked response
    return stampedResponse(404, clock.now - 8 * 3600 * 1000 + 50);
  };
  globalThis.Sync.init(fakeAdapter({ setServerOffset: (ms) => offsets.push(ms) }));
  await settleFetches(1, fetches);
  assert.ok(fetches.length >= 1);
  assert.deepEqual(offsets, []);
}));

test('a prompt response anchors the clock as before', () => withFakeClock(async (clock) => {
  const offsets = [];
  const fetches = [];
  globalThis.fetch = async (url) => {
    fetches.push(url);
    clock.now += 400; // a real network round trip
    return stampedResponse(404, clock.now + 50);
  };
  globalThis.Sync.init(fakeAdapter({ setServerOffset: (ms) => offsets.push(ms) }));
  await settleFetches(1, fetches); // the charge pull, then the settings pull behind it
  assert.ok(offsets.length >= 1);
  assert.ok(offsets.every((o) => o === 50), String(offsets));
}));

test('a parked watch answered inside its park plus slack still anchors the clock', () => withFakeClock(async (clock) => {
  const offsets = [];
  const fetches = [];
  const { WATCH_WAIT_S, TIME_SLACK_MS } = I;
  const STRETCH = WATCH_WAIT_S * 1000 + TIME_SLACK_MS - 1000;
  let stretched = false;
  globalThis.fetch = async (url) => {
    fetches.push(String(url));
    if (!/wait=/.test(String(url))) {
      // The first charge pull gives the watch a version to park on.
      return /charge$/.test(String(url))
        ? stampedResponse(200, clock.now + 50, { ETag: '"1"' })
        : stampedResponse(404, clock.now + 50);
    }
    // The server parked the whole wait, then the network took a little more. One
    // stretched round; the rounds after it run on the pace floor like any idle watch.
    await new Promise((r) => setImmediate(r));
    const stamp = clock.now + 50; // the server stamps when it answers
    if (!stretched) { stretched = true; clock.now += STRETCH; }
    return stampedResponse(304, stamp);
  };
  const adapter = fakeAdapter({ setServerOffset: (ms) => offsets.push(ms) });
  globalThis.Sync.init(adapter);
  await settleFetches(1, fetches);
  offsets.length = 0;
  globalThis.Sync.onActive();
  await settleFetches(3, fetches);
  globalThis.Sync.onIdle();
  assert.ok(fetches.some((u) => /wait=/.test(u)), 'the watch parked');
  assert.ok(offsets.includes(50 - STRETCH), 'the parked round anchored the clock: ' + offsets);
}));

test('a parked watch held past its park plus slack does not', () => withFakeClock(async (clock) => {
  const offsets = [];
  const fetches = [];
  const { WATCH_WAIT_S, TIME_SLACK_MS } = I;
  const STRETCH = WATCH_WAIT_S * 1000 + TIME_SLACK_MS + 1000;
  let stretched = false;
  globalThis.fetch = async (url) => {
    fetches.push(String(url));
    if (!/wait=/.test(String(url))) {
      return /charge$/.test(String(url))
        ? stampedResponse(200, clock.now + 50, { ETag: '"1"' })
        : stampedResponse(404, clock.now + 50);
    }
    await new Promise((r) => setImmediate(r));
    const stamp = clock.now + 50; // the server stamps when it answers
    if (!stretched) { stretched = true; clock.now += STRETCH; }
    return stampedResponse(304, stamp);
  };
  const adapter = fakeAdapter({ setServerOffset: (ms) => offsets.push(ms) });
  globalThis.Sync.init(adapter);
  await settleFetches(1, fetches);
  offsets.length = 0;
  globalThis.Sync.onActive();
  await settleFetches(3, fetches);
  globalThis.Sync.onIdle();
  assert.ok(fetches.some((u) => /wait=/.test(u)), 'the watch parked');
  // The prompt rounds around it anchor as usual; the held reading never lands.
  assert.ok(offsets.every((o) => o === 50), 'the held reading must be dropped: ' + offsets);
}));
