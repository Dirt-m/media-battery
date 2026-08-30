// Live read only view of the charge. Edits live in the friction gated options page,
// sync on its own page.

const browser = globalThis.browser || globalThis.chrome;
const $ = (r) => document.querySelector(`[data-role="${r}"]`);
const app = document.querySelector('.app');

// Site id -> display name for the "draining (site)" label. Built-ins from
// platforms.js, custom sites carry their name in the snapshot.
const SITE_NAMES = {};
for (const p of (globalThis.TRACKED_PLATFORMS || [])) SITE_NAMES[p.id] = p.name;
function drainingName(st) {
  const id = st.drainingSite;
  if (!id) return 'another device'; // a mirrored remote drain has no local tab
  if (SITE_NAMES[id]) return SITE_NAMES[id];
  const c = (st.customSites || []).find((s) => s.id === id);
  return (c && c.name) || id;
}

function fmtClock(seconds) {
  const s = Math.max(0, Math.round(seconds));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${String(r).padStart(2, '0')}`;
}

function render(st) {
  if (!st) return;
  const { charge, capacity, draining, depleted, cooldownRemaining } = st;
  // The cap that holds right now; an open capacity window lowers it.
  const cap = typeof st.effCapacity === 'number' ? st.effCapacity : capacity;
  const paused = !!st.rechargePaused;

  // The real banked charge, which keeps climbing through the cooldown.
  $('time').textContent = fmtClock(charge);

  const pct = cap > 0 ? Math.min(100, (charge / cap) * 100) : 0;
  const full = charge >= cap;

  // Green above 20%; below that red while draining, amber while charging. Dead is
  // red, since red means blocked everywhere here.
  let stateName = 'ok';
  if (depleted) stateName = 'dead';
  else if (pct <= 20) stateName = draining ? 'danger' : 'low';
  app.dataset.state = stateName;

  // Bolt only while the charge is climbing. A dead battery still charges through the
  // cooldown; a paused or pinned one does not.
  app.dataset.flow = draining ? 'draining' : full ? 'full' : paused ? 'paused' : 'charging';

  $('bar').style.width = pct + '%';

  let label;
  if (depleted) label = cooldownRemaining > 0 ? `blocked for ${fmtClock(cooldownRemaining)}` : 'blocked';
  else if (draining) label = `draining (${drainingName(st)})`;
  else if (full) label = cap < capacity ? 'capped by your hours' : 'full';
  else if (paused) label = 'charging paused';
  else label = 'recharging';
  $('time-label').textContent = label;

  renderAccess(st.missingAccess || []);
}

// Sites Firefox holds no host grant for. Nothing enforces there, no drain and no
// cover, so say so and offer the re grant. permissions.request needs a user gesture
// and the button click is one.
let missingAccess = [];

function renderAccess(miss) {
  missingAccess = miss;
  const box = $('access');
  if (!miss.length) { box.hidden = true; return; }
  const names = miss.map((m) => m.name);
  const line = names.length <= 2
    ? names.join(' and ')
    : `${names[0]}, ${names[1]} and ${names.length - 2} more`;
  $('access-text').textContent = `Firefox turned off access to ${line}`;
  box.hidden = false;
}

$('access-fix').addEventListener('click', async () => {
  const origins = missingAccess.flatMap((m) => m.origins);
  if (!origins.length) return;
  try { await browser.permissions.request({ origins }); } catch (_) {}
  refresh();
});

async function refresh() {
  try {
    const st = await browser.runtime.sendMessage({ type: 'getState' });
    render(st);
  } catch (_) { /* background waking up */ }
}

async function refreshSync() {
  try {
    const s = await browser.runtime.sendMessage({ type: 'syncStatus' });
    const btn = $('sync');
    if (!s || !s.on) { app.dataset.sync = 'off'; btn.title = 'Sync off'; }
    else if (s.state === 'offline') { app.dataset.sync = 'offline'; btn.title = 'Sync offline'; }
    else if (s.state === 'syncing') { app.dataset.sync = 'on'; btn.title = 'Syncing'; }
    else { app.dataset.sync = 'on'; btn.title = 'Synced'; }
    btn.setAttribute('aria-label', btn.title);
  } catch (_) { /* background waking up */ }
}

// On Android openOptionsPage puts the tab behind the popup and leaves the popup up:
// looks like a dead button, stacks a tab per tap. An explicit active tab plus close
// behaves. Desktop keeps openOptionsPage, which refocuses an existing settings tab.
$('settings').addEventListener('click', async () => {
  const info = await browser.runtime.getPlatformInfo().catch(() => null);
  if (info && info.os === 'android') {
    await browser.tabs.create({ url: browser.runtime.getURL('options.html'), active: true });
  } else {
    browser.runtime.openOptionsPage();
  }
  window.close();
});
$('sync').addEventListener('click', async () => {
  await browser.tabs.create({ url: browser.runtime.getURL('sync.html'), active: true });
  window.close();
});

refresh();
refreshSync();
setInterval(refresh, 1000);
setInterval(refreshSync, 3000);
