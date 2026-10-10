// Tests for background.js. The browser and Sync globals are stubbed before the
// require, and time is injected by writing lastTickTs / asOf / beat timestamps
// directly (sbNow is Date.now plus offset), so the production code runs unmodified.
// Run: node --test

const { test, before } = require('node:test');
const assert = require('node:assert/strict');

require('../platforms.js'); // sets globalThis.TRACKED_PLATFORMS, like the manifest load order
require('../rules.js');      // sets globalThis.SBRules
require('../projection.js'); // sets globalThis.SBProjection

// Inert Sync: background only ever notifies it; nothing here calls back.
globalThis.Sync = {
  init() {}, onActive() {}, onIdle() {}, onEngagementChange() {},
  onSettingsChanged() {}, pushChargeSoon() {}, pullSoon() {}, onHeartbeat() {},
  enable() {}, link() {}, setServer() {}, unlink() {},
  defaultServer: () => 'https://example.invalid',
  _internals: { formatCode: (c) => c }
};

// Minimal browser stub: storage backed by an object, events exposing their
// listeners so tests can fire them, everything else a no-op.
const listeners = {};
function evt(name) {
  return { addListener: (fn) => { listeners[name] = fn; } };
}
const storageData = {};
const stub = {
  storage: {
    local: {
      get: async () => ({ ...storageData }),
      set: async (obj) => { Object.assign(storageData, obj); }
    }
  },
  runtime: {
    onConnect: evt('onConnect'),
    onMessage: evt('onMessage'),
    onInstalled: evt('onInstalled'),
    getURL: (p) => p
  },
  tabs: { onRemoved: evt('tabRemoved'), onAttached: evt('tabAttached'), create: () => {} },
  windows: { onFocusChanged: evt('focusChanged'), getAll: async () => [] },
  permissions: { contains: async () => true, remove: async () => {} },
  alarms: { create() {}, clear() {}, onAlarm: evt('onAlarm') },
  action: {
    setBadgeText(o) { lastBadgeText = o.text; },
    setBadgeBackgroundColor() {},
    setBadgeTextColor() {}
  }
};
let lastBadgeText = '';
globalThis.__sbBrowserStub = stub;

const B = require('../background.js');

before(() => B.ready);

// Known-good baseline; each test overrides what it cares about.
function reset(over = {}) {
  B.onscreenTabs.clear();
  B.setMinimizedWindows(new Set());
  B.setState({
    ...B.DEFAULTS,
    enabledSites: {},
    customSites: [],
    hourRules: [],
    sitePasses: {},
    settingsMeta: {},
    charge: 900,
    capacity: 1800,
    rechargePerMin: 5,
    depleted: false,
    depletedAt: null,
    remoteDraining: false,
    remoteDrainingTs: 0,
    serverOffset: 0,
    lastTickTs: Date.now(),
    deviceId: 'me'
  });
  B.setState(over);
}

// A fresh on-screen beat for a tab; overrides shape the engagement signals.
function beat(tabId, over = {}) {
  B.onscreenTabs.set(tabId, {
    ts: Date.now(), playing: false, inputAgo: 0, site: 'youtube', windowId: 1, ...over
  });
}

// --- maybeAdoptAnchor: lower wins, projected state, echo and lease rules ------

test('adopts a lower anchor and takes its projected charge', () => {
  reset({ charge: 900 });
  const r = B.maybeAdoptAnchor({ charge: 300, asOf: Date.now(), draining: false, writer: 'other' });
  assert.equal(r.adopted, true);
  assert.ok(Math.abs(B.getState().charge - 300) < 1);
});

test('rejects a higher idle anchor and tells the caller to overwrite', () => {
  reset({ charge: 300 });
  const r = B.maybeAdoptAnchor({ charge: 900, asOf: Date.now(), draining: false, writer: 'other' });
  assert.equal(r.adopted, false);
  assert.equal(r.overwrite, true);
  assert.ok(Math.abs(B.getState().charge - 300) < 1);
});

test('never adopts its own echo', () => {
  reset({ charge: 900, deviceId: 'me' });
  const r = B.maybeAdoptAnchor({ charge: 100, asOf: Date.now(), draining: false, writer: 'me' });
  assert.equal(r.adopted, false);
  assert.ok(Math.abs(B.getState().charge - 900) < 1);
});

test('ADOPT_EPS boundary: local plus 2 adopts, local plus 3 rejects', () => {
  // Future timestamps freeze both sides: no recharge drift on either projection.
  const future = Date.now() + 5000;
  reset({ charge: 900, lastTickTs: future });
  let r = B.maybeAdoptAnchor({ charge: 902, asOf: future, draining: false, writer: 'other' });
  assert.equal(r.adopted, true);

  reset({ charge: 900, lastTickTs: future });
  r = B.maybeAdoptAnchor({ charge: 903, asOf: future, draining: false, writer: 'other' });
  assert.equal(r.adopted, false);
});

test('a lower non depleted anchor lowers the charge but cannot cut a running cooldown short', () => {
  const now = Date.now();
  const depletedAt = now - 2 * 60 * 1000; // mid cooldown
  reset({ charge: 5, depleted: true, depletedAt, lastTickTs: now });
  const r = B.maybeAdoptAnchor({
    charge: 1, asOf: now + 5000, draining: false, depleted: false, writer: 'other'
  });
  assert.equal(r.adopted, true);
  const s = B.getState();
  assert.ok(s.charge <= 1.1); // the lower charge landed
  assert.equal(s.depleted, true); // the cooldown did not
  assert.equal(s.depletedAt, depletedAt);
});

test('a remote depletion is still adopted while cooling, so devices agree on depletedAt', () => {
  const now = Date.now();
  reset({ charge: 5, depleted: true, depletedAt: now - 2 * 60 * 1000, lastTickTs: now });
  const remoteAt = now - 3 * 60 * 1000;
  const r = B.maybeAdoptAnchor({
    charge: 0, asOf: now, draining: false, depleted: true, depletedAt: remoteAt, writer: 'other'
  });
  assert.equal(r.adopted, true);
  assert.equal(B.getState().depletedAt, remoteAt);
});

test('a higher FRESH draining anchor is rejected without an overwrite (no CAS ping pong)', () => {
  const now = Date.now();
  reset({ charge: 300, lastTickTs: now });
  const r = B.maybeAdoptAnchor({ charge: 900, asOf: now - 5000, draining: true, writer: 'other' });
  assert.equal(r.adopted, false);
  assert.equal(r.overwrite, false);
});

test('a higher STALE draining anchor is rejected with an overwrite', () => {
  const now = Date.now();
  reset({ charge: 300, lastTickTs: now });
  const r = B.maybeAdoptAnchor({
    charge: 900, asOf: now - (B.DRAIN_LEASE_MS + 60 * 1000), draining: true, writer: 'other'
  });
  assert.equal(r.adopted, false);
  assert.equal(r.overwrite, true);
});

test('a rejected stopped anchor from the mirrored writer still ends the mirror', () => {
  const now = Date.now();
  reset({ charge: 900, lastTickTs: now - 30000 });
  // Followed a writer for thirty seconds: the mirror drained this device to 870.
  let r = B.maybeAdoptAnchor({ charge: 900, asOf: now - 30000, draining: true, writer: 'other' });
  assert.equal(r.adopted, true);
  assert.ok(Math.abs(B.getState().charge - 870) < 1);
  assert.equal(B.isDraining(), true);
  // The writer stopped three seconds ago and says so. Its value sits above the mirror
  // by more than the epsilon, so it is not adopted, but the mirror must end.
  r = B.maybeAdoptAnchor({ charge: 873, asOf: now - 3000, draining: false, writer: 'other' });
  assert.equal(r.adopted, false);
  assert.equal(r.overwrite, true);
  assert.equal(B.getState().remoteDraining, false);
  assert.equal(B.isDraining(), false);
});

test('an older stopped anchor does not end a newer mirror', () => {
  const now = Date.now();
  reset({ charge: 900, lastTickTs: now });
  B.maybeAdoptAnchor({ charge: 900, asOf: now - 5000, draining: true, writer: 'other' });
  const r = B.maybeAdoptAnchor({ charge: 900, asOf: now - 20000, draining: false, writer: 'other' });
  assert.equal(r.adopted, false);
  assert.equal(B.getState().remoteDraining, true);
});

test('the remote drain mirror lease is seeded from the anchor asOf, not from now', () => {
  const asOf = Date.now() - 10000;
  reset({ charge: 900 });
  const r = B.maybeAdoptAnchor({ charge: 300, asOf, draining: true, writer: 'other' });
  assert.equal(r.adopted, true);
  const s = B.getState();
  assert.equal(s.remoteDraining, true);
  assert.equal(s.remoteDrainingTs, asOf);
});

// --- recompute: drain, depletion, cooldown -------------------------------------

test('drains 1s/sec while engaged and not depleted', () => {
  reset({ charge: 100, lastTickTs: Date.now() - 10000 });
  beat(1, { playing: true });
  B.recompute();
  assert.ok(Math.abs(B.getState().charge - 90) < 0.5);
});

test('enters depletion at zero with depletedAt at the crossing tick', () => {
  reset({ charge: 5, lastTickTs: Date.now() - 10000 });
  beat(1, { playing: true });
  B.recompute();
  const s = B.getState();
  assert.equal(s.charge, 0);
  assert.equal(s.depleted, true);
  assert.equal(s.depletedAt, s.lastTickTs);
});

test('recharges through the cooldown and stays blocked inside it', () => {
  const now = Date.now();
  reset({ charge: 0, depleted: true, depletedAt: now - 60000, lastTickTs: now - 60000 });
  B.recompute();
  const s = B.getState();
  assert.equal(s.depleted, true);
  assert.ok(Math.abs(s.charge - 5) < 0.5); // 60s at 5/min
});

test('lifts depletion only after COOLDOWN_SECONDS, with the cooldown recharge banked', () => {
  const now = Date.now();
  const past = (B.COOLDOWN_SECONDS + 1) * 1000;
  reset({ charge: 0, depleted: true, depletedAt: now - past, lastTickTs: now - past });
  B.recompute();
  const s = B.getState();
  assert.equal(s.depleted, false);
  assert.equal(s.depletedAt, null);
  assert.ok(s.charge > 0);
});

test('never drains while depleted, even with an engaged tab', () => {
  reset({
    charge: 50, depleted: true,
    depletedAt: Date.now() - 30000, lastTickTs: Date.now() - 10000
  });
  beat(1, { playing: true });
  B.recompute();
  assert.ok(B.getState().charge > 50); // recharged, not drained
});

// --- engagement: beats, lease, minimized windows -------------------------------

test('a fresh beat with recent input counts as engaged', () => {
  reset();
  beat(1, { inputAgo: 0 });
  assert.equal(B.isEngaged(), true);
});

test('an expired lease does not count', () => {
  reset();
  beat(1, { ts: Date.now() - B.ONSCREEN_LEASE_MS - 1000 });
  assert.equal(B.isEngaged(), false);
});

test('a beat from a minimized window does not count', () => {
  reset();
  beat(1, { windowId: 7 });
  B.setMinimizedWindows(new Set([7]));
  assert.equal(B.isEngaged(), false);
});

test('audible video with no input counts; stale input alone does not', () => {
  reset();
  beat(1, { playing: true, inputAgo: Infinity });
  assert.equal(B.isEngaged(), true);
  beat(1, { playing: false, inputAgo: B.ENGAGE_INPUT_MS + 5000 });
  assert.equal(B.isEngaged(), false);
});

test('a switched off site never counts', () => {
  reset({ enabledSites: { youtube: false } });
  beat(1, { playing: true });
  assert.equal(B.isEngaged(), false);
});

test('dragging a tab to another window updates its windowId', () => {
  reset();
  beat(1, { windowId: 7, playing: true });
  B.setMinimizedWindows(new Set([7]));
  assert.equal(B.isEngaged(), false);
  listeners.tabAttached(1, { newWindowId: 8 });
  assert.equal(B.isEngaged(), true);
});

test('a closing tab attributes its trailing seconds under the old engagement', () => {
  reset({ charge: 100, lastTickTs: Date.now() - 10000 });
  beat(1, { playing: true });
  listeners.tabRemoved(1);
  const s = B.getState();
  assert.ok(Math.abs(s.charge - 90) < 0.5); // drained, not recharged
  assert.equal(B.onscreenTabs.has(1), false);
});

// --- applyServerOffset: every timestamp crosses the jump together --------------

test('applyServerOffset shifts lastTickTs, depletedAt, and beat timestamps together', () => {
  const now = Date.now();
  reset({ charge: 0, depleted: true, depletedAt: now - 1000, lastTickTs: now });
  beat(1, { playing: true });
  assert.equal(B.isEngaged(), true);
  B.applyServerOffset(60000);
  const s = B.getState();
  assert.equal(s.depletedAt, now - 1000 + 60000);
  assert.ok(Math.abs(s.lastTickTs - (now + 60000)) < 500);
  assert.ok(Math.abs(B.onscreenTabs.get(1).ts - (now + 60000)) < 500);
  // Beats shift too, so engagement does not flip on the first sync.
  assert.equal(B.isEngaged(), true);
});

// --- depletionSeq: identifies a dead period where depletedAt cannot -------------

test('depletionSeq bumps on going dead and holds still across a clock re-anchor', () => {
  reset({ charge: 5, lastTickTs: Date.now() - 10000, depletionSeq: 4 });
  beat(1, { playing: true });
  B.recompute();
  assert.equal(B.getState().depleted, true);
  assert.equal(B.getState().depletionSeq, 5);
  // Re-anchoring shifts depletedAt (asserted above); the seq must not move, or a
  // synced tab would read every server response as a new dead period and drop its
  // per video unblock.
  B.applyServerOffset(60000);
  assert.equal(B.getState().depletionSeq, 5);
});

test('adopting a remote depletion while alive bumps the seq; while already dead it holds', () => {
  const now = Date.now();
  reset({ charge: 50, lastTickTs: now, depletionSeq: 2 });
  B.maybeAdoptAnchor({
    charge: 0, asOf: now, draining: false, depleted: true,
    depletedAt: now - 1000, writer: 'other'
  });
  assert.equal(B.getState().depleted, true);
  assert.equal(B.getState().depletionSeq, 3);

  // The same dead period re-read from a later anchor whose depletedAt differs:
  // still one period, no bump.
  B.maybeAdoptAnchor({
    charge: 0, asOf: Date.now(), draining: false, depleted: true,
    depletedAt: now - 3000, writer: 'other'
  });
  assert.equal(B.getState().depleted, true);
  assert.equal(B.getState().depletionSeq, 3);
});

// --- settings and message handlers ----------------------------------------------

test('applySettings floors rechargePerMin at 1 so a depleted battery can always climb out', () => {
  reset();
  B.applySettings({ rechargePerMin: 0 });
  assert.equal(B.getState().rechargePerMin, 1);
});

test('a save that changes no mode neither rewrites nor restamps enabledSites', () => {
  reset({ enabledSites: { youtube: 'block', 'app:com.vinted': 'block' } });
  const before = B.settingsSnapshot();
  // The options page emits carried ids first and every rendered row explicitly, so the
  // same modes arrive as different bytes. That must not read as a change, or a capacity
  // only save would restamp the map and push a stale copy of the phone's app modes.
  B.applySettings({
    enabledSites: { 'app:com.vinted': 'block', youtube: 'block', reddit: true, instagram: true }
  });
  assert.deepEqual(B.changedSettingKeys(before), []);
  assert.equal(B.getState().enabledSites['app:com.vinted'], 'block');
});

test('a save omitting ids it has no row for keeps them, and the real change still lands', () => {
  reset({ enabledSites: { 'app:com.vinted': 'block', youtube: false } });
  const before = B.settingsSnapshot();
  B.applySettings({ enabledSites: { youtube: true } }); // an old options page pruning save
  const s = B.getState();
  assert.equal(s.enabledSites['app:com.vinted'], 'block');
  assert.deepEqual(B.changedSettingKeys(before), ['enabledSites']); // youtube off -> on
});

test('a synced map omitting local ids keeps them, matching the phone', () => {
  reset({ enabledSites: { 'app:com.vinted': 'block' } });
  B.applyRemoteSettings({ enabledSites: { youtube: false } });
  const s = B.getState();
  assert.equal(s.enabledSites['app:com.vinted'], 'block');
  assert.equal(s.enabledSites.youtube, false);
});

test('a real mode change still restamps enabledSites', () => {
  reset({ enabledSites: { youtube: true } });
  const before = B.settingsSnapshot();
  B.applySettings({ enabledSites: { youtube: 'block' } });
  assert.deepEqual(B.changedSettingKeys(before), ['enabledSites']);
});

test('applyRemoteSettings floors rechargePerMin at 1 too', () => {
  reset();
  B.applyRemoteSettings({ rechargePerMin: 0 });
  assert.equal(B.getState().rechargePerMin, 1);
});

test('warnSeconds accepts 0 (warning off) and clamps a negative to it', () => {
  reset();
  B.applySettings({ warnSeconds: 0 });
  assert.equal(B.getState().warnSeconds, 0);
  B.applySettings({ warnSeconds: -60 });
  assert.equal(B.getState().warnSeconds, 0);
  B.applyRemoteSettings({ warnSeconds: 600 });
  assert.equal(B.getState().warnSeconds, 600);
});

test('frictionCount is 1 to 5 whole questions, locally and from a sync', () => {
  reset();
  assert.equal(B.getState().frictionCount, 1);
  B.applySettings({ frictionCount: 3 });
  assert.equal(B.getState().frictionCount, 3);
  B.applySettings({ frictionCount: 9 });
  assert.equal(B.getState().frictionCount, 5);
  B.applySettings({ frictionCount: 0 });
  assert.equal(B.getState().frictionCount, 1);
  B.applyRemoteSettings({ frictionCount: 2.4 });
  assert.equal(B.getState().frictionCount, 2);
  B.applyRemoteSettings({ frictionCount: 'lots' });
  assert.equal(B.getState().frictionCount, 1);
  assert.equal(B.snapshot().frictionCount, 1);
  assert.ok('frictionCount' in B.syncAdapter.getSettings());
});

test('the phone\'s trackedApps ride along: stored from a sync, re-emitted, never invented', () => {
  reset();
  assert.ok(!('trackedApps' in B.syncAdapter.getSettings()));
  const before = B.settingsSnapshot();
  B.applyRemoteSettings({ trackedApps: ['com.instagram.android', 7, '', 'com.vinted'] });
  assert.deepEqual(B.getState().trackedApps, ['com.instagram.android', 'com.vinted']);
  assert.deepEqual(B.syncAdapter.getSettings().trackedApps, ['com.instagram.android', 'com.vinted']);
  assert.deepEqual(B.changedSettingKeys(before), ['trackedApps']);
  // Junk shapes leave the list alone rather than emptying it.
  B.applyRemoteSettings({ trackedApps: 'com.vinted' });
  assert.deepEqual(B.getState().trackedApps, ['com.instagram.android', 'com.vinted']);
  // A local save (the options page) never carries the key, so it stays put.
  B.applySettings({ capacity: 1200 });
  assert.deepEqual(B.getState().trackedApps, ['com.instagram.android', 'com.vinted']);
  // A phone that untracked everything sends an empty list with a stamp: still carried.
  B.setState({ settingsMeta: { trackedApps: 5 } });
  B.applyRemoteSettings({ trackedApps: [] });
  assert.deepEqual(B.syncAdapter.getSettings().trackedApps, []);
});

test('badge text carries the flow arrow: down draining, up recharging, bare when full', () => {
  // 905, not 900: at exactly 15 min a millisecond of real drain between the two
  // recomputes tips the floor to 14 and the test flakes.
  reset({ charge: 905 }); // just over 15 min, idle, so recharging
  B.recompute();
  assert.equal(lastBadgeText, '↑15');
  beat(1);
  B.settle();
  assert.equal(lastBadgeText, '↓15');
  B.onscreenTabs.clear();
  B.setState({ charge: 1800 }); // full: no direction to show
  B.recompute();
  assert.equal(lastBadgeText, '30');
});

test('snapshot names the engaged site and carries warnSeconds', () => {
  reset({ warnSeconds: 300 });
  beat(1, { site: 'reddit' });
  let s = B.snapshot();
  assert.equal(s.drainingSite, 'reddit');
  assert.equal(s.warnSeconds, 300);
  // A mirrored remote drain has no local tab to name.
  B.onscreenTabs.clear();
  B.setState({ remoteDraining: true, remoteDrainingTs: Date.now() });
  s = B.snapshot();
  assert.equal(s.draining, true);
  assert.equal(s.drainingSite, null);
});

test('applyRemoteSettings normalizes synced custom hosts and drops the ones that do not', () => {
  reset();
  B.applyRemoteSettings({
    customSites: [
      { id: 'foo.com', host: 'HTTPS://Foo.COM/some/path' },
      { id: 'bad', host: 'not a host' }
    ]
  });
  assert.deepEqual(B.getState().customSites, [{ id: 'foo.com', name: 'foo.com', host: 'foo.com' }]);
});

test('useReserve adds RESERVE_SECONDS and clears depletion', async () => {
  reset({ charge: 0, depleted: true, depletedAt: Date.now() - 1000 });
  const snap = await listeners.onMessage({ type: 'useReserve' });
  assert.equal(snap.depleted, false);
  assert.ok(Math.abs(B.getState().charge - B.RESERVE_SECONDS) < 1);
});

test('removeCustomSite sets enabledSites false; re-adding clears it', async () => {
  reset();
  await listeners.onMessage({ type: 'addCustomSite', host: 'https://www.Example.com/feed', name: 'Example' });
  assert.deepEqual(B.getState().customSites, [{ id: 'example.com', name: 'Example', host: 'example.com' }]);

  await listeners.onMessage({ type: 'removeCustomSite', id: 'example.com' });
  let s = B.getState();
  assert.deepEqual(s.customSites, []);
  // False, not deleted: an already injected tab reads a missing key as enabled.
  assert.equal(s.enabledSites['example.com'], false);

  await listeners.onMessage({ type: 'addCustomSite', host: 'example.com' });
  s = B.getState();
  assert.equal(s.customSites.length, 1);
  assert.ok(!('example.com' in s.enabledSites)); // tracked again
});

// --- hour rules and site passes -------------------------------------------------

// "HH:MM" for a Date, the shape rules store; windows are built wide around now so
// minute truncation can't push the current moment outside them.
function hmOf(date) {
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}
function windowAroundNow(extra) {
  const from = hmOf(new Date(Date.now() - 120 * 60000));
  const to = hmOf(new Date(Date.now() + 120 * 60000));
  return { id: 'hr1', from, to, scope: 'all', sites: [], ...extra };
}

test('a site blocked by an open hour rule never counts as engaged', () => {
  reset({ hourRules: [windowAroundNow({ action: 'block' })] });
  beat(1, { playing: true });
  assert.equal(B.isEngaged(), false);
});

test('a site pass lets a blocked site count (and drain) again', () => {
  reset({
    hourRules: [windowAroundNow({ action: 'block' })],
    sitePasses: { youtube: Date.now() + 60000 }
  });
  beat(1, { playing: true });
  assert.equal(B.isEngaged(), true);
});

test('an hour off rule makes a site inert, scoped to the picked sites', () => {
  reset({ hourRules: [windowAroundNow({ action: 'off', scope: 'only', sites: ['youtube'] })] });
  beat(1, { playing: true, site: 'youtube' });
  assert.equal(B.isEngaged(), false);
  beat(2, { playing: true, site: 'reddit' });
  assert.equal(B.isEngaged(), true);
});

test('a settings Block site never counts without a pass', () => {
  reset({ enabledSites: { youtube: 'block' } });
  beat(1, { playing: true });
  assert.equal(B.isEngaged(), false);
  B.setState({ sitePasses: { youtube: Date.now() + 60000 } });
  assert.equal(B.isEngaged(), true);
});

test('an open recharge 0 window stops recharge across the whole gap', () => {
  reset({
    charge: 100,
    hourRules: [windowAroundNow({ action: 'recharge', percent: 0 })],
    lastTickTs: Date.now() - 60 * 60000
  });
  B.recompute();
  assert.ok(Math.abs(B.getState().charge - 100) < 1); // nothing recharged
});

test('an open capacity window clamps the banked charge', () => {
  reset({
    charge: 1200,
    hourRules: [windowAroundNow({ action: 'capacity', minutes: 10 })],
    lastTickTs: Date.now()
  });
  B.recompute();
  assert.ok(B.getState().charge <= 600);
});

test('useSitePass grants a five minute pass', async () => {
  reset({ enabledSites: { youtube: 'block' } });
  const snap = await listeners.onMessage({ type: 'useSitePass', site: 'youtube' });
  assert.ok(Math.abs(snap.sitePasses.youtube - B.SITE_PASS_SECONDS) <= 1);
  assert.equal(B.siteUsable('youtube', Date.now()), true);
});

test('an expired pass is pruned on recompute', () => {
  reset({ sitePasses: { youtube: Date.now() - 1000 } });
  B.recompute();
  assert.deepEqual(B.getState().sitePasses, {});
});

test('applySettings sanitizes hour rules and site modes', () => {
  reset();
  B.applySettings({
    hourRules: [
      { id: 'ok', from: '22:00', to: '07:00', action: 'block' },
      { id: 'bad', from: '10:00', to: '10:00', action: 'block' }
    ],
    enabledSites: { youtube: 'block', reddit: false, x: 'nonsense' }
  });
  const s = B.getState();
  assert.deepEqual(s.hourRules.map((r) => r.id), ['ok']);
  assert.equal(s.enabledSites.youtube, 'block');
  assert.equal(s.enabledSites.reddit, false);
  assert.equal(s.enabledSites.x, true);
});

test('snapshot carries hour rules, passes as seconds, and the paused flag', () => {
  reset({
    hourRules: [windowAroundNow({ action: 'recharge', percent: 0 })],
    sitePasses: { youtube: Date.now() + 90 * 1000 }
  });
  const s = B.snapshot();
  assert.equal(s.hourRules.length, 1);
  assert.ok(s.sitePasses.youtube >= 89 && s.sitePasses.youtube <= 91);
  assert.equal(s.rechargePaused, true);
  assert.equal(s.passSeconds, B.SITE_PASS_SECONDS);
});

// --- custom script registration: serialized, never interleaved ------------------

test('syncCustomScripts calls run one at a time', async () => {
  reset({ customSites: [{ id: 'a.com', name: 'a', host: 'a.com' }] });
  const events = [];
  const tick = () => new Promise((r) => setTimeout(r, 5));
  stub.permissions.contains = async () => true;
  stub.scripting = {
    getRegisteredContentScripts: async () => { events.push('list'); await tick(); return []; },
    unregisterContentScripts: async () => { events.push('unregister'); await tick(); },
    registerContentScripts: async () => { events.push('register'); await tick(); }
  };
  try {
    await Promise.all([B.syncCustomScripts(), B.syncCustomScripts()]);
    // Unserialized, both calls would list before either registered.
    assert.deepEqual(events, ['list', 'register', 'list', 'register']);
  } finally {
    delete stub.scripting;
    stub.permissions.contains = async () => false;
  }
});

// --- port connect: first tick waits for load() ----------------------------------

test('a connecting port gets its first tick only after load has resolved', async () => {
  reset({ charge: 0, depleted: true, depletedAt: Date.now(), lastTickTs: Date.now() });
  const msgs = [];
  let disconnect;
  const port = {
    name: 'sb',
    sender: { tab: { id: 9, windowId: 1 } },
    onMessage: { addListener() {} },
    onDisconnect: { addListener(fn) { disconnect = fn; } },
    postMessage: (m) => msgs.push(m)
  };
  try {
    listeners.onConnect(port);
    // Nothing synchronous: the DEFAULTS snapshot would have shown the site unblocked.
    assert.equal(msgs.length, 0);
    await B.ready;
    await new Promise((r) => setTimeout(r, 0));
    assert.equal(msgs.length, 1);
    assert.equal(msgs[0].type, 'tick');
    assert.equal(msgs[0].depleted, true);
  } finally {
    if (disconnect) disconnect(); // releases the ticker so the process can exit
  }
});

// --- load(): corrupted storage cannot brick the battery --------------------------

test('load sanitizes corrupted capacity, rechargePerMin, and charge', async () => {
  Object.assign(storageData, {
    capacity: 0, rechargePerMin: 'junk', charge: 99999,
    depleted: false, depletedAt: null
  });
  await B.load();
  const s = B.getState();
  assert.equal(s.capacity, 60); // floor, not NaN or zero
  assert.equal(s.rechargePerMin, B.DEFAULTS.rechargePerMin); // non number falls back
  assert.equal(s.charge, 60); // clamped to the repaired capacity
});

test('load repairs a NaN capacity to the default', async () => {
  Object.assign(storageData, { capacity: NaN, rechargePerMin: 5, charge: 500 });
  await B.load();
  const s = B.getState();
  assert.equal(s.capacity, B.DEFAULTS.capacity);
  assert.equal(s.charge, 500);
});

// --- clock re-anchoring: passes ride the jump, hour rules stay on wall time ------

const HOUR_MS = 3600 * 1000;

// A rule window built around the device's wall clock, `before` minutes back to
// `after` minutes ahead.
function windowMins(before, after, extra) {
  return {
    id: 'hr1',
    from: hmOf(new Date(Date.now() - before * 60000)),
    to: hmOf(new Date(Date.now() + after * 60000)),
    scope: 'all', sites: [], ...extra
  };
}

test('a running site pass keeps its remaining seconds across a clock re-anchor', () => {
  const now = Date.now();
  reset({
    enabledSites: { youtube: 'block' },
    sitePasses: { youtube: now + 300 * 1000 },
    lastTickTs: now
  });
  // Expiries are minted in sbNow space, so a ten minute jump would otherwise
  // expire a five minute pass outright.
  B.applyServerOffset(10 * 60000);
  assert.equal(B.getState().sitePasses.youtube, now + 300 * 1000 + 10 * 60000);
  const snap = B.snapshot();
  assert.ok(snap.sitePasses.youtube >= 299 && snap.sitePasses.youtube <= 301);
  assert.equal(B.siteUsable('youtube', now + 10 * 60000), true);
  // And back the other way (unlink resets the offset): no free extra minutes.
  B.applyServerOffset(0);
  assert.equal(B.getState().sitePasses.youtube, now + 300 * 1000);
});

test('an hour rule window is read on the device wall clock, not server time', () => {
  const OFF = 2 * HOUR_MS;
  reset({ hourRules: [windowMins(30, 30, { action: 'block' })], serverOffset: OFF });
  // The window is open right now on this device's clock; two hours of server
  // skew must not close it.
  assert.equal(B.siteUsable('youtube', Date.now() + OFF), false);
  reset({ hourRules: [windowMins(30, 30, { action: 'block' })], serverOffset: 0 });
  assert.equal(B.siteUsable('youtube', Date.now()), false);
});

test('recompute charges the same over a wall clock gap at any server offset', () => {
  const rules = [windowMins(30, 30, { action: 'recharge', percent: 0 })];
  const base = { charge: 100, rechargePerMin: 60, capacity: 1800, hourRules: rules };

  reset({ ...base, serverOffset: 0, lastTickTs: Date.now() - 20 * 60000 });
  B.recompute();
  const zero = B.getState().charge;

  const OFF = 2 * HOUR_MS;
  reset({ ...base, serverOffset: OFF, lastTickTs: Date.now() + OFF - 20 * 60000 });
  B.recompute();
  const shifted = B.getState().charge;

  assert.ok(Math.abs(zero - 100) < 0.5);      // the open window stopped charging
  assert.ok(Math.abs(shifted - zero) < 0.5);  // and the offset changed nothing
});

test('no up arrow while an open capacity window pins the charge', () => {
  const rules = [windowMins(60, 60, { action: 'capacity', minutes: 10 })];
  reset({ charge: 600, capacity: 1800, hourRules: rules, lastTickTs: Date.now() });
  B.recompute();
  assert.equal(lastBadgeText, '10'); // at the window cap: nothing is climbing
  reset({ charge: 300, capacity: 1800, hourRules: rules, lastTickTs: Date.now() });
  B.recompute();
  assert.equal(lastBadgeText, '↑5'); // below it, the arrow is back
});

// --- Site access watch: a revoked host grant must never fail silently ---------

test('a tracked site with no host grant lands in missingAccess and turns the badge red', async () => {
  reset({ lastTickTs: Date.now() });
  stub.permissions.contains = async ({ origins }) => !origins.some((o) => o.includes('youtube'));
  await B.refreshAccess();
  assert.deepEqual(B.snapshot().missingAccess.map((m) => m.id), ['youtube']);
  assert.equal(lastBadgeText, '!');
  // The grant coming back clears the warning and the badge shows the gauge again.
  stub.permissions.contains = async () => true;
  await B.refreshAccess();
  assert.equal(B.snapshot().missingAccess.length, 0);
  assert.notEqual(lastBadgeText, '!');
});

test('an off site does not ask for access, a blocked one still does', async () => {
  reset({ lastTickTs: Date.now() });
  stub.permissions.contains = async ({ origins }) =>
    !origins.some((o) => o.includes('youtube') || o.includes('reddit'));
  B.setState({ enabledSites: { youtube: false, reddit: 'block' } });
  await B.refreshAccess();
  // Off: inert by choice, no grant needed. Block: the cover is a content script,
  // so the grant still matters.
  assert.deepEqual(B.snapshot().missingAccess.map((m) => m.id), ['reddit']);
  stub.permissions.contains = async () => true;
  await B.refreshAccess();
});

test('a custom site missing its grant is reported under its own name', async () => {
  reset({ lastTickTs: Date.now() });
  B.setState({ customSites: [{ id: 'vinted.nl', name: 'vinted.nl', host: 'vinted.nl' }] });
  stub.permissions.contains = async ({ origins }) => !origins.some((o) => o.includes('vinted'));
  await B.refreshAccess();
  assert.deepEqual(B.snapshot().missingAccess.map((m) => m.id), ['vinted.nl']);
  stub.permissions.contains = async () => true;
  await B.refreshAccess();
  B.setState({ customSites: [] });
});
