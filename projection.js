// Pure anchor math: what a sync anchor implies the charge is right now.
// (anchor, now, env) in, {charge, depleted, depletedAt} out. No browser APIs, so the
// test suite require()s it in node. Loaded before sync.js in the background.

(function () {
  'use strict';

  // How far past its asOf a draining anchor keeps draining. A live writer pushes every
  // ~10s and heartbeats every minute even under timer throttling, so silence past this
  // means it is gone (crash, suspend, offline) and the projection recharges from there.
  // Caps a vanished writer's phantom drain at 90s instead of the whole gap.
  const DRAIN_HORIZON_MS = 90 * 1000;

  // Project an anchor {charge, asOf, draining, depleted, depletedAt} to `now` under
  // env {capacity, rechargePerMin, cooldownSeconds}. Same rules as recompute(): drain
  // 1s/sec, recharge runs through the cooldown, depletion lifts once the cooldown has
  // passed and there is charge banked. depletedAt is the moment the drain crossed zero,
  // not the moment we looked, so two devices agree on when the cooldown ends.
  function projectAnchor(a, now, env) {
    let charge = Math.max(0, Number(a.charge) || 0);
    let depleted = !!a.depleted;
    let depletedAt = a.depletedAt != null ? a.depletedAt : null;
    let t = Math.min(a.asOf, now); // an anchor from the future projects as-is

    if (a.draining && !depleted) {
      const drainEnd = Math.min(now, a.asOf + DRAIN_HORIZON_MS);
      const drained = Math.max(0, (drainEnd - t) / 1000);
      if (drained < charge) {
        charge -= drained;
        t = drainEnd;
      } else {
        t = t + charge * 1000;
        charge = 0;
        depleted = true;
        depletedAt = t;
      }
    }

    // Everything after the drain window recharges, dead or alive. With hour rules the
    // recharge is the piecewise walk from rules.js, shifted by env.serverOffset so the
    // windows land on the device's wall clock, the same clock its local ticks use. A
    // skewed clock diverges only at window edges, and lower wins absorbs that.
    if (t < now && env.hourRules && env.hourRules.length && globalThis.SBRules) {
      const off = env.serverOffset || 0;
      charge = SBRules.projectRecharge(env.hourRules, charge, t - off, now - off, env);
    } else {
      charge = Math.min(env.capacity,
        charge + Math.max(0, (now - t) / 1000) * (env.rechargePerMin / 60));
    }

    if (depleted) {
      const began = depletedAt != null ? depletedAt : t;
      if (now - began >= env.cooldownSeconds * 1000 && charge > 0) {
        depleted = false;
        depletedAt = null;
      }
    }

    return { charge, depleted, depletedAt };
  }

  const api = { projectAnchor, DRAIN_HORIZON_MS };
  globalThis.SBProjection = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})();
