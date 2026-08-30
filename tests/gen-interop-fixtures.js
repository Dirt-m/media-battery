// Golden vectors for the sync wire format, generated from the real sync.js.
//
// The Android port re-implements the same crypto in Kotlin, and only real JS output can
// prove the two agree. This writes what the extension produces for frozen inputs: five
// codes from fixed byte patterns, four blobs under fixed IVs so the ciphertext is
// reproducible byte for byte, numbers that pin JS's double to string rendering, and
// settings documents that pin how a sync doc serializes.
//
// Run from the repo root:
//   node tests/gen-interop-fixtures.js            regenerate the vectors
//   node tests/gen-interop-fixtures.js --check    fail if the committed file has drifted
//   node tests/gen-interop-fixtures.js path.json  write somewhere else
//
// Drift means the wire format changed, which orphans every existing sync profile and
// every Android device. Only regenerate alongside a deliberate format bump.
//
// No dependencies. crypto.getRandomValues is stubbed while encrypting so the IVs are the
// fixed ones; everything else is real WebCrypto.

'use strict';

const fs = require('node:fs');
const path = require('node:path');

const DEFAULT_OUT = path.join(
  __dirname, '..', 'android', 'core', 'src', 'test', 'resources', 'interop', 'js-vectors.json'
);

// --- frozen inputs ------------------------------------------------------------

// 16 bytes each: the two edges, a counting pattern, and two arbitrary but fixed ones.
const CODE_PATTERNS = [
  '00000000000000000000000000000000',
  'ffffffffffffffffffffffffffffffff',
  '000102030405060708090a0b0c0d0e0f',
  '9f1c3b7e2a5d48c60f9e13a7b5d2c840',
  '3d5a90c4e7112f86bb04d9773ce1a258'
];

// The two docs sync writes. The anchor carries the fields background.js getAnchor emits,
// in that order, hand synced with background.js. The settings doc carries hideYtSidebar,
// a key Android has no use for and must still round-trip, plus a fractional number,
// since number formatting is part of the plaintext.
const CHARGE_DOC = {
  charge: 1234.5,
  asOf: 1735689600000,
  draining: true,
  depleted: false,
  depletedAt: null,
  writer: 'a1b2c3d4'
};

const SETTINGS_DOC = {
  keys: {
    capacity: { value: 1800, ts: 1735689600000 },
    rechargePerMin: { value: 3.75, ts: 1735689500000 },
    hideYtSidebar: { value: true, ts: 1735689400000 },
    warnSeconds: { value: 300, ts: 1735689300000 }
  }
};

// Blobs for the third and fifth code, both docs, each with its own fixed IV.
const BLOB_CASES = [
  { pattern: 2, doc: 'charge', iv: '000102030405060708090a0b', obj: CHARGE_DOC },
  { pattern: 2, doc: 'settings', iv: '0b0a090807060504030201ff', obj: SETTINGS_DOC },
  { pattern: 4, doc: 'charge', iv: '112233445566778899aabbcc', obj: CHARGE_DOC },
  { pattern: 4, doc: 'settings', iv: 'ccbbaa998877665544332211', obj: SETTINGS_DOC }
];

// Pins JSON.stringify's double rendering: an integral double prints without a
// fraction (1800, not 1800.0), a fractional one prints the shortest string that round
// trips back to the same double, and the exponent thresholds (>= 1e21 and < 1e-6)
// switch to exponential form. Android's own formatter has to land on the same strings.
const NUMBER_VALUES = [
  0, -0, 1, 5, 300, 1800, 3.75, 100.5, -12.5, 0.1, 1 / 3, 2 / 3, 1e-6, 1e-7,
  1735689600000, 1e20, 1e21, 1.7976931348623157e308, 5e-324, 1234.5, 0.000123,
  123456789012345680000
];

// Documents shaped like sync.js's localSettingsDoc (an ordered map key -> { value, ts },
// that property order). "typical" is the extension's seven synced keys, hour rules
// included in the shape sanitizeRules() emits. "fractional" pins an integral double
// still printing as 1800, not 1800.0. "foreign" pins that a device re-emits a key it
// does not recognize byte for byte.
const SETTINGS_FIXTURES = [
  {
    name: 'typical',
    entries: [
      { key: 'capacity', kind: 'number', ts: 1735689600000, value: 1800 },
      { key: 'rechargePerMin', kind: 'number', ts: 1735689500000, value: 5 },
      { key: 'warnSeconds', kind: 'number', ts: 1735689400000, value: 300 },
      { key: 'hideYtSidebar', kind: 'boolean', ts: 1735689300000, value: false },
      {
        key: 'enabledSites', kind: 'verbatim', ts: 1735689200000,
        // The app: id pins that a phone's app modes ride this map like any site.
        value: { youtube: true, instagram: false, reddit: 'block', 'app:com.vinted': 'block' }
      },
      {
        key: 'customSites', kind: 'verbatim', ts: 1735689100000,
        value: [{ id: 'c1', name: 'Hacker News', host: 'news.ycombinator.com' }]
      },
      {
        key: 'hourRules', kind: 'verbatim', ts: 1735689000000,
        value: [
          { id: 'r1', from: '22:00', to: '07:00', action: 'block', scope: 'all', sites: [] },
          { id: 'r2', from: '09:00', to: '17:00', action: 'recharge', percent: 0 }
        ]
      }
    ]
  },
  {
    name: 'fractional',
    entries: [
      { key: 'rechargePerMin', kind: 'number', ts: 1735689600000, value: 3.75 },
      { key: 'capacity', kind: 'number', ts: 1, value: 1800 },
      { key: 'warnSeconds', kind: 'number', ts: 0, value: 0 }
    ]
  },
  {
    name: 'foreign',
    entries: [
      { key: 'capacity', kind: 'number', ts: 10, value: 1800 },
      {
        key: 'androidTrackedApps', kind: 'verbatim', ts: 20,
        value: ['com.example.one', 'com.example.two']
      },
      { key: 'someFutureFlag', kind: 'verbatim', ts: 30, value: 'on' }
    ]
  }
];

// Anchors as the wire carries them, pinning the document and not just the crypto: the
// six getAnchor fields in emission order, a fractional charge, a set and a null
// depletedAt, a null writer. Kotlin has to encode each to the exact JSON string and
// decode it back, so a field rename or a number rendering change fails a build.
const ANCHOR_FIXTURES = [
  {
    name: 'draining',
    anchor: {
      charge: 1234.5, asOf: 1735689600000, draining: true, depleted: false,
      depletedAt: null, writer: 'a1b2c3d4'
    }
  },
  {
    name: 'depleted',
    anchor: {
      charge: 0, asOf: 1735689650000, draining: false, depleted: true,
      depletedAt: 1735689640123, writer: 'ffeeddcc00112233'
    }
  },
  {
    name: 'bare',
    anchor: {
      charge: 1800, asOf: 1735689700000, draining: false, depleted: false,
      depletedAt: null, writer: null
    }
  }
];

// --- helpers ------------------------------------------------------------------

const fromHex = (hex) =>
  new Uint8Array(hex.match(/../g).map((h) => parseInt(h, 16)));

const toB64 = (bytes) => Buffer.from(bytes).toString('base64');

// IEEE754 big-endian bits as hex, so Kotlin can compare its own Double bit pattern
// against JS's without going through either side's string formatter.
function numberToBitsHex(v) {
  const b = Buffer.alloc(8);
  b.writeDoubleBE(v);
  return b.toString('hex');
}

// Rebuilds the doc sync.js writes ({ keys: { key: { value, ts } } }, that property
// order) from a fixture's entries, so entriesJson and json can never drift apart.
function settingsDocFromEntries(entries) {
  const doc = {};
  for (const e of entries) doc[e.key] = { value: e.value, ts: e.ts };
  return doc;
}

// sync.js reads the global crypto every call, so swapping the global swaps the IV
// source. subtle stays the real one.
function withFixedIv(ivHex, fn) {
  const real = globalThis.crypto;
  const iv = fromHex(ivHex);
  const stub = {
    subtle: real.subtle,
    getRandomValues: (arr) => { arr.set(iv.subarray(0, arr.length)); return arr; }
  };
  Object.defineProperty(globalThis, 'crypto', { value: stub, configurable: true });
  return Promise.resolve()
    .then(fn)
    .finally(() => {
      Object.defineProperty(globalThis, 'crypto', {
        get: () => real, configurable: true, enumerable: true
      });
    });
}

async function build() {
  require(path.join(__dirname, '..', 'sync.js')); // IIFE, installs globalThis.Sync
  const I = globalThis.Sync._internals;

  const codes = [];
  for (const hex of CODE_PATTERNS) {
    const code = I.base32Encode(fromHex(hex));
    const keys = await I.deriveKeys(code);
    codes.push({
      codeBytesHex: hex,
      code,
      formatted: I.formatCode(code),
      routingId: keys.routingId,
      authToken: keys.authToken
    });
  }

  const blobs = [];
  for (const c of BLOB_CASES) {
    const code = codes[c.pattern].code;
    const { aesKey } = await I.deriveKeys(code);
    const blob = await withFixedIv(c.iv, () => I.encryptDoc(aesKey, c.doc, c.obj));
    if (toB64(blob.slice(0, 12)) !== toB64(fromHex(c.iv))) {
      throw new Error('the IV stub did not take, refusing to emit a random vector');
    }
    // Decrypt it back through the real code as a sanity check on the stub.
    const back = await I.decryptDoc(aesKey, c.doc, blob);
    if (back === null) throw new Error('generated blob does not decrypt');
    blobs.push({
      codeBytesHex: CODE_PATTERNS[c.pattern],
      code,
      doc: c.doc,
      ivHex: c.iv,
      plaintext: JSON.stringify(c.obj),
      blobB64: toB64(blob)
    });
  }

  const numbers = NUMBER_VALUES.map((v) => ({
    bitsHex: numberToBitsHex(v),
    json: JSON.stringify(v)
  }));

  const settingsDocs = SETTINGS_FIXTURES.map((f) => {
    const entries = f.entries.map((e) => ({
      key: e.key, kind: e.kind, ts: e.ts, valueJson: JSON.stringify(e.value)
    }));
    const doc = settingsDocFromEntries(f.entries);
    return {
      name: f.name,
      entriesJson: JSON.stringify(entries),
      json: JSON.stringify({ keys: doc })
    };
  });

  const anchorDocs = ANCHOR_FIXTURES.map((f) => ({
    name: f.name,
    anchor: f.anchor,
    json: JSON.stringify(f.anchor)
  }));

  return JSON.stringify({ codes, blobs, numbers, settingsDocs, anchorDocs }, null, 2) + '\n';
}

async function main() {
  const args = process.argv.slice(2);
  const check = args.includes('--check');
  const outArg = args.find((a) => !a.startsWith('--'));
  const out = outArg ? path.resolve(outArg) : DEFAULT_OUT;

  const generated = await build();

  if (check) {
    let existing;
    try {
      existing = fs.readFileSync(out, 'utf8');
    } catch (_) {
      console.error(`missing vectors file: ${out}`);
      process.exit(1);
    }
    if (existing !== generated) {
      console.error(`sync wire format drift: ${out} does not match sync.js`);
      process.exit(1);
    }
    console.log(`ok, vectors match sync.js: ${out}`);
    return;
  }

  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, generated);
  console.log(`wrote ${out}`);
}

main().catch((e) => { console.error(e); process.exit(1); });
