package site.dirt23.battery.core.projection

import site.dirt23.battery.core.Constants
import site.dirt23.battery.core.model.Anchor
import site.dirt23.battery.core.model.HourRule
import site.dirt23.battery.core.model.RuleAction
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The extension's projection.test.js, ported test by test. The invariants that broke once:
 * a stale draining anchor stops draining at the horizon, and a projected depletion keeps
 * the moment it crossed zero.
 *
 * The two hour rule tests pin a fixed instant in a fixed zone where the JS reads the
 * machine's real clock, so CI gets the same answer anywhere.
 */
class ProjectionTest {
    private val ams: ZoneId = ZoneId.of("Europe/Amsterdam")
    private val env = ProjectionEnv(
        capacity = 1800.0,
        rechargePerMin = 5.0,
        cooldownSeconds = 600,
        zone = ams,
    )

    /** Charge per second while recharging. */
    private val rate = env.rechargePerMin / 60.0

    /** Midday, so a two hour window either side of it stays on one day. */
    private val now = ZonedDateTime.of(2026, 6, 10, 12, 0, 0, 0, ams).toInstant().toEpochMilli()

    private fun hm(millis: Long): String {
        val z = Instant.ofEpochMilli(millis).atZone(ams)
        return "%02d:%02d".format(z.hour, z.minute)
    }

    @Test
    fun `a live draining anchor drains 1s per sec inside the horizon`() {
        val p = Projection.projectAnchor(Anchor(charge = 900.0, asOf = 0, draining = true), 30 * 1000L, env)
        assertEquals(870.0, p.charge, 1e-6)
        assertEquals(false, p.depleted)
    }

    @Test
    fun `a stale draining anchor stops draining at the horizon and recharges after`() {
        val threeHours = 3 * 3600 * 1000L
        val p = Projection.projectAnchor(Anchor(charge = 900.0, asOf = 0, draining = true), threeHours, env)
        val afterHorizon = 900 - Constants.DRAIN_HORIZON_MS / 1000.0
        val expected = afterHorizon + ((threeHours - Constants.DRAIN_HORIZON_MS) / 1000.0) * rate
        assertTrue(abs(p.charge - min(env.capacity, expected)) < 1e-6)
        assertEquals(false, p.depleted)
        // The old unbounded projection returned 0 here, which bled every synced device dry
        // when a writer vanished mid drain.
        assertTrue(p.charge > afterHorizon)
    }

    @Test
    fun `re-reading the same stale anchor later never lowers the projection`() {
        val a = Anchor(charge = 600.0, asOf = 0, draining = true)
        var prev = Double.NEGATIVE_INFINITY
        for (mins in listOf(2, 5, 10, 30, 120)) {
            val p = Projection.projectAnchor(a, mins * 60 * 1000L, env)
            assertTrue(p.charge >= prev)
            prev = p.charge
        }
    }

    @Test
    fun `a drain crossing zero depletes at the moment it crossed not the moment read`() {
        val p = Projection.projectAnchor(Anchor(charge = 30.0, asOf = 0, draining = true), 60 * 1000L, env)
        assertEquals(true, p.depleted)
        assertEquals(30 * 1000L, p.depletedAt)
        // Recharge already ran from the crossing to now.
        assertEquals(30 * rate, p.charge, 1e-6)
    }

    @Test
    fun `two devices reading a depleting anchor at different times agree on depletedAt`() {
        val a = Anchor(charge = 30.0, asOf = 0, draining = true)
        val p1 = Projection.projectAnchor(a, 45 * 1000L, env)
        val p2 = Projection.projectAnchor(a, 80 * 1000L, env)
        assertEquals(p1.depletedAt, p2.depletedAt)
    }

    @Test
    fun `a depleted anchor stays depleted inside the cooldown and recharges through it`() {
        val p = Projection.projectAnchor(
            Anchor(charge = 0.0, asOf = 0, draining = false, depleted = true, depletedAt = 0),
            5 * 60 * 1000L, env,
        )
        assertEquals(true, p.depleted)
        assertEquals(5 * 60 * rate, p.charge, 1e-6)
    }

    @Test
    fun `a depleted anchor revives once the cooldown has passed with charge banked`() {
        val p = Projection.projectAnchor(
            Anchor(charge = 0.0, asOf = 0, draining = false, depleted = true, depletedAt = 0),
            11 * 60 * 1000L, env,
        )
        assertEquals(false, p.depleted)
        assertNull(p.depletedAt)
    }

    @Test
    fun `with zero recharge a depleted anchor never flaps back alive`() {
        val dead = env.copy(rechargePerMin = 0.0)
        val p = Projection.projectAnchor(
            Anchor(charge = 0.0, asOf = 0, draining = false, depleted = true, depletedAt = 0),
            24 * 3600 * 1000L, dead,
        )
        assertEquals(true, p.depleted)
        assertEquals(0.0, p.charge, 1e-6)
    }

    @Test
    fun `recharge caps at capacity`() {
        val p = Projection.projectAnchor(
            Anchor(charge = 1790.0, asOf = 0, draining = false), 3600 * 1000L, env,
        )
        assertEquals(env.capacity, p.charge, 1e-6)
    }

    @Test
    fun `an anchor from the future projects as is`() {
        val p = Projection.projectAnchor(
            Anchor(charge = 500.0, asOf = 60 * 1000L, draining = true), 0, env,
        )
        assertEquals(500.0, p.charge, 1e-6)
        assertEquals(false, p.depleted)
    }

    @Test
    fun `a draining flag on an already depleted anchor never drains`() {
        val p = Projection.projectAnchor(
            Anchor(charge = 10.0, asOf = 0, draining = true, depleted = true, depletedAt = 0),
            30 * 1000L, env,
        )
        assertTrue(p.charge > 10) // recharged, not drained
    }

    @Test
    fun `hour rules in the env route recharge through the piecewise walk`() {
        // Hour windows are wall clock times. Charging is stopped in a window around now,
        // so an idle anchor from an hour ago must not have climbed.
        val withRules = env.copy(
            hourRules = listOf(
                HourRule(
                    id = "hr", from = hm(now - 120 * 60_000L), to = hm(now + 120 * 60_000L),
                    action = RuleAction.RECHARGE, percent = 0,
                )
            )
        )
        val anchor = Anchor(charge = 300.0, asOf = now - 3600 * 1000L, draining = false)
        val p = Projection.projectAnchor(anchor, now, withRules)
        assertTrue(abs(p.charge - 300) < 1)
        // The same anchor without rules recharges the whole hour.
        val flat = Projection.projectAnchor(anchor, now, env)
        assertTrue(flat.charge > 500)
    }

    @Test
    fun `serverOffset in the env puts the recharge window back on the wall clock`() {
        // Anchor timestamps are server time, hour windows are local wall time. The offset
        // in the env converts back, so a device two hours off the server still reads its
        // own windows.
        val offset = 2 * 3600 * 1000L
        val wallNow = now
        val withOffset = env.copy(
            rechargePerMin = 60.0,
            serverOffset = offset,
            hourRules = listOf(
                HourRule(
                    id = "hr", from = hm(wallNow - 30 * 60_000L), to = hm(wallNow + 30 * 60_000L),
                    action = RuleAction.RECHARGE, percent = 0,
                )
            ),
        )
        val serverNow = wallNow + offset
        val anchor = Anchor(charge = 300.0, asOf = serverNow - 20 * 60_000L, draining = false)

        val p = Projection.projectAnchor(anchor, serverNow, withOffset)
        assertTrue(abs(p.charge - 300) < 1) // charging stopped: nothing climbed

        // At raw server time the same window sits two hours in the past, so the gap
        // charges instead.
        val p2 = Projection.projectAnchor(anchor, serverNow, withOffset.copy(serverOffset = 0))
        assertTrue(p2.charge > 315)
    }
}
