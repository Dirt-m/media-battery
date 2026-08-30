package site.dirt23.battery.core.engine

import site.dirt23.battery.core.Constants
import site.dirt23.battery.core.model.Anchor
import site.dirt23.battery.core.model.RuleAction
import site.dirt23.battery.core.model.SiteMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sync facing half of background.test.js: adopting an anchor, re-anchoring the clock,
 * and the dead period counter that survives both.
 *
 * The two bounds under test: a sync can only lower this device's charge, never raise it, so
 * two idle devices cannot recharge the same battery twice; and a stale draining anchor can
 * only lower it by the drain horizon, so a writer that vanished mid drain costs a bounded
 * amount.
 */
class EngineSyncTest {
    private fun remote(
        charge: Double,
        asOf: Long,
        draining: Boolean = false,
        depleted: Boolean = false,
        depletedAt: Long? = null,
    ) = Anchor(
        charge = charge,
        asOf = asOf,
        draining = draining,
        depleted = depleted,
        depletedAt = depletedAt,
        writer = "other",
    )

    // --- Lower wins ---------------------------------------------------------------------

    @Test
    fun `adopts a lower anchor and takes its projected charge`() {
        val r = Rig { charge = 900.0 }
        val res = r.engine.maybeAdoptAnchor(remote(charge = 300.0, asOf = T0))
        assertTrue(res.adopted)
        assertEquals(300.0, r.s.charge, 1.0)
    }

    @Test
    fun `rejects a higher idle anchor and tells the caller to overwrite`() {
        val r = Rig { charge = 300.0 }
        val res = r.engine.maybeAdoptAnchor(remote(charge = 900.0, asOf = T0))
        assertFalse(res.adopted)
        assertTrue(res.overwrite)
        assertEquals(300.0, r.s.charge, 1.0)
    }

    @Test
    fun `never adopts its own echo`() {
        val r = Rig { charge = 900.0 }
        val res = r.engine.maybeAdoptAnchor(Anchor(charge = 100.0, asOf = T0, writer = "me"))
        assertFalse(res.adopted)
        assertEquals(900.0, r.s.charge, 1.0)
    }

    @Test
    fun `the adopt epsilon takes local plus two and rejects local plus three`() {
        val a = Rig { charge = 900.0 }
        assertTrue(a.engine.maybeAdoptAnchor(remote(charge = 902.0, asOf = T0)).adopted)

        val b = Rig { charge = 900.0 }
        assertFalse(b.engine.maybeAdoptAnchor(remote(charge = 903.0, asOf = T0)).adopted)
    }

    @Test
    fun `a lower living anchor lowers the charge but cannot cut a running cooldown short`() {
        val depletedAt = T0 - 2 * 60_000 // mid cooldown
        val r = Rig {
            charge = 5.0
            depleted = true
            this.depletedAt = depletedAt
        }
        val res = r.engine.maybeAdoptAnchor(remote(charge = 1.0, asOf = T0 + 5000, depleted = false))
        assertTrue(res.adopted)
        assertTrue(r.s.charge <= 1.1) // the lower charge landed
        assertTrue(r.s.depleted) // the cooldown did not
        assertEquals(depletedAt, r.s.depletedAt)
    }

    @Test
    fun `a remote depletion is adopted while cooling, so devices agree on when it began`() {
        val remoteAt = T0 - 3 * 60_000
        val r = Rig {
            charge = 5.0
            depleted = true
            depletedAt = T0 - 2 * 60_000
        }
        val res = r.engine.maybeAdoptAnchor(
            remote(charge = 0.0, asOf = T0, depleted = true, depletedAt = remoteAt),
        )
        assertTrue(res.adopted)
        assertEquals(remoteAt, r.s.depletedAt)
    }

    @Test
    fun `a higher fresh draining anchor is rejected without an overwrite`() {
        // Overwriting a live writer would only ping-pong with the device actually in use.
        val r = Rig { charge = 300.0 }
        val res = r.engine.maybeAdoptAnchor(remote(charge = 900.0, asOf = T0 - 5000, draining = true))
        assertFalse(res.adopted)
        assertFalse(res.overwrite)
    }

    @Test
    fun `a higher stale draining anchor is rejected with an overwrite`() {
        val r = Rig { charge = 300.0 }
        val stale = T0 - (Constants.DRAIN_HORIZON_MS + 60_000)
        val res = r.engine.maybeAdoptAnchor(remote(charge = 900.0, asOf = stale, draining = true))
        assertFalse(res.adopted)
        assertTrue(res.overwrite)
    }

    @Test
    fun `the remote drain mirror lease is seeded from the anchor write time, not from now`() {
        val asOf = T0 - 10_000
        val r = Rig { charge = 900.0 }
        val res = r.engine.maybeAdoptAnchor(remote(charge = 300.0, asOf = asOf, draining = true))
        assertTrue(res.adopted)
        assertTrue(r.s.remoteDraining)
        assertEquals(asOf, r.s.remoteDrainingTs)
    }

    @Test
    fun `a follower mirrors a remote drain while the lease holds and charges once it lapses`() {
        val r = Rig { charge = 900.0 }
        r.engine.maybeAdoptAnchor(remote(charge = 900.0, asOf = T0, draining = true))
        r.advance(30_000)
        r.engine.tick()
        assertEquals(870.0, r.s.charge, 0.5) // mirrored one second per second

        r.advance(5 * 60_000) // the writer went silent, so the lease lapses
        r.engine.tick()
        assertTrue(r.s.charge > 870.0)
    }

    @Test
    fun `a rejected stopped anchor from the mirrored writer still ends the mirror`() {
        val r = Rig { charge = 900.0 }
        r.engine.maybeAdoptAnchor(remote(charge = 900.0, asOf = T0, draining = true))
        r.advance(30_000)
        r.engine.tick()
        assertEquals(870.0, r.s.charge, 0.5)

        // The writer stopped three seconds ago and says so. Its value sits above the
        // mirror by more than the epsilon, so it is not adopted, but the mirror must end.
        val res = r.engine.maybeAdoptAnchor(remote(charge = 873.0, asOf = T0 + 27_000, draining = false))
        assertFalse(res.adopted)
        assertTrue(res.overwrite)
        assertFalse(r.s.remoteDraining)

        r.advance(30_000)
        r.engine.tick()
        assertTrue(r.s.charge > 870.0) // recharging, not phantom draining to the lease
    }

    @Test
    fun `an older stopped anchor does not end a newer mirror`() {
        val r = Rig { charge = 900.0 }
        r.engine.maybeAdoptAnchor(remote(charge = 900.0, asOf = T0, draining = true))
        r.advance(10_000)
        val res = r.engine.maybeAdoptAnchor(remote(charge = 950.0, asOf = T0 - 5_000, draining = false))
        assertFalse(res.adopted)
        assertTrue(r.s.remoteDraining)
    }

    @Test
    fun `the engaged device follows no one`() {
        val r = Rig { charge = 900.0 }
        r.engine.onEngaged("youtube")
        r.engine.maybeAdoptAnchor(remote(charge = 500.0, asOf = T0, draining = true))
        assertFalse(r.s.remoteDraining)
    }

    @Test
    fun `getAnchor advertises a local drain and never a mirrored one`() {
        val r = Rig { charge = 900.0 }
        r.engine.onEngaged("youtube")
        val mine = r.engine.getAnchor()
        assertTrue(mine.draining)
        assertEquals("me", mine.writer)
        assertEquals(r.s.lastTickTs, mine.asOf)

        r.engine.onIdle()
        r.engine.maybeAdoptAnchor(remote(charge = 500.0, asOf = T0, draining = true))
        assertFalse(r.engine.getAnchor().draining)
    }

    // --- Re-anchoring the clock ------------------------------------------------------------

    @Test
    fun `applyServerOffset shifts the tick stamp and the depletion stamp together`() {
        val r = Rig {
            charge = 0.0
            depleted = true
            depletedAt = T0 - 1000
        }
        r.engine.onEngaged("youtube")
        r.engine.applyServerOffset(60_000)
        assertEquals(T0 - 1000 + 60_000, r.s.depletedAt)
        assertEquals(T0 + 60_000, r.s.lastTickTs)
    }

    @Test
    fun `a running pass keeps its remaining seconds across a re-anchor and its inverse`() {
        val r = Rig {
            siteModes["youtube"] = SiteMode.BLOCK
            sitePasses["youtube"] = T0 + 300_000
        }
        // Expiries are minted in server anchored time, so a ten minute jump would otherwise
        // expire a five minute pass outright.
        r.engine.applyServerOffset(10 * 60_000)
        assertEquals(T0 + 300_000 + 10 * 60_000, r.s.sitePasses["youtube"])
        assertEquals(300, r.engine.snapshot().sitePasses["youtube"])
        assertTrue(r.engine.siteUsable("youtube"))

        // And back the other way, the way unlinking resets it: no free extra minutes.
        r.engine.applyServerOffset(0)
        assertEquals(T0 + 300_000, r.s.sitePasses["youtube"])
    }

    @Test
    fun `the charge over a wall clock gap is the same at any server offset`() {
        val rules = listOf(window(30, 30, RuleAction.RECHARGE, percent = 0))
        val offset = 2 * 3600_000L

        val a = Rig {
            charge = 100.0
            rechargePerMin = 60.0
            hourRules = rules
            lastTickTs = T0 - 20 * 60_000
        }
        a.engine.tick()

        val b = Rig {
            charge = 100.0
            rechargePerMin = 60.0
            hourRules = rules
            serverOffset = offset
            lastTickTs = T0 + offset - 20 * 60_000
        }
        b.engine.tick()

        assertEquals(100.0, a.s.charge, 0.5) // the open window stopped charging
        assertEquals(a.s.charge, b.s.charge, 0.5) // and the offset changed nothing
    }

    @Test
    fun `an hour rule window is read on the device wall clock, not on server time`() {
        val rules = listOf(window(30, 30, RuleAction.BLOCK))
        val skewed = Rig {
            hourRules = rules
            serverOffset = 2 * 3600_000
            lastTickTs = T0 + 2 * 3600_000
        }
        // The window is open right now on this device's clock; two hours of skew must not
        // close it.
        assertFalse(skewed.engine.siteUsable("youtube"))

        val plain = Rig { hourRules = rules }
        assertFalse(plain.engine.siteUsable("youtube"))
    }

    // --- The dead period counter --------------------------------------------------------------

    @Test
    fun `depletionSeq bumps on going dead and holds still across a re-anchor`() {
        val r = Rig {
            charge = 5.0
            depletionSeq = 4
        }
        r.engine.onEngaged("youtube")
        r.advance(10_000)
        r.engine.tick()
        assertTrue(r.s.depleted)
        assertEquals(5, r.s.depletionSeq)

        // Re-anchoring shifts depletedAt, so a timestamp can't identify a dead period. If
        // the seq moved, anything keyed on it would read every server response as a fresh
        // dead period.
        r.engine.applyServerOffset(60_000)
        assertEquals(5, r.s.depletionSeq)
    }

    @Test
    fun `adopting a remote depletion bumps the seq while alive and holds while already dead`() {
        val r = Rig {
            charge = 50.0
            depletionSeq = 2
        }
        r.engine.maybeAdoptAnchor(
            remote(charge = 0.0, asOf = T0, depleted = true, depletedAt = T0 - 1000),
        )
        assertTrue(r.s.depleted)
        assertEquals(3, r.s.depletionSeq)

        // The same dead period re-read from a later anchor with a different depletedAt.
        // Still one period, no bump.
        r.engine.maybeAdoptAnchor(
            remote(charge = 0.0, asOf = T0, depleted = true, depletedAt = T0 - 3000),
        )
        assertTrue(r.s.depleted)
        assertEquals(3, r.s.depletionSeq)
    }
}
