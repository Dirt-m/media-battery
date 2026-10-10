// Runs on every tracked platform. Two jobs: report to the background timekeeper
// whether this tab is engaged, and cover the page when the background says the battery
// is dead (pausing media, offering a friction gated way to spend reserve).
//
// "Engaged" is platform agnostic. The tab beats to the background while it is on screen
// (requestAnimationFrame only advances while the tab is being painted, so a second
// monitor counts and a minimized window does not). Each beat carries whether an audible
// video is playing (muted autoplay feeds are not watching) and how long since the last
// input here: engaged means on screen AND (audible video OR input within the last
// minute). All observed in this tab, no OS idle API.
//
// A site switched off in settings is inert here: no beats, no cover.
//
// The overlay lives in a Shadow DOM so the site's CSS can't touch it, but that alone
// still leaks input to the page: key events are composed and bubble to the page's
// document, so YouTube would seek on number keys and toggle captions or mute on c/m
// while you typed the friction phrase, and clicks would land underneath. Hence the
// input shield (see installShield). The page itself is never modified, so unblocking
// lands on the same screen.

(async function () {
  if (window.__sbLoaded) return; // a built-in and a custom registration can overlap
  window.__sbLoaded = true;

  const browser = globalThis.browser || globalThis.chrome;

  let platform = (globalThis.currentPlatform && globalThis.currentPlatform()) || null;
  if (!platform) {
    // A user-added (custom) host: resolve its name from stored customSites.
    try {
      const { customSites = [] } = await browser.storage.local.get('customSites');
      const h = location.hostname;
      const m = customSites.find((c) => h === c.host || h.endsWith('.' + c.host));
      if (m) platform = { id: m.id, name: m.name, hosts: [m.host] };
    } catch (_) {}
  }
  if (!platform) return; // not a tracked host
  const site = platform.id;

  const state = {
    mode: 'on',              // this site's settings mode: 'on', 'off', or 'block'
    depleted: false,         // is the battery dead (sites blocked)?
    cooldownRemaining: 0,    // seconds until the block lifts
    reserveSeconds: 300,     // charge spending reserve adds back
    passSeconds: 300,        // how long a pass through a blocked site lasts
    passUntil: 0,            // performance.now() when this site's pass runs out
    hourRules: [],           // between-hours rules; evaluated here against this site
    rechargePaused: false,   // an open hour rule has charging stopped
    hideYtSidebar: false,    // always strip the YouTube sidebar (a setting)
    frictionCount: 1         // questions the gate asks (a setting)
  };

  // Hour rules and the settings mode re-evaluate on the 1s poll, so a window opening
  // or closing flips the page without waiting for a background tick.
  function hourEffects() {
    return SBRules.siteEffectsAt(state.hourRules, site, new Date());
  }
  function siteOff() {
    return state.mode === 'off' || hourEffects().off;
  }
  function passActive() {
    return performance.now() < state.passUntil;
  }
  // Blocked outright by the settings Block mode or an open hour rule, unless a five
  // minute pass is running. Nothing to do with the battery being dead.
  function siteBlocked() {
    if (siteOff() || passActive()) return false;
    return state.mode === 'block' || hourEffects().blocked;
  }

  // YouTube only: on a watch page the reserve action becomes "unblock this video". It
  // lifts the cover for that one video id (the battery stays dead) and strips the
  // recommendations sidebar.
  const isYouTube = site === 'youtube';
  let unblockedVideoId = null;
  let depletionEpoch = null;  // depletionSeq of the dead period the unblock belongs to
  let sidebarStyle = null;

  // The port to the background. An idled event page severs its ports and on an SPA the
  // next full navigation can be days away, so a dead port has to reconnect itself or
  // the tab neither drains nor ever covers. Reconnecting wakes the background, which
  // answers with a fresh tick. Only the visible paths reconnect (the rAF beat, becoming
  // visible), so a hidden tab lets the background sleep.
  let port = null;
  let portRetryAt = 0; // backoff after a failed connect (extension reloaded or gone)

  function connectPort() {
    if (port || performance.now() < portRetryAt) return;
    let p;
    try {
      p = browser.runtime.connect({ name: 'sb' });
    } catch (_) {
      portRetryAt = performance.now() + 5000;
      return;
    }
    port = p;
    p.onMessage.addListener(onTick);
    p.onDisconnect.addListener(() => { if (port === p) port = null; });
  }

  // Shared by ticks and the boot getState: both carry the same snapshot shape.
  function readSnapshot(m) {
    const mode = m.enabledSites ? m.enabledSites[site] : undefined;
    state.mode = mode === false ? 'off' : mode === 'block' ? 'block' : 'on';
    state.hourRules = Array.isArray(m.hourRules) ? m.hourRules : [];
    state.rechargePaused = !!m.rechargePaused;
    state.depleted = m.depleted;
    state.cooldownRemaining = m.cooldownRemaining || 0;
    state.hideYtSidebar = !!m.hideYtSidebar;
    if (typeof m.frictionCount === 'number') state.frictionCount = m.frictionCount;
    if (typeof m.reserveSeconds === 'number') state.reserveSeconds = m.reserveSeconds;
    if (typeof m.passSeconds === 'number') state.passSeconds = m.passSeconds;
    // Passes arrive as seconds remaining; anchor them to this tab's clock.
    const left = m.sitePasses ? m.sitePasses[site] : 0;
    state.passUntil = left > 0 ? performance.now() + left * 1000 : 0;
  }

  function onTick(m) {
    if (m.type !== 'tick') return;
    readSnapshot(m);
    maybeWarn(m);
    maybeShowTimeLeft(m);
    // Forget the per video pass whenever the dead period changes (revived, or a fresh
    // depletion), so a stale one can't survive a dead, alive, dead cycle the tab slept
    // through. Keyed on the seq rather than depletedAt because with sync on every server
    // response re-anchors the clock and shifts depletedAt, which used to read as a new
    // dead period and ate the pass.
    if (m.depletionSeq !== depletionEpoch) { depletionEpoch = m.depletionSeq; unblockedVideoId = null; }
    applyOverlay();
    applySidebar();
  }

  connectPort();

  // --- Engagement detection ---------------------------------------------------
  // "On screen" is proven by beating on requestAnimationFrame, which only advances
  // while the tab is being painted: true on any monitor, even an unfocused second one,
  // false when minimized or in a background tab. document.visibilityState gets that
  // wrong on some platforms (a minimized window can stay "visible"), so we require
  // both. Each beat also reports audible video and time since the last input here; the
  // background leases the beats and decides engagement from the two.

  // Audible only: Instagram's feed and YouTube's hover previews autoplay muted video
  // nonstop, which must not count as watching.
  function audibleVideoPlaying() {
    for (const v of document.querySelectorAll('video')) {
      if (!v.paused && !v.ended && v.readyState >= 2 && !v.muted && v.volume > 0) return true;
    }
    return false;
  }

  // Last real input in this tab. Registered before the block overlay's shield so these
  // fire first; opening the page counts as input. No scroll: pages fire it
  // programmatically (a live chat autoscrolls forever with nobody there), and a real
  // scroll already arrives as wheel, touch, key, or pointer.
  //
  // Kept on two clocks. performance.now() stands still while a phone sleeps, so on its
  // own the last tap before the screen went dark would read as seconds old on wake and
  // count as a minute of use. The wall clock keeps counting through the sleep; the older
  // of the two readings is the honest one (a wall clock set backwards reads negative
  // and simply loses).
  let lastInputPerf = performance.now();
  let lastInputWall = Date.now();
  const INPUT_EVENTS = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'touchstart'];
  for (const ev of INPUT_EVENTS) {
    window.addEventListener(ev, () => { lastInputPerf = performance.now(); lastInputWall = Date.now(); },
      { capture: true, passive: true });
  }
  function inputAgo() {
    return Math.round(Math.max(performance.now() - lastInputPerf, Date.now() - lastInputWall));
  }

  let lastBeat = -Infinity; // beat immediately on the first painted frame
  function beat(ts) {
    // An off site stays silent. A blocked site still beats: the background won't count
    // it, but a pass then starts the drain without waiting for a fresh connection.
    if (!siteOff() && document.visibilityState === 'visible' && ts - lastBeat >= 1000) {
      lastBeat = ts;
      connectPort(); // the beat is the reconnect path: it only runs while painted
      try {
        if (port) port.postMessage({
          type: 'onscreen',
          site,
          playing: audibleVideoPlaying(),
          inputAgo: inputAgo()
        });
      } catch (_) { port = null; }
    }
    requestAnimationFrame(beat);
  }
  requestAnimationFrame(beat);

  // An explicit goodbye on top of the lease, so the background releases this tab and
  // stops the drain now instead of waiting the lease out. The lease still covers the
  // paths where no event fires at all.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      connectPort(); // coming back on screen revives a dead port right away
    } else if (port) {
      // No reconnect just to say goodbye: a severed port already released this tab.
      try { port.postMessage({ type: 'offscreen' }); } catch (_) { port = null; }
    }
  });

  // Poll: keeps media paused under the overlay, redraws the overlay after SPA DOM swaps,
  // and keeps the sidebar hidden. Engagement is reported by the rAF beat, not here.
  setInterval(() => { enforce(); applyOverlay(); applySidebar(); }, 1000);

  // While dead, keep media paused under the overlay. Audio too, or a podcast plays on
  // under the cover. Web Audio is a known gap.
  function enforce() {
    if (!shouldBlock()) return;
    for (const v of document.querySelectorAll('video, audio')) {
      if (!v.paused) { try { v.pause(); } catch (_) {} }
    }
  }

  // --- Low battery warning ------------------------------------------------------
  // A corner notice the first time the charge drops under the warn threshold while
  // draining (a setting; 0 turns it off), so the block never lands unannounced.
  // Re-arms once the charge climbs clear again.

  let warnArmed = true;

  function maybeWarn(m) {
    if (typeof m.charge !== 'number') return;
    if (m.depleted) { hidePill(); return; } // the overlay says it all now
    const warnSecs = Number(m.warnSeconds) || 0;
    if (warnSecs <= 0) { warnArmed = true; return; }
    if (m.charge > warnSecs + 60) { warnArmed = true; return; }
    // A covered page needs no toast, and one already up must not linger over the
    // cover. The re-arms above still ran, so the warning fires once the block lifts.
    if (shouldBlock()) { hidePill(); return; }
    if (!warnArmed || siteOff() || m.charge > warnSecs || !m.draining) return;
    warnArmed = false;
    const mins = Math.max(1, Math.ceil(m.charge / 60));
    showPill(`Media Battery: ${mins} min left`, '#f0a93b', 8000);
  }

  // --- Time left on arrival -----------------------------------------------------
  // The same pill when a tracked page opens (a synced setting, on by default), gone
  // again quickly. Once per page load. Covered and off pages tell their own story, and
  // a warning already up names the same number, so it keeps the slot.

  let entryDone = false;

  function maybeShowTimeLeft(m) {
    if (entryDone || typeof m.charge !== 'number') return;
    entryDone = true;
    if (m.showTimeLeft === false || siteOff() || shouldBlock() || pillHost) return;
    const mins = Math.max(1, Math.ceil(m.charge / 60));
    // Same color language as the gauge: green while comfortable, amber when low.
    const cap = typeof m.effCapacity === 'number' ? m.effCapacity : m.capacity;
    const low = cap > 0 && m.charge / cap <= 0.2;
    showPill(`${mins} min left`, low ? '#f0a93b' : '#36c06f', 2500);
  }

  // --- Corner pill --------------------------------------------------------------
  // The one bottom right slot both notices render into. A new pill replaces whatever
  // is up, and the overlay going up clears it.

  let pillHost = null;
  let pillTimers = [];

  function showPill(text, dotColor, ms) {
    hidePill();
    pillHost = document.createElement('div');
    pillHost.style.cssText = 'all: initial; position: fixed; right: 16px; bottom: 16px; z-index: 2147483647;';
    const sh = pillHost.attachShadow({ mode: 'open' });
    sh.innerHTML = `
      <style>
        .pill { display: flex; align-items: center; gap: 9px; background: #1b2027;
                border: 1px solid #2b323b; border-radius: 12px; padding: 10px 14px;
                font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
                font-size: 13px; font-weight: 600; color: #e8eaed; white-space: nowrap;
                box-shadow: 0 8px 28px rgba(0, 0, 0, 0.45);
                opacity: 0; transition: opacity .25s ease; }
        .dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: ${dotColor}; }
      </style>
      <div class="pill"><span class="dot"></span>${text}</div>
    `;
    (document.body || document.documentElement).appendChild(pillHost);
    const pill = sh.querySelector('.pill');
    requestAnimationFrame(() => { pill.style.opacity = '1'; });
    pillTimers.push(setTimeout(() => {
      pill.style.opacity = '0';
      pillTimers.push(setTimeout(hidePill, 300));
    }, ms));
  }

  function hidePill() {
    for (const t of pillTimers) clearTimeout(t);
    pillTimers = [];
    if (pillHost) { pillHost.remove(); pillHost = null; }
  }

  // --- Input shield -----------------------------------------------------------
  // The list of event types we don't want the page to act on while blocked.
  const SHIELD_EVENTS = ['keydown', 'keyup', 'keypress', 'click', 'dblclick',
    'auxclick', 'contextmenu', 'mousedown', 'mouseup', 'pointerdown', 'pointerup',
    'wheel', 'touchstart', 'touchend', 'touchmove'];
  // The gate's own typing also emits these; they only originate inside the overlay,
  // so they need stopping at the host on the way up, never at the window capture.
  const BUBBLE_EVENTS = [...SHIELD_EVENTS,
    'beforeinput', 'input', 'compositionstart', 'compositionend'];
  let shieldOn = false;
  // passive:false is required: wheel and touch listeners on window default to passive,
  // which would silently ignore preventDefault and let the page keep scrolling.
  const CAPTURE_OPTS = { capture: true, passive: false };

  function aimedAtOverlay(e) {
    const path = e.composedPath ? e.composedPath() : [];
    return host ? path.indexOf(host) !== -1 : false;
  }

  // Window, capture phase: kill anything the page would otherwise receive. Events
  // aimed at the overlay pass through here so the friction inputs work; they get
  // stopped on the way back up instead (blockGateBubble).
  function shieldCapture(e) {
    if (aimedAtOverlay(e)) return;
    e.stopImmediatePropagation();
    if (e.cancelable) e.preventDefault();
    // A swallowed Tab means keyboard focus is still out on the page, where the
    // shield eats every key; pull it into the overlay so the keyboard works at all.
    if (e.type === 'keydown' && e.key === 'Tab') focusOverlay();
  }

  // Overlay host, bubble phase: the gate's own events have reached the inputs by now,
  // so stop them before the page's document level handlers see them. This is what kept
  // YouTube seeking and muting as you typed.
  function blockGateBubble(e) {
    e.stopPropagation();
  }

  function installShield() {
    if (shieldOn) return;
    for (const ev of SHIELD_EVENTS) window.addEventListener(ev, shieldCapture, CAPTURE_OPTS);
    shieldOn = true;
  }

  function removeShield() {
    if (!shieldOn) return;
    for (const ev of SHIELD_EVENTS) window.removeEventListener(ev, shieldCapture, true);
    shieldOn = false;
  }

  // --- Block overlay (Shadow DOM so the site's CSS can't touch it) -------------

  let host = null;
  let shadow = null;
  let gate = null;
  let overlayKind = null; // 'dead' | 'site' | 'gate'

  const SHELL_HTML = `
    <style>
      :host { all: initial; }
      /* Background and card colors match the settings page (options.css). */
      .wrap { position: fixed; inset: 0; display: flex; align-items: center;
              justify-content: center; padding: 28px;
              background: #0f1115;
              font-family: system-ui, -apple-system, "Segoe UI", sans-serif; }
      .card { background: #1b2027; border: 1px solid #2b323b; border-radius: 16px; }

      /* Dead state: one flat line, status left, a quiet reserve button. Sizes in px,
         not rem, so the host page's root font-size can't shrink them. */
      .card.dead { display: inline-flex; align-items: center; gap: 13px;
                   padding: 11px 12px 11px 15px; max-width: calc(100vw - 56px); }
      .card.dead .ic { flex: none; display: flex; color: #ef5350; }
      .card.dead .ic svg { width: 17px; height: 17px; }
      .title { flex: none; font-size: 13px; font-weight: 700; color: #f3f5f7; white-space: nowrap; }
      .divider { flex: none; width: 1px; height: 14px; background: #2f3742; }
      .timer { font-size: 12px; color: #818a95; white-space: nowrap; }
      .timer b { color: #b7bdc5; font-weight: 600; font-variant-numeric: tabular-nums; }
      .unlock-btn { flex: none; margin-left: 5px; font-family: inherit; font-size: 12px;
                    font-weight: 600; color: #828b96; background: transparent;
                    border: 1px solid #2f3742; border-radius: 8px; padding: 7px 12px;
                    cursor: pointer; white-space: nowrap; transition: color .12s, border-color .12s; }
      .unlock-btn:hover { color: #cfd4da; border-color: #434c58; }

      /* Gate state: the card grows into a panel for the friction widget, and scrolls
         rather than overflowing on a short viewport. */
      .card.gate { display: block; width: 100%; max-width: 460px; padding: 28px;
                   max-height: calc(100vh - 56px); overflow-y: auto; }
      .gate-host { }

      /* Phones: the one line card can't fit, so it stacks. Icon and title, then the
         status line, then the button full width. */
      @media (max-width: 560px) {
        .wrap { padding: 16px; }
        .card.dead { flex-wrap: wrap; justify-content: center; row-gap: 9px;
                     padding: 16px 18px 14px; max-width: calc(100vw - 32px); }
        .card.dead .divider { display: none; }
        .card.dead .timer { flex: 1 1 100%; text-align: center; white-space: normal; }
        .card.dead .unlock-btn { flex: 1 1 100%; margin-left: 0; padding: 10px 12px; }
        .card.gate { padding: 22px 18px; max-height: calc(100vh - 32px); }
      }
    </style>
    <div class="wrap"><div class="card dead" data-role="card"></div></div>
  `;

  // An empty battery glyph for the dead status.
  const BATTERY_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" '
    + 'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
    + '<rect x="2" y="7" width="16" height="10" rx="2"/><line x1="22" y1="11" x2="22" y2="13"/>'
    + '<line x1="6" y1="12" x2="9" y2="12"/></svg>';

  // A padlock for a site blocked outright (settings Block or an hour rule).
  const LOCK_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" '
    + 'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
    + '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/></svg>';

  // Keyboard access. Left alone the overlay is unreachable: focus sits on the page,
  // where the shield swallows every keydown, so Tab never arrives. Focus moves into the
  // shadow root on mount (and whenever the shield eats a Tab), and Tab wraps at the
  // overlay's edges so it can't escape back to the page.
  function focusables() {
    if (!shadow) return [];
    return [...shadow.querySelectorAll('button, input, textarea')].filter((el) => !el.disabled);
  }

  function focusOverlay() {
    const els = focusables();
    if (els.length) els[0].focus();
  }

  function trapTab(e) {
    if (e.key !== 'Tab') return;
    const els = focusables();
    if (!els.length) return;
    const idx = els.indexOf(shadow.activeElement);
    if (e.shiftKey && idx <= 0) { e.preventDefault(); els[els.length - 1].focus(); }
    else if (!e.shiftKey && (idx === -1 || idx === els.length - 1)) { e.preventDefault(); els[0].focus(); }
  }

  // --- Fullscreen ---------------------------------------------------------------
  // Only the fullscreen element gets painted, so a cover appended to body is invisible
  // under a fullscreen video, while the shield still eats every tap the page would use
  // to leave fullscreen. On a phone that is a frozen video with no way out short of
  // killing the browser. So the cover mounts inside the fullscreen element, where it
  // shows, and the document is asked to leave fullscreen, so the page comes back with
  // the browser's own controls around it.
  function overlayParent() {
    return document.fullscreenElement || document.body || document.documentElement;
  }

  function leaveFullscreen() {
    if (!document.fullscreenElement || !document.exitFullscreen) return;
    try {
      const p = document.exitFullscreen();
      if (p && p.catch) p.catch(() => {});
    } catch (_) {}
  }

  // Fullscreen coming or going moves the cover to wherever it can be seen. The poll
  // re-exits a fullscreen entered under the cover.
  document.addEventListener('fullscreenchange', () => {
    if (!host || !host.isConnected) return;
    const parent = overlayParent();
    if (host.parentNode !== parent) parent.appendChild(host);
  });

  function ensureHost() {
    if (host && host.isConnected) return;
    host = document.createElement('div');
    host.id = 'sb-overlay-host';
    host.style.cssText = 'all: initial; position: fixed; inset: 0; z-index: 2147483647;';
    // Closed, so page scripts can't reach in via host.shadowRoot. Our own reference
    // lives in the closure, which is all this code uses.
    shadow = host.attachShadow({ mode: 'closed' });
    shadow.innerHTML = SHELL_HTML;
    // Trap our own gate's events at the host so they never bubble to the page.
    for (const ev of BUBBLE_EVENTS) host.addEventListener(ev, blockGateBubble);
    host.addEventListener('keydown', trapTab);
    overlayKind = null;
    overlayParent().appendChild(host);
    installShield();
  }

  function removeOverlay() {
    if (gate) { gate.destroy(); gate = null; }
    if (host) { host.remove(); host = null; shadow = null; }
    overlayKind = null;
    removeShield();
  }

  // Whether the cover belongs up and which story it tells: 'dead' is the battery,
  // 'site' a block on this site itself (settings Block or an hour rule). Dead wins when
  // both hold, since a site pass buys nothing on a dead battery: a depleted site never
  // drains and stays covered either way.
  function blockKind() {
    if (siteOff()) return null;
    if (state.depleted && !videoUnblocked()) return 'dead';
    return siteBlocked() ? 'site' : null;
  }

  function shouldBlock() {
    return blockKind() !== null;
  }

  // --- YouTube per video unblock ----------------------------------------------

  function currentVideoId() {
    if (!isYouTube) return null;
    try {
      const v = new URLSearchParams(location.search).get('v');
      if (v) return v;
    } catch (_) {}
    // Shorts, live, and embed pages carry the id in the path, not ?v=.
    const m = location.pathname.match(/^\/(?:shorts|live|embed)\/([\w-]+)/);
    return m ? m[1] : null;
  }

  // On any YouTube page showing one video (watch, shorts, live, embed) reserve unblocks
  // that video instead of refilling. Elsewhere (home, search) it falls back to refill.
  function videoUnblockMode() {
    return !!currentVideoId();
  }

  function videoUnblocked() {
    const id = currentVideoId();
    return !!id && id === unblockedVideoId;
  }

  // Hide the YouTube recommendations sidebar: always when the setting is on, and while
  // a single video is unblocked. A page level style, since it targets YouTube's own
  // DOM, not our overlay.
  function applySidebar() {
    const hide = videoUnblocked() || (isYouTube && state.hideYtSidebar);
    if (hide && !sidebarStyle) {
      sidebarStyle = document.createElement('style');
      sidebarStyle.id = 'sb-hide-sidebar';
      sidebarStyle.textContent =
        '#secondary, #related, ytd-watch-next-secondary-results-renderer { display: none !important; }';
      (document.head || document.documentElement).appendChild(sidebarStyle);
    } else if (!hide && sidebarStyle) {
      sidebarStyle.remove();
      sidebarStyle = null;
    }
  }

  function applyOverlay() {
    const kind = blockKind();
    if (!kind) { removeOverlay(); return; }
    enforce();
    if (overlayKind === 'gate') {
      if (host && host.isConnected) return; // unlock in progress; don't redraw over it
      gate = null; // the page wiped our host mid gate; fall through and rebuild
    }
    ensureHost();
    leaveFullscreen();
    if (overlayKind !== kind) drawCard(kind);
    updateTimer();
  }

  function fmtClock(seconds) {
    const s = Math.max(0, Math.round(seconds));
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }

  function reserveMins() {
    return Math.max(1, Math.round(state.reserveSeconds / 60));
  }

  function passMins() {
    return Math.max(1, Math.round(state.passSeconds / 60));
  }

  function minsWord(n) {
    return n === 1 ? 'minute' : 'minutes';
  }

  // When the block on this site lifts, as wall clock "HH:MM", or null when there's no
  // end to name (settings Block mode, or hour rules covering the whole day).
  function blockEndClock() {
    if (state.mode === 'block') return null;
    for (let i = 1; i <= 1440; i++) {
      const d = new Date(Date.now() + i * 60000);
      if (!SBRules.siteEffectsAt(state.hourRules, site, d).blocked) {
        return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
      }
    }
    return null;
  }

  function drawCard(kind) {
    overlayKind = kind;
    const card = shadow.querySelector('[data-role="card"]');
    card.className = 'card dead';
    const btnLabel = kind === 'site'
      ? `Just ${passMins()} more ${minsWord(passMins())}`
      : videoUnblockMode() ? 'Unblock this video'
        : `Just ${reserveMins()} more ${minsWord(reserveMins())}`;
    card.innerHTML = `
      <span class="ic">${kind === 'site' ? LOCK_ICON : BATTERY_ICON}</span>
      <span class="title">${kind === 'site' ? 'Blocked' : 'Battery dead'}</span>
      <span class="divider"></span>
      <span class="timer" data-role="timer"></span>
      <button class="unlock-btn" data-role="unlock">${btnLabel}</button>
    `;
    const unlock = card.querySelector('[data-role="unlock"]');
    unlock.addEventListener('click', () => showUnlockGate(kind));
    unlock.focus(); // the shield mutes the page anyway; land the keyboard here
    updateTimer();
  }

  // Live status text, refreshed every poll while the card is up. Dead counts down to
  // the block lifting (the battery recharges the whole time); site blocked names when
  // the hour rule window closes, or just says it came from settings.
  function updateTimer() {
    if ((overlayKind !== 'dead' && overlayKind !== 'site') || !shadow) return;
    const el = shadow.querySelector('[data-role="timer"]');
    if (!el) return;
    if (overlayKind === 'site') {
      const end = blockEndClock();
      el.innerHTML = end ? `until <b>${end}</b>`
        : state.mode === 'block' ? 'from your settings' : 'by your hours';
      return;
    }
    if (state.cooldownRemaining > 0) {
      el.innerHTML = `recharging, back in <b>${fmtClock(state.cooldownRemaining)}</b>`;
    } else {
      el.textContent = state.rechargePaused ? 'charging is paused by your hours' : 'recharging';
    }
  }

  function showUnlockGate(kind) {
    overlayKind = 'gate';
    const card = shadow.querySelector('[data-role="card"]');
    card.className = 'card gate';
    card.innerHTML = '<div class="gate-host" data-role="gate"></div>';
    const gateHost = card.querySelector('[data-role="gate"]');
    const siteMode = kind === 'site';
    const videoMode = !siteMode && videoUnblockMode();
    const mins = siteMode ? passMins() : reserveMins();
    const plainLabel = `Just ${mins} more ${minsWord(mins)}`;
    gate = FrictionGate.mount(gateHost, {
      title: videoMode ? 'Just this video, I swear!' : `${plainLabel}, I swear!`,
      confirmLabel: videoMode ? 'Unblock this video' : plainLabel,
      cancelLabel: 'Back',
      count: state.frictionCount,
      askWhy: true,
      whyMinWords: 5,
      large: true, // full screen lock: bigger, page-independent text
      onCancel: () => { gate = null; drawCard(kind); },
      onSuccess: async () => {
        if (siteMode) {
          // Five minutes through the block, this site only. Set locally too so the
          // 1s poll can't redraw the cover before the tick lands.
          state.passUntil = performance.now() + state.passSeconds * 1000;
          try { await browser.runtime.sendMessage({ type: 'useSitePass', site }); } catch (_) {}
          gate = null;
          removeOverlay();
          applyOverlay(); // another cover may still apply; don't leave a 1s gap
          return;
        }
        if (videoMode) {
          // Let just this one video through; the battery stays dead.
          unblockedVideoId = currentVideoId();
          gate = null;
          removeOverlay();
          applyOverlay();
          applySidebar();
          return;
        }
        try { await browser.runtime.sendMessage({ type: 'useReserve' }); } catch (_) {}
        // Clear locally so the 1s poll can't redraw the dead card in the gap before
        // the background's tick lands, which confirms it.
        state.depleted = false;
        gate = null;
        removeOverlay();
        applyOverlay();
      }
    });
  }

  // --- Boot -------------------------------------------------------------------

  async function boot() {
    try {
      const st = await browser.runtime.sendMessage({ type: 'getState' });
      if (st) {
        readSnapshot(st);
        depletionEpoch = st.depletionSeq;
      }
    } catch (_) {}
    applyOverlay();
    applySidebar();
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }
})();
