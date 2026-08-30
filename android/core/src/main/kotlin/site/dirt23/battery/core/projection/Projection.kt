package site.dirt23.battery.core.projection

import site.dirt23.battery.core.Constants
import site.dirt23.battery.core.model.Anchor
import site.dirt23.battery.core.model.HourRule
import site.dirt23.battery.core.rules.Rules
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * The settings a projection runs under. Anchor timestamps are server time while hour
 * windows are wall time, so [serverOffset] is how far server time runs ahead of [zone].
 */
data class ProjectionEnv(
    val capacity: Double,
    val rechargePerMin: Double,
    val cooldownSeconds: Int,
    val hourRules: List<HourRule> = emptyList(),
    val serverOffset: Long = 0L,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** What an anchor implies right now. */
data class Projected(val charge: Double, val depleted: Boolean, val depletedAt: Long?)

/**
 * What a sync anchor implies the charge is right now. Ported from the extension's
 * projection.js. No state, no platform APIs.
 */
object Projection {
    /**
     * Project [a] to [now], mirroring the engine's tick: drain is 1s per second, recharge
     * runs through the cooldown, and depletion lifts once the cooldown has passed and there
     * is charge banked. When the drain crosses zero, `depletedAt` is the moment it crossed,
     * not the moment we looked, so two devices projecting one anchor agree on when the
     * cooldown ends.
     *
     * Drain is bounded by [Constants.DRAIN_HORIZON_MS] past the anchor's `asOf`. A live
     * writer pushes far more often, so silence past the horizon means it is gone and a
     * stale draining anchor costs at most that much phantom drain, not the whole gap.
     *
     * The crossing moment truncates to whole milliseconds where JS keeps a fraction. The
     * cooldown it feeds is ten minutes long.
     */
    fun projectAnchor(a: Anchor, now: Long, env: ProjectionEnv): Projected {
        var charge = max(0.0, if (a.charge.isFinite()) a.charge else 0.0)
        var depleted = a.depleted
        var depletedAt = a.depletedAt
        var t = min(a.asOf, now) // an anchor from the future projects as is

        if (a.draining && !depleted) {
            val drainEnd = min(now, a.asOf + Constants.DRAIN_HORIZON_MS)
            val drained = max(0.0, (drainEnd - t) / 1000.0)
            if (drained < charge) {
                charge -= drained
                t = drainEnd
            } else {
                t += (charge * 1000).toLong()
                charge = 0.0
                depleted = true
                depletedAt = t
            }
        }

        // Everything after the drain window recharges, dead or alive. With hour rules the
        // recharge is the piecewise walk, shifted by the server offset so the windows land
        // on the device's wall clock. A skewed device diverges only at window edges, which
        // lower-wins absorbs.
        charge = if (t < now && env.hourRules.isNotEmpty()) {
            Rules.projectRecharge(
                env.hourRules,
                charge,
                t - env.serverOffset,
                now - env.serverOffset,
                Rules.RechargeEnv(env.capacity, env.rechargePerMin),
                env.zone,
            )
        } else {
            min(env.capacity, charge + max(0.0, (now - t) / 1000.0) * (env.rechargePerMin / 60.0))
        }

        if (depleted) {
            val began = depletedAt ?: t
            if (now - began >= env.cooldownSeconds * 1000L && charge > 0) {
                depleted = false
                depletedAt = null
            }
        }

        return Projected(charge, depleted, depletedAt)
    }
}
