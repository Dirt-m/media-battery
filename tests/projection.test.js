// Tests for projection.js. Two of these came from real bugs: a stale draining anchor
// must stop draining at the horizon, and a projected depletion keeps the moment it
// actually crossed zero.
// Run: node --test

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { projectAnchor, DRAIN_HORIZON_MS } = require('../projection.js');

const ENV = { capacity: 1800, rechargePerMin: 5, cooldownSeconds: 600 };
const RATE = ENV.rechargePerMin / 60; // charge per second while recharging

test('a live draining anchor drains 1s/sec inside the horizon', () => {
  const p = projectAnchor({ charge: 900, asOf: 0, draining: true }, 30 * 1000, ENV);
  assert.equal(p.charge, 870);
  assert.equal(p.depleted, false);
});

test('a stale draining anchor stops draining at the horizon and recharges after', () => {
  const threeHours = 3 * 3600 * 1000;
  const p = projectAnchor({ charge: 900, asOf: 0, draining: true }, threeHours, ENV);
  const afterHorizon = 900 - DRAIN_HORIZON_MS / 1000;
  const expected = afterHorizon + ((threeHours - DRAIN_HORIZON_MS) / 1000) * RATE;
  assert.ok(Math.abs(p.charge - Math.min(ENV.capacity, expected)) < 1e-6);
  assert.equal(p.depleted, false);
  // The old unbounded projection returned 0 here, which bled every synced device dry
  // after a writer vanished mid drain.
  assert.ok(p.charge > afterHorizon);
});

test('re-reading the same stale anchor later never lowers the projection', () => {
  const a = { charge: 600, asOf: 0, draining: true };
  let prev = -Infinity;
  for (const mins of [2, 5, 10, 30, 120]) {
    const p = projectAnchor(a, mins * 60 * 1000, ENV);
    assert.ok(p.charge >= prev);
    prev = p.charge;
  }
});

test('a drain crossing zero depletes at the moment it crossed, not the moment read', () => {
  const p = projectAnchor({ charge: 30, asOf: 0, draining: true }, 60 * 1000, ENV);
  assert.equal(p.depleted, true);
  assert.equal(p.depletedAt, 30 * 1000);
  // Recharge already ran from the crossing to now.
  assert.ok(Math.abs(p.charge - 30 * RATE) < 1e-6);
});

test('two devices reading a depleting anchor at different times agree on depletedAt', () => {
  const a = { charge: 30, asOf: 0, draining: true };
  const p1 = projectAnchor(a, 45 * 1000, ENV);
  const p2 = projectAnchor(a, 80 * 1000, ENV);
  assert.equal(p1.depletedAt, p2.depletedAt);
});

test('a depleted anchor stays depleted inside the cooldown and recharges through it', () => {
  const p = projectAnchor(
    { charge: 0, asOf: 0, draining: false, depleted: true, depletedAt: 0 },
    5 * 60 * 1000, ENV);
  assert.equal(p.depleted, true);
  assert.ok(Math.abs(p.charge - 5 * 60 * RATE) < 1e-6);
});

test('a depleted anchor revives once the cooldown has passed with charge banked', () => {
  const p = projectAnchor(
    { charge: 0, asOf: 0, draining: false, depleted: true, depletedAt: 0 },
    11 * 60 * 1000, ENV);
  assert.equal(p.depleted, false);
  assert.equal(p.depletedAt, null);
});

test('with zero recharge a depleted anchor never flaps back alive', () => {
  const env = { ...ENV, rechargePerMin: 0 };
  const p = projectAnchor(
    { charge: 0, asOf: 0, draining: false, depleted: true, depletedAt: 0 },
    24 * 3600 * 1000, env);
  assert.equal(p.depleted, true);
  assert.equal(p.charge, 0);
});

test('recharge caps at capacity', () => {
  const p = projectAnchor({ charge: 1790, asOf: 0, draining: false }, 3600 * 1000, ENV);
  assert.equal(p.charge, ENV.capacity);
});

test('an anchor from the future projects as-is', () => {
  const p = projectAnchor({ charge: 500, asOf: 60 * 1000, draining: true }, 0, ENV);
  assert.equal(p.charge, 500);
  assert.equal(p.depleted, false);
});

test('a draining flag on an already depleted anchor never drains', () => {
  const p = projectAnchor(
    { charge: 10, asOf: 0, draining: true, depleted: true, depletedAt: 0 },
    30 * 1000, ENV);
  assert.ok(p.charge > 10); // recharged, not drained
});

test('hour rules in the env route recharge through the piecewise walk', () => {
  // Real clock times here: hour windows are local wall time. Charging stopped in
  // a window around now, so an idle anchor from an hour ago must not have climbed.
  require('../rules.js');
  const hmOf = (d) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  const now = Date.now();
  const env = {
    ...ENV,
    hourRules: [{
      id: 'hr', action: 'recharge', percent: 0,
      from: hmOf(new Date(now - 120 * 60000)), to: hmOf(new Date(now + 120 * 60000))
    }]
  };
  const p = projectAnchor({ charge: 300, asOf: now - 3600 * 1000, draining: false }, now, env);
  assert.ok(Math.abs(p.charge - 300) < 1);
  // The same anchor without rules recharges the whole hour.
  const flat = projectAnchor({ charge: 300, asOf: now - 3600 * 1000, draining: false }, now, ENV);
  assert.ok(flat.charge > 500);
});

test('serverOffset in the env puts the recharge window back on the wall clock', () => {
  // Anchor timestamps are server time; hour windows are the device's local wall
  // time. With the offset in the env the walk converts back, so a device whose
  // clock sits two hours off the server still reads its own windows.
  require('../rules.js');
  const hm = (d) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  const OFF = 2 * 3600 * 1000;
  const wallNow = Date.now();
  const env = {
    ...ENV,
    rechargePerMin: 60,
    serverOffset: OFF,
    hourRules: [{
      id: 'hr', action: 'recharge', percent: 0,
      from: hm(new Date(wallNow - 30 * 60000)), to: hm(new Date(wallNow + 30 * 60000))
    }]
  };
  const serverNow = wallNow + OFF;
  const anchor = { charge: 300, asOf: serverNow - 20 * 60000, draining: false };

  const p = projectAnchor(anchor, serverNow, env);
  assert.ok(Math.abs(p.charge - 300) < 1); // charging stopped: nothing climbed

  // Read at raw server time the same window sits two hours in the past, so the gap
  // charges instead.
  const p2 = projectAnchor(anchor, serverNow, { ...env, serverOffset: 0 });
  assert.ok(p2.charge > 315);
});
