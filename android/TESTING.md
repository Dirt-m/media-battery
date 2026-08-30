# Checking a build on a phone

Nothing here is automated. The unit tests cover the math; this is the list of things
that only a real phone can prove, roughly in the order they matter. Defaults assumed:
30 minute battery, 5 seconds of recharge per minute.

Build and install:

```
./gradlew :app:assembleFullDebug
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

Then track YouTube from Settings and use it as the tracked app below.

## Battery

1. In a tracked app the reading falls one second per second. Leave the app and it
   stops right away.
2. Away from every tracked app it climbs 5 seconds per minute of wall clock.
3. Force stop the app, use a tracked app for two minutes, relaunch. The charge is
   lower than you left it, never higher. The gap is replayed from the usage event log.
4. Run the charge to zero. The cover shows in under a second, reads "recharging, back
   in 9:58", and lifts itself ten minutes later with some charge banked from the
   cooldown.
5. On a dead battery, answer the gate's question and the why box. The charge is 5
   minutes higher and every tracked app works again. On an app that is blocked (rather
   than a dead battery) the same gate opens that one app for five minutes and adds no
   charge.
6. Switch between a tracked app and an untracked one repeatedly. The cover never
   shows over the untracked app, not even for a frame.
7. Try to track Settings, the launcher, the dialer, or the keyboard. The apps list
   never offers them.
8. Reboot with the app set up. The notification comes back on its own and the charge
   continues from where it was.

## Detection

9. With accessibility on, open a blocked app from the launcher. The cover is up before
   the app has drawn anything useful. With accessibility off it can take up to a
   second. That second is all the accessibility grant buys.
10. Switch the accessibility service off while the battery is running. A line on the
    gauge says blocks land slower, and blocks keep landing off the usage log.
11. Start a video in a tracked app, lock the phone, come back two minutes later. The
    reading is lower and the gauge names that app. Pause the audio and the drain stops
    about ten seconds later (the grace that keeps a seek or an ad break from cutting
    the count).
12. Run the charge to zero while a tracked app is playing. The sound pauses once.
    Starting it again is allowed.

## Cover

13. With a cover up, swipe into the recents switcher. The cover is the front card,
    titled for what it covers ("Vinted is blocked"). Tapping it brings the cover back.
    Tapping the covered app's own card brings the cover back immediately, with no
    grace. That card still shows the app underneath; it's a system snapshot and nothing
    in the app can repaint it.
14. Swipe back on the cover. You land on the home screen, not in the covered app and
    not in Media Battery, and no cover card is left in the switcher.
15. With a cover up, open some untracked app from the switcher, then open the switcher
    again. The cover card is gone. Open the blocked app and it comes straight back.

## Hours

16. Add a block rule ending a few minutes from now with scope All apps, then open a
    tracked app. The cover reads "until HH:MM" and lifts on that minute by itself.
    Adding the rule was free; removing it costs a question.
17. With that rule still open, pay the gate. The app works and the reading drains as
    normal, because a rule block never touches the battery. Five minutes later the
    cover is back.
18. Add a charging rule at 0% covering the night and leave the phone alone through it.
    In the morning the charge is where it was. At 50% the same night banks half.

## Surfaces

19. Set the low charge warning to 3 minutes and run the charge down in a tracked app.
    The warning arrives once, as its own notification, and not again while you keep
    draining. Let the charge climb a minute clear of the threshold and drain back
    down: it fires again.
20. Put the tile in quick settings. It reads the minutes left and one word for the
    state, and follows you between a tracked app and the home screen.
21. Put the widget on the home screen. Same reading, same colours as the gauge, moving
    as the minutes do. It has no buttons: reserve costs the gate wherever it's spent.
22. Open a tracked app with charge banked. A small pill in the bottom right shows the
    minutes left for a moment. A covered app shows the cover instead, a switched off
    app shows nothing, and the setting turns it off on every synced device.
23. Leave the phone alone overnight and look at the system battery usage screen.
    Media Battery should be near the bottom. With the screen off and nothing playing
    it holds no wakelock, makes no network requests, and writes nothing.

## Sync, with the Firefox extension on a desktop

24. Get a code from the extension's sync page and type it into the phone's Join box.
    The status card reads Synced and the phone's charge drops to the desktop's if that
    was lower. A code with a typo is refused and nothing changes.
25. Watch something in a tracked app on the phone. Within seconds the desktop popup
    shows the same falling charge and names the drain "another device".
26. Force stop the phone app mid drain. The desktop keeps mirroring for about ninety
    seconds, then goes back to recharging. It never drains to zero on its own.
27. Run the charge to zero on the desktop. The next tracked app opened on the phone is
    covered, and the cooldown ends at the same moment on both.
28. Set an app to Block on the phone and add an hour rule in the extension. Both show
    up on both, since settings merge one key at a time.
