package site.dirt23.battery.core.rules

import site.dirt23.battery.core.model.HourRule
import site.dirt23.battery.core.model.RawHourRule
import site.dirt23.battery.core.model.RuleAction
import site.dirt23.battery.core.model.RuleScope
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The extension's rules.test.js, ported test by test. The JS suite builds its windows off
 * the machine's real clock; this one pins a fixed instant in a fixed zone, so the same wall
 * clock times come out on any CI machine. The margins are as wide as the originals, so
 * minute truncation still cannot push "now" outside a window.
 */
class RulesTest {
    private val ams: ZoneId = ZoneId.of("Europe/Amsterdam")

    /** Midday, well clear of midnight, so a two hour margin either side stays on one day. */
    private val now = ts(2026, 6, 10, 12, 0)

    private fun ts(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, ams).toInstant().toEpochMilli()

    private fun hm(millis: Long): String {
        val z = Instant.ofEpochMilli(millis).atZone(ams)
        return "%02d:%02d".format(z.hour, z.minute)
    }

    private fun minsAgo(m: Int): Long = now - m * 60_000L
    private fun minsAhead(m: Int): Long = now + m * 60_000L

    private fun openWindow(
        id: String = "r1",
        action: RuleAction,
        scope: RuleScope = RuleScope.ALL,
        sites: List<String> = emptyList(),
        percent: Int = 0,
        minutes: Int = 0,
    ) = HourRule(id, hm(minsAgo(120)), hm(minsAhead(120)), action, scope, sites, percent, minutes)

    private fun closedWindow(
        id: String = "r2",
        action: RuleAction,
        scope: RuleScope = RuleScope.ALL,
        sites: List<String> = emptyList(),
        percent: Int = 0,
        minutes: Int = 0,
    ) = HourRule(id, hm(minsAhead(120)), hm(minsAhead(240)), action, scope, sites, percent, minutes)

    private val env = Rules.RechargeEnv(capacity = 100_000.0, rechargePerMin = 60.0)

    // --- sanitizeRules ---------------------------------------------------------------

    @Test
    fun `sanitizeRules keeps well formed rules and drops broken ones`() {
        val out = Rules.sanitizeRules(
            listOf(
                RawHourRule(id = "a", from = "22:00", to = "07:00", action = "block", scope = "all"),
                RawHourRule(id = "b", from = "9:00", to = "17:30", action = "recharge", percent = 250.0),
                RawHourRule(id = "c", from = "10:00", to = "10:00", action = "block"), // equal times
                RawHourRule(id = "d", from = "25:00", to = "11:00", action = "block"), // bad time
                RawHourRule(id = "e", from = "10:00", to = "11:00", action = "nonsense"), // bad action
                RawHourRule(id = "f", from = "10:00", to = "11:00", action = "only", scope = "only"),
                RawHourRule(
                    id = "g", from = "10:00", to = "11:00", action = "off",
                    scope = "only", sites = emptyList(),
                ), // only + nothing picked
                RawHourRule(id = "h", from = "10:00", to = "11:00", action = "capacity", minutes = 99999.0),
                // The JS suite ends with 'junk' and null. A bare string is not a shape this
                // type admits, so both stand in as nulls.
                null,
                null,
            )
        )
        assertEquals(listOf("a", "b", "h"), out.map { it.id })
        assertEquals(100, out[1].percent) // clamped
        assertEquals(1440, out[2].minutes) // clamped
        assertEquals(RuleScope.ALL, out[0].scope)
        assertEquals(emptyList(), out[0].sites)
    }

    @Test
    fun `sanitizeRules survives non arrays and mints missing ids`() {
        // The JS calls sanitizeRules(null) and sanitizeRules('junk'); null is the only one
        // the signature admits, and it is the same "not an array" branch.
        assertEquals(emptyList(), Rules.sanitizeRules(null))
        val out = Rules.sanitizeRules(listOf(RawHourRule(from = "10:00", to = "11:00", action = "block")))
        assertEquals(1, out.size)
        assertTrue(out[0].id.length > 1)
    }

    // --- when a window is open: plain and midnight wrapping ---------------------------
    //
    // Openness is asked through the effects, since that is how callers ask it: one block
    // rule covering everything, so the answer is the window.

    private fun windowOpenAt(rule: HourRule, atMillis: Long): Boolean =
        Rules.siteEffectsAt(listOf(rule), "youtube", atMillis, ams).blocked

    @Test
    fun `a window covers its own span and stays shut outside it`() {
        assertEquals(true, windowOpenAt(openWindow(action = RuleAction.BLOCK), now))
        assertEquals(false, windowOpenAt(closedWindow(action = RuleAction.BLOCK), now))
    }

    @Test
    fun `a window wraps midnight when from is later than to`() {
        val night = HourRule("n", "22:00", "07:00", RuleAction.BLOCK)
        assertEquals(true, windowOpenAt(night, ts(2026, 6, 10, 23, 30)))
        assertEquals(true, windowOpenAt(night, ts(2026, 6, 10, 3, 0)))
        assertEquals(false, windowOpenAt(night, ts(2026, 6, 10, 12, 0)))
        assertEquals(false, windowOpenAt(night, ts(2026, 6, 10, 7, 0))) // end is exclusive
    }

    // --- appliesTo / siteEffectsAt ----------------------------------------------------

    @Test
    fun `appliesTo honors all only and except scopes`() {
        fun rule(scope: RuleScope, sites: List<String>) =
            HourRule("x", "10:00", "11:00", RuleAction.BLOCK, scope, sites)

        assertEquals(true, Rules.appliesTo(rule(RuleScope.ALL, emptyList()), "youtube"))
        assertEquals(true, Rules.appliesTo(rule(RuleScope.ONLY, listOf("youtube")), "youtube"))
        assertEquals(false, Rules.appliesTo(rule(RuleScope.ONLY, listOf("youtube")), "reddit"))
        assertEquals(false, Rules.appliesTo(rule(RuleScope.EXCEPT, listOf("youtube")), "youtube"))
        assertEquals(true, Rules.appliesTo(rule(RuleScope.EXCEPT, listOf("youtube")), "reddit"))
    }

    @Test
    fun `siteEffectsAt reports block and off from open windows only`() {
        val rules = listOf(
            openWindow("b1", RuleAction.BLOCK, RuleScope.ONLY, listOf("youtube")),
            openWindow("o1", RuleAction.OFF, RuleScope.ONLY, listOf("reddit")),
            closedWindow("b2", RuleAction.BLOCK, RuleScope.ALL),
        )
        assertEquals(
            Rules.SiteEffects(blocked = true, off = false),
            Rules.siteEffectsAt(rules, "youtube", now, ams),
        )
        assertEquals(
            Rules.SiteEffects(blocked = false, off = true),
            Rules.siteEffectsAt(rules, "reddit", now, ams),
        )
        assertEquals(
            Rules.SiteEffects(blocked = false, off = false),
            Rules.siteEffectsAt(rules, "tiktok", now, ams),
        )
    }

    // --- charging effects -------------------------------------------------------------

    @Test
    fun `chargeFactorAt takes the strictest open recharge window`() {
        val rules = listOf(
            openWindow("a", RuleAction.RECHARGE, percent = 50),
            openWindow("b", RuleAction.RECHARGE, percent = 0),
            closedWindow("c", RuleAction.RECHARGE, percent = 10),
        )
        assertEquals(0.0, Rules.chargeFactorAt(rules, now, ams), 1e-6)
        assertEquals(0.5, Rules.chargeFactorAt(listOf(rules[0]), now, ams), 1e-6)
        assertEquals(1.0, Rules.chargeFactorAt(listOf(rules[2]), now, ams), 1e-6)
    }

    @Test
    fun `capacityAt lowers to the strictest open capacity window`() {
        val rules = listOf(
            openWindow("a", RuleAction.CAPACITY, minutes = 10),
            closedWindow("b", RuleAction.CAPACITY, minutes = 2),
        )
        assertEquals(600.0, Rules.capacityAt(rules, now, 1800.0, ams), 1e-6)
        assertEquals(1800.0, Rules.capacityAt(emptyList(), now, 1800.0, ams), 1e-6)
    }

    // --- projectRecharge: the piecewise walk ------------------------------------------

    @Test
    fun `projectRecharge with no rules equals the flat formula`() {
        val c = Rules.projectRecharge(
            emptyList(), 100.0, now - 600_000, now,
            Rules.RechargeEnv(1800.0, 6.0), ams,
        )
        assertTrue(abs(c - 160) < 0.001) // 10 min at 6/min
    }

    @Test
    fun `projectRecharge skips the part of the gap a stopped charging window covers`() {
        // Gap: the last 2 hours. Charging stopped from 1 hour ago until 1 hour ahead:
        // only the first hour of the gap recharges.
        val rules = Rules.sanitizeRules(
            listOf(
                RawHourRule(
                    from = hm(minsAgo(60)), to = hm(minsAhead(60)),
                    action = "recharge", percent = 0.0,
                )
            )
        )
        val c = Rules.projectRecharge(rules, 0.0, now - 2 * 3600 * 1000L, now, env, ams)
        assertTrue(abs(c - 3600) < 70, "expected ~3600, got $c") // minute truncation slack
    }

    @Test
    fun `projectRecharge halves the rate inside a percent window`() {
        val rules = Rules.sanitizeRules(
            listOf(
                RawHourRule(
                    from = hm(minsAgo(120)), to = hm(minsAhead(120)),
                    action = "recharge", percent = 50.0,
                )
            )
        )
        val c = Rules.projectRecharge(rules, 0.0, now - 3600 * 1000L, now, env, ams)
        assertTrue(abs(c - 1800) < 70, "expected ~1800, got $c")
    }

    @Test
    fun `a capacity window clamps banked charge even when slept through`() {
        // 3000 banked; a 10 minute (600s) capacity window covered the first half of the
        // gap. Entering it clamps to 600; after it ends the charge climbs again.
        val rules = Rules.sanitizeRules(
            listOf(
                RawHourRule(
                    from = hm(minsAgo(20)), to = hm(minsAgo(10)),
                    action = "capacity", minutes = 10.0,
                )
            )
        )
        val slow = Rules.RechargeEnv(100_000.0, 6.0) // 0.1/s
        val c = Rules.projectRecharge(rules, 3000.0, now - 20 * 60_000L, now, slow, ams)
        // Clamped to 600 at the window, then ~10 min at 6/min = ~60 back.
        assertTrue(abs(c - 660) < 20, "expected ~660, got $c")
    }

    @Test
    fun `projectRecharge never exceeds the base capacity`() {
        val c = Rules.projectRecharge(
            emptyList(), 1700.0, now - 3600 * 1000L, now,
            Rules.RechargeEnv(1800.0, 60.0), ams,
        )
        assertEquals(1800.0, c, 1e-6)
    }

    @Test
    fun `an overnight charging window wraps midnight in the walk`() {
        // Deterministic clock: recharge stopped 22:00 to 07:00. From 21:00 to 08:00
        // (11 h) only 21:00 to 22:00 and 07:00 to 08:00 recharge: 2 h worth.
        val from = ts(2026, 6, 9, 21, 0)
        val to = ts(2026, 6, 10, 8, 0)
        val rules = Rules.sanitizeRules(
            listOf(RawHourRule(from = "22:00", to = "07:00", action = "recharge", percent = 0.0))
        )
        val c = Rules.projectRecharge(rules, 0.0, from, to, env, ams)
        assertTrue(abs(c - 7200) < 1, "expected 7200, got $c")
    }

    // --- DST: the walk runs on wall clock edges over real elapsed time -----------------
    //
    // Not in the JS suite. The boundary walk is where the port could have used millisecond
    // arithmetic and drifted on the two days a year that are not 24 hours long. Expected
    // values come from the extension's rules.js under TZ=Europe/Amsterdam.

    @Test
    fun `the walk holds across the spring forward transition`() {
        // 2026-03-29, 02:00 to 03:00 never happens: the day is 23 hours long.
        val night = Rules.sanitizeRules(
            listOf(RawHourRule(from = "22:00", to = "07:00", action = "recharge", percent = 0.0))
        )
        // The skipped hour falls inside the closed window, so the two open hours are all
        // that charge, exactly as on an ordinary night.
        val overnight = Rules.projectRecharge(
            night, 0.0, ts(2026, 3, 28, 21, 0), ts(2026, 3, 29, 8, 0), env, ams,
        )
        assertEquals(7200.0, overnight, 1e-6)
        assertInRange(overnight)

        // Outside the window the walk is the flat formula over real elapsed time: 01:00 to
        // 05:00 is three hours that day, not four.
        val late = Rules.sanitizeRules(
            listOf(RawHourRule(from = "22:00", to = "01:00", action = "recharge", percent = 0.0))
        )
        val from = ts(2026, 3, 29, 1, 0)
        val to = ts(2026, 3, 29, 5, 0)
        val outside = Rules.projectRecharge(late, 0.0, from, to, env, ams)
        assertEquals(flat(from, to), outside, 1e-6)
        assertEquals(10800.0, outside, 1e-6)
        assertInRange(outside)

        // A window that lives entirely in the skipped hour collapses to nothing: both its
        // edges resolve forward to the same instant, 03:30, so nothing is ever stopped.
        val inGap = Rules.sanitizeRules(
            listOf(RawHourRule(from = "02:30", to = "03:30", action = "recharge", percent = 0.0))
        )
        val gapFrom = ts(2026, 3, 29, 1, 0)
        val gapTo = ts(2026, 3, 29, 6, 0)
        val across = Rules.projectRecharge(inGap, 0.0, gapFrom, gapTo, env, ams)
        assertEquals(flat(gapFrom, gapTo), across, 1e-6)
        assertEquals(14400.0, across, 1e-6)
        assertInRange(across)
    }

    @Test
    fun `the walk holds across the fall back transition`() {
        // 2026-10-25, 02:00 to 03:00 happens twice: the day is 25 hours long.
        val night = Rules.sanitizeRules(
            listOf(RawHourRule(from = "22:00", to = "07:00", action = "recharge", percent = 0.0))
        )
        // The repeated hour falls inside the closed window, so again only the two open
        // hours charge even though ten real hours passed.
        val overnight = Rules.projectRecharge(
            night, 0.0, ts(2026, 10, 24, 21, 0), ts(2026, 10, 25, 8, 0), env, ams,
        )
        assertEquals(7200.0, overnight, 1e-6)
        assertInRange(overnight)

        // Outside the window: 01:00 to 05:00 is five real hours that day.
        val late = Rules.sanitizeRules(
            listOf(RawHourRule(from = "22:00", to = "01:00", action = "recharge", percent = 0.0))
        )
        val from = ts(2026, 10, 25, 1, 0)
        val to = ts(2026, 10, 25, 5, 0)
        val outside = Rules.projectRecharge(late, 0.0, from, to, env, ams)
        assertEquals(flat(from, to), outside, 1e-6)
        assertEquals(18000.0, outside, 1e-6)
        assertInRange(outside)

        // A one hour window sitting on the repeated hour costs two: it opens at the first
        // 02:30 and closes at the single 03:30, which is two hours of real time later.
        val inFold = Rules.sanitizeRules(
            listOf(RawHourRule(from = "02:30", to = "03:30", action = "recharge", percent = 0.0))
        )
        val foldFrom = ts(2026, 10, 25, 1, 0)
        val foldTo = ts(2026, 10, 25, 6, 0)
        val across = Rules.projectRecharge(inFold, 0.0, foldFrom, foldTo, env, ams)
        assertEquals(14400.0, across, 1e-6)
        assertEquals(21600.0, flat(foldFrom, foldTo), 1e-6)
        assertInRange(across)
    }

    /** What the walk would give with no rules at all: every second at the full rate. */
    private fun flat(from: Long, to: Long): Double =
        minOf(env.capacity, ((to - from) / 1000.0) * (env.rechargePerMin / 60.0))

    private fun assertInRange(charge: Double) {
        assertTrue(charge >= 0.0 && charge <= env.capacity, "out of range: $charge")
    }
}
