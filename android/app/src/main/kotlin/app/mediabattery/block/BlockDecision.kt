package app.mediabattery.block

import site.dirt23.battery.core.model.HourRule
import site.dirt23.battery.core.model.SiteMode
import site.dirt23.battery.core.model.Snapshot
import site.dirt23.battery.core.rules.Rules
import java.time.Instant
import java.time.ZoneId

/** What, if anything, belongs over the app in front. */
sealed interface BlockDecision {
    /** Nothing. The app is free to use. */
    data object None : BlockDecision

    /** The battery ran out. Everything tracked is covered until the cooldown lifts. */
    data class Dead(val cooldownRemaining: Double, val rechargePaused: Boolean) : BlockDecision

    /**
     * This one app is covered, by the settings Block mode or by an open hour rule. The
     * battery is untouched.
     *
     * [untilMinuteOfDay] is when the cover lifts, if that can be named: an hour rule with an
     * edge inside the next day. A settings Block has no end, and neither do rules covering
     * the whole day, so it is null there and the copy says where the cover came from instead.
     * [fromSettings] picks between those two, as the extension's overlay does.
     */
    data class AppBlocked(
        val id: String,
        val untilMinuteOfDay: Int?,
        val fromSettings: Boolean,
    ) : BlockDecision
}

/**
 * Snapshot, mode and rules in; one decision out.
 *
 * Two orderings carry the invariants. Off outranks block, so an app switched off in settings
 * or covered by an open off rule is never covered by anything, which is what makes an off
 * rule an exemption rather than a loophole. And dead outranks an app block, since a five
 * minute pass is worthless on a dead battery.
 */
object BlockDecisions {
    fun decide(
        snapshot: Snapshot,
        id: String?,
        mode: SiteMode,
        rules: List<HourRule>,
        nowWallMs: Long,
        zone: ZoneId,
        /**
         * When a rule block lifts. A function, called only when a rule block is what actually
         * holds, because working it out walks a day of minutes: the cover's page hands in an
         * answer it keeps for as long as the cover stands.
         */
        liftsAt: () -> Int? = { id?.let { liftsAt(rules, it, nowWallMs, zone) } },
    ): BlockDecision {
        if (id == null) return BlockDecision.None

        val effects = Rules.siteEffectsAt(rules, id, nowWallMs, zone)
        if (mode == SiteMode.OFF || effects.off) return BlockDecision.None

        if (snapshot.depleted) {
            return BlockDecision.Dead(snapshot.cooldownRemaining, snapshot.rechargePaused)
        }

        val blocked = mode == SiteMode.BLOCK || effects.blocked
        if (!blocked) return BlockDecision.None
        // A paid pass is a hole in this one cover and nothing else.
        if ((snapshot.sitePasses[id] ?: 0) > 0) return BlockDecision.None

        return BlockDecision.AppBlocked(
            id = id,
            untilMinuteOfDay = if (mode == SiteMode.BLOCK) null else liftsAt(),
            fromSettings = mode == SiteMode.BLOCK,
        )
    }

    /**
     * The minute of the day the rule block lifts, or null when nothing lifts inside a day.
     * Minute by minute like the extension's overlay: rule edges are minutes and a day is 1440
     * of them, so this is the costly part of a decision and worth asking once. While the block
     * holds the answer does not move, so only the app or the rules can change it.
     */
    fun liftsAt(rules: List<HourRule>, id: String, nowWallMs: Long, zone: ZoneId): Int? {
        for (i in 1..1440) {
            val at = nowWallMs + i * 60_000L
            if (!Rules.siteEffectsAt(rules, id, at, zone).blocked) {
                val z = Instant.ofEpochMilli(at).atZone(zone)
                return z.hour * 60 + z.minute
            }
        }
        return null
    }
}
