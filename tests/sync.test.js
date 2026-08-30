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
