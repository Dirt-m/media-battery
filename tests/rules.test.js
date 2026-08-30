// Tests for rules.js. Windows are local wall time, so the rules here are built
// relative to "now" with wide margins (an hour or more each side) rather than pinned
// clock values.
// Run: node --test

const { test } = require('node:test');
const assert = require('node:assert/strict');

const R = require('../rules.js');

// "HH:MM" for a Date, the shape the options page stores.
function hm(date) {
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}
function minsAgo(m) { return new Date(Date.now() - m * 60000); }
function minsAhead(m) { return new Date(Date.now() + m * 60000); }

// A window that is open right now, wide enough that minute truncation can't
// push "now" outside it.
function openWindow(extra = {}) {
  return { id: 'r1', from: hm(minsAgo(120)), to: hm(minsAhead(120)), ...extra };
}
function closedWindow(extra = {}) {
  return { id: 'r2', from: hm(minsAhead(120)), to: hm(minsAhead(240)), ...extra };
}

// --- sanitizeRules -------------------------------------------------------------

test('sanitizeRules keeps well formed rules and drops broken ones', () => {
  const out = R.sanitizeRules([
    { id: 'a', from: '22:00', to: '07:00', action: 'block', scope: 'all' },
    { id: 'b', from: '9:00', to: '17:30', action: 'recharge', percent: 250 },
    { id: 'c', from: '10:00', to: '10:00', action: 'block' },          // equal times
    { id: 'd', from: '25:00', to: '11:00', action: 'block' },          // bad time
    { id: 'e', from: '10:00', to: '11:00', action: 'nonsense' },       // bad action
    { id: 'f', from: '10:00', to: '11:00', action: 'only', scope: 'only' },
    { id: 'g', from: '10:00', to: '11:00', action: 'off', scope: 'only', sites: [] }, // only + nothing picked
    { id: 'h', from: '10:00', to: '11:00', action: 'capacity', minutes: 99999 },
    'junk', null
  ]);
  assert.deepEqual(out.map((r) => r.id), ['a', 'b', 'h']);
  assert.equal(out[1].percent, 100);   // clamped
  assert.equal(out[2].minutes, 1440);  // clamped
  assert.equal(out[0].scope, 'all');
  assert.deepEqual(out[0].sites, []);
});

test('sanitizeRules survives non arrays and mints missing ids', () => {
  assert.deepEqual(R.sanitizeRules(null), []);
  assert.deepEqual(R.sanitizeRules('junk'), []);
  const out = R.sanitizeRules([{ from: '10:00', to: '11:00', action: 'block' }]);
  assert.equal(out.length, 1);
  assert.ok(out[0].id.length > 1);
});

// --- activeAt: plain and midnight wrapping windows ------------------------------

test('activeAt covers a plain window and stays shut outside it', () => {
  const rule = openWindow({ action: 'block' });
  assert.equal(R.activeAt(rule, new Date()), true);
  const shut = closedWindow({ action: 'block' });
  assert.equal(R.activeAt(shut, new Date()), false);
});

test('activeAt wraps midnight when from is later than to', () => {
  const d = new Date(2026, 5, 10, 23, 30);
  const night = { from: '22:00', to: '07:00', action: 'block' };
  assert.equal(R.activeAt(night, d), true);
  assert.equal(R.activeAt(night, new Date(2026, 5, 10, 3, 0)), true);
  assert.equal(R.activeAt(night, new Date(2026, 5, 10, 12, 0)), false);
  assert.equal(R.activeAt(night, new Date(2026, 5, 10, 7, 0)), false); // end is exclusive
});

// --- appliesTo / siteEffectsAt --------------------------------------------------

test('appliesTo honors all, only, and except scopes', () => {
  assert.equal(R.appliesTo({ scope: 'all' }, 'youtube'), true);
  assert.equal(R.appliesTo({ scope: 'only', sites: ['youtube'] }, 'youtube'), true);
  assert.equal(R.appliesTo({ scope: 'only', sites: ['youtube'] }, 'reddit'), false);
  assert.equal(R.appliesTo({ scope: 'except', sites: ['youtube'] }, 'youtube'), false);
  assert.equal(R.appliesTo({ scope: 'except', sites: ['youtube'] }, 'reddit'), true);
});

test('siteEffectsAt reports block and off from open windows only', () => {
  const rules = [
    openWindow({ id: 'b1', action: 'block', scope: 'only', sites: ['youtube'] }),
    openWindow({ id: 'o1', action: 'off', scope: 'only', sites: ['reddit'] }),
    closedWindow({ id: 'b2', action: 'block', scope: 'all' })
  ];
  assert.deepEqual(R.siteEffectsAt(rules, 'youtube', new Date()), { blocked: true, off: false });
  assert.deepEqual(R.siteEffectsAt(rules, 'reddit', new Date()), { blocked: false, off: true });
  assert.deepEqual(R.siteEffectsAt(rules, 'tiktok', new Date()), { blocked: false, off: false });
});

// --- charging effects -----------------------------------------------------------

test('chargeFactorAt takes the strictest open recharge window', () => {
  const rules = [
    openWindow({ id: 'a', action: 'recharge', percent: 50 }),
    openWindow({ id: 'b', action: 'recharge', percent: 0 }),
    closedWindow({ id: 'c', action: 'recharge', percent: 10 })
  ];
  assert.equal(R.chargeFactorAt(rules, new Date()), 0);
  assert.equal(R.chargeFactorAt([rules[0]], new Date()), 0.5);
  assert.equal(R.chargeFactorAt([rules[2]], new Date()), 1);
});

test('capacityAt lowers to the strictest open capacity window', () => {
  const rules = [
    openWindow({ id: 'a', action: 'capacity', minutes: 10 }),
    closedWindow({ id: 'b', action: 'capacity', minutes: 2 })
  ];
  assert.equal(R.capacityAt(rules, new Date(), 1800), 600);
  assert.equal(R.capacityAt([], new Date(), 1800), 1800);
});

// --- projectRecharge: the piecewise walk ---------------------------------------

const ENV = { capacity: 100000, rechargePerMin: 60 }; // 1 charge second per second

test('projectRecharge with no rules equals the flat formula', () => {
  const now = Date.now();
  const c = R.projectRecharge([], 100, now - 600000, now, { capacity: 1800, rechargePerMin: 6 });
  assert.ok(Math.abs(c - 160) < 0.001); // 10 min at 6/min
});

test('projectRecharge skips the part of the gap a stopped charging window covers', () => {
  // Gap: the last 2 hours. Charging stopped from 1 hour ago until 1 hour ahead:
  // only the first hour of the gap recharges.
  const now = Date.now();
  const rules = R.sanitizeRules([
    { from: hm(minsAgo(60)), to: hm(minsAhead(60)), action: 'recharge', percent: 0 }
  ]);
  const c = R.projectRecharge(rules, 0, now - 2 * 3600 * 1000, now, ENV);
  assert.ok(Math.abs(c - 3600) < 70, `expected ~3600, got ${c}`); // minute truncation slack
});

test('projectRecharge halves the rate inside a percent window', () => {
  const now = Date.now();
  const rules = R.sanitizeRules([
    { from: hm(minsAgo(120)), to: hm(minsAhead(120)), action: 'recharge', percent: 50 }
  ]);
  const c = R.projectRecharge(rules, 0, now - 3600 * 1000, now, ENV);
  assert.ok(Math.abs(c - 1800) < 70, `expected ~1800, got ${c}`);
});

test('a capacity window clamps banked charge even when slept through', () => {
  // 3000 banked; a 10 minute (600s) capacity window covered the first half of the
  // gap. Entering it clamps to 600; after it ends the charge climbs again.
  const now = Date.now();
  const rules = R.sanitizeRules([
    { from: hm(minsAgo(20)), to: hm(minsAgo(10)), action: 'capacity', minutes: 10 }
  ]);
  const env = { capacity: 100000, rechargePerMin: 6 }; // 0.1/s
  const c = R.projectRecharge(rules, 3000, now - 20 * 60000, now, env);
  // Clamped to 600 at the window, then ~10 min at 6/min = ~60 back.
  assert.ok(Math.abs(c - 660) < 20, `expected ~660, got ${c}`);
});

test('projectRecharge never exceeds the base capacity', () => {
  const now = Date.now();
  const c = R.projectRecharge([], 1700, now - 3600 * 1000, now, { capacity: 1800, rechargePerMin: 60 });
  assert.equal(c, 1800);
});

test('an overnight charging window wraps midnight in the walk', () => {
  // Deterministic clock: recharge stopped 22:00 to 07:00. From 21:00 to 08:00
  // (11 h) only 21:00 to 22:00 and 07:00 to 08:00 recharge: 2 h worth.
  const from = new Date(2026, 5, 9, 21, 0).getTime();
  const to = new Date(2026, 5, 10, 8, 0).getTime();
  const rules = R.sanitizeRules([{ from: '22:00', to: '07:00', action: 'recharge', percent: 0 }]);
  const c = R.projectRecharge(rules, 0, from, to, ENV);
  assert.ok(Math.abs(c - 7200) < 1, `expected 7200, got ${c}`);
});
