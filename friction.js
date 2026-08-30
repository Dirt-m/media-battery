// The friction confirmation gate.
//
// Muscle memory only breaks if the challenge changes every attempt, so the gate draws
// small tasks at random from a pool and regenerates them on every failed try: retype a
// phrase, arithmetic with precedence, add three numbers, a power of two, sort numbers,
// tally two letters across a block of text. None can be pasted.
//
// opts.count is how many questions, flat: one from the block overlay, one from the
// settings page when loosening. opts.askWhy also demands a typed reason.
//
// Exposed as window.FrictionGate.mount(container, opts). `container` may be a shadow
// root (block overlay) or a normal element (options page), so the styles are injected
// scoped into it.

(function () {
  const WORDS = [
    'amber', 'basalt', 'cedar', 'dusk', 'ember', 'fjord', 'gravel', 'harbor',
    'ivory', 'jade', 'kelp', 'lantern', 'marble', 'nimbus', 'onyx', 'pebble',
    'quartz', 'rivet', 'slate', 'tundra', 'umber', 'velvet', 'willow', 'zephyr',
    'copper', 'meadow', 'cobalt', 'thistle', 'cinder', 'lichen'
  ];

  const rint = (n) => Math.floor(Math.random() * n);

  function shuffle(arr) {
    for (let i = arr.length - 1; i > 0; i--) {
      const j = rint(i + 1);
      [arr[i], arr[j]] = [arr[j], arr[i]];
    }
    return arr;
  }

  // n distinct words in random order.
  const sampleWords = (n) => shuffle(WORDS.slice()).slice(0, n);
  // Collapse any input into single spaced lower case tokens for order comparisons.
  const normList = (s) => s.trim().toLowerCase().split(/\s+/).filter(Boolean).join(' ');
  const wordsIn = (s) => s.trim().split(/\s+/).filter(Boolean).length;

  // --- Retype a longish phrase exactly -----------------------------------------
  // 4 words plus a 2 digit number, one word upper cased so letter case matters. Sits
  // in the pool with the tasks below.
  function phraseTask() {
    const words = sampleWords(4);
    const upper = rint(4);
    const parts = words.map((w, i) => (i === upper ? w.toUpperCase() : w));
    parts.splice(rint(parts.length + 1), 0, String(10 + rint(90)));
    const p = parts.join('-');
    return { label: 'Type this exactly (no pasting)', target: p, kind: 'text',
      check: (v) => v === p };
  }

  // --- The rest of the task pool -----------------------------------------------
  // Each one costs real thought, changes every time, and validates unambiguously.
  // Returns { label, target, kind, check }: `target` is an optional monospace block to
  // read from, `kind` 'numeric' shows a number keypad.

  const TASKS = [
    // Precedence matters, so there's no single step shortcut.
    function arithmetic() {
      const a = 3 + rint(7), b = 3 + rint(7);
      const plus = rint(2) === 0;
      const c = 2 + rint(plus ? 18 : Math.max(1, a * b - 3)); // minus stays non-negative
      const answer = plus ? a * b + c : a * b - c;
      return { label: `What's ${a} × ${b} ${plus ? '+' : '−'} ${c}?`, target: null,
        kind: 'numeric', check: (v) => v.trim() === String(answer) };
    },
    // Add three 2-digit numbers in your head.
    function sumList() {
      const nums = Array.from({ length: 3 }, () => 10 + rint(90));
      const answer = nums.reduce((s, n) => s + n, 0);
      return { label: 'Add all of these together', target: nums.join('   '),
        kind: 'numeric', check: (v) => v.trim() === String(answer) };
    },
    // High enough that you have to double your way up.
    function powerOfTwo() {
      const n = 4 + rint(9); // exponent 4..12
      const answer = Math.pow(2, n);
      return { label: `What's 2 to the power of ${n}?`, target: null,
        kind: 'numeric', check: (v) => v.trim() === String(answer) };
    },
    // Long enough that you can't eyeball the order.
    function sortDescending() {
      const nums = [];
      while (nums.length < 7) { const n = 10 + rint(90); if (!nums.includes(n)) nums.push(n); }
      const sorted = [...nums].sort((a, b) => b - a).join(' ');
      return { label: 'Type these numbers largest to smallest',
        target: shuffle(nums.slice()).join('   '), kind: 'text',
        check: (v) => normList(v) === sorted };
    },
    // Scan a block of text and tally two letters at once.
    function countLetters() {
      const text = sampleWords(8).join(' ');
      const present = [...new Set(text.replace(/[^a-z]/g, ''))];
      const [x, y] = shuffle(present).slice(0, 2);
      const n = text.split('').filter((ch) => ch === x || ch === y).length;
      return { label: `How many times do the letters "${x}" and "${y}" appear below, combined?`,
        target: text, kind: 'numeric', check: (v) => v.trim() === String(n) };
    }
  ];

  // Drawn at random from the whole pool, phrase retype included, so even a one
  // question gate asks something different each time. Cycles if n exceeds the pool.
  function buildQuestions(n) {
    const pool = shuffle([phraseTask, ...TASKS]);
    const out = [];
    for (let i = 0; i < n; i++) out.push(pool[i % pool.length]());
    return out;
  }

  const STYLE = `
    /* Palette and components mirror options.css. Sizes are variables: rem defaults for
       the options page, a px .lg scale for the block overlay, which can't rely on the
       host page's root font-size. */
    .ftg { box-sizing: border-box; font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
           color: #e8eaed; text-align: left;
           --sb-ink: #e8eaed; --sb-muted: #9aa3ad; --sb-line: #2b323b; --sb-accent: #2fa45a;
           --sb-field: #0f1318;
           --fs-title: 1.4rem; --fs-msg: 0.95rem; --fs-label: 1.05rem; --fs-target: 1.05rem;
           --fs-input: 0.98rem; --fs-btn: 0.95rem; --fs-status: 0.88rem; }
    .ftg.lg { --fs-title: 18px; --fs-msg: 13px; --fs-label: 15px; --fs-target: 15px;
              --fs-input: 15px; --fs-btn: 14px; --fs-status: 13px; }
    .ftg * { box-sizing: border-box; }
    /* Inputs are nested inside their label so assistive tech gets the association. */
    .ftg-field > label { display: block; }
    .ftg-title { font-size: var(--fs-title); font-weight: 800; letter-spacing: -.01em;
                 margin: 0 0 8px; color: var(--sb-ink); }
    .ftg-msg { font-size: var(--fs-msg); line-height: 1.5; color: var(--sb-muted); margin: 0 0 22px; }
    .ftg-msg b { color: var(--sb-ink); }
    .ftg-field { margin-bottom: 18px; }
    .ftg-label { display: block; font-size: var(--fs-label); font-weight: 700;
                 color: var(--sb-ink); margin-bottom: 8px; line-height: 1.4; }
    .ftg-target { display: inline-block; font-size: var(--fs-target); font-weight: 700;
                  color: var(--sb-ink); background: var(--sb-field);
                  border: 2px solid var(--sb-line); border-radius: 10px; padding: 10px 12px;
                  user-select: none; -webkit-user-select: none; margin-bottom: 8px; }
    .ftg-input { width: 100%; font-size: var(--fs-input); font-family: inherit; padding: 10px 12px;
                 border: 2px solid var(--sb-line); border-radius: 10px; background: var(--sb-field);
                 color: var(--sb-ink); outline: none; }
    /* Focus is neutral. Green is reserved for the why box hitting its word count;
       answer inputs are never marked before submit. */
    .ftg-input:focus { border-color: #8b98a5; }
    .ftg-why { width: 100%; min-height: 74px; resize: vertical; font-size: var(--fs-input);
               font-family: inherit; line-height: 1.5; padding: 10px 12px;
               border: 2px solid var(--sb-line); border-radius: 10px; background: var(--sb-field);
               color: var(--sb-ink); outline: none; }
    .ftg-why:focus { border-color: #8b98a5; }
    .ftg-why.ok { border-color: #36c06f; }
    .ftg-why-count { display: block; font-size: var(--fs-status); color: var(--sb-muted); margin-top: 6px; }
    .ftg-why-count.ok { color: #36c06f; }
    .ftg-actions { display: flex; gap: 14px; align-items: center; margin-top: 24px; }
    .ftg-btn { font-size: var(--fs-btn); font-weight: 700; border: 0; cursor: pointer; }
    /* Dark ink on the green; white only reached 3.2:1 on it. */
    .ftg-confirm { background: var(--sb-accent); color: #0f1115; padding: 12px 22px; border-radius: 9px; }
    .ftg-confirm:hover:not(:disabled) { background: #36c06f; }
    .ftg-confirm:disabled { opacity: .5; cursor: default; }
    .ftg-cancel { background: transparent; color: var(--sb-ink); border: 2px solid var(--sb-line);
                  padding: 11px 18px; border-radius: 10px; }
    .ftg-cancel:hover { border-color: #3a4450; }
    .ftg-status { font-size: var(--fs-status); font-weight: 700; color: var(--sb-muted);
                  margin-left: auto; text-align: right; }
    .ftg-status.err { color: #ef5350; }
  `;

  // Stop every paste/copy/drop route so answers must actually be typed.
  function lockInput(el) {
    ['paste', 'drop', 'copy', 'cut', 'contextmenu'].forEach((ev) =>
      el.addEventListener(ev, (e) => e.preventDefault()));
  }

  function mount(container, opts) {
    opts = opts || {};
    const count = Math.max(1, opts.count || 1);
    const askWhy = !!opts.askWhy;
    const whyMin = askWhy ? Math.max(1, opts.whyMinWords || 1) : 0;
    // The container's own document, since this mounts in a page or in a shadow root.
    const doc = (container && container.ownerDocument) || document;

    const root = doc.createElement('div');
    root.className = opts.large ? 'ftg lg' : 'ftg';
    const style = doc.createElement('style');
    style.textContent = STYLE;
    root.appendChild(style);

    root.insertAdjacentHTML('beforeend', `
      <div class="ftg-title">${escapeHtml(opts.title || 'Confirm')}</div>
      ${/* message is the one HTML capable slot: callers escape their own user data */ ''}
      ${opts.message ? `<div class="ftg-msg">${opts.message}</div>` : ''}
      ${askWhy ? `
      <div class="ftg-field">
        <label>
          <span class="ftg-label">Why are you coming back?</span>
          <textarea class="ftg-why" data-role="why" autocomplete="off" spellcheck="false"
                    placeholder="At least ${whyMin} words"></textarea>
        </label>
        <span class="ftg-why-count" data-role="why-count"></span>
      </div>` : ''}
      <div data-role="questions"></div>
      <div class="ftg-actions">
        <button class="ftg-btn ftg-confirm" data-role="confirm">${escapeHtml(opts.confirmLabel || 'Confirm')}</button>
        ${opts.onCancel ? `<button class="ftg-btn ftg-cancel" data-role="cancel">${escapeHtml(opts.cancelLabel || 'Cancel')}</button>` : ''}
        <span class="ftg-status" data-role="status"></span>
      </div>
    `);
    container.appendChild(root);

    const q = (r) => root.querySelector(`[data-role="${r}"]`);
    const questionsWrap = q('questions');
    const confirmBtn = q('confirm');
    const cancelBtn = q('cancel');
    const status = q('status');
    const whyInput = askWhy ? q('why') : null;
    const whyCount = askWhy ? q('why-count') : null;
    if (whyInput) lockInput(whyInput);

    let questions = [];
    let inputs = [];

    const whyOk = () => !askWhy || wordsIn(whyInput.value) >= whyMin;

    function newChallenge() {
      questions = buildQuestions(count);
      questionsWrap.innerHTML = '';
      inputs = questions.map((qn, i) => {
        const field = doc.createElement('div');
        field.className = 'ftg-field';
        field.insertAdjacentHTML('beforeend', `
          <label>
            <span class="ftg-label">${escapeHtml(qn.label)}</span>
            ${qn.target != null ? `<span class="ftg-target">${escapeHtml(qn.target)}</span>` : ''}
            <input class="ftg-input" type="text" autocomplete="off" autocapitalize="off"
                   autocorrect="off" spellcheck="false"
                   inputmode="${qn.kind === 'numeric' ? 'numeric' : 'text'}" />
          </label>
        `);
        questionsWrap.appendChild(field);
        const inp = field.querySelector('input');
        lockInput(inp);
        return inp;
      });
      if (askWhy && whyInput) whyInput.focus();
      else if (inputs[0]) inputs[0].focus();
    }

    // Answers are checked only on submit. Live marking would be an oracle: the
    // counting tasks become "increment until the border turns green". The why box does
    // update live, since a word count gives nothing away.
    function updateWhy() {
      if (!askWhy) return;
      const n = wordsIn(whyInput.value);
      const ok = n >= whyMin;
      whyInput.classList.toggle('ok', ok);
      whyCount.classList.toggle('ok', ok);
      whyCount.textContent = ok ? `${n} words` : `${n}/${whyMin} words`;
    }

    if (askWhy) whyInput.addEventListener('input', updateWhy);

    confirmBtn.addEventListener('click', (e) => {
      // Only a real click may pass the gate; a page script firing click() must not.
      if (!e.isTrusted) return;
      const challengesOk = questions.every((qn, i) => qn.check(inputs[i].value));
      if (challengesOk && whyOk()) { if (opts.onSuccess) opts.onSuccess(); return; }
      status.classList.add('err');
      if (challengesOk && !whyOk()) {
        // Answers are right, just say more. Don't throw them away for this.
        status.textContent = `Say a bit more (at least ${whyMin} words).`;
        whyInput.focus();
        return;
      }
      // Something's wrong. Fresh set so you start over.
      status.textContent = "Not quite. Here's a fresh set.";
      newChallenge();
    });

    if (cancelBtn) cancelBtn.addEventListener('click', (e) => {
      if (!e.isTrusted) return;
      opts.onCancel();
    });

    newChallenge();
    updateWhy();

    return { destroy() { root.remove(); } };
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  (globalThis).FrictionGate = { mount };
})();
