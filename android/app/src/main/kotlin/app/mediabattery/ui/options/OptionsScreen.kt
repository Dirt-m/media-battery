package app.mediabattery.ui.options

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mediabattery.AppGraph
import app.mediabattery.R
import app.mediabattery.oem.OemKeepAlive
import app.mediabattery.ui.apps.appsSection
import app.mediabattery.ui.apps.rememberAppRows
import app.mediabattery.ui.common.ButtonRow
import app.mediabattery.ui.common.CardTitle
import app.mediabattery.ui.common.Limits.CAP_MAX
import app.mediabattery.ui.common.Limits.CAP_MIN
import app.mediabattery.ui.common.Limits.CAP_STEP
import app.mediabattery.ui.common.PrimaryButton
import app.mediabattery.ui.common.SbCard
import app.mediabattery.ui.common.ScreenHeader
import app.mediabattery.ui.common.SecondaryButton
import app.mediabattery.ui.common.SectionLabel
import app.mediabattery.ui.common.SegmentOption
import app.mediabattery.ui.common.Segmented
import app.mediabattery.ui.common.StatusChip
import app.mediabattery.ui.common.Stepper
import app.mediabattery.ui.friction.FrictionGate
import app.mediabattery.ui.friction.GateRequest
import app.mediabattery.ui.hours.HoursSection
import app.mediabattery.ui.hours.HoursTimeSheet
import app.mediabattery.ui.hours.rememberHoursEditor
import app.mediabattery.ui.onboarding.OnboardingState
import app.mediabattery.ui.theme.SbColors
import site.dirt23.battery.core.Constants
import site.dirt23.battery.core.engine.SettingsPatch
import site.dirt23.battery.core.model.Snapshot
import site.dirt23.battery.core.rules.RulesDiff
import kotlin.math.roundToInt

/**
 * Every setting on one page, in the extension's order: the battery's numbers, the apps and
 * what the battery does about each, the hour rules, then the permissions the phone runs on.
 * Sync keeps its own page, because it is setup rather than a setting.
 *
 * The page is one lazy list rather than a scrolling column, so the app rows cost only what
 * is on screen even when a search turns up two hundred of them. The catch: a section
 * scrolled far enough away is disposed, so every draft lives in a holder up here and not in
 * the section that draws it.
 *
 * Only the two drafted edits have a save button, the three numbers and the hour rules,
 * where the control is a stepper or a time and every intermediate value would otherwise
 * land. Everything else applies on the tap.
 *
 * The friction gate is the page's, not a section's. A section asks for it with a
 * [GateRequest] and the page draws the one gate, so loosening costs the same wherever the
 * tap came from.
 */
@Composable
fun OptionsScreen(
    graph: AppGraph,
    permissions: OnboardingState,
    actions: PermissionActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Everything the page holds is remembered before the gate can take the screen over, so a
    // draft waiting on the gate is still there when it is paid.
    var gate by remember { mutableStateOf<GateRequest?>(null) }
    val settings by graph.engineHost.settings.collectAsStateWithLifecycle()
    val appRows = rememberAppRows(graph)
    val hours = rememberHoursEditor()
    val numbers = remember { NumbersEditor() }
    var query by rememberSaveable { mutableStateOf("") }

    val vendor = remember { OemKeepAlive.current() }

    gate?.let { request ->
        // Back out of the gate is the Cancel button, nothing else. Without this the page's
        // own back would leave the settings screen and drop the pending change.
        BackHandler {
            request.onCancel()
            gate = null
        }
        FrictionGate(
            title = stringResource(R.string.settings_gate_title),
            confirmLabel = stringResource(R.string.settings_gate_confirm),
            // Read once as the gate goes up: the toll is set before the gate is asked for.
            count = graph.engineHost.snapshot.value.frictionCount,
            askWhy = false,
            onCancel = {
                request.onCancel()
                gate = null
            },
            onSuccess = {
                request.onPaid()
                gate = null
            },
            modifier = modifier
                .fillMaxSize()
                .background(SbColors.Bg)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(24.dp),
        )
        return
    }

    // The insets are the column's, not the box's, so the time picker's scrim covers the
    // whole screen, status bar included.
    Box(modifier.fillMaxSize().background(SbColors.Bg)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            ScreenHeader(
                title = stringResource(R.string.settings_title),
                back = stringResource(R.string.settings_back),
                onBack = onBack,
            )
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { SectionLabel(stringResource(R.string.options_battery), divider = false) }
                item { BatteryNumbers(graph, numbers) { gate = it } }
                item { TimeLeftCard(graph) }
                item { QuestionsCard(graph) { gate = it } }

                item { SectionLabel(stringResource(R.string.apps_title)) }
                appsSection(graph, appRows, settings.siteModes, query, { query = it }) { gate = it }

                item { SectionLabel(stringResource(R.string.hours_title)) }
                item { HoursSection(graph, settings.hourRules, hours) { gate = it } }

                item { SectionLabel(stringResource(R.string.settings_permissions)) }
                item {
                    SbCard {
                        StatusChip(stringResource(R.string.onboarding_usage_title), permissions.usageAccess, actions.onUsage)
                        StatusChip(stringResource(R.string.onboarding_overlay_title), permissions.overlay, actions.onOverlay)
                        StatusChip(stringResource(R.string.onboarding_notifications_title), permissions.notifications, actions.onNotifications)
                        permissions.accessibility?.let {
                            StatusChip(stringResource(R.string.onboarding_accessibility_title), it, actions.onAccessibility)
                        }
                        StatusChip(stringResource(R.string.onboarding_listener_title), permissions.notificationAccess, actions.onNotificationAccess)
                        StatusChip(stringResource(R.string.onboarding_exempt_title), permissions.batteryExempt, actions.onBatteryExemption)
                    }
                }

                vendor?.let {
                    item {
                        SbCard {
                            CardTitle(stringResource(R.string.oem_title), stringResource(it.line))
                            Spacer(Modifier.height(12.dp))
                            SecondaryButton(stringResource(R.string.oem_open), actions.onOem)
                        }
                    }
                }
            }
        }

        HoursTimeSheet(hours, settings.hourRules)
    }
}

/** The trips out to system settings this page offers, one per row. */
data class PermissionActions(
    val onUsage: () -> Unit,
    val onOverlay: () -> Unit,
    val onNotifications: () -> Unit,
    val onAccessibility: () -> Unit,
    val onNotificationAccess: () -> Unit,
    val onBatteryExemption: () -> Unit,
    val onOem: () -> Unit,
)

// --- The three numbers -----------------------------------------------------------------

/**
 * The battery's own numbers: how much charge a day buys, how much the battery holds, and
 * when it warns. Raising either of the first two is loosening and costs the gate; lowering
 * is free. The warning is neither, since it changes what the user is told rather than what
 * they may do.
 *
 * The reading is collected here rather than on the page, so a charge ticking once a second
 * redraws this card and nothing else.
 */
@Composable
private fun BatteryNumbers(graph: AppGraph, editor: NumbersEditor, onGate: (GateRequest) -> Unit) {
    val snapshot by graph.engineHost.snapshot.collectAsStateWithLifecycle()
    val live = remember(snapshot.rechargePerMin, snapshot.capacity, snapshot.warnSeconds) {
        Numbers.of(snapshot)
    }
    val draft = editor.draft ?: live

    // What the card was showing before the user touched it. The save writes only the keys
    // that moved against this, so a value another device changed while the page sat open is
    // not written back from an untouched draft.
    fun edit(next: Numbers) {
        if (editor.opened == null) editor.opened = live
        editor.draft = next
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SbCard {
            CardTitle(
                stringResource(R.string.settings_speed),
                stringResource(R.string.settings_speed_why),
            )
            Spacer(Modifier.height(12.dp))
            Stepper(
                value = stringResource(R.string.settings_speed_value, draft.hoursPerDay),
                onStep = { edit(draft.stepSpeed(it)) },
                canDecrease = draft.hoursPerDay > SPEED_MIN,
                canIncrease = draft.hoursPerDay < SPEED_MAX,
            )
        }
        SbCard {
            CardTitle(
                stringResource(R.string.settings_capacity),
                stringResource(R.string.settings_capacity_why),
            )
            Spacer(Modifier.height(12.dp))
            Stepper(
                value = stringResource(R.string.settings_minutes, draft.capacityMinutes),
                onStep = { edit(draft.stepCapacity(it)) },
                canDecrease = draft.capacityMinutes > CAP_MIN,
                canIncrease = draft.capacityMinutes < CAP_MAX,
            )
        }
        SbCard {
            CardTitle(
                stringResource(R.string.settings_warn),
                stringResource(R.string.settings_warn_why),
            )
            Spacer(Modifier.height(12.dp))
            Stepper(
                value = if (draft.warnMinutes == 0) {
                    stringResource(R.string.settings_warn_off)
                } else {
                    stringResource(R.string.settings_minutes, draft.warnMinutes)
                },
                onStep = { edit(draft.stepWarn(it)) },
                canDecrease = draft.warnMinutes > 0,
                canIncrease = draft.warnMinutes < WARN_MAX,
            )
        }
        ButtonRow {
            PrimaryButton(
                label = stringResource(R.string.settings_save),
                enabled = draft != live,
                onClick = {
                    val opened = editor.opened ?: live
                    // Loosening is judged against what holds now; which keys get written is
                    // judged against what the card was showing before the first tap.
                    if (RulesDiff.loosening(live.asSettings(), draft.asSettings())) {
                        onGate(
                            GateRequest(
                                // Nothing was paid, so the numbers go back to what is saved.
                                onCancel = { editor.reset() },
                                onPaid = {
                                    draft.save(graph, opened)
                                    // The draft stays: the save is a message to the engine
                                    // and the reading catches up a tick later, so clearing
                                    // it here would flash the old numbers.
                                    editor.opened = null
                                },
                            ),
                        )
                    } else {
                        draft.save(graph, opened)
                        editor.opened = null
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// --- The arrival pill ------------------------------------------------------------------

/**
 * Whether opening a tracked app shows a short notice with the time left. A synced setting
 * (`showTimeLeft`, the extension's arrival toast), applied on the tap and ungated like the
 * warning.
 */
@Composable
private fun TimeLeftCard(graph: AppGraph) {
    val snapshot by graph.engineHost.snapshot.collectAsStateWithLifecycle()
    SbCard {
        CardTitle(
            stringResource(R.string.settings_time_left),
            stringResource(R.string.settings_time_left_why),
        )
        Spacer(Modifier.height(12.dp))
        Segmented(
            options = listOf(
                SegmentOption(stringResource(R.string.mode_on)),
                SegmentOption(stringResource(R.string.mode_off), SbColors.Line),
            ),
            selected = if (snapshot.showTimeLeft) 0 else 1,
            onSelect = { index ->
                graph.engineHost.applySettings(
                    SettingsPatch(showTimeLeft = index == 0),
                    listOf("showTimeLeft"),
                )
            },
        )
    }
}

// --- The gate's size --------------------------------------------------------------------

/**
 * How many questions every friction gate asks, this cover's and this page's alike. A synced
 * setting (`frictionCount`), applied on the tap. Asking for fewer is loosening and costs the
 * gate as it stands; asking for more is free.
 */
@Composable
private fun QuestionsCard(graph: AppGraph, onGate: (GateRequest) -> Unit) {
    val snapshot by graph.engineHost.snapshot.collectAsStateWithLifecycle()
    val current = snapshot.frictionCount
    fun apply(count: Int) {
        graph.engineHost.applySettings(SettingsPatch(frictionCount = count), listOf("frictionCount"))
    }
    SbCard {
        CardTitle(
            stringResource(R.string.settings_questions),
            stringResource(R.string.settings_questions_why),
        )
        Spacer(Modifier.height(12.dp))
        Segmented(
            options = (Constants.FRICTION_COUNT_MIN..Constants.FRICTION_COUNT_MAX).map { SegmentOption(it.toString()) },
            selected = current - Constants.FRICTION_COUNT_MIN,
            onSelect = { index ->
                val next = index + Constants.FRICTION_COUNT_MIN
                when {
                    next == current -> Unit
                    next < current -> onGate(GateRequest(onPaid = { apply(next) }))
                    else -> apply(next)
                }
            },
        )
    }
}

/**
 * The three numbers while they are being edited. Held by the page, since the card is one
 * item in a lazy list and is disposed once it scrolls away.
 */
@Stable
private class NumbersEditor {
    var draft by mutableStateOf<Numbers?>(null)
    var opened by mutableStateOf<Numbers?>(null)

    fun reset() {
        draft = null
        opened = null
    }
}

private const val SPEED_MIN = 0.5
private const val SPEED_MAX = 24.0
private const val SPEED_STEP = 0.5

/** A day, the same ceiling the extension's field has and the engine clamps to. */
private const val WARN_MAX = 1440

/**
 * The recharge rate is stored as seconds per minute and shown as hours per day of charge,
 * exactly as the extension shows it. A day holds 1440 minutes, so one hour a day of budget
 * is 3600/1440 = 2.5 seconds per minute.
 */
private const val SEC_PER_MIN_PER_HOUR_DAY = 2.5

private data class Numbers(
    val hoursPerDay: Double,
    val capacityMinutes: Int,
    val warnMinutes: Int,
) {
    fun stepSpeed(step: Int) = copy(
        hoursPerDay = (((hoursPerDay + step * SPEED_STEP) * 2).roundToInt() / 2.0)
            .coerceIn(SPEED_MIN, SPEED_MAX),
    )

    fun stepCapacity(step: Int) =
        copy(capacityMinutes = (capacityMinutes + step * CAP_STEP).coerceIn(CAP_MIN, CAP_MAX))

    fun stepWarn(step: Int) = copy(warnMinutes = (warnMinutes + step).coerceIn(0, WARN_MAX))

    /** What the gate is asked about. The warning is not a limit, so it is left out. */
    fun asSettings() = RulesDiff.Settings(
        rechargePerMin = hoursPerDay * SEC_PER_MIN_PER_HOUR_DAY,
        capacity = capacityMinutes * 60.0,
    )

    /**
     * Only the keys that moved against [from], the reading the card opened with, so an
     * untouched number cannot clobber another device's edit. A key left out of the patch
     * never gets a fresh timestamp to win a merge with.
     */
    fun save(graph: AppGraph, from: Numbers) {
        val keys = ArrayList<String>(3)
        if (hoursPerDay != from.hoursPerDay) keys.add("rechargePerMin")
        if (capacityMinutes != from.capacityMinutes) keys.add("capacity")
        if (warnMinutes != from.warnMinutes) keys.add("warnSeconds")
        if (keys.isEmpty()) return
        graph.engineHost.applySettings(
            SettingsPatch(
                rechargePerMin = (hoursPerDay * SEC_PER_MIN_PER_HOUR_DAY).takeIf { "rechargePerMin" in keys },
                capacity = (capacityMinutes * 60.0).takeIf { "capacity" in keys },
                warnSeconds = (warnMinutes * 60).takeIf { "warnSeconds" in keys },
            ),
            keys,
        )
    }

    companion object {
        fun of(snap: Snapshot): Numbers {
            return Numbers(
                hoursPerDay = ((snap.rechargePerMin / SEC_PER_MIN_PER_HOUR_DAY) * 2).roundToInt() / 2.0,
                capacityMinutes = (snap.capacity / 60.0).roundToInt().coerceIn(CAP_MIN, CAP_MAX),
                warnMinutes = (snap.warnSeconds / 60.0).roundToInt().coerceIn(0, WARN_MAX),
            )
        }
    }
}
