package site.dirt23.battery.core.engine

import site.dirt23.battery.core.Constants
import site.dirt23.battery.core.model.BatteryState
import site.dirt23.battery.core.model.RawHourRule
import site.dirt23.battery.core.model.RuleAction
import site.dirt23.battery.core.model.RuleScope
import site.dirt23.battery.core.model.SiteMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The extension's background.test.js, ported test by test: the charge math, the dead
 * period, what counts as time spent, settings, hour rules, and passes. The sync half lives
 * in EngineSyncTest.
 *
 * The browser shaped tests are gone (tab beats, on screen leases, minimized windows, the
 * toolbar badge, port connects, script registration): plumbing this engine does not have.
 * Engagement here is level triggered, so an off site proves itself by draining nothing
 * instead of by refusing a beat.
 */
class EngineTest {
    // --- The tick: drain, depletion, cooldown ------------------------------------------

    @Test
    fun `drains one second per second while engaged and not depleted`() {
        val r = Rig { charge = 100.0 }
        r.engine.onEngaged("youtube")
        r.advance(10_000)
        r.engine.tick()
        assertEquals(90.0, r.s.charge, 0.5)
    }

    @Test
    fun `a stop with sync on settles to now and pays the goodbye pad`() {
        val r = Rig(syncOn = true) { charge = 100.0 }
        r.engine.onEngaged("youtube")
        r.advance(10_000)
        r.engine.onIdle(atMs = r.clock.wall - 3_000) // noticed three seconds late
        assertEquals(100.0 - 10.0 - Constants.GOODBYE_PAD_SECONDS, r.s.charge, 0.5)
    }

    @Test
    fun `a stop with sync off settles to the moment it happened and pays nothing`() {
        val r = Rig { charge = 100.0 }
        r.engine.onEngaged("youtube")
        r.advance(10_000)
        r.engine.onIdle(atMs = r.clock.wall - 3_000)
        assertEquals(93.0, r.s.charge, 0.5) // 7s drained, then 3s of recharge
    }

    @Test
    fun `the goodbye pad never takes the charge below zero`() {
        val r = Rig(syncOn = true) { charge = 1.0 }
        r.engine.onEngaged("youtube")
        r.engine.onIdle()
        assertEquals(1.0, r.s.charge, 0.01)
        assertFalse(r.s.depleted)
    }

    @Test
    fun `enters depletion at zero with depletedAt at the crossing tick`() {
        val r = Rig { charge = 5.0 }
        r.engine.onEngaged("youtube")
        r.advance(10_000)
        r.engine.tick()
        assertEquals(0.0, r.s.charge)
        assertTrue(r.s.depleted)
        assertEquals(r.s.lastTickTs, r.s.depletedAt)
        assertEquals(T0 + 10_000, r.s.depletedAt)
    }

    @Test
    fun `recharges through the cooldown and stays blocked inside it`() {
        val r = Rig {
            charge = 0.0
            depleted = true
            depletedAt = T0 - 60_000
            lastTickTs = T0 - 60_000
        }
        r.engine.tick()
        assertTrue(r.s.depleted)
        assertEquals(5.0, r.s.charge, 0.5) // 60s at 5 per minute
    }

    @Test
    fun `lifts depletion only after the cooldown, with its recharge banked`() {
        val past = (Constants.COOLDOWN_SECONDS + 1) * 1000L
        val r = Rig {
            charge = 0.0
            depleted = true
            depletedAt = T0 - past
            lastTickTs = T0 - past
        }
        r.engine.tick()
        assertFalse(r.s.depleted)
        assertNull(r.s.depletedAt)
        assertTrue(r.s.charge > 0)
    }

    @Test
    fun `never drains while depleted, even with an engaged app`() {
        val r = Rig {
            charge = 50.0
            depleted = true
            depletedAt = T0 - 30_000
        }
        r.engine.onEngaged("youtube")
        r.advance(10_000)
        r.engine.tick()
        assertTrue(r.s.charge > 50) // recharged, not drained
    }

    @Test
    fun `the cooldown ends with enough banked charge not to die again on contact`() {
        val r = Rig { charge = 5.0 }
        r.engine.onEngaged("youtube")
        r.advance(10_000)
        r.engine.tick()
        assertTrue(r.s.depleted)
        r.advance(Constants.COOLDOWN_SECONDS * 1000L)
        r.engine.tick()
        assertFalse(r.s.depleted)
        assertEquals(Constants.COOLDOWN_SECONDS / 60.0 * 5.0, r.s.charge, 0.5)
    }

    // --- What counts: off outranks block, a pass opens a block --------------------------

    @Test
    fun `a switched off app never counts`() {
        val r = Rig {
            charge = 100.0
            siteModes["youtube"] = SiteMode.OFF
        }
        r.engine.onEngaged("youtube")
        r.advance(10_000)
        r.engine.tick()
        assertFalse(r.engine.siteUsable("youtube"))
        assertTrue(r.s.charge > 100) // inert, so the battery kept charging
    }

    @Test
    fun `a settings blocked app never counts without a pass, and drains the moment one lands`() {
        val r = Rig {
            charge = 100.0
            siteModes["youtube"] = SiteMode.BLOCK
        }
        r.engine.onEngaged("youtube")
        r.advance(10_000)
        r.engine.tick()
        assertFalse(r.engine.siteUsable("youtube"))
        assertTrue(r.s.charge > 100) // time behind the cover never drains

        r.engine.useSitePass("youtube")
        val before = r.s.charge
        r.advance(10_000)
        r.engine.tick()
        assertEquals(before - 10, r.s.charge, 0.5)
    }

    @Test
    fun `an app blocked by an open hour rule never counts`() {
        val r = Rig {
            charge = 100.0
            hourRules = listOf(window(120, 120, RuleAction.BLOCK))
        }
        r.engine.onEngaged("youtube")
        r.advance(10_000)
        r.engine.tick()
        assertFalse(r.engine.siteUsable("youtube"))
        assertTrue(r.s.charge > 100)
    }

    @Test
    fun `a pass lets an app blocked by an hour rule count again`() {
        val r = Rig {
            charge = 100.0
            hourRules = listOf(window(120, 120, RuleAction.BLOCK))
            sitePasses["youtube"] = T0 + 60_000
        }
        assertTrue(r.engine.siteUsable("youtube"))
        r.engine.onEngaged("youtube")
        r.advance(10_000)
        r.engine.tick()
        assertEquals(90.0, r.s.charge, 0.5)
    }

    @Test
    fun `an off rule makes an app inert, scoped to the picked apps`() {
        val r = Rig {
            hourRules = listOf(
                window(120, 120, RuleAction.OFF, scope = RuleScope.ONLY, sites = listOf("youtube")),
            )
        }
        assertFalse(r.engine.siteUsable("youtube"))
        assertTrue(r.engine.siteUsable("reddit"))
    }

    @Test
    fun `an open off rule outranks both a block and the pass that would open it`() {
        // Off is checked before blocked everywhere, so an off rule exempts the site
        // outright.
        val r = Rig {
            siteModes["youtube"] = SiteMode.BLOCK
            hourRules = listOf(window(120, 120, RuleAction.OFF))
            sitePasses["youtube"] = T0 + 60_000
        }
        assertFalse(r.engine.siteUsable("youtube"))
    }

    // --- Timestamped transitions -------------------------------------------------------

    @Test
    fun `a late transition is attributed to when it happened, not when it arrived`() {
        val r = Rig { charge = 100.0 }
        r.engine.onEngaged("youtube", atMs = T0)
        r.advance(60_000)
        // The stop really happened fifty seconds ago and is only being delivered now.
        r.engine.onIdle(atMs = T0 + 10_000)
        assertEquals(90.0 + 50 * (5.0 / 60), r.s.charge, 0.5)
    }

    @Test
    fun `a transition timestamped before the last settle cannot rewrite spent time`() {
        val r = Rig { charge = 100.0 }
        r.engine.onEngaged("youtube")
        r.advance(30_000)
        r.engine.tick() // 30s spent and settled
        r.engine.onIdle(atMs = T0) // claims the stop happened before any of it
        assertEquals(70.0, r.s.charge, 0.5)
    }

    // --- Reserve and passes -------------------------------------------------------------

    @Test
    fun `useReserve adds the reserve and clears depletion`() {
        val r = Rig {
            charge = 0.0
            depleted = true
            depletedAt = T0 - 1000
        }
        val snap = r.engine.useReserve()
        assertFalse(snap.depleted)
        assertNull(snap.depletedAt)
        assertEquals(Constants.RESERVE_SECONDS.toDouble(), r.s.charge, 1.0)
    }

    @Test
    fun `useSitePass grants exactly the pass length`() {
        val r = Rig { siteModes["youtube"] = SiteMode.BLOCK }
        val snap = r.engine.useSitePass("youtube")
        assertEquals(Constants.SITE_PASS_SECONDS, snap.sitePasses["youtube"])
        assertEquals(T0 + Constants.SITE_PASS_SECONDS * 1000L, r.s.sitePasses["youtube"])
        assertTrue(r.engine.siteUsable("youtube"))
    }

    @Test
    fun `an expired pass is pruned on the next settle`() {
        val r = Rig { sitePasses["youtube"] = T0 - 1000 }
        r.engine.tick()
        assertTrue(r.s.sitePasses.isEmpty())
    }

    @Test
    fun `a pass adds nothing to the battery`() {
        val r = Rig {
            charge = 500.0
            siteModes["youtube"] = SiteMode.BLOCK
        }
        r.engine.useSitePass("youtube")
        assertEquals(500.0, r.s.charge, 0.5)
    }

    // --- Hour rules on the charge --------------------------------------------------------

    @Test
    fun `an open recharge zero window stops recharge across the whole slept gap`() {
        val r = Rig {
            charge = 100.0
            hourRules = listOf(window(120, 120, RuleAction.RECHARGE, percent = 0))
            lastTickTs = T0 - 60 * 60_000
        }
        r.engine.tick()
        assertEquals(100.0, r.s.charge, 1.0)
    }

    @Test
    fun `an open capacity window clamps the banked charge`() {
        val r = Rig {
            charge = 1200.0
            hourRules = listOf(window(120, 120, RuleAction.CAPACITY, minutes = 10))
        }
        r.engine.tick()
        assertTrue(r.s.charge <= 600)
        assertEquals(600, r.engine.snapshot().effCapacity)
    }

    // --- The snapshot ---------------------------------------------------------------------

    @Test
    fun `the snapshot names the engaged app and carries the warning threshold`() {
        val r = Rig { warnSeconds = 300 }
        r.engine.onEngaged("reddit")
        var snap = r.engine.snapshot()
        assertEquals("reddit", snap.drainingId)
        assertTrue(snap.draining)
        assertEquals(300, snap.warnSeconds)

        // A mirrored remote drain has no local app to name.
        r.engine.onIdle()
        r.s.remoteDraining = true
        r.s.remoteDrainingTs = T0
        snap = r.engine.snapshot()
        assertTrue(snap.draining)
        assertNull(snap.drainingId)
    }

    @Test
    fun `the snapshot carries passes as seconds remaining and the paused flag`() {
        val r = Rig {
            hourRules = listOf(window(120, 120, RuleAction.RECHARGE, percent = 0))
            sitePasses["youtube"] = T0 + 90_000
        }
        val snap = r.engine.snapshot()
        assertEquals(90, snap.sitePasses["youtube"])
        assertTrue(snap.rechargePaused)
        assertEquals(Constants.SITE_PASS_SECONDS, snap.passSeconds)
        assertEquals(Constants.RESERVE_SECONDS, snap.reserveSeconds)
    }

    @Test
    fun `the cooldown remaining counts down and reads zero while alive`() {
        val r = Rig {
            charge = 0.0
            depleted = true
            depletedAt = T0 - 60_000
        }
        assertEquals(Constants.COOLDOWN_SECONDS - 60.0, r.engine.snapshot().cooldownRemaining, 0.5)
        assertEquals(0.0, Rig().engine.snapshot().cooldownRemaining)
    }

    // --- Settings ---------------------------------------------------------------------------

    @Test
    fun `applySettings settles the elapsed period before mutating`() {
        // Ten idle minutes bank their recharge under the old settings, and the save stamps
        // lastTickTs at now. Without the settle the save left a stale stamp and the gap got
        // re-read later under the new settings.
        val r = Rig { charge = 600.0 }
        r.clock.advance(10 * 60_000L)
        r.engine.applySettings(SettingsPatch(warnSeconds = 60))
        assertEquals(650.0, r.s.charge, 1e-6)
        assertEquals(r.clock.wall, r.s.lastTickTs)
    }

    @Test
    fun `applySettings floors the recharge rate at one so a dead battery can always climb out`() {
        val r = Rig()
        r.engine.applySettings(SettingsPatch(rechargePerMin = 0.0))
        assertEquals(1.0, r.s.rechargePerMin)
    }

    @Test
    fun `the warning threshold accepts zero and clamps a negative to it`() {
        val r = Rig()
        r.engine.applySettings(SettingsPatch(warnSeconds = 0))
        assertEquals(0, r.s.warnSeconds)
        r.engine.applySettings(SettingsPatch(warnSeconds = -60))
        assertEquals(0, r.s.warnSeconds)
        r.engine.applySettings(SettingsPatch(warnSeconds = 600))
        assertEquals(600, r.s.warnSeconds)
    }

    @Test
    fun `applySettings sanitizes hour rules`() {
        val r = Rig()
        r.engine.applySettings(
            SettingsPatch(
                hourRules = listOf(
                    RawHourRule(id = "ok", from = "22:00", to = "07:00", action = "block"),
                    RawHourRule(id = "bad", from = "10:00", to = "10:00", action = "block"),
                ),
            ),
        )
        assertEquals(listOf("ok"), r.s.hourRules.map { it.id })
    }

    @Test
    fun `a lowered capacity re-clamps the banked charge`() {
        val r = Rig { charge = 1800.0 }
        r.engine.applySettings(SettingsPatch(capacity = 600.0))
        assertEquals(600.0, r.s.charge, 0.5)
        assertEquals(600, r.engine.snapshot().capacity)
    }

    @Test
    fun `capacity clamps to its own bounds`() {
        val r = Rig()
        r.engine.applySettings(SettingsPatch(capacity = 0.0))
        assertEquals(Constants.CAPACITY_MIN_SECONDS.toDouble(), r.s.capacity)
        r.engine.applySettings(SettingsPatch(capacity = 999_999.0))
        assertEquals(Constants.CAPACITY_MAX_SECONDS.toDouble(), r.s.capacity)
    }

    // --- Load: corrupted storage cannot brick the battery --------------------------------------

    @Test
    fun `sanitize repairs a zero capacity, a junk rate, and an over-full charge`() {
        val s = BatteryState(
            charge = 99_999.0,
            capacity = 0.0,
            rechargePerMin = Double.NaN,
            lastTickTs = T0,
        ).sanitize(T0)
        assertEquals(Constants.CAPACITY_MIN_SECONDS.toDouble(), s.capacity)
        assertEquals(Constants.DEFAULT_RECHARGE_PER_MIN, s.rechargePerMin)
        assertEquals(Constants.CAPACITY_MIN_SECONDS.toDouble(), s.charge)
    }

    @Test
    fun `sanitize repairs a NaN capacity to the default and leaves a valid charge alone`() {
        val s = BatteryState(charge = 500.0, capacity = Double.NaN, lastTickTs = T0).sanitize(T0)
        assertEquals(Constants.DEFAULT_CAPACITY_SECONDS.toDouble(), s.capacity)
        assertEquals(500.0, s.charge)
    }

    @Test
    fun `sanitize anchors a dead battery that arrived with no start time`() {
        // Without this the cooldown could never lift.
        val s = BatteryState(depleted = true, depletedAt = null, lastTickTs = 0).sanitize(T0)
        assertEquals(T0, s.lastTickTs)
        assertEquals(T0, s.depletedAt)
    }

    // --- Clock jumps ------------------------------------------------------------------------

    @Test
    fun `a wall clock jump spends no charge and leaves a pass its remaining time`() {
        val r = Rig(guardClockJumps = true) {
            charge = 100.0
            sitePasses["youtube"] = T0 + 300_000
        }
        r.engine.tick() // baseline for the monotonic clock
        r.clock.jumpWall(3_600_000) // the clock was set an hour forward
        r.engine.tick()
        assertEquals(100.0, r.s.charge, 0.1)
        assertEquals(r.clock.wallNow(), r.s.lastTickTs)
        assertEquals(300, r.engine.snapshot().sitePasses["youtube"])
    }

    @Test
    fun `a wall clock jump landing on a settings save is not spent as time`() {
        // The save settles before it mutates, and that settle must sit behind the same
        // guard as every other entry point or the jump gets consumed as elapsed time.
        val r = Rig(guardClockJumps = true) { charge = 900.0 }
        r.engine.onEngaged("youtube") // baseline for the monotonic clock, draining
        r.clock.jumpWall(3_600_000) // the clock was set an hour forward
        r.engine.applySettings(SettingsPatch(rechargePerMin = 6.0))
        assertEquals(900.0, r.s.charge, 0.1)
        assertEquals(6.0, r.s.rechargePerMin)
    }

    @Test
    fun `a real offline gap still recharges after a restart`() {
        // The monotonic clock restarts with the process, so a cold start has no baseline to
        // compare against and must not mistake an hour offline for a jump.
        val r = Rig(guardClockJumps = true) {
            charge = 100.0
            lastTickTs = T0 - 3_600_000
        }
        r.engine.tick()
        assertEquals(100.0 + 60 * 5.0, r.s.charge, 0.5)
    }

    @Test
    fun `onWallClockChanged settles the time that really passed and then re-anchors`() {
        val r = Rig { charge = 100.0 }
        r.engine.tick() // baseline
        r.advance(60_000) // a real minute
        r.clock.jumpWall(3_600_000) // and then the clock moved an hour
        r.engine.onWallClockChanged()
        assertEquals(100.0 + 5.0, r.s.charge, 0.1) // one minute of recharge, not sixty one
        assertEquals(r.clock.wallNow(), r.s.lastTickTs)
    }
}
