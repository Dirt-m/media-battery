// Pure hour rule math. A rule holds between two times of day (local clock, wrapping
// midnight) and does one of four things: block sites, stop counting battery use on
// sites, slow or stop charging, lower the capacity. Rules plus a Date in, effects
// out. No browser APIs, so this also runs in content scripts and under node.
//
// projectRecharge() walks recharge over a span piecewise across every charging rule
// edge. recompute() and the sync projection both use it, so a gap the browser slept
// through recharges exactly what the windows allowed.

(function () {
  'use strict';

  const ACTIONS = ['block', 'off', 'recharge', 'capacity'];
  const SCOPES = ['all', 'only', 'except'];

  // "HH:MM" -> minutes since local midnight, or null.
  function parseHM(s) {
    const m = /^(\d{1,2}):(\d{2})$/.exec(String(s || ''));
    if (!m) return null;
    const h = Number(m[1]), min = Number(m[2]);
    if (h > 23 || min > 59) return null;
    return h * 60 + min;
  }

  // Every input path (storage, saves, sync) goes through here. A rule that cannot
  // mean anything (equal times, unknown action, "only" with no sites) is dropped
  // rather than half repaired.
  function sanitizeRules(raw) {
    if (!Array.isArray(raw)) return [];
    const out = [];
    for (const r of raw) {
      if (!r || typeof r !== 'object') continue;
      const from = parseHM(r.from), to = parseHM(r.to);
      if (from == null || to == null || from === to) continue;
      if (ACTIONS.indexOf(r.action) < 0) continue;
      const rule = {
        id: (typeof r.id === 'string' && r.id) ? r.id.slice(0, 32)
          : 'r' + Math.random().toString(36).slice(2, 10),
        from: r.from, to: r.to, action: r.action
      };
      if (r.action === 'recharge') {
        const p = Number(r.percent);
        rule.percent = isFinite(p) ? Math.min(100, Math.max(0, Math.round(p))) : 0;
      } else if (r.action === 'capacity') {
        const mins = Number(r.minutes);
        if (!isFinite(mins)) continue;
        rule.minutes = Math.min(1440, Math.max(1, Math.round(mins)));
      } else {
        rule.scope = SCOPES.indexOf(r.scope) >= 0 ? r.scope : 'all';
        rule.sites = Array.isArray(r.sites)
          ? r.sites.filter((s) => typeof s === 'string' && s).slice(0, 64) : [];
        if (rule.scope === 'only' && !rule.sites.length) continue;
      }
      out.push(rule);
      if (out.length >= 32) break;
    }
    return out;
  }

  // Is the window open at this local time? from > to wraps midnight (22:00 to 07:00
  // is the night). End minute is exclusive, so back to back windows don't overlap.
  function activeAt(rule, date) {
    const m = date.getHours() * 60 + date.getMinutes();
    const from = parseHM(rule.from), to = parseHM(rule.to);
    if (from == null || to == null) return false;
    return from < to ? (m >= from && m < to) : (m >= from || m < to);
  }

  function appliesTo(rule, siteId) {
    const scope = rule.scope || 'all';
    if (scope === 'all') return true;
    const picked = (rule.sites || []).indexOf(siteId) >= 0;
    return scope === 'only' ? picked : !picked;
  }

  // What the open windows say about one site. off means it neither drains nor gets
  // covered, same as switching the site off in settings.
  function siteEffectsAt(rules, siteId, date) {
    let blocked = false, off = false;
    for (const r of rules || []) {
      if ((r.action !== 'block' && r.action !== 'off') || !activeAt(r, date)) continue;
      if (!appliesTo(r, siteId)) continue;
      if (r.action === 'block') blocked = true;
      else off = true;
    }
    return { blocked, off };
  }

  // Charge speed multiplier: 1 with no open recharge window, else the strictest open
  // one. 0 stops charging.
  function chargeFactorAt(rules, date) {
    let f = 1;
    for (const r of rules || []) {
      if (r.action === 'recharge' && activeAt(r, date)) f = Math.min(f, (r.percent || 0) / 100);
    }
    return f;
  }

  // Base capacity, lowered by any open capacity window. Strictest wins.
  function capacityAt(rules, date, capacity) {
    let cap = capacity;
    for (const r of rules || []) {
      if (r.action === 'capacity' && activeAt(r, date)) cap = Math.min(cap, r.minutes * 60);
    }
    return cap;
  }

  // Next moment strictly after t where a charging rule starts or ends. Block and off
  // rules don't touch the charge, so their edges don't segment the walk. Goes through
  // the Date constructor so DST days keep their local wall times.
  function nextChargeBoundary(rules, t) {
    let best = Infinity;
    const d = new Date(t);
    for (const r of rules || []) {
      if (r.action !== 'recharge' && r.action !== 'capacity') continue;
      for (const mark of [parseHM(r.from), parseHM(r.to)]) {
        if (mark == null) continue;
        const h = Math.floor(mark / 60), min = mark % 60;
        let next = new Date(d.getFullYear(), d.getMonth(), d.getDate(), h, min).getTime();
        if (next <= t) next = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1, h, min).getTime();
        if (next > t && next < best) best = next;
      }
    }
    return best;
  }

  // Recharge from fromTs to toTs, piecewise per charging window. Each segment charges
  // at the window's factor and clamps to its capacity; entering a lower capacity
  // window clamps the banked charge too, so sleeping through a window costs the same
  // as living through it. With no charging rules this reduces to the flat formula.
  function projectRecharge(rules, charge, fromTs, toTs, env) {
    let c = Math.max(0, Number(charge) || 0);
    let t = fromTs;
    const perSec = (env.rechargePerMin || 0) / 60;
    let guard = 40000; // decades of daily edges; a runaway walk falls back to flat
    while (t < toTs && guard-- > 0) {
      const d = new Date(t);
      const cap = capacityAt(rules, d, env.capacity);
      const factor = chargeFactorAt(rules, d);
      const next = Math.min(toTs, nextChargeBoundary(rules, t));
      c = Math.min(c, cap);
      c = Math.min(cap, c + ((next - t) / 1000) * perSec * factor);
      t = next;
    }
    if (t < toTs) c = c + ((toTs - t) / 1000) * perSec;
    return Math.min(c, env.capacity);
  }

  const api = {
    sanitizeRules, activeAt, appliesTo, siteEffectsAt,
    chargeFactorAt, capacityAt, projectRecharge, parseHM
  };
  globalThis.SBRules = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})();
