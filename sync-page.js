// The sync settings page: get a code (turns sync on), join another device's battery
// with its code, pick the server, watch the status card. Setup only, so no friction
// gate. The actual sync work is in sync.js; this page just messages the background.

const browser = globalThis.browser || globalThis.chrome;
const $ = (id) => document.getElementById(id);

const statusDot = $('statusDot');
const statusLabel = $('statusLabel');
const statusDetail = $('statusDetail');
const syncOffBtn = $('syncOff');
const getCodeRow = $('getCodeRow');
const getCodeBtn = $('getCode');
const codeRow = $('codeRow');
const syncCodeInput = $('syncCode');
const syncCopyBtn = $('syncCopy');
const syncJoin = $('syncJoin');
const syncJoinBtn = $('syncJoinBtn');
const syncServer = $('syncServer');
const syncServerSave = $('syncServerSave');
const syncMsg = $('syncMsg');

// Action feedback (bad code, server errors) moves under the row that caused it, so it
// can't land below the Advanced fold. Steady state lives in the status card up top.
const syncMsgHome = syncMsg.parentElement;
function setMsg(text, kind, nearEl) {
  const block = (nearEl && nearEl.closest('.sync-block')) || syncMsgHome;
  if (syncMsg.parentElement !== block) block.appendChild(syncMsg);
  syncMsg.textContent = text;
  syncMsg.className = 'status sync-status' + (kind ? ' ' + kind : '');
}

function relAge(ms) {
  if (ms < 45000) return 'just now';
  if (ms < 3600000) return Math.round(ms / 60000) + ' min ago';
  return Math.round(ms / 3600000) + ' h ago';
}

// Grow a code textarea to fit its content, so nobody copies half a code by hand.
function fit(ta) {
  ta.style.height = 'auto';
  ta.style.height = ta.scrollHeight + 'px';
}

function render(s) {
  const on = !!(s && s.on);

  let dot = 'off', label = 'Off', detail = 'Each device keeps its own battery.';
  if (on) {
    if (s.state === 'offline') { dot = 'offline'; label = 'Offline'; detail = 'Retrying.'; }
    else if (s.state === 'syncing') { dot = 'syncing'; label = 'Syncing'; detail = ''; }
    else { dot = 'synced'; label = 'Synced'; detail = s.lastSyncTs ? relAge(Math.max(0, Date.now() - s.lastSyncTs)) : ''; }
  }
  statusDot.dataset.state = dot;
  statusLabel.textContent = label;
  statusDetail.textContent = detail;

  syncOffBtn.hidden = !on;
  getCodeRow.hidden = on;
  codeRow.hidden = !on;
  if (on && document.activeElement !== syncCodeInput) {
    syncCodeInput.value = s.code || '';
    fit(syncCodeInput);
  }
  if (document.activeElement !== syncServer) syncServer.value = (s && s.serverUrl) || '';
}

async function refresh() {
  try {
    render(await browser.runtime.sendMessage({ type: 'syncStatus' }));
  } catch (_) { /* background waking up */ }
}

getCodeBtn.addEventListener('click', async () => {
  getCodeBtn.disabled = true;
  try {
    await browser.runtime.sendMessage({ type: 'syncEnable' });
  } finally {
    getCodeBtn.disabled = false;
  }
  setMsg('');
  await refresh();
});

syncOffBtn.addEventListener('click', async () => {
  if (!confirm('Turn off sync on this device? Your code stays valid on your other devices.')) return;
  await browser.runtime.sendMessage({ type: 'syncUnlink' });
  setMsg('');
  await refresh();
});

syncCopyBtn.addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(syncCodeInput.value);
    syncCopyBtn.textContent = 'Copied';
    setTimeout(() => { syncCopyBtn.textContent = 'Copy'; }, 1500);
  } catch (_) {
    syncCodeInput.select();
  }
});

async function join() {
  const code = syncJoin.value.trim();
  if (!code) return;
  if (!confirm('Joining replaces this device\'s battery and settings with the shared one.')) return;
  syncJoinBtn.disabled = true;
  try {
    const r = await browser.runtime.sendMessage({ type: 'syncLink', code });
    if (r && r.ok) {
      syncJoin.value = '';
      setMsg('');
      await refresh();
    } else {
      const err = r && r.error;
      setMsg(err === 'badCode' ? 'That code is not valid.'
        : err === 'noProfile' ? 'No battery on the server for that code. Check it for typos.'
        : 'Could not reach the server.', 'err', syncJoinBtn);
    }
  } finally {
    syncJoinBtn.disabled = false;
  }
}

syncJoinBtn.addEventListener('click', join);
syncJoin.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); join(); }
});
syncJoin.addEventListener('input', () => fit(syncJoin));

syncServerSave.addEventListener('click', async () => {
  const url = syncServer.value.trim();
  if (url) {
    let origin;
    try { origin = new URL(url).origin + '/*'; }
    catch (_) { setMsg('Enter a full URL like https://sync.example.com', 'err', syncServerSave); return; }
    let granted = false;
    try { granted = await browser.permissions.request({ origins: [origin] }); } catch (_) {}
    if (!granted) { setMsg('That server needs permission.', 'err', syncServerSave); return; }
  }
  await browser.runtime.sendMessage({ type: 'syncSetServer', url });
  setMsg('');
  await refresh();
});

refresh();
setInterval(refresh, 3000);
