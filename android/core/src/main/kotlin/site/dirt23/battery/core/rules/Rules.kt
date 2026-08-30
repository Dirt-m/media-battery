package site.dirt23.battery.core.rules

import site.dirt23.battery.core.model.HourRule
import site.dirt23.battery.core.model.RawHourRule
import site.dirt23.battery.core.model.RuleAction
import site.dirt23.battery.core.model.RuleScope
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The pure hour rule math, ported from the extension's rules.js. Rules plus a timestamp in,
 * effects out, so the engine, the UI and the tests all read the same answer.
 *
 * The non-trivial piece is [projectRecharge], which walks a span piecewise across every
 * charging rule edge, so a gap the device slept through recharges what the windows allowed.
 *
 * Time zone is an explicit argument. JS reads a wall clock off `new Date(ts)` using the
 * system zone; here every conversion takes a [ZoneId], defaulting to the system zone for
 * convenience but passed explicitly by the tests and by anything projecting an anchor.
 * Conversions go through [ZonedDateTime] rather than millisecond arithmetic so a walk
 * across a DST transition lands on the instants a browser in that zone computes: a local
 * time a spring forward skipped resolves forward, one a fall back repeated resolves to the
 * first of the two, matching the ECMAScript Date constructor.
 */
object Rules {
    private val HM = Regex("""^(\d{1,2}):(\d{2})$""")
    /** How many rules [sanitizeRules] keeps. Public because the editor stops there too. */
    const val MAX_RULES = 32
    private const val MAX_SITES = 64
    private const val ID_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"

    /** Result of [siteEffectsAt]. */
    data class SiteEffects(val blocked: Boolean, val off: Boolean)

    /** Seconds, and seconds per minute. */
    data class RechargeEnv(val capacity: Double, val rechargePerMin: Double)

    /** "HH:MM" to minutes since local midnight, or null when it is not a time of day. */
    fun parseHM(s: String?): Int? {
        val m = HM.matchEntire(s ?: "") ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        return h * 60 + min
    }

    /**
     * Storage and sync both funnel through this, and what comes out is a bounded list of
     * well formed rules. A rule that cannot mean anything (equal times, unknown action,
     * "only" with no sites picked) is dropped rather than kept half working. A null list is
     * the "not an array" case and yields nothing.
     *
     * The two numeric fields are deliberately asymmetric, as in the extension: a recharge
     * percent that is not a number falls back to 0 (charging stopped, the strict reading),
     * while a capacity rule with non numeric minutes is dropped, since there is no safe cap
     * to guess.
     */
    fun sanitizeRules(raw: List<RawHourRule?>?): List<HourRule> {
        if (raw == null) return emptyList()
        val out = ArrayList<HourRule>()
        for (r in raw) {
            if (r == null) continue
            val from = parseHM(r.from)
            val to = parseHM(r.to)
            if (from == null || to == null || from == to) continue
            val action = RuleAction.fromWire(r.action) ?: continue
            val id = if (!r.id.isNullOrEmpty()) r.id.take(32) else mintId()
            val base = HourRule(id = id, from = r.from!!, to = r.to!!, action = action)

            val rule: HourRule
            if (action == RuleAction.RECHARGE) {
                val p = r.percent
                val pct = if (p != null && p.isFinite()) clamp(jsRound(p), 0L, 100L).toInt() else 0
                rule = base.copy(percent = pct)
            } else if (action == RuleAction.CAPACITY) {
                val mins = r.minutes
                if (mins == null || !mins.isFinite()) continue
                rule = base.copy(minutes = clamp(jsRound(mins), 1L, 1440L).toInt())
            } else {
                val scope = RuleScope.fromWire(r.scope) ?: RuleScope.ALL
                val sites = (r.sites ?: emptyList()).filter { !it.isNullOrEmpty() }
                    .take(MAX_SITES).map { it!! }
                if (scope == RuleScope.ONLY && sites.isEmpty()) continue
                rule = base.copy(scope = scope, sites = sites)
            }

            out.add(rule)
            if (out.size >= MAX_RULES) break
        }
        return out
    }

    /** Does the rule's scope cover this site? Recharge and capacity rules cover everything. */
    fun appliesTo(rule: HourRule, siteId: String): Boolean {
        if (rule.scope == RuleScope.ALL) return true
        val picked = rule.sites.contains(siteId)
        return if (rule.scope == RuleScope.ONLY) picked else !picked
    }

    /**
     * What the open windows say about one site right now. `off` means an open off rule
     * covers it, so it neither drains nor gets covered, like a site switched off in settings.
     */
    fun siteEffectsAt(
        rules: List<HourRule>,
        siteId: String,
        atMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): SiteEffects {
        val m = minutesOfDay(atMillis, zone)
        var blocked = false
        var off = false
        for (r in rules) {
            if (r.action != RuleAction.BLOCK && r.action != RuleAction.OFF) continue
            if (!activeAtMinutes(r, m)) continue
            if (!appliesTo(r, siteId)) continue
            if (r.action == RuleAction.BLOCK) blocked = true else off = true
        }
        return SiteEffects(blocked, off)
    }

    /** 1 with no open recharge window, else the strictest open one. 0 stops charging. */
    fun chargeFactorAt(
        rules: List<HourRule>,
        atMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Double {
        val m = minutesOfDay(atMillis, zone)
        var f = 1.0
        for (r in rules) {
            if (r.action == RuleAction.RECHARGE && activeAtMinutes(r, m)) {
                f = min(f, r.percent / 100.0)
            }
        }
        return f
    }

    /** Base capacity lowered by any open capacity window, strictest winning. */
    fun capacityAt(
        rules: List<HourRule>,
        atMillis: Long,
        capacity: Double,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Double {
        val m = minutesOfDay(atMillis, zone)
        var cap = capacity
        for (r in rules) {
            if (r.action == RuleAction.CAPACITY && activeAtMinutes(r, m)) {
                cap = min(cap, r.minutes * 60.0)
            }
        }
        return cap
    }

    /**
     * The next instant strictly after [t] that any charging rule starts or ends, else
     * [Long.MAX_VALUE] (the JS returns Infinity; callers only take a min against it). Block
     * and off rules never touch the charge, so their edges do not segment the walk.
     *
     * Boundaries are a local date plus a local time, never a millisecond offset, so a DST
     * day keeps its wall times: the day the clocks go forward is 23 hours long and its
     * 22:00 edge still lands at 22:00.
     */
    fun nextChargeBoundary(
        rules: List<HourRule>,
        t: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        var best = Long.MAX_VALUE
        val day = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        for (r in rules) {
            if (r.action != RuleAction.RECHARGE && r.action != RuleAction.CAPACITY) continue
            for (mark in listOf(parseHM(r.from), parseHM(r.to))) {
                if (mark == null) continue
                val time = LocalTime.of(mark / 60, mark % 60)
                var next = ZonedDateTime.of(day, time, zone).toInstant().toEpochMilli()
                if (next <= t) {
                    next = ZonedDateTime.of(day.plusDays(1), time, zone).toInstant().toEpochMilli()
                }
                if (next > t && next < best) best = next
            }
        }
        return best
    }

    /**
     * Recharge from [fromTs] to [toTs], piecewise per charging window. Each segment
     * recharges at the window's factor and clamps to its capacity, and entering a lower
     * capacity window clamps the banked charge too, so sleeping through a window costs the
     * same as living through it. With no charging rules this is the flat formula.
     *
     * The guard bounds the walk at decades of daily edges; past it the rest finishes flat
     * rather than spinning, the same bail out the extension has.
     */
    fun projectRecharge(
        rules: List<HourRule>,
        charge: Double,
        fromTs: Long,
        toTs: Long,
        env: RechargeEnv,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Double {
        var c = max(0.0, if (charge.isFinite()) charge else 0.0)
        var t = fromTs
        val perSec = env.rechargePerMin / 60.0
        var guard = 40_000
        while (t < toTs && guard-- > 0) {
            val cap = capacityAt(rules, t, env.capacity, zone)
            val factor = chargeFactorAt(rules, t, zone)
            val next = minOf(toTs, nextChargeBoundary(rules, t, zone))
            c = min(c, cap)
            c = min(cap, c + ((next - t) / 1000.0) * perSec * factor)
            t = next
        }
        if (t < toTs) c += ((toTs - t) / 1000.0) * perSec
        return min(c, env.capacity)
    }

    /**
     * `from` after `to` wraps midnight (22:00 to 07:00 covers the night). The end minute is
     * exclusive so back to back windows never overlap.
     */
    private fun activeAtMinutes(rule: HourRule, m: Int): Boolean {
        val from = parseHM(rule.from) ?: return false
        val to = parseHM(rule.to) ?: return false
        return if (from < to) m >= from && m < to else m >= from || m < to
    }

    private fun minutesOfDay(atMillis: Long, zone: ZoneId): Int {
        val z = Instant.ofEpochMilli(atMillis).atZone(zone)
        return z.hour * 60 + z.minute
    }

    /**
     * JS Math.round is floor(x + 0.5), ties toward positive infinity. Not kotlin.math.round,
     * which is rint and breaks ties to even.
     */
    private fun jsRound(x: Double): Long = Math.round(x)

    private fun clamp(v: Long, lo: Long, hi: Long): Long = min(hi, max(lo, v))

    private fun mintId(): String = buildString {
        append('r')
        repeat(8) { append(ID_CHARS[Random.nextInt(ID_CHARS.length)]) }
    }
}
