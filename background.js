// The authoritative timekeeper and single source of truth.
//
// The battery is one banked charge in seconds. It drains 1s/sec while any tab reports
// engaged use of a tracked, enabled platform, and recharges at `rechargePerMin` (capped)
// whenever nothing is engaged. Time always comes from a timestamp delta, never from
// interval cadence, so a suspended event page can't corrupt it.
//
// At zero the battery goes dead: a fixed real time cooldown with the sites blocked.
// Recharge keeps running through it, so the cooldown ends with charge banked and the
// battery can't re-die the instant you touch a site. Reserve is the way out early: a
// fixed chunk of charge back, and the battery revives on the spot.

// The real browser (or chrome) global in the extension. Under node the tests set
// globalThis.__sbBrowserStub before requiring this file, so the wiring below runs
// against the stub.
const browser = globalThis.browser || globalThis.chrome || globalThis.__sbBrowserStub;

// What spending reserve adds back. Not a setting.
const RESERVE_SECONDS = 300; // 5 min

// Not a setting: synced devices have to agree on when a cooldown ends.
const COOLDOWN_SECONDS = 600; // 10 min

// How long a friction gated pass through a blocked site (settings Block or an hour
// rule) lasts.
const SITE_PASS_SECONDS = 300; // 5 min

const DEFAULTS = {
  charge: 1800,           // start with a full battery (30 min) on first install
  rechargePerMin: 5,      // seconds recharged per real minute while away (2 h/day)
  capacity: 1800,         // maximum battery (30 min)
  warnSeconds: 300,       // low charge warning threshold (5 min); 0 turns the warning off
  enabledSites: {},       // site id -> false (off) or 'block'; missing means tracked. Also
                          // carries ids other devices own (a phone's app:<package> modes)
  customSites: [],        // user-added sites: [{ id, name, host }]
  hourRules: [],          // between-hours rules (see rules.js for the shape)
  sitePasses: {},         // site id -> expiry ts of a five minute pass through a block
  hideYtSidebar: false,   // always strip the YouTube recommendations sidebar
  showTimeLeft: true,     // brief corner toast with the time left when a tracked page opens
  depleted: false,
  depletedAt: null,       // timestamp the current depletion began; drives the cooldown
  depletionSeq: 0,        // counts dead periods; content scripts key the per video pass
                          // on it, since depletedAt shifts whenever sync re-anchors
                          // the clock
  lastTickTs: Date.now(),
  // Sync mirror: this device is following another that is actively draining, so it
  // drains in step instead of recharging. Set from an adopted draining anchor.
  remoteDraining: false,
  remoteDrainingTs: 0,    // asOf of the adopted draining anchor: the lease runs from the
                          // write time, so re-reading a stale anchor can't renew it
  // Cross-device sync (see sync.js). Off until syncCode is set; it all derives from that
  // one secret. snapshot() omits these, so no content script sees them.
  deviceId: null,         // random per-install id stamped into pushed anchors, so a
                          // device never follows its own echo
  syncCode: null,         // the one secret: identity, auth, and encryption derive from it
  serverUrl: null,        // custom sync server, or null for the default
  serverOffset: 0,        // ms added to Date.now() to reach server time (clock anchoring)
  settingsMeta: {}        // per synced-setting last-write timestamp, for the merge
};

// Always a live object, never null, so synchronous listeners are safe. Stored values
// load shortly after startup and overlay the defaults.
let state = { ...DEFAULTS };
// Sync status the options page reads via the 'syncStatus' message.
let syncStatusCache = { on: false, state: 'off', lastSyncTs: 0 };
const ready = load();

// tabId -> { ts, playing, inputAgo, site, windowId }: the last on-screen beat from each
// tab. A tab only beats while it is being painted (see content.js), so no recent beat
// means that tab is off screen.
const onscreenTabs = new Map();
const ONSCREEN_LEASE_MS = 2500; // beats arrive ~1/s; older than this means off screen
// Windows currently minimized. Belt and braces over the rAF beat: profiles with occlusion
// tracking off (remote desktop, some automation setups) keep painting a minimized window,
// so beats from one never count. Refreshed on focus changes and periodically from the
// ticker; window ids come from each port's sender tab.
let minimizedWindows = new Set();
const ports = new Set();       // connected content-script ports
let ticker = null;
let ticksSincePersist = 0;
let ticksSinceRecheck = 0;

async function load() {
  const stored = await browser.storage.local.get(null);
  state = { ...DEFAULTS, ...stored };
  // Sanitized like every other ingress: an out of shape stored value would otherwise
  // survive until the first save, and its collapse there would restamp the whole map.
  state.enabledSites = sanitizeSiteModes(stored.enabledSites || {});
  state.customSites = Array.isArray(stored.customSites) ? stored.customSites : [];
  state.hourRules = SBRules.sanitizeRules(stored.hourRules);
  state.sitePasses = (stored.sitePasses && typeof stored.sitePasses === 'object')
    ? { ...stored.sitePasses } : {};
  state.settingsMeta = (stored.settingsMeta && typeof stored.settingsMeta === 'object') ? stored.settingsMeta : {};
  if (!state.lastTickTs) state.lastTickTs = sbNow();
  // Dead but with no start time (older build, or a hand edit): anchor the cooldown to
  // now so it can lift.
  if (state.depleted && !state.depletedAt) state.depletedAt = state.lastTickTs;
  // Corrupted storage must not brick the battery: capacity 0 or NaN makes the charge
  // clamp meaningless, and a recharge rate of 0 leaves a depleted battery dead forever.
  // Non numbers fall back to the defaults, then clamp.
  if (!Number.isFinite(Number(state.capacity))) state.capacity = DEFAULTS.capacity;
  state.capacity = clamp(state.capacity, 60, 24 * 3600);
  if (!Number.isFinite(Number(state.rechargePerMin))) state.rechargePerMin = DEFAULTS.rechargePerMin;
  state.rechargePerMin = clamp(state.rechargePerMin, 1, 600);
  if (!Number.isFinite(Number(state.warnSeconds))) state.warnSeconds = DEFAULTS.warnSeconds;
  state.warnSeconds = clamp(state.warnSeconds, 0, 24 * 3600); // 0 is a valid off switch
  state.showTimeLeft = state.showTimeLeft !== false; // missing means the default, on
  state.charge = clamp(state.charge, 0, state.capacity);
  if (!state.deviceId) {
    state.deviceId = Array.from(crypto.getRandomValues(new Uint8Array(8)))
      .map((b) => b.toString(16).padStart(2, '0')).join('');
    persist();
  }
  updateBadge();
  Sync.init(syncAdapter);
}

function clamp(n, lo, hi) {
  n = Number(n);
  if (!isFinite(n)) n = lo;
  return Math.min(hi, Math.max(lo, n));
}

async function persist() {
  await browser.storage.local.set(state);
}

// A tab counts as engaged if it is on screen (a fresh beat) AND either an audible video
// is playing in it or it saw input within the last minute. On screen alone would drain
// while you are away; input alone would drain while minimized. Both signals ride in the
// beats, observed by the tab itself. browser.idle used to fill the input role and proved
// unreliable in the field.
const ENGAGE_INPUT_MS = 60 * 1000;

// Is a five minute pass through a block currently running for this site?
function passActive(siteId, now) {
  return !!(state.sitePasses && state.sitePasses[siteId] > now);
}

// Does time on this site count right now? Off (settings or an open hour rule) never
// counts. A blocked site only counts while a pass lets it through, since the overlay
// covers it otherwise and time behind a cover must not drain.
function siteUsable(siteId, now) {
  if (state.enabledSites[siteId] === false) return false;
  const eff = SBRules.siteEffectsAt(state.hourRules, siteId, new Date(wallTime(now)));
  if (eff.off) return false;
  const blocked = state.enabledSites[siteId] === 'block' || eff.blocked;
  return !blocked || passActive(siteId, now);
}

function engagedTab() {
  const now = sbNow();
  for (const info of onscreenTabs.values()) {
    if (!siteUsable(info.site, now)) continue; // off or blocked: never counts
    if (info.windowId != null && minimizedWindows.has(info.windowId)) continue;
    if (now - info.ts >= ONSCREEN_LEASE_MS) continue;
    // inputAgo was measured when the beat was sent; age it to now.
    const inputAgo = (typeof info.inputAgo === 'number' ? info.inputAgo : Infinity)
      + (now - info.ts);
    if (info.playing || inputAgo < ENGAGE_INPUT_MS) return info;
  }
  return null;
}
function isEngaged() {
  return engagedTab() !== null;
}

// True while the battery is dead and still inside its cooldown.
function isCooling(now) {
  return state.depleted && state.depletedAt != null
    && (now - state.depletedAt) < COOLDOWN_SECONDS * 1000;
}

// Server anchored now. All timekeeping runs on this, so a synced anchor's timestamps
// (server time) project correctly whatever this device's clock says. With sync off
// serverOffset is 0 and this is Date.now().
function sbNow() {
  return Date.now() + (state.serverOffset || 0);
}

// Hour rules run on local wall time, the clock the user reads, so rule evaluation
// converts back from server time. wallTime(sbNow()) is always Date.now(), and both ends
// of a gap shift together, so durations never change.
function wallTime(ts) { return ts - (state.serverOffset || 0); }

// Changing the clock anchor jumps sbNow() by the delta while every stored timestamp was
// minted under the old anchor. Settle the current period first, then shift them across
// the jump; otherwise the delta reads as elapsed time in recompute(), and a device ten
// minutes behind the server would insta-drain ten minutes of charge on its first sync
// response while engaged.
function applyServerOffset(ms) {
  ms = Number(ms) || 0;
  const delta = ms - (state.serverOffset || 0);
  if (!delta) return;
  recompute(); // close the current period under the old anchor
  state.serverOffset = ms;
  state.lastTickTs += delta;
  if (state.depletedAt != null) state.depletedAt += delta;
  if (state.remoteDrainingTs) state.remoteDrainingTs += delta;
  // Pass expiries are sbNow-space stamps too; left alone a re-anchor would stretch or
  // shrink a running pass by the jump.
  for (const id of Object.keys(state.sitePasses || {})) state.sitePasses[id] += delta;
  // The beat timestamps came from the old anchor too; left alone they read as expired
  // leases and drop engagement for a beat on the first sync.
  for (const info of onscreenTabs.values()) info.ts += delta;
}

// Draining is always 1s/sec, so a follower can mirror another device's drain exactly.
// The lease runs from the anchor's asOf (its write time), the same horizon the projection
// uses: a writer that vanishes mid drain stops mattering this long after its last write,
// however often its stale anchor gets re-read.
const DRAIN_LEASE_MS = SBProjection.DRAIN_HORIZON_MS;
function remoteDrainActive() {
  return state.remoteDraining && (sbNow() - state.remoteDrainingTs) < DRAIN_LEASE_MS;
}
// The battery is draining if this device is engaged, or if it is following another
// device that is (and we are not dead).
function isDraining() {
  return !state.depleted && (isEngaged() || remoteDrainActive());
}

// Close out the period since the last tick using the current engaged state, then
// advance the clock. Call this immediately before changing engaged state.
function recompute() {
  const now = sbNow();
  const elapsed = Math.max(0, (now - state.lastTickTs) / 1000);
  state.lastTickTs = now;
  // Expired site passes just fall away; the tab's next tick re-covers it.
  for (const id of Object.keys(state.sitePasses || {})) {
    if (!(state.sitePasses[id] > now)) delete state.sitePasses[id];
  }
  // The active device owns its own state; it never follows a remote drainer.
  if (isEngaged()) state.remoteDraining = false;

  if (isDraining()) {
    // Drain while engaged (or mirroring a remote drainer) and not dead. A dead page is
    // covered, so it can never count as engaged; with the cooldown lifting on a timer,
    // that is what guarantees the battery always climbs back out.
    state.charge = Math.max(0, state.charge - elapsed);
  } else {
    // Recharge runs through the cooldown too (isDraining is false while dead). The
    // piecewise walk honors the hour rules across the whole gap, so a night the browser
    // slept through recharges exactly what the windows allowed.
    state.charge = SBRules.projectRecharge(state.hourRules, state.charge,
      wallTime(now - elapsed * 1000), wallTime(now),
      { capacity: state.capacity, rechargePerMin: state.rechargePerMin });
  }
  // An open capacity window caps the banked charge, whichever way the tick went.
  state.charge = Math.min(state.charge,
    SBRules.capacityAt(state.hourRules, new Date(wallTime(now)), state.capacity));

  if (!state.depleted) {
    if (state.charge <= 0) {
      state.depleted = true;
      state.depletedAt = now;
      state.depletionSeq = (state.depletionSeq || 0) + 1;
      state.charge = 0;
      // Persist at the crossing: a crash before the periodic persist would mint this
      // seq twice, and a tab holding it as its epoch would carry a per video pass into
      // the next dead period.
      persist();
    }
  } else if (!isCooling(now) && state.charge > 0) {
    // Cooldown over, and its recharge left something to spend, so unblock. (charge > 0
    // keeps a zero recharge rate from flapping dead/alive.)
    state.depleted = false;
    state.depletedAt = null;
  }

  updateBadge();
}

function snapshot() {
  const now = sbNow();
  const nowD = new Date(wallTime(now));
  const cooldownRemaining = isCooling(now)
    ? Math.max(0, COOLDOWN_SECONDS - (now - state.depletedAt) / 1000)
    : 0;
  const tab = engagedTab();
  // Passes travel as seconds remaining, not expiry stamps: content scripts keep their
  // own clocks and only need "how much longer".
  const passes = {};
  for (const id of Object.keys(state.sitePasses || {})) {
    const left = (state.sitePasses[id] - now) / 1000;
    if (left > 0) passes[id] = Math.ceil(left);
  }
  return {
    charge: state.charge,
    capacity: state.capacity,
    // The base capacity lowered by any open capacity window. The popup's gauge runs on
    // this, so the bar and bolt don't promise a climb the window won't allow.
    effCapacity: SBRules.capacityAt(state.hourRules, nowD, state.capacity),
    rechargePerMin: state.rechargePerMin,
    warnSeconds: state.warnSeconds,
    reserveSeconds: RESERVE_SECONDS,
    passSeconds: SITE_PASS_SECONDS,
    enabledSites: state.enabledSites,
    customSites: state.customSites,
    hourRules: state.hourRules,
    sitePasses: passes,
    // An open recharge 0 window: the dead overlay says so instead of "recharging".
    rechargePaused: SBRules.chargeFactorAt(state.hourRules, nowD) === 0,
    hideYtSidebar: state.hideYtSidebar,
    showTimeLeft: state.showTimeLeft,
    cooldownRemaining,          // seconds until the block lifts (0 when alive)
    depleted: state.depleted,   // sites are blocked
    depletedAt: state.depletedAt, // epoch of the current dead period (null when alive)
    depletionSeq: state.depletionSeq || 0, // bumps per dead period, stable within one
    draining: isDraining(),     // draining here or mirroring another device's drain
    // Site id of the engaged tab; null while the drain is a mirrored remote one.
    drainingSite: tab ? (tab.site || null) : null,
    // Sites Firefox holds no host grant for (see refreshAccess). The popup renders
    // these and offers the re grant.
    missingAccess
  };
}

function broadcast() {
  const msg = { type: 'tick', ...snapshot() };
  for (const p of ports) {
    try { p.postMessage(msg); } catch (_) { /* port gone */ }
  }
}

// Recompute, push the new state to tabs, and keep the sync writer role in step with
// engagement. Called whenever engagement could have changed: a beat, a lease expiring on
// a tick, a tab closing. Sync.onEngagementChange dedupes internally.
function settle() {
  recompute();
  broadcast();
  Sync.onEngagementChange(isEngaged());
}

function startTicker() {
  if (ticker) return;
  ticker = setInterval(() => {
    settle(); // a tick also catches an on-screen lease expiring
    if (++ticksSincePersist >= 5) { ticksSincePersist = 0; persist(); }
    // Window minimize has no event; re-poll the window states now and then.
    if (++ticksSinceRecheck >= 15) { ticksSinceRecheck = 0; refreshWindows(); }
  }, 1000);
}

function stopTicker() {
  if (ticker) { clearInterval(ticker); ticker = null; }
  persist();
}

function updateBadge() {
  // Android has browser.action but no toolbar badge; the setters are missing there.
  if (!browser.action || !browser.action.setBadgeText) return;
  // A site with no host grant outranks the gauge: nothing drains or covers there, and a
  // number would say everything is fine when it isn't.
  if (missingAccess.length) {
    browser.action.setBadgeText({ text: '!' });
    browser.action.setBadgeBackgroundColor({ color: '#ef5350' });
    if (browser.action.setBadgeTextColor) browser.action.setBadgeTextColor({ color: '#0f1115' });
    return;
  }
  const mins = state.charge / 60;
  // Down while draining, up while recharging (including through a dead cooldown), bare
  // when full or when an hour rule has charging stopped.
  const nowD = new Date(wallTime(sbNow()));
  const paused = SBRules.chargeFactorAt(state.hourRules, nowD) === 0;
  const cap = SBRules.capacityAt(state.hourRules, nowD, state.capacity);
  const arrow = isDraining() ? '↓' : (state.charge < cap && !paused) ? '↑' : '';
  const text = arrow + (mins >= 1 ? String(Math.floor(mins))
    : state.charge > 0 ? '<1' : '0');
  // Same colors as the popup: green while comfortable, red when blocked or low and
  // draining, amber when low but climbing back. Low is measured against the effective
  // cap, so the arrow and the color agree under an open capacity window.
  const low = cap > 0 && state.charge / cap <= 0.2;
  let color;
  if (state.depleted) color = '#ef5350';
  else if (!low) color = '#36c06f';
  else color = isDraining() ? '#ef5350' : '#f0a93b';
  browser.action.setBadgeText({ text });
  browser.action.setBadgeBackgroundColor({ color });
  // Dark ink: white sat under 3.5:1 on all three badge colors.
  if (browser.action.setBadgeTextColor) browser.action.setBadgeTextColor({ color: '#0f1115' });
}

// --- Content-script ports: per-tab engagement reporting + live battery updates ---

browser.runtime.onConnect.addListener((port) => {
  if (port.name !== 'sb') return;
  const tabId = port.sender && port.sender.tab && port.sender.tab.id;
  const windowId = port.sender && port.sender.tab && port.sender.tab.windowId;
  ports.add(port);
  startTicker();
  // A tab is open: catch up and start watching. Gated on load(), since a port can
  // connect before init has run and a throw here would abort the listener wiring below,
  // leaving the tab permanently untracked.
  ready.then(() => { if (ports.size) Sync.onActive(); });

  port.onMessage.addListener((m) => {
    if (tabId == null) return;
    if (m.type === 'onscreen') {
      recompute(); // attribute elapsed time under the OLD engagement first
      onscreenTabs.set(tabId, {
        ts: sbNow(),
        playing: !!m.playing,
        inputAgo: typeof m.inputAgo === 'number' ? m.inputAgo : Infinity,
        site: m.site,
        windowId
      });
      settle();
    } else if (m.type === 'offscreen') {
      // The tab saw itself go hidden: release it now instead of waiting out the lease.
      recompute();
      onscreenTabs.delete(tabId);
      settle();
    }
  });

  port.onDisconnect.addListener(() => {
    ports.delete(port);
    recompute();
    if (tabId != null) onscreenTabs.delete(tabId);
    settle();
    if (ports.size === 0) { stopTicker(); Sync.onIdle(); }
  });

  // Send state as soon as load() resolves, so the tab can block or unblock on load.
  // Before that the state is still DEFAULTS, and a snapshot of those would briefly
  // render a depleted site unblocked on a cold start.
  ready.then(() => {
    if (ports.has(port)) {
      recompute();
      try { port.postMessage({ type: 'tick', ...snapshot() }); } catch (_) {}
    }
  });
});

// A tab closing without a clean disconnect still has to release its on-screen state.
// Attribute the trailing time under the old engagement first, like every other path.
browser.tabs.onRemoved.addListener((tabId) => {
  if (!onscreenTabs.has(tabId)) return;
  recompute();
  onscreenTabs.delete(tabId);
  settle();
});

// A tab dragged to another window keeps its port, so the windowId captured at connect
// goes stale and a visible tab could stay suppressed as minimized forever. onAttached
// needs no extra permission.
browser.tabs.onAttached.addListener((tabId, info) => {
  const entry = onscreenTabs.get(tabId);
  if (!entry) return;
  recompute(); // attribute elapsed time under the old window first
  entry.windowId = info.newWindowId;
  settle();
});

// No event fires on minimize. onFocusChanged is the nearest "something changed" signal,
// so re-query the real window states whenever it fires, plus on the ticker's periodic
// recheck.
async function refreshWindows() {
  if (!browser.windows) return;
  try {
    const wins = await browser.windows.getAll();
    const next = new Set(wins.filter((w) => w.state === 'minimized').map((w) => w.id));
    const changed = next.size !== minimizedWindows.size
      || [...next].some((id) => !minimizedWindows.has(id));
    if (changed) {
      recompute();
      minimizedWindows = next;
      settle();
    }
  } catch (_) {}
}

if (browser.windows) {
  browser.windows.onFocusChanged.addListener(() => { refreshWindows(); });
}

// --- One-off request API for popup, options, and the overlay ---

browser.runtime.onMessage.addListener(async (msg) => {
  await ready;
  switch (msg && msg.type) {
    case 'getState':
      Sync.pullSoon(); // opening the popup nudges a pull (throttled inside Sync)
      recompute();
      return snapshot();

    case 'useReserve':
      // Add the fixed 5 minutes back and revive the battery on the spot. The overlay
      // gates this behind the friction widget.
      recompute();
      state.charge = clamp(state.charge + RESERVE_SECONDS, 0, state.capacity);
      if (state.charge > 0) { state.depleted = false; state.depletedAt = null; }
      recompute();
      await persist();
      broadcast();
      // The push writes the server, but lower wins means other devices keep a lower
      // charge: the revive raises only this device.
      Sync.pushChargeSoon();
      return snapshot();

    case 'useSitePass': {
      // Five friction gated minutes through a blocked site (settings Block or an hour
      // rule). The battery is untouched and time on the passed site drains as normal.
      if (!msg.site) return snapshot();
      recompute();
      state.sitePasses = { ...state.sitePasses, [msg.site]: sbNow() + SITE_PASS_SECONDS * 1000 };
      await persist();
      settle(); // the passed site can start draining right away
      return snapshot();
    }

    case 'saveSettings': {
      // Stamp only the keys that actually change, so an unchanged value can't clobber
      // another device's newer edit in the last write wins merge.
      const before = settingsSnapshot();
      applySettings(msg.settings || {});
      recompute();
      await persist();
      broadcast();
      Sync.onSettingsChanged(changedSettingKeys(before));
      refreshAccess(); // a mode flip can change which grants matter
      return { ok: true, ...snapshot() };
    }

    case 'addCustomSite': {
      // The options page has already been granted host permission for this site
      // (requested there, from a user click). Store it and register the script.
      const host = normalizeHost(msg.host);
      if (!host) return { ok: false, error: 'badHost' };
      if (!state.customSites.some((c) => c.id === host)) {
        state.customSites = [...state.customSites, { id: host, name: msg.name || host, host }];
        // A re-added site sheds the false its removal left behind, so it is tracked again.
        const changed = ['customSites'];
        if (state.enabledSites[host] === false) {
          delete state.enabledSites[host];
          changed.push('enabledSites');
        }
        await persist();
        await syncCustomScripts();
        broadcast();
        Sync.onSettingsChanged(changed);
      }
      refreshAccess();
      return { ok: true, ...snapshot() };
    }

    case 'removeCustomSite': {
      const id = msg.id;
      state.customSites = state.customSites.filter((c) => c.id !== id);
      // Set false rather than delete: an already injected content script in an open tab
      // treats a missing key as enabled and would keep draining. The broadcast below
      // makes open tabs go inert immediately.
      state.enabledSites[id] = false;
      await persist();
      await syncCustomScripts();
      try { await browser.permissions.remove({ origins: originPatterns(id) }); } catch (_) {}
      broadcast();
      Sync.onSettingsChanged(['customSites', 'enabledSites']);
      refreshAccess();
      return { ok: true, ...snapshot() };
    }

    // --- Sync controls for the options page ---

    case 'syncStatus':
      return {
        on: !!state.syncCode,
        state: syncStatusCache.state,
        lastSyncTs: syncStatusCache.lastSyncTs,
        code: state.syncCode ? Sync._internals.formatCode(state.syncCode) : null,
        serverUrl: state.serverUrl || Sync.defaultServer()
      };

    case 'syncEnable': {
      const r = await Sync.enable();
      if (ports.size) Sync.onActive();
      Sync.onEngagementChange(isEngaged()); // start pushing if a tab is already engaged
      return r;
    }

    case 'syncLink': {
      const r = await Sync.link(msg.code || '');
      if (r && r.ok) {
        if (ports.size) Sync.onActive();
        Sync.onEngagementChange(isEngaged());
      }
      return r;
    }

    case 'syncSetServer':
      Sync.setServer(msg.url || '');
      return { ok: true, serverUrl: state.serverUrl || Sync.defaultServer() };

    case 'syncUnlink':
      Sync.unlink();
      return { ok: true };

    default:
      return undefined;
  }
});

// A shallow view of the synced settings, for diffing which keys a save changed.
function settingsSnapshot() {
  return {
    rechargePerMin: state.rechargePerMin,
    capacity: state.capacity,
    warnSeconds: state.warnSeconds,
    enabledSites: JSON.stringify(state.enabledSites),
    hourRules: JSON.stringify(state.hourRules),
    hideYtSidebar: state.hideYtSidebar,
    showTimeLeft: state.showTimeLeft
  };
}
function changedSettingKeys(before) {
  const now = settingsSnapshot();
  return Object.keys(now).filter((k) => now[k] !== before[k]);
}

function applySettings(s) {
  // Floor of 1: at 0 a depleted battery could never climb back out.
  if (s.rechargePerMin != null) state.rechargePerMin = clamp(s.rechargePerMin, 1, 600);
  if (s.capacity != null) state.capacity = clamp(s.capacity, 60, 24 * 3600);
  if (s.warnSeconds != null) state.warnSeconds = clamp(s.warnSeconds, 0, 24 * 3600);
  if (s.enabledSites && typeof s.enabledSites === 'object') {
    state.enabledSites = mergeSiteModes(state.enabledSites, sanitizeSiteModes(s.enabledSites));
  }
  if (Array.isArray(s.hourRules)) state.hourRules = SBRules.sanitizeRules(s.hourRules);
  if (s.hideYtSidebar != null) state.hideYtSidebar = !!s.hideYtSidebar;
  if (s.showTimeLeft != null) state.showTimeLeft = !!s.showTimeLeft;
  // Banked charge can never exceed the (possibly lowered) capacity.
  state.charge = clamp(state.charge, 0, state.capacity);
}

// A site entry is false (off), 'block', or true/missing (tracked); anything else from a
// save or a sync collapses to tracked. The map holds this browser's platforms and custom
// sites, plus ids other devices own (a phone's app:<package> modes) and false tombstones
// for removed custom sites. Ids are never platform checked, only carried.
function sanitizeSiteModes(map) {
  const out = {};
  for (const id of Object.keys(map)) {
    out[id] = map[id] === false ? false : map[id] === 'block' ? 'block' : true;
  }
  return out;
}

// The one way a new site-mode map lands on the stored one, from a save and from a sync
// alike. Two rules, both about ids this device does not own.
//
// An omitted id is kept, not dropped. Nothing deletes a mode on purpose (removing a
// custom site writes an explicit false), so absence means an older writer built the map
// without it, and dropping it would silently loosen another device's Block into tracked.
//
// A map that changes no mode changes nothing. A save makes every rendered row explicit
// and a phone emits its own key order, so identical modes can arrive as different bytes.
// Returning the stored object keeps those bytes and with them the key's sync timestamp,
// so a capacity-only save cannot restamp the map and push this device's possibly stale
// copy of another device's modes as the newest write.
function mergeSiteModes(cur, next) {
  const out = { ...next };
  for (const id of Object.keys(cur)) {
    if (!(id in out)) out[id] = cur[id];
  }
  const mode = (v) => (v === false ? 'off' : v === 'block' ? 'block' : 'on');
  const same = [...new Set([...Object.keys(cur), ...Object.keys(out)])]
    .every((id) => mode(cur[id]) === mode(out[id]));
  return same ? cur : out;
}

// --- Sync bridge: the surface sync.js drives, so all state math stays here -----

// What an anchor implies right now. Pure math in projection.js: a draining anchor
// projects drain only for a bounded horizon past its asOf (a live writer pushes far more
// often than that), then recharges. A writer that vanished mid drain (lid closed, crash,
// network drop) therefore costs at most the horizon, never the whole gap.
function projectAnchor(a, now) {
  return SBProjection.projectAnchor(a, now, {
    capacity: state.capacity,
    rechargePerMin: state.rechargePerMin,
    cooldownSeconds: COOLDOWN_SECONDS,
    hourRules: state.hourRules,
    serverOffset: state.serverOffset || 0
  });
}

// Lower charge always wins, so a sync can lower this device but never raise it. Adopt
// the remote only if it projects to a charge at or below ours (a small epsilon absorbs
// sub-second projection noise so followers don't churn). If the remote is higher, keep
// ours and tell the caller to overwrite the server, except while the higher anchor is a
// live draining writer: overwriting then would just CAS ping-pong with the engaged
// device, so it waits until that writer goes stale or idle.
//
// Adoption takes the projected state, not the raw anchor, so a remote depletion keeps the
// depletedAt of the moment the charge actually crossed zero: every device agrees on when
// the cooldown ends and re-reading the anchor can't restart it. A device never adopts its
// own echo (writer === deviceId), since local state is newer than anything it pushed.
const ADOPT_EPS = 2; // seconds
function maybeAdoptAnchor(a) {
  if (!a || typeof a.charge !== 'number' || typeof a.asOf !== 'number') return { adopted: false };
  if (a.writer && a.writer === state.deviceId) return { adopted: false };
  recompute(); // settle local to now before comparing
  const now = sbNow();
  const p = projectAnchor(a, now);
  if (p.charge > state.charge + ADOPT_EPS) {
    return { adopted: false, overwrite: !(a.draining && now - a.asOf < DRAIN_LEASE_MS) };
  }

  state.charge = clamp(p.charge, 0, state.capacity);
  // A lower non depleted anchor lowers the charge but cannot cut a running cooldown short.
  if (!(state.depleted && isCooling(now) && !p.depleted)) {
    // A remote depletion arriving while this device is alive starts a new dead
    // period here. While already dead, an updated anchor (whose depletedAt may
    // differ) is the same period continuing, so the seq holds still.
    if (p.depleted && !state.depleted) state.depletionSeq = (state.depletionSeq || 0) + 1;
    state.depleted = p.depleted;
    state.depletedAt = p.depletedAt;
  }
  state.lastTickTs = now;
  if (!isEngaged()) {
    // Mirror a live remote drain between pulls. The lease timestamp is the anchor's
    // asOf, so a writer that keeps writing keeps the mirror alive and a vanished one
    // expires DRAIN_LEASE_MS after its last write however often we re-read it.
    state.remoteDraining = !!a.draining && !p.depleted;
    state.remoteDrainingTs = a.asOf;
  }
  recompute();
  broadcast();
  persist();
  return { adopted: true };
}

// Settings merged in from another device. Same clamps as a local save, plus a re-register
// of the custom scripts if the custom-site list changed.
function applyRemoteSettings(values) {
  let customChanged = false;
  if (values.rechargePerMin != null) state.rechargePerMin = clamp(values.rechargePerMin, 1, 600);
  if (values.capacity != null) state.capacity = clamp(values.capacity, 60, 24 * 3600);
  if (values.warnSeconds != null) state.warnSeconds = clamp(values.warnSeconds, 0, 24 * 3600);
  if (values.enabledSites && typeof values.enabledSites === 'object') {
    state.enabledSites = mergeSiteModes(state.enabledSites, sanitizeSiteModes(values.enabledSites));
  }
  if (Array.isArray(values.hourRules)) state.hourRules = SBRules.sanitizeRules(values.hourRules);
  if (values.hideYtSidebar != null) state.hideYtSidebar = !!values.hideYtSidebar;
  if (values.showTimeLeft != null) state.showTimeLeft = !!values.showTimeLeft;
  if (Array.isArray(values.customSites)) {
    // Same host hygiene as the add path: a synced entry whose host doesn't normalize is
    // dropped, not registered.
    const next = values.customSites
      .filter((c) => c && c.id && c.host)
      .map((c) => {
        const host = normalizeHost(c.host);
        return host ? { id: c.id, name: c.name || host, host } : null;
      })
      .filter(Boolean);
    if (JSON.stringify(next) !== JSON.stringify(state.customSites)) { state.customSites = next; customChanged = true; }
  }
  state.charge = clamp(state.charge, 0, state.capacity);
  recompute();
  persist();
  if (customChanged) syncCustomScripts();
  refreshAccess();
  broadcast();
}

// The adapter sync.js talks to, and the only bridge between the sync engine and the
// battery state.
const syncAdapter = {
  getConfig: () => ({
    syncCode: state.syncCode,
    serverUrl: state.serverUrl,
    serverOffset: state.serverOffset,
    settingsMeta: state.settingsMeta
  }),
  saveConfig: (partial) => {
    // serverOffset never lands by plain assignment (unlink resets it that way): it has
    // to go through the re-anchoring, or the jump reads as elapsed time.
    if ('serverOffset' in partial) {
      applyServerOffset(partial.serverOffset);
      partial = { ...partial };
      delete partial.serverOffset;
    }
    Object.assign(state, partial);
    persist();
  },
  getAnchor: () => ({
    charge: state.charge,
    asOf: state.lastTickTs,
    // Only a local, engaged drain is advertised. A mirrored remote drain is another
    // device's story; republishing it would forge a fresh write time onto it.
    draining: !state.depleted && isEngaged(),
    depleted: state.depleted,
    depletedAt: state.depletedAt,
    writer: state.deviceId
  }),
  maybeAdoptAnchor,
  getSettings: () => ({
    rechargePerMin: state.rechargePerMin,
    capacity: state.capacity,
    warnSeconds: state.warnSeconds,
    enabledSites: state.enabledSites,
    customSites: state.customSites,
    hourRules: state.hourRules,
    hideYtSidebar: state.hideYtSidebar,
    showTimeLeft: state.showTimeLeft
  }),
  applyRemoteSettings,
  isEngaged,
  sbNow,
  setServerOffset: applyServerOffset,
  setStatus: (s) => { syncStatusCache = s; },
  // A draining device pushes on a timer, but a minimized window throttles that timer.
  // An alarm keeps firing under throttling, so run one while draining.
  setHeartbeat: (on) => {
    if (!browser.alarms) return;
    if (on) browser.alarms.create('sb-push', { periodInMinutes: 1 });
    else browser.alarms.clear('sb-push');
  }
};

// --- Custom sites: register the content scripts on user-added hosts ----------
// Built-in platforms ship as static content_scripts in the manifest. Custom hosts are
// injected at runtime, but only for hosts whose permission the user actually granted.

function originPatterns(host) {
  return [`*://${host}/*`, `*://*.${host}/*`];
}

function normalizeHost(input) {
  if (!input) return null;
  let s = String(input).trim().toLowerCase();
  s = s.replace(/^[a-z]+:\/\//, '');   // drop scheme
  s = s.split(/[/?#]/)[0];             // drop path/query/hash
  s = s.split(':')[0].replace(/^www\./, ''); // drop port and a leading www.
  return /^[a-z0-9.-]+\.[a-z]{2,}$/.test(s) ? s : null;
}

// Add, remove, and applyRemoteSettings can all trigger a re-registration at once, and
// the unregister-then-register passes would interleave. Run them one at a time behind a
// chained promise.
let scriptSyncChain = Promise.resolve();
function syncCustomScripts() {
  const run = () => doSyncCustomScripts();
  scriptSyncChain = scriptSyncChain.then(run, run);
  return scriptSyncChain;
}

async function doSyncCustomScripts() {
  const scripting = browser.scripting;
  if (!scripting || !scripting.registerContentScripts) return;
  try {
    const existing = await scripting.getRegisteredContentScripts();
    const mine = existing.filter((s) => s.id && s.id.indexOf('sb-custom-') === 0).map((s) => s.id);
    if (mine.length) await scripting.unregisterContentScripts({ ids: mine });
  } catch (_) {}
  for (const c of state.customSites) {
    const origins = originPatterns(c.host);
    let granted = false;
    try { granted = await browser.permissions.contains({ origins }); } catch (_) {}
    if (!granted) continue;
    try {
      await scripting.registerContentScripts([{
        id: 'sb-custom-' + c.id,
        matches: origins,
        js: ['platforms.js', 'rules.js', 'friction.js', 'content.js'],
        runAt: 'document_start',
        allFrames: false
      }]);
    } catch (_) { /* origin not permitted or already registered */ }
  }
}

ready.then(syncCustomScripts);

// --- Site access watch --------------------------------------------------------
// MV3 host permissions are revocable at any moment: the extensions panel carries a per
// site "Only When Clicked", and without the grant Firefox never injects the content
// scripts. No drain, no cover, no error. So the grants are checked here, and a miss goes
// on the badge and into the popup, which offers the one tap re grant.

let missingAccess = []; // [{ id, name, origins }] for every site that should enforce

async function refreshAccess() {
  if (!browser.permissions || !browser.permissions.contains) return;
  const wanted = [];
  for (const p of (globalThis.TRACKED_PLATFORMS || [])) {
    // Off means nothing to enforce. Block still needs the grant, since the cover is a
    // content script.
    if (state.enabledSites[p.id] === false) continue;
    wanted.push({ id: p.id, name: p.name, origins: p.matches });
  }
  for (const c of state.customSites) {
    if (state.enabledSites[c.id] === false) continue;
    wanted.push({ id: c.id, name: c.name || c.host, origins: originPatterns(c.host) });
  }
  const out = [];
  for (const w of wanted) {
    let granted = false;
    try { granted = await browser.permissions.contains({ origins: w.origins }); } catch (_) {}
    if (!granted) out.push(w);
  }
  missingAccess = out;
  updateBadge();
}

ready.then(refreshAccess);

// A grant appearing also unlocks custom script registration. Granting through the panel
// never re-ran it before, so the site stayed dark until the next background load.
if (browser.permissions && browser.permissions.onAdded) {
  browser.permissions.onAdded.addListener(() => { refreshAccess(); syncCustomScripts(); });
  browser.permissions.onRemoved.addListener(() => { refreshAccess(); });
}

browser.runtime.onInstalled.addListener((details) => {
  ready.then(persist);
  // First install: open the welcome page, so a block screen mid video is never the
  // first the user hears of the extension.
  if (details && details.reason === 'install') {
    browser.tabs.create({ url: browser.runtime.getURL('welcome.html') });
  }
});

// Heartbeat: fires even when a minimized window throttles the setInterval timers. Settle
// the charge for the elapsed time, then let the sync engine push it if this device is
// the active drainer.
if (browser.alarms) {
  browser.alarms.onAlarm.addListener(async (alarm) => {
    if (alarm.name !== 'sb-push') return;
    await ready;
    recompute();
    broadcast();
    Sync.onHeartbeat();
  });
}

// Node test seam. In the browser `module` is undefined, so none of this runs. Tests
// provide globalThis.__sbBrowserStub and a Sync stub before requiring this file; time is
// injected by setting lastTickTs and asOf values directly (sbNow is Date.now plus
// serverOffset).
if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    ready, load, recompute, settle, snapshot,
    isEngaged, isDraining, isCooling,
    maybeAdoptAnchor, applySettings, applyRemoteSettings, applyServerOffset,
    settingsSnapshot, changedSettingKeys,
    normalizeHost, syncCustomScripts, syncAdapter, siteUsable, refreshAccess,
    getState: () => state,
    setState: (partial) => Object.assign(state, partial),
    onscreenTabs,
    setMinimizedWindows: (set) => { minimizedWindows = set; },
    DEFAULTS, RESERVE_SECONDS, COOLDOWN_SECONDS, SITE_PASS_SECONDS,
    ONSCREEN_LEASE_MS, ENGAGE_INPUT_MS, ADOPT_EPS, DRAIN_LEASE_MS
  };
}
