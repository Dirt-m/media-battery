package site.dirt23.battery.core.rules

import site.dirt23.battery.core.model.HourRule
import site.dirt23.battery.core.model.RuleAction
import site.dirt23.battery.core.model.SiteMode

/**
 * Which settings changes have to be paid for, ported from the extension's options.js
 * (`loosening`, `rulesLoosening`, `ruleRestrictive`, `MODE_RANK`). Given a before and an
 * after: does this move toward more usage? If so, the friction gate goes in front of the
 * save. Tightening is free. Loosening always costs the same flat amount.
 *
 * Android asks in three pieces, following how its screens are cut: the battery numbers card
 * asks [loosening] with a [Settings] holding only the two numbers, the Hours editor asks
 * [rulesLoosening], and the apps list asks [rank] about the one mode a tap moves. So
 * [Settings.siteModes], [Settings.hourRules] and the mode loop in [loosening] are on no
 * Android path; they are the extension's whole-page shape, kept and tested in case a screen
 * later hands over the lot.
 */
object RulesDiff {

    /**
     * The settings the gate cares about. No warning threshold: it changes what the user is
     * told, never what they may do, and the extension does not gate it either.
     */
    data class Settings(
        val rechargePerMin: Double,
        val capacity: Double,
        val siteModes: Map<String, SiteMode> = emptyMap(),
        val hourRules: List<HourRule> = emptyList(),
    )

    /** Block strictest, off loosest. Moving down this order is what costs. */
    fun rank(mode: SiteMode): Int = when (mode) {
        SiteMode.BLOCK -> 2
        SiteMode.ON -> 1
        SiteMode.OFF -> 0
    }

    /** Block, slower charging and lower capacity restrict. An off rule frees its sites. */
    fun ruleRestrictive(rule: HourRule): Boolean = rule.action != RuleAction.OFF

    /**
     * Dropping a restrictive rule, adding a permissive one, or editing any rule at all.
     * Edits always gate, as in the extension: whether an edited window tightens or loosens
     * is too easy to argue about. Rules match by id, so same id plus any other change is an
     * edit.
     */
    fun rulesLoosening(before: List<HourRule>, after: List<HourRule>): Boolean {
        val a = after.associateBy { it.id }
        val b = before.associateBy { it.id }
        for (rule in before) {
            val now = a[rule.id]
            if (now == null) {
                if (ruleRestrictive(rule)) return true
            } else if (now != rule) {
                return true
            }
        }
        for (rule in after) {
            if (rule.id !in b && !ruleRestrictive(rule)) return true
        }
        return false
    }

    /**
     * True if any part of the change moves toward more usage: more daily charge, a bigger
     * battery, an app dropping to a looser mode, or an hour rule giving ground.
     *
     * Modes are compared over the union of both maps, a missing id reading as [SiteMode.ON].
     * The extension iterates only the after map since it rebuilds every row on every save;
     * the union means an id that vanishes is judged rather than waved through.
     */
    fun loosening(before: Settings, after: Settings): Boolean {
        if (after.rechargePerMin > before.rechargePerMin) return true
        if (after.capacity > before.capacity) return true
        for (id in before.siteModes.keys + after.siteModes.keys) {
            val was = before.siteModes[id] ?: SiteMode.ON
            val now = after.siteModes[id] ?: SiteMode.ON
            if (rank(now) < rank(was)) return true
        }
        return rulesLoosening(before.hourRules, after.hourRules)
    }
}
