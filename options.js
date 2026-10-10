// The settings page. Saving is gated behind the same friction widget used to get past
// a block, so the rules can't be loosened on impulse. The tracked sites list is the
// built-in platforms plus the user's custom sites, with a YouTube sidebar toggle.

const browser = globalThis.browser || globalThis.chrome;
const $ = (id) => document.getElementById(id);

const PLATFORMS = globalThis.TRACKED_PLATFORMS || [];

const fields = {
  regen: $('regen'),
  cap: $('cap'),
  warn: $('warn'),
  questions: $('questions')
};
const saveBtn = $('save');
const statusEl = $('status');
const backdrop = $('gate-backdrop');
const gateHost = $('gate-host');
const siteRows = $('siteRows');
const customInput = $('customInput');
const addBtn = $('addBtn');
const customStatus = $('customStatus');
const ruleRows = $('ruleRows');
const addRuleBtn = $('addRule');
const ruleStatus = $('ruleStatus');
const entryToastBtn = $('entryToast');
entryToastBtn.addEventListener('click', () => {
  entryToastBtn.setAttribute('aria-pressed', entryToastBtn.getAttribute('aria-pressed') === 'true' ? 'false' : 'true');
  markDirty();
});

let current = null;
let activeGate = null;
let siteSelects = {};   // site id -> <select> (built-in and custom)
let ytSidebarBtn = null;
let ruleDraft = [];     // working copy of the hour rules while editing

// Recharge is stored as seconds/minute and shown as hours/day of charge. A day holds
// 1440 minutes, so 1 h/day = 3600/1440 = 2.5 s/min.
const SEC_PER_MIN_PER_HOUR_DAY = 2.5;

function setStatus(text, kind) {
  statusEl.textContent = text;
  statusEl.className = 'status' + (kind ? ' ' + kind : '');
}

function markDirty() {
  setStatus(dirty() ? 'Unsaved changes.' : '', null);
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// Pull a bare host out of whatever the user typed (url, with www, with a path).
function normalizeHost(input) {
  if (!input) return null;
  const s = String(input).trim().toLowerCase()
    .replace(/^[a-z]+:\/\//, '').split(/[/?#]/)[0].split(':')[0].replace(/^www\./, '');
  return /^[a-z0-9.-]+\.[a-z]{2,}$/.test(s) ? s : null;
}

// A site's settings mode: 'on' (tracked, the default), 'off', or 'block'.
function siteMode(map, id) {
  if (!map) return 'on';
  return map[id] === false ? 'off' : map[id] === 'block' ? 'block' : 'on';
}
// Stricter modes rank higher; moving down the ranks is loosening.
const MODE_RANK = { block: 2, on: 1, off: 0 };

// --- Tracked-sites rows -------------------------------------------------------

function toggleRow(id, name, removable) {
  const row = document.createElement('div');
  row.className = 'row';
  row.innerHTML = `
    <div class="row-text"><label for="site-${esc(id)}">${esc(name)}</label></div>
    <div class="row-ctl">
      <select class="site-toggle" id="site-${esc(id)}" data-site="${esc(id)}">
        <option value="on">On</option>
        <option value="off">Off</option>
        <option value="block">Block</option>
      </select>
      ${removable ? `<button class="x-btn" data-remove="${esc(id)}" title="Remove" aria-label="Remove">&times;</button>` : ''}
    </div>`;
  const sel = row.querySelector('select');
  siteSelects[id] = sel;
  sel.addEventListener('change', markDirty);
  if (removable) {
    row.querySelector('[data-remove]').addEventListener('click', () => removeCustom(id));
  }
  return row;
}

// Inline toggle on the YouTube row for hiding the recommendations rail.
function addSidebarToggle(row) {
  const ctl = row.querySelector('.row-ctl');
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.id = 'ytSidebar';
  btn.className = 'mini-toggle';
  btn.textContent = 'Hide sidebar';
  btn.title = 'Hide the YouTube sidebar';
  btn.setAttribute('aria-pressed', 'false');
  btn.addEventListener('click', () => {
    btn.setAttribute('aria-pressed', btn.getAttribute('aria-pressed') === 'true' ? 'false' : 'true');
    markDirty();
  });
  ctl.insertBefore(btn, ctl.firstChild);
  ytSidebarBtn = btn;
}

function buildSiteRows() {
  siteSelects = {};
  ytSidebarBtn = null;
  siteRows.innerHTML = '';
  for (const p of PLATFORMS) {
    const row = toggleRow(p.id, p.name, false);
    if (p.id === 'youtube') addSidebarToggle(row);
    siteRows.appendChild(row);
  }
  for (const c of (current.customSites || [])) {
    siteRows.appendChild(toggleRow(c.id, c.name, true));
  }
}

function applyValues() {
  fields.regen.value = Math.round((current.rechargePerMin / SEC_PER_MIN_PER_HOUR_DAY) * 10) / 10;
  fields.cap.value = Math.round(current.capacity / 60);
  fields.warn.value = Math.round((current.warnSeconds || 0) / 60);
  fields.questions.value = current.frictionCount || 1;
  for (const id of Object.keys(siteSelects)) {
    siteSelects[id].value = siteMode(current.enabledSites, id);
  }
  if (ytSidebarBtn) ytSidebarBtn.setAttribute('aria-pressed', current.hideYtSidebar ? 'true' : 'false');
  entryToastBtn.setAttribute('aria-pressed', current.showTimeLeft === false ? 'false' : 'true');
}

async function refresh() {
  current = await browser.runtime.sendMessage({ type: 'getState' });
  buildSiteRows();
  ruleDraft = (current.hourRules || []).map((r) => ({
    scope: 'all', sites: [], percent: 0, minutes: 10, ...r,
    sites: [...(r.sites || [])]
  }));
  renderRules();
  applyValues();
}

// --- Hour rules editor --------------------------------------------------------

const RULE_ACTIONS = [
  { value: 'block', label: 'Block sites' },
  { value: 'off', label: 'No battery use' },
  { value: 'recharge', label: 'Slower charging' },
  { value: 'capacity', label: 'Lower capacity' }
];

function allSites() {
  return [
    ...PLATFORMS.map((p) => ({ id: p.id, name: p.name })),
    ...(current.customSites || []).map((c) => ({ id: c.id, name: c.name }))
  ];
}

function newRuleId() {
  return 'r' + Math.random().toString(36).slice(2, 10);
}

// One rule is two lines: when and what on the first, the action's own controls on
// the second (a value, or which sites it touches).
function ruleRow(rule) {
  const row = document.createElement('div');
  row.className = 'rule';

  const line = document.createElement('div');
  line.className = 'rule-line';
  line.innerHTML = `
    <input type="time" class="rule-time" data-k="from" value="${esc(rule.from || '')}" />
    <span class="rule-sep">to</span>
    <input type="time" class="rule-time" data-k="to" value="${esc(rule.to || '')}" />
    <select class="site-toggle rule-action">
      ${RULE_ACTIONS.map((a) => `<option value="${a.value}"${a.value === rule.action ? ' selected' : ''}>${a.label}</option>`).join('')}
    </select>
    <button class="x-btn" title="Remove rule" aria-label="Remove rule">&times;</button>`;
  row.appendChild(line);

  for (const inp of line.querySelectorAll('.rule-time')) {
    inp.addEventListener('change', () => { rule[inp.dataset.k] = inp.value; markDirty(); });
  }
  line.querySelector('.rule-action').addEventListener('change', (e) => {
    rule.action = e.target.value;
    renderRules();
    markDirty();
  });
  line.querySelector('.x-btn').addEventListener('click', () => {
    ruleDraft = ruleDraft.filter((r) => r !== rule);
    renderRules();
    markDirty();
  });

  const params = document.createElement('div');
  params.className = 'rule-line rule-params';
  if (rule.action === 'recharge') {
    params.innerHTML = `
      <div class="row-input sm"><input type="number" min="0" max="100" step="5" value="${rule.percent == null ? '' : Number(rule.percent) || 0}" /><span class="unit">% speed</span></div>
      <span class="rule-hint">0 stops charging</span>`;
    params.querySelector('input').addEventListener('input', (e) => {
      rule.percent = e.target.value === '' ? null : Number(e.target.value); markDirty(); });
  } else if (rule.action === 'capacity') {
    params.innerHTML = `
      <div class="row-input sm"><input type="number" min="1" max="1440" step="1" value="${rule.minutes == null ? '' : Number(rule.minutes) || 10}" /><span class="unit">min cap</span></div>`;
    params.querySelector('input').addEventListener('input', (e) => {
      rule.minutes = e.target.value === '' ? null : Number(e.target.value); markDirty(); });
  } else {
    params.innerHTML = `
      <select class="site-toggle rule-scope">
        <option value="all"${rule.scope === 'all' ? ' selected' : ''}>All sites</option>
        <option value="only"${rule.scope === 'only' ? ' selected' : ''}>Only these</option>
        <option value="except"${rule.scope === 'except' ? ' selected' : ''}>All but these</option>
      </select>
      <span class="rule-sites${rule.scope === 'except' ? ' except' : ''}"></span>`;
    params.querySelector('.rule-scope').addEventListener('change', (e) => {
      rule.scope = e.target.value;
      renderRules();
      markDirty();
    });
    if (rule.scope !== 'all') {
      const wrap = params.querySelector('.rule-sites');
      for (const s of allSites()) {
        const pill = document.createElement('button');
        pill.type = 'button';
        pill.className = 'mini-toggle';
        pill.textContent = s.name;
        pill.setAttribute('aria-pressed', rule.sites.includes(s.id) ? 'true' : 'false');
        pill.addEventListener('click', () => {
          const on = rule.sites.includes(s.id);
          rule.sites = on ? rule.sites.filter((x) => x !== s.id) : [...rule.sites, s.id];
          pill.setAttribute('aria-pressed', on ? 'false' : 'true');
          markDirty();
        });
        wrap.appendChild(pill);
      }
    }
  }
  row.appendChild(params);
  return row;
}

function renderRules() {
  ruleRows.innerHTML = '';
  for (const rule of ruleDraft) ruleRows.appendChild(ruleRow(rule));
  ruleStatus.textContent = '';
}

// What a draft rule saves as: only the fields its action uses.
function collectRule(r) {
  const out = { id: r.id, from: r.from, to: r.to, action: r.action };
  if (r.action === 'recharge') out.percent = Math.min(100, Math.max(0, Math.round(Number(r.percent) || 0)));
  else if (r.action === 'capacity') out.minutes = Math.min(1440, Math.max(1, Math.round(Number(r.minutes) || 1)));
  else { out.scope = r.scope || 'all'; out.sites = r.scope === 'all' ? [] : [...r.sites]; }
  return out;
}

function collectRules() {
  return ruleDraft.map(collectRule);
}

// The first thing wrong with the drafted rules, or null.
function rulesProblem() {
  for (const r of ruleDraft) {
    if (!r.from || !r.to) return 'Every rule needs both times.';
    if (r.from === r.to) return 'A rule needs two different times.';
    if ((r.action === 'block' || r.action === 'off') && r.scope === 'only' && !r.sites.length) {
      return 'Pick at least one site for the "only these" rule.';
    }
    if (r.action === 'recharge'
      && (r.percent == null || !isFinite(Number(r.percent)) || Number(r.percent) < 0)) {
      return 'The slower charging rule needs a percent, 0 or more.';
    }
    if (r.action === 'capacity' && (r.minutes == null || !isFinite(Number(r.minutes)) || Number(r.minutes) < 1)) {
      return 'The lower capacity rule needs minutes.';
    }
  }
  return null;
}

// Block, slower charging, and lower capacity all limit things; a no battery use rule
// frees its sites instead.
function ruleRestrictive(r) {
  return r.action !== 'off';
}

// Dropping a restrictive rule, adding a permissive one, or editing any rule at all
// counts as loosening. Edits gate unconditionally: whether an edit tightens or loosens
// a window is too easy to argue about, and the gate is cheap.
function rulesLoosening(before, after) {
  const b = new Map(before.map((r) => [r.id, JSON.stringify(r)]));
  const a = new Map(after.map((r) => [r.id, JSON.stringify(r)]));
  for (const r of before) {
    if (!a.has(r.id)) { if (ruleRestrictive(r)) return true; }
    else if (a.get(r.id) !== b.get(r.id)) return true;
  }
  for (const r of after) {
    if (!b.has(r.id) && !ruleRestrictive(r)) return true;
  }
  return false;
}

// --- Collect / diff -----------------------------------------------------------

function collectSites() {
  const map = {};
  // Ids this page has no row for ride through a save untouched: the phone's app modes
  // (app:<package>) and whatever a future device adds. Rebuilding the map from only the
  // rendered rows used to delete them, and a deleted mode reads as tracked on the device
  // that owns it, turning a phone's Block into an On.
  for (const id of Object.keys((current && current.enabledSites) || {})) {
    if (!(id in siteSelects)) map[id] = current.enabledSites[id];
  }
  for (const id of Object.keys(siteSelects)) {
    const v = siteSelects[id].value;
    map[id] = v === 'on' ? true : v === 'off' ? false : 'block';
  }
  return map;
}

function collect() {
  return {
    rechargePerMin: Number(fields.regen.value) * SEC_PER_MIN_PER_HOUR_DAY,
    capacity: Math.round(Number(fields.cap.value) * 60),
    warnSeconds: Math.round(Number(fields.warn.value) * 60),
    enabledSites: collectSites(),
    hourRules: collectRules(),
    hideYtSidebar: ytSidebarBtn ? ytSidebarBtn.getAttribute('aria-pressed') === 'true' : !!current.hideYtSidebar,
    showTimeLeft: entryToastBtn.getAttribute('aria-pressed') === 'true',
    frictionCount: Math.round(Number(fields.questions.value))
  };
}

function collectedMode(v) {
  return v === false ? 'off' : v === 'block' ? 'block' : 'on';
}

function dirty() {
  if (!current) return false;
  const s = collect();
  if (s.rechargePerMin !== current.rechargePerMin) return true;
  if (s.capacity !== current.capacity) return true;
  if (s.warnSeconds !== current.warnSeconds) return true;
  if (s.hideYtSidebar !== !!current.hideYtSidebar) return true;
  if (s.showTimeLeft !== (current.showTimeLeft !== false)) return true;
  if (s.frictionCount !== (current.frictionCount || 1)) return true;
  if (JSON.stringify(s.hourRules) !== JSON.stringify(current.hourRules || [])) return true;
  for (const id of Object.keys(s.enabledSites)) {
    if (siteMode(current.enabledSites, id) !== collectedMode(s.enabledSites[id])) return true;
  }
  return false;
}

// Any change toward more usage: more daily charge, a bigger battery, fewer questions
// at the gate, a site dropping to a looser mode, or an hour rule giving ground. These
// need the gate.
function loosening() {
  const s = collect();
  if (s.rechargePerMin > current.rechargePerMin) return true;
  if (s.capacity > current.capacity) return true;
  if (s.frictionCount < (current.frictionCount || 1)) return true;
  for (const id of Object.keys(s.enabledSites)) {
    if (MODE_RANK[collectedMode(s.enabledSites[id])] < MODE_RANK[siteMode(current.enabledSites, id)]) return true;
  }
  if (rulesLoosening(current.hourRules || [], s.hourRules)) return true;
  return false;
}

async function commit() {
  await browser.runtime.sendMessage({ type: 'saveSettings', settings: collect() });
  await refresh();
  setStatus('Saved.', 'ok');
}

// --- Custom sites -------------------------------------------------------------

async function addCustom() {
  const host = normalizeHost(customInput.value);
  if (!host) { customStatus.textContent = 'Enter a domain like example.com'; customStatus.className = 'status err'; return; }
  // A built-in owns its subdomains (m.youtube.com is still YouTube), so a subdomain
  // must not become a second, phantom toggle.
  const builtin = PLATFORMS.find((p) =>
    p.hosts.some((h) => host === h || host.endsWith('.' + h)));
  if (builtin) { customStatus.textContent = `Covered by ${builtin.name} already.`; customStatus.className = 'status err'; return; }
  const taken = (current.customSites || []).some((c) => c.id === host);
  if (taken) { customStatus.textContent = 'That one is already tracked.'; customStatus.className = 'status err'; return; }

  const origins = [`*://${host}/*`, `*://*.${host}/*`];
  let granted = false;
  try { granted = await browser.permissions.request({ origins }); } catch (_) {}
  if (!granted) { customStatus.textContent = 'It needs permission for that site to track it.'; customStatus.className = 'status err'; return; }

  await browser.runtime.sendMessage({ type: 'addCustomSite', host });
  customInput.value = '';
  await refresh();
  customStatus.textContent = `Added ${host}. It starts tracking on that site's next load.`;
  customStatus.className = 'status ok';
}

// Removal is loosening too: an untracked site is a free site, so it takes the same
// gate as switching one off.
function removeCustom(id) {
  openGate({
    message: `Removing ${esc(id)} stops tracking it. Answer the question to remove.`,
    confirmLabel: 'Remove it',
    onSuccess: async () => {
      await browser.runtime.sendMessage({ type: 'removeCustomSite', id });
      await refresh();
      customStatus.textContent = `Removed ${id}.`;
      customStatus.className = 'status ok';
    }
  });
}

// --- Wiring -------------------------------------------------------------------

saveBtn.addEventListener('click', () => {
  // A blanked number field reads as 0, which would silently save recharge 0 (a battery
  // that never recovers), a floor capacity, or a switched off warning.
  const regen = fields.regen.value.trim();
  const cap = fields.cap.value.trim();
  const warn = fields.warn.value.trim();
  if (!regen || !cap || !warn
    || !isFinite(Number(regen)) || !isFinite(Number(cap)) || !isFinite(Number(warn))
    || Number(warn) < 0) {
    setStatus('Fill in charging speed, capacity, and the warning.', 'err');
    return;
  }
  const questions = Number(fields.questions.value.trim());
  if (!Number.isInteger(questions) || questions < 1 || questions > 5) {
    setStatus('Questions is a whole number from 1 to 5.', 'err');
    return;
  }
  const rp = rulesProblem();
  if (rp) { setStatus(rp, 'err'); ruleStatus.textContent = rp; ruleStatus.className = 'status err'; return; }
  if (!dirty()) { setStatus('Nothing to save.', null); return; }
  if (loosening()) {
    openGate({
      message: 'You\'re loosening a rule. Answer the question to save.',
      confirmLabel: 'Save it',
      onSuccess: commit
    });
  } else {
    commit();
  }
});

addBtn.addEventListener('click', addCustom);
customInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') addCustom(); });

// A new rule starts restrictive (block everything overnight), so adding and saving it
// never needs the gate. Loosening it from there does.
addRuleBtn.addEventListener('click', () => {
  ruleDraft.push({
    id: newRuleId(), from: '22:00', to: '07:00',
    action: 'block', scope: 'all', sites: [], percent: 0, minutes: 10
  });
  renderRules();
  markDirty();
});

let gateReturnFocus = null;

function onGateKeydown(e) {
  if (e.key === 'Escape') { e.preventDefault(); closeGate(); }
}

function openGate(opts) {
  gateReturnFocus = document.activeElement;
  backdrop.hidden = false;
  activeGate = FrictionGate.mount(gateHost, {
    title: 'Confirm the change',
    message: opts.message,
    count: current.frictionCount || 1, // the same flat toll as the block overlay
    confirmLabel: opts.confirmLabel,
    cancelLabel: 'Back',
    onCancel: closeGate,
    onSuccess: async () => {
      closeGate();
      await opts.onSuccess();
    }
  });
  document.addEventListener('keydown', onGateKeydown);
  const first = gateHost.querySelector('input, textarea');
  if (first) first.focus();
}

function closeGate() {
  document.removeEventListener('keydown', onGateKeydown);
  if (activeGate) { activeGate.destroy(); activeGate = null; }
  backdrop.hidden = true;
  if (gateReturnFocus && typeof gateReturnFocus.focus === 'function') gateReturnFocus.focus();
  gateReturnFocus = null;
}

Object.values(fields).forEach((f) => {
  f.addEventListener('change', markDirty);
  f.addEventListener('input', markDirty);
});

refresh();
