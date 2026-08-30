# Media Battery

A battery for the apps and sites that eat your day. Time in them drains it, time away
refills it, and at empty they're blocked for ten minutes. Getting back in early is
possible but annoying on purpose.

It comes in three parts that share one battery:

- an Android app (`android/`) that applies it to any app on the phone
- a Firefox extension (this directory) for the sites, on desktop and on Android
- a small sync server (`server/`) so one code gives one charge across every device

You can use either client on its own. Sync is optional.

## Install

### Android

Download the APK from the [releases page](https://github.com/Dirt-m/media-battery/releases)
and install it. Android 10 or newer.

To get updates automatically, add this repository's URL in
[Obtainium](https://github.com/ImranR98/Obtainium). It follows the release feed.

It isn't on F-Droid yet. The listing text is already in `android/fastlane/`, and the
`full` flavor is the one meant for it.

The app asks for six permissions on first launch and explains each one. Usage access,
display over other apps, and notifications are needed for it to work at all; the rest
can be skipped. Details in [android/README.md](android/README.md#permissions).

### Firefox

Install it from [addons.mozilla.org](https://addons.mozilla.org/en-US/firefox/addon/media-battery/).
Firefox for Android (142 or newer) runs the same extension; the popup lives in the
extensions menu there, and there is no toolbar badge.

To run it from source instead, open `about:debugging#/runtime/this-firefox`, click
Load Temporary Add-on, and pick `manifest.json`. A temporary add-on unloads when
Firefox restarts, but the charge and settings survive.

## How it works

You have a charge measured in seconds. It drains one second per second while you're
in a tracked app or site, and recharges at a set rate while you're not, up to a cap.
The defaults are a 30 minute battery that refills at 2 hours per day. A corner
warning appears when the charge drops under a threshold (5 minutes by default), and
opening a tracked app or site shows the time left for a moment.

Only real use counts. In the browser that means the tab is on screen and you're
either interacting with it or a video is playing with sound. Step away for a minute
with nothing audible playing and the drain pauses, so a tab left open in the
background doesn't burn your charge. Muted autoplay never counts. On the phone it
means the app is in front with the screen on and unlocked, or a tracked app is
playing sound with the screen off.

At zero everything tracked is blocked and any media pauses. The battery is dead for a
fixed ten minutes of real time. It keeps recharging through the cooldown, so when the
block lifts you have a little charge banked instead of dying again the moment you open
something.

If you genuinely need something sooner, spend reserve. It adds five minutes and
revives everything at once, and it costs a task drawn fresh each time (retype a
phrase with pasting disabled, some arithmetic, sort a list, count letters in a block
of text) plus a line on why you're coming back. Get anything wrong and the set is
regenerated. The cost is always the same: an escalating toll just invites turning the
whole thing off.

On a YouTube video the extension instead offers to unblock that one video, with the
recommendations sidebar removed, and leaves the battery dead.

### Sites, apps, and modes

The extension ships with YouTube, Reddit, Instagram, TikTok, X, Facebook, Twitch, and
LinkedIn, and you can add any site by domain. The app tracks whatever apps you pick.
Each one is on, off, or blocked: off never drains and never blocks, blocked shows the
block screen no matter the charge. The gate on a blocked app or site buys five minutes
on that one and adds no charge.

Loosening anything in settings (a looser mode, a higher capacity, faster charging,
removing or editing an hour rule) goes through the same gate, one question, no line
on why.

### Hours

Rules that hold between two times of day, every day, on the device's own clock. A rule
can block apps and sites (all, only some, or all but some), stop counting the battery
on some of them, slow or stop the charging, or lower the capacity. Charging stopped
overnight banks nothing whether the device was awake or not: the math walks the rule
windows across any gap it slept through.

Adding a restrictive rule is free. Removing or editing one costs the question.

### Sync

Get a code on one device and enter it on the others. Everything derives from that one
code: the routing id, the auth token, and the encryption key. The server only ever
stores ciphertext and has no accounts, so losing the code means losing the profile.

The one rule that makes sharing safe is that a sync can only lower a device's charge,
never raise it. Whichever device is being used writes the charge; the others mirror
it. Two idle devices can't both recharge the same battery, and a stale value can't
clobber a drained one.

The default server is a small instance run by the author. You can point both clients
at your own; see [server/README.md](server/README.md).

## What it can't do

It can't stop someone who has decided to get around it. The extension can be disabled
from `about:addons` and its stored charge edited; the app can be uninstalled in ten
seconds. What it does stop is opening a feed out of muscle memory and looking up an
hour later.

## Repository layout

The extension is plain JavaScript with no build step. `node --test` runs its suite
(Node 22).

- `platforms.js`, the built-in list of tracked sites
- `rules.js`, hour rule evaluation, shared by the background, content, and settings
- `projection.js`, what a sync anchor implies the charge is right now
- `background.js`, the timekeeper; everything else reports to it or renders its state
- `content.js`, runs on tracked sites: reports use, draws the block screen
- `friction.js`, the question gate, shared by the block screen and settings
- `sync.js`, the sync client; `sync.html` and `sync-page.js`, the sync page
- `popup.*`, the gauge; `options.*`, settings; `welcome.html`, first run
- `tests/`, the node suites and `gen-interop-fixtures.js`, which pins the wire format
  the two clients share

`android/` is the app (see its [README](android/README.md)) and `server/` the sync
server (Go, one binary, SQLite). Each has its own CI workflow under `.github/`.
