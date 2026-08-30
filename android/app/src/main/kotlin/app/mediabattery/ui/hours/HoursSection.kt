package app.mediabattery.ui.hours

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mediabattery.AppGraph
import app.mediabattery.R
import app.mediabattery.ui.common.ButtonRow
import app.mediabattery.ui.common.Limits.CAP_MAX
import app.mediabattery.ui.common.Limits.CAP_MIN
import app.mediabattery.ui.common.Limits.CAP_STEP
import app.mediabattery.ui.common.Limits.MAX_RULES
import app.mediabattery.ui.common.Pill
import app.mediabattery.ui.common.PrimaryButton
import app.mediabattery.ui.common.SbCard
import app.mediabattery.ui.common.SecondaryButton
import app.mediabattery.ui.common.SegmentOption
import app.mediabattery.ui.common.Segmented
import app.mediabattery.ui.common.Stepper
import app.mediabattery.ui.friction.GateRequest
import app.mediabattery.ui.theme.SbColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import site.dirt23.battery.core.engine.SettingsPatch
import site.dirt23.battery.core.identity.AppCatalog
import site.dirt23.battery.core.model.HourRule
import site.dirt23.battery.core.model.RawHourRule
import site.dirt23.battery.core.model.RuleAction
import site.dirt23.battery.core.model.RuleScope
import site.dirt23.battery.core.rules.Rules
import site.dirt23.battery.core.rules.RulesDiff
import kotlin.random.Random

/**
 * The hours section of the options page: one card per rule, two times and one action each.
 *
 * A rule holds between two times on the wall clock, wraps midnight, and does exactly one
 * thing. The card shows the two times, the four things a rule can be, and the controls for
 * whichever one is picked.
 *
 * Adding is free: a new rule starts as an overnight block of everything, so Add and Save
 * never costs a gate. Loosening from there does, and what counts as loosening is
 * [RulesDiff], the same answer the extension's options page reaches. Editing a rule at all
 * counts as loosening, since whether a window moved toward more usage or less is too easy
 * to argue about.
 *
 * Everything saved goes out as [RawHourRule] and comes back through `Rules.sanitizeRules`
 * inside the engine, the single validator. This section refuses the two mistakes that
 * validator can only answer by silently dropping a rule.
 *
 * The draft lives in [HoursEditor] rather than in this composable: the section rides in a
 * lazy page and is disposed once it scrolls away.
 */
@Composable
fun HoursSection(
    graph: AppGraph,
    saved: List<HourRule>,
    editor: HoursEditor,
    onGate: (GateRequest) -> Unit,
) {
    // Untouched, the section shows the rules the engine holds right now, so a merge from
    // another device lands on the page. The first edit freezes it, and from then on the
    // draft is what the user is looking at.
    val draft = editor.draft ?: saved.map { it.toDraft() }
    val options = rememberSiteOptions(graph, draft)

    // Every edit clears the complaint the last Save left behind.
    fun edit(next: List<DraftRule>) {
        editor.draft = next
        editor.problem = null
    }

    fun commit(rules: List<DraftRule>) {
        graph.engineHost.applySettings(
            SettingsPatch(hourRules = rules.map { it.toRaw() }),
            listOf(SETTINGS_KEY),
        )
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.hours_why),
            color = SbColors.Muted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        for (rule in draft) {
            RuleCard(
                rule = rule,
                options = options,
                onEdit = { next -> edit(draft.map { if (it.id == next.id) next else it }) },
                onRemove = { edit(draft.filterNot { it.id == rule.id }) },
                onPickTime = { which -> editor.picking = TimePick(rule.id, which) },
            )
            Spacer(Modifier.height(10.dp))
        }
        if (draft.isEmpty()) {
            Text(
                text = stringResource(R.string.hours_empty),
                color = SbColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        editor.problem?.let {
            Text(
                text = stringResource(it),
                color = SbColors.Red,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        ButtonRow {
            SecondaryButton(
                label = stringResource(R.string.hours_add),
                // The validator keeps the first 32 and drops the rest, so the last one that
                // can be saved is the last one that can be added.
                enabled = draft.size < MAX_RULES,
                onClick = { edit(draft + newRule()) },
            )
            PrimaryButton(
                label = stringResource(R.string.hours_save),
                enabled = dirty(saved, draft),
                onClick = {
                    val trouble = problemWith(draft)
                    editor.problem = trouble
                    if (trouble != null) return@PrimaryButton
                    val after = Rules.sanitizeRules(draft.map { it.toRaw() })
                    if (RulesDiff.rulesLoosening(saved, after)) {
                        onGate(
                            GateRequest(
                                onPaid = { commit(draft) },
                                // Nothing was paid, so the rules go back to what is saved.
                                onCancel = { editor.reset() },
                            ),
                        )
                    } else {
                        commit(draft)
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The rules as they are being edited, plus which time is being picked. Held by the page so
 * the picker can sit above the scroll and the draft can outlive the section scrolling away.
 *
 * A null draft means untouched: the section shows the saved rules and Save is dark, so a
 * page left open on a phone that syncs shows what the battery actually holds.
 */
@Stable
class HoursEditor internal constructor() {
    internal var draft by mutableStateOf<List<DraftRule>?>(null)
    internal var picking by mutableStateOf<TimePick?>(null)
    internal var problem by mutableStateOf<Int?>(null)

    /** Back to following the saved rules, complaint cleared. */
    internal fun reset() {
        draft = null
        problem = null
    }
}

@Composable
fun rememberHoursEditor(): HoursEditor = remember { HoursEditor() }

/** The time picker, when one is open. It sits above the page, not inside its scroll. */
@Composable
fun HoursTimeSheet(editor: HoursEditor, saved: List<HourRule>) {
    val pick = editor.picking ?: return
    val draft = editor.draft ?: saved.map { it.toDraft() }
    val rule = draft.firstOrNull { it.id == pick.id }
    if (rule == null) {
        editor.picking = null
        return
    }
    TimeSheet(
        initial = if (pick.start) rule.from else rule.to,
        onCancel = { editor.picking = null },
        onPick = { hm ->
            val next = if (pick.start) rule.copy(from = hm) else rule.copy(to = hm)
            editor.draft = draft.map { if (it.id == next.id) next else it }
            editor.problem = null
            editor.picking = null
        },
    )
}

// --- One rule -------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleCard(
    rule: DraftRule,
    options: List<SiteOption>,
    onEdit: (DraftRule) -> Unit,
    onRemove: () -> Unit,
    onPickTime: (Boolean) -> Unit,
) {
    SbCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeButton(rule.from) { onPickTime(true) }
            Text(
                text = stringResource(R.string.hours_to),
                color = SbColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            TimeButton(rule.to) { onPickTime(false) }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.hours_remove),
                color = SbColors.Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (action in RuleAction.entries) {
                Pill(
                    label = stringResource(actionLabel(action)),
                    selected = rule.action == action,
                    onClick = { onEdit(rule.copy(action = action)) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when (rule.action) {
            RuleAction.RECHARGE -> PercentControl(rule, onEdit)
            RuleAction.CAPACITY -> CapacityControl(rule, onEdit)
            RuleAction.BLOCK, RuleAction.OFF -> ScopeControl(rule, options, onEdit)
        }
    }
}

@Composable
private fun TimeButton(value: String, onClick: () -> Unit) {
    Text(
        text = value,
        color = SbColors.Ink,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SbColors.Bg)
            .border(1.dp, SbColors.Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun PercentControl(rule: DraftRule, onEdit: (DraftRule) -> Unit) {
    Text(
        text = stringResource(R.string.hours_percent_value, rule.percent),
        color = SbColors.Ink,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
    )
    Slider(
        value = rule.percent.toFloat(),
        onValueChange = { onEdit(rule.copy(percent = it.toInt())) },
        valueRange = 0f..100f,
        steps = PERCENT_STOPS,
        colors = SliderDefaults.colors(
            thumbColor = SbColors.Green,
            activeTrackColor = SbColors.Green,
            inactiveTrackColor = SbColors.Line,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
    )
    Text(
        text = stringResource(R.string.hours_percent_hint),
        color = SbColors.Muted,
        fontSize = 12.sp,
    )
}

@Composable
private fun CapacityControl(rule: DraftRule, onEdit: (DraftRule) -> Unit) {
    Stepper(
        value = stringResource(R.string.hours_capacity_value, rule.minutes),
        onStep = { step ->
            onEdit(rule.copy(minutes = (rule.minutes + step * CAP_STEP).coerceIn(CAP_MIN, CAP_MAX)))
        },
        canDecrease = rule.minutes > CAP_MIN,
        canIncrease = rule.minutes < CAP_MAX,
        modifier = Modifier.widthIn(max = 260.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScopeControl(rule: DraftRule, options: List<SiteOption>, onEdit: (DraftRule) -> Unit) {
    val scopes = listOf(RuleScope.ALL, RuleScope.ONLY, RuleScope.EXCEPT)
    Segmented(
        options = listOf(
            SegmentOption(stringResource(R.string.hours_scope_all)),
            SegmentOption(stringResource(R.string.hours_scope_only)),
            SegmentOption(stringResource(R.string.hours_scope_except), SbColors.Red),
        ),
        selected = scopes.indexOf(rule.scope).coerceAtLeast(0),
        onSelect = { onEdit(rule.copy(scope = scopes[it])) },
    )
    if (rule.scope == RuleScope.ALL) return
    Spacer(Modifier.height(10.dp))
    if (options.isEmpty()) {
        Text(stringResource(R.string.hours_no_apps), color = SbColors.Muted, fontSize = 13.sp)
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (site in options) {
            val picked = site.id in rule.sites
            Pill(
                label = site.name,
                selected = picked,
                // An "all but these" pick is carved out of the rule rather than covered by
                // it, so it reads red the way the extension's does.
                color = if (rule.scope == RuleScope.EXCEPT) SbColors.Red else SbColors.Accent,
                onClick = {
                    val sites = if (picked) rule.sites - site.id else rule.sites + site.id
                    onEdit(rule.copy(sites = sites))
                },
            )
        }
    }
}

// --- The time picker ------------------------------------------------------------------

/** Which end of which rule is being set. */
internal data class TimePick(val id: String, val start: Boolean)

/**
 * Two columns of numbers under a scrim. Hand rolled rather than the platform dialog, which
 * would have brought its own type, corners, and accent to the one screen that uses it.
 */
@Composable
private fun TimeSheet(initial: String, onCancel: () -> Unit, onPick: (String) -> Unit) {
    val start = Rules.parseHM(initial) ?: 0
    var hour by remember(initial) { mutableStateOf(start / 60) }
    var minute by remember(initial) { mutableStateOf(start % 60) }

    // The scrim dismisses and the card swallows, both without a ripple.
    val scrimTaps = remember { MutableInteractionSource() }
    val cardTaps = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC0B0E12))
            .clickable(interactionSource = scrimTaps, indication = null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SbColors.Surface)
                .border(1.dp, SbColors.Line, RoundedCornerShape(14.dp))
                .clickable(interactionSource = cardTaps, indication = null) {}
                .padding(16.dp),
        ) {
            Text(
                text = "%02d:%02d".format(hour, minute),
                color = SbColors.Ink,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                NumberColumn(0..23, hour, Modifier.weight(1f)) { hour = it }
                Spacer(Modifier.width(10.dp))
                NumberColumn(0..59, minute, Modifier.weight(1f)) { minute = it }
            }
            Spacer(Modifier.height(14.dp))
            ButtonRow {
                SecondaryButton(stringResource(R.string.hours_cancel), onCancel)
                PrimaryButton(
                    label = stringResource(R.string.hours_set),
                    onClick = { onPick("%02d:%02d".format(hour, minute)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NumberColumn(
    range: IntRange,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val values = remember(range) { range.toList() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 2).coerceAtLeast(0))
    LazyColumn(
        state = state,
        modifier = modifier
            .heightIn(max = 200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SbColors.Bg)
            .border(1.dp, SbColors.Line, RoundedCornerShape(10.dp)),
    ) {
        items(values) { value ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (value == selected) SbColors.Accent else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "%02d".format(value),
                    color = if (value == selected) SbColors.Ink else SbColors.Muted,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// --- The draft ---------------------------------------------------------------------

/**
 * A rule while it is being edited. Every action's fields are carried at once, so switching
 * a rule from a block to a capacity cap and back does not lose what was typed; only the
 * fields the picked action uses are written on the way out.
 */
internal data class DraftRule(
    val id: String,
    val from: String,
    val to: String,
    val action: RuleAction,
    val scope: RuleScope,
    val sites: List<String>,
    val percent: Int,
    val minutes: Int,
)

private const val SETTINGS_KEY = "hourRules"


/** Twenty one stops from 0 to 100, which is the extension's 5% step. */
private const val PERCENT_STOPS = 19

internal fun HourRule.toDraft() = DraftRule(
    id = id,
    from = from,
    to = to,
    action = action,
    scope = scope,
    sites = sites,
    percent = percent,
    minutes = if (minutes in CAP_MIN..CAP_MAX) minutes else 10,
)

private fun DraftRule.toRaw(): RawHourRule = when (action) {
    RuleAction.RECHARGE -> RawHourRule(
        id = id, from = from, to = to, action = action.wire,
        percent = percent.coerceIn(0, 100).toDouble(),
    )

    RuleAction.CAPACITY -> RawHourRule(
        id = id, from = from, to = to, action = action.wire,
        minutes = minutes.coerceIn(CAP_MIN, CAP_MAX).toDouble(),
    )

    RuleAction.BLOCK, RuleAction.OFF -> RawHourRule(
        id = id, from = from, to = to, action = action.wire,
        scope = scope.wire,
        sites = if (scope == RuleScope.ALL) emptyList() else sites,
    )
}

/** A new rule is an overnight block of everything, the strictest a rule can be. */
private fun newRule() = DraftRule(
    id = "r" + (1..8).map { ID_CHARS[Random.nextInt(ID_CHARS.length)] }.joinToString(""),
    from = "22:00",
    to = "07:00",
    action = RuleAction.BLOCK,
    scope = RuleScope.ALL,
    sites = emptyList(),
    percent = 0,
    minutes = 10,
)

private const val ID_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"

private fun dirty(saved: List<HourRule>, draft: List<DraftRule>): Boolean =
    Rules.sanitizeRules(draft.map { it.toRaw() }) != saved

/**
 * The first thing wrong with the drafted rules, or null. Only the two the validator would
 * answer by dropping the rule: an empty window, and an "only these" rule with nothing
 * picked.
 */
private fun problemWith(draft: List<DraftRule>): Int? {
    for (rule in draft) {
        if (rule.from == rule.to) return R.string.hours_problem_times
        val scoped = rule.action == RuleAction.BLOCK || rule.action == RuleAction.OFF
        if (scoped && rule.scope == RuleScope.ONLY && rule.sites.isEmpty()) {
            return R.string.hours_problem_only
        }
    }
    return null
}

private fun actionLabel(action: RuleAction): Int = when (action) {
    RuleAction.BLOCK -> R.string.hours_action_block
    RuleAction.OFF -> R.string.hours_action_off
    RuleAction.RECHARGE -> R.string.hours_action_recharge
    RuleAction.CAPACITY -> R.string.hours_action_capacity
}

// --- Which apps a scope can name ------------------------------------------------------

/** One app a rule can be pointed at. */
internal data class SiteOption(val id: String, val name: String)

/**
 * The tracked apps, plus any id the rules already name. A rule from another device can
 * point at something this phone does not have, and leaving it off the list would drop it
 * from the rule on the next save.
 */
@Composable
private fun rememberSiteOptions(graph: AppGraph, draft: List<DraftRule>): List<SiteOption> {
    val named = draft.flatMap { it.sites }.toSet()
    val state = produceState(initialValue = emptyList<SiteOption>(), graph, named) {
        graph.tracked.packages.collectLatest { packages ->
            value = withContext(Dispatchers.Default) {
                val ids = LinkedHashSet<String>()
                packages.forEach { ids.add(AppCatalog.idFor(it)) }
                ids.addAll(named)
                ids.map { id -> SiteOption(id, graph.labels.siteName(id)) }
                    .sortedBy { it.name.lowercase() }
            }
        }
    }
    return state.value
}
