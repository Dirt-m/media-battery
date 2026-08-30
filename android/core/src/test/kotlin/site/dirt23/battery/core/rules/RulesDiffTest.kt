package site.dirt23.battery.core.rules

import site.dirt23.battery.core.model.HourRule
import site.dirt23.battery.core.model.RuleAction
import site.dirt23.battery.core.model.RuleScope
import site.dirt23.battery.core.model.SiteMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The extension's options.js loosening decision, pinned. Watch the asymmetries: an off rule
 * is the only permissive one, and an edit gates whichever way it moves the window.
 */
class RulesDiffTest {

    private fun block(id: String, from: String = "22:00", to: String = "07:00") =
        HourRule(id = id, from = from, to = to, action = RuleAction.BLOCK, scope = RuleScope.ALL)

    private fun off(id: String, from: String = "12:00", to: String = "13:00") =
        HourRule(id = id, from = from, to = to, action = RuleAction.OFF, scope = RuleScope.ALL)

    private fun recharge(id: String, percent: Int) =
        HourRule(id = id, from = "22:00", to = "07:00", action = RuleAction.RECHARGE, percent = percent)

    private fun capacity(id: String, minutes: Int) =
        HourRule(id = id, from = "22:00", to = "07:00", action = RuleAction.CAPACITY, minutes = minutes)

    private val base = RulesDiff.Settings(rechargePerMin = 5.0, capacity = 1800.0)

    // --- rules ---------------------------------------------------------------------

    @Test
    fun `no change never gates`() {
        val rules = listOf(block("a"), off("b"), recharge("c", 50))
        assertFalse(RulesDiff.rulesLoosening(rules, rules))
    }

    @Test
    fun `adding a restrictive rule is free`() {
        assertFalse(RulesDiff.rulesLoosening(emptyList(), listOf(block("a"))))
        assertFalse(RulesDiff.rulesLoosening(emptyList(), listOf(recharge("a", 0))))
        assertFalse(RulesDiff.rulesLoosening(emptyList(), listOf(capacity("a", 10))))
    }

    @Test
    fun `adding an off rule gates`() {
        assertTrue(RulesDiff.rulesLoosening(emptyList(), listOf(off("a"))))
    }

    @Test
    fun `removing a restrictive rule gates`() {
        assertTrue(RulesDiff.rulesLoosening(listOf(block("a")), emptyList()))
        assertTrue(RulesDiff.rulesLoosening(listOf(recharge("a", 25)), emptyList()))
        assertTrue(RulesDiff.rulesLoosening(listOf(capacity("a", 10)), emptyList()))
    }

    @Test
    fun `removing an off rule is free`() {
        assertFalse(RulesDiff.rulesLoosening(listOf(off("a")), emptyList()))
    }

    @Test
    fun `any edit gates, whichever way it moves`() {
        // Wider block window, plainly tightening, still gates.
        assertTrue(RulesDiff.rulesLoosening(listOf(block("a")), listOf(block("a", to = "09:00"))))
        // Narrower: loosening, same answer.
        assertTrue(RulesDiff.rulesLoosening(listOf(block("a")), listOf(block("a", to = "06:00"))))
        // Slower charging is stricter and still an edit.
        assertTrue(RulesDiff.rulesLoosening(listOf(recharge("a", 50)), listOf(recharge("a", 10))))
        // Even an off rule, permissive to begin with.
        assertTrue(RulesDiff.rulesLoosening(listOf(off("a")), listOf(off("a", to = "14:00"))))
    }

    @Test
    fun `a scope change is an edit`() {
        val before = listOf(block("a"))
        val after = listOf(block("a").copy(scope = RuleScope.ONLY, sites = listOf("youtube")))
        assertTrue(RulesDiff.rulesLoosening(before, after))
    }

    @Test
    fun `swapping one restrictive rule for another gates on the removal`() {
        assertTrue(RulesDiff.rulesLoosening(listOf(block("a")), listOf(block("b"))))
    }

    @Test
    fun `order does not matter`() {
        val before = listOf(block("a"), off("b"))
        assertFalse(RulesDiff.rulesLoosening(before, listOf(off("b"), block("a"))))
    }

    // --- settings ------------------------------------------------------------------

    @Test
    fun `raising charging speed or capacity gates and lowering is free`() {
        assertTrue(RulesDiff.loosening(base, base.copy(rechargePerMin = 7.5)))
        assertFalse(RulesDiff.loosening(base, base.copy(rechargePerMin = 2.5)))
        assertTrue(RulesDiff.loosening(base, base.copy(capacity = 3600.0)))
        assertFalse(RulesDiff.loosening(base, base.copy(capacity = 600.0)))
    }

    @Test
    fun `an unchanged settings pair never gates`() {
        assertFalse(RulesDiff.loosening(base, base))
    }

    @Test
    fun `a mode dropping rank gates and climbing is free`() {
        val blocked = base.copy(siteModes = mapOf("youtube" to SiteMode.BLOCK))
        val on = base.copy(siteModes = mapOf("youtube" to SiteMode.ON))
        val offMode = base.copy(siteModes = mapOf("youtube" to SiteMode.OFF))
        assertTrue(RulesDiff.loosening(blocked, on))
        assertTrue(RulesDiff.loosening(on, offMode))
        assertTrue(RulesDiff.loosening(blocked, offMode))
        assertFalse(RulesDiff.loosening(on, blocked))
        assertFalse(RulesDiff.loosening(offMode, on))
    }

    @Test
    fun `a missing mode reads as on, on both sides`() {
        val blocked = base.copy(siteModes = mapOf("youtube" to SiteMode.BLOCK))
        // The id vanishing means tracked, which is looser than blocked.
        assertTrue(RulesDiff.loosening(blocked, base))
        // Appearing as On out of nothing is no change.
        assertFalse(RulesDiff.loosening(base, base.copy(siteModes = mapOf("youtube" to SiteMode.ON))))
        // Appearing as Off out of nothing is a drop from the tracked default.
        assertTrue(RulesDiff.loosening(base, base.copy(siteModes = mapOf("youtube" to SiteMode.OFF))))
    }

    @Test
    fun `rules reach the settings level answer`() {
        val before = base.copy(hourRules = listOf(block("a")))
        assertTrue(RulesDiff.loosening(before, base))
        assertFalse(RulesDiff.loosening(base, before))
    }
}
