# AMO listing copy

One paragraph per line in this file: the text gets copied straight into AMO fields, and hard wrapped lines would carry their line breaks along.

## Name
Media Battery

## Summary (one line)
A battery for media sites that recharges on its own. Time on the tracked sites drains it; it refills while you're away, and at empty the sites are blocked with a deliberately fiddly way back in when you really need it.

## Description
Nobody plans to lose an hour to a feed. You open it to check one thing, and the tab is still there at midnight. The usual fix is a blocker, and the usual end of that story is turning the blocker off on day three because it got in the way of something real.

Media Battery is a battery instead of a wall. Time on the sites you pick drains it, time away refills it, and when it runs empty those sites go dark for ten minutes. Nothing is ever forbidden. It just stops being free, and the charge ticking down on the toolbar makes the spending visible while it happens instead of after. It only counts time you're actually there: background tabs, minimized windows, and muted autoplay never drain it, while a video playing with sound does.

You can cheat, but not on autopilot. Getting back in early means clearing a small task that changes on every attempt (retyping a phrase with pasting disabled, arithmetic, sorting numbers, counting letters) and writing a line on why you're back. Wrong answer, fresh set. The toll is small and always the same, so it never turns into a reason to switch the extension off. Loosening the rules in settings walks through the same gate, so there's no side door either.

It comes with YouTube, Reddit, Instagram, TikTok, X, Facebook, Twitch, and LinkedIn built in, and you can add any site by domain. Hours let you shape the day: windows that block sites outright, exempt them, slow the charging, or cap the battery, and any blocked site can still be opened for five minutes through the same gate. One optional code shares a single battery across your devices, end to end encrypted, no account, and the lower charge always wins, so a second device can never refill what the first one spent. It runs on Firefox for Android too, so the phone spends from the same battery as the desk.

It can't stop someone who has decided to get around it. The extension can be switched off in two clicks and its saved data edited, like any extension. What it does stop is the version of you that never decided anything, the one that opens a feed out of muscle memory and looks up an hour later.

## Version 1.7.3, release notes
The questions at the gate now cost about the same whichever one comes up: no more a trivial power of two one time and a seven number sort the next. A wrong answer keeps the same kind of question with new numbers, so retrying is no longer a way to fish for an easy one. And how many questions a gate asks is now a setting, one to five, the same for the unblock gate and the settings gate and shared across devices; asking for fewer goes through the gate. Also fixes the battery turning up empty, in the morning or at random, with the extension on Firefox for Android. Android holds a backgrounded Firefox still, and a sync reply the server sent in the evening could be read the next morning as if it had just arrived. That set the extension's clock back by the whole night, so last night's charge counted as the current one and sync carried it to every device. A reply that took longer than it possibly could no longer sets the clock. Also on the phone: a video blocked in fullscreen now leaves fullscreen, so the block is visible and the page can be left; before, the only way out was closing the app. And a tap made before the phone went to sleep no longer counts as fresh use when it wakes.

## Version 1.7.2, release notes
With sync on, leaving a site or app on one device no longer leaves the others a little lower. The stop used to be missed on the other devices, which kept draining in step for up to a minute and a half; now it ends the moment the stop arrives. Also fixes a rare case where the phone app woke up to a dead battery after a restart and passed it on to the browser.

## Version 1.7.1, release notes
Firefox can quietly drop a site permission (the per site toggle in the extensions panel does it without a word), and without the grant the blocking layer never loads while the popup still works, which is the worst way for a self control tool to fail. The extension now watches its grants: losing one puts a red ! on the toolbar badge and a Restore button in the popup that asks for it back. Also, loosening a setting now asks one question instead of two, the same flat toll as the unblock gate.

## Version 1.7.0, release notes
The extension now runs on Firefox for Android: same battery, same sync code, so the phone's browser and the desk drain one charge. The popup and settings fit a phone screen, and the charge badge stays a desktop thing, since Android has no toolbar to put it on. Instead, opening a tracked site now shows the time left in the corner for a moment, on every device; settings can turn it off. Also carries the sync fixes since 1.6.0: a settings save only touches what actually changed, so it can no longer overwrite another device's choices with a stale copy.

## Version 1.6.0, release notes
Hour rules: set windows that block sites, exempt them from the battery, slow or stop the charging, or lower the capacity. Sites can also be blocked outright from settings, and any block can be opened for five minutes through the question gate. The gate itself is flat now: an unblock always asks one question, a settings change always two, because a predictable toll gets paid and an escalating one invites the off switch. Rule windows follow the clock on your wall on every device, and a long list of small edge cases around sync, clocks, and the block overlay got fixed on the way.

## Version 1.5.0, release notes
Sync devices now hear about a change the moment it lands instead of on the next poll, so a drain on one device shows on the others right away, and an idle browser barely touches the server.

## Version 1.4.0, release notes
Draining now keys off what the tab itself can see: the site is on screen and there's recent input or a video playing with sound. The system wide idle detector is gone; it could keep the battery draining after you'd walked away.

## Version 1.3.0, release notes
Cross-device sync: one code shares a battery between devices, end to end encrypted, no account, and you can self-host the server. Early unblocks now escalate: each one adds a question to the gate, up to five in all, and asks why you're coming back, resetting overnight. The battery recharges through the dead cooldown, so the block lifts with a little charge banked. The popup gained clear charging and draining states.

## Version 1.2.0, release notes
More built-in platforms (X, Facebook, Twitch, LinkedIn alongside the original four), and you can now add your own sites by domain from settings. New YouTube toggle to hide the recommendations sidebar.

## Version 1.1.0, release notes
Reserve power now refills the battery itself: spend it and you get a set chunk of charge back (default 5 minutes) that revives every tracked site, instead of a per site pass. A dead battery now truly pauses recharge for the whole cooldown, then charges back from empty. You can also switch each site on or off in settings.

## Suggested category / tags
Category: Privacy & Security, or Tabs (AMO has no dedicated focus category; "Other" works too)

Tags: social media, focus, time limit, productivity, self control
