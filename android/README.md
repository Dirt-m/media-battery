# Media Battery for Android

The Android side of Media Battery: the same self recharging battery as the Firefox
extension, applied to whichever apps you pick. It shares the sync protocol, the server,
and the battery math with the extension at the repo root, so one sync code gives one
battery across the phone and the desktop.

Installing it is covered in the [top level README](../README.md). This file is about
building it.

## Layout

Two Gradle modules.

`core/` is plain JVM Kotlin with no Android dependencies: the battery engine, hour
rules, sync projection, sync crypto and wire format, the friction question pool, and
the package to platform id table. The extension's node test suites are ported here
and run with `./gradlew :core:test`, no SDK or emulator needed.

`app/` is the app itself (Kotlin, Jetpack Compose, minSdk 29). Two flavors:

- `full`, for sideloading and F-Droid. Has the accessibility service and asks for the
  battery optimisation exemption directly.
- `play`, without either, for Play's policies.

The differences live in source sets and manifests, not in `if` branches, and CI builds
both.

## Building

You need a JDK (17 or newer) and the Android SDK, found through `local.properties` or
`ANDROID_HOME`.

```
./gradlew :core:test :app:testFullDebugUnitTest
./gradlew :app:assembleFullDebug
```

The debug APK lands in `app/build/outputs/apk/full/debug/`. Release builds are made by
the `android release` workflow on a `v*` tag; see `.github/workflows/android-release.yml`.
There is no simulator or debug clock: to test the cycle quickly, lower the capacity and
raise the charging speed in Settings.

`fastlane/metadata/android/` holds the store listing text in the layout F-Droid reads
straight from the repository.

## Permissions

First launch asks for six things and says what each one is for. The first three are
needed for the app to work at all; the other three can be skipped.

- Usage access: how the battery knows which app is in front. Without it nothing is
  counted.
- Display over other apps: what lets the service put the cover up while nothing of
  its own is on screen. The cover is an activity in its own task, so this grant only
  buys the right to launch it.
- Notifications: the charge reading lives in the shade.
- Accessibility (`full` only): a faster "app came to the front" signal, so a cover
  lands the instant an app opens instead of up to a second later. One event type, no
  window content.
- Notification access: the only way Android exposes the media session list, which is
  how the battery hears a tracked app playing sound. The listener reads no
  notifications; it only overrides connect and disconnect.
- Background battery: keeps the service from being paused on aggressive OEMs.

## Interop with the extension

The constants, crypto parameters, anchor semantics, and settings document in `core/`
have to match `background.js`, `sync.js`, `rules.js`, and `projection.js` byte for byte
wherever bytes travel. Golden vectors under `core/src/test/resources/interop/` pin this
from both sides: the extension's `tests/gen-interop-fixtures.js` generates them and its
CI fails if `sync.js` drifts, and the Kotlin tests must decrypt and re encrypt them
byte identically.

Identity: an app the built-in table knows maps to the extension's platform id (the
YouTube package is `youtube`), everything else is `app:<package>`. Both travel in the
synced `enabledSites` map, so two phones on one profile share their app blocks. Which
apps are tracked never syncs.

## Testing on a device

See [TESTING.md](TESTING.md).
