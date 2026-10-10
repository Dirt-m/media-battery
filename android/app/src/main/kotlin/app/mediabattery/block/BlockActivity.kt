package app.mediabattery.block

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.mediabattery.MediaBatteryApp
import app.mediabattery.R
import app.mediabattery.service.BatteryService
import app.mediabattery.ui.theme.MediaBatteryTheme
import kotlinx.coroutines.launch
import site.dirt23.battery.core.model.SiteMode

/**
 * The cover: an activity, not an overlay window. A task gets a card in the switcher, so
 * swiping up while covered shows the block screen rather than the app it covers, which an
 * overlay window could never do. The task is its own, by affinity, so finishing the cover
 * lands on the covered app or on home, never inside this app's own screens.
 *
 * The card behind it still shows whatever the system last snapshotted of the blocked app.
 * Nothing here can repaint another app's card, so tapping it lands the user in that app for
 * as long as it takes the cover to come back up.
 *
 * It works out its own reason to exist rather than following the service's live decision:
 * while this page is in front the app in front is this one, so the service would see nothing
 * blockable and take the cover straight back down, in a loop. Instead the page is told which
 * app it covers and watches the battery for that app. That is also what lifts it, since a
 * cooldown ending, an hour window closing and a pass landing all arrive through the snapshot.
 */
class BlockActivity : ComponentActivity() {

    /**
     * The app being covered. State rather than a plain field: the task is `singleTask`, so a
     * second cover for a different app arrives at this same instance as a new intent, and
     * the screen has to follow it.
     */
    private var blockedId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live = this
        blockedId = intent?.getStringExtra(EXTRA_ID)
        val graph = (application as MediaBatteryApp).graph

        setContent {
            MediaBatteryTheme {
                val snapshot by graph.engineHost.snapshot.collectAsStateWithLifecycle()
                val settings by graph.engineHost.settings.collectAsStateWithLifecycle()
                val id = blockedId
                // A new story starts with the gate closed: questions answered for a dead
                // battery must not carry over to an app block, or the other way round.
                var gateOpen by remember(id) { mutableStateOf(false) }

                // Walking a day of minutes, so it is worked out once: the snapshot arrives
                // every few seconds and cannot move when an hour rule lifts.
                val liftsAt = remember(id, settings.hourRules) {
                    if (id == null) {
                        null
                    } else {
                        BlockDecisions.liftsAt(
                            settings.hourRules,
                            id,
                            graph.clock.wallNow(),
                            graph.clock.zone(),
                        )
                    }
                }

                val decision = remember(snapshot, settings, id) {
                    if (id == null) {
                        if (snapshot.depleted) {
                            BlockDecision.Dead(snapshot.cooldownRemaining, snapshot.rechargePaused)
                        } else {
                            BlockDecision.None
                        }
                    } else {
                        BlockDecisions.decide(
                            snapshot = snapshot,
                            id = id,
                            mode = settings.siteModes[id] ?: SiteMode.ON,
                            rules = settings.hourRules,
                            nowWallMs = graph.clock.wallNow(),
                            zone = graph.clock.zone(),
                            liftsAt = { liftsAt },
                        )
                    }
                }

                // Nothing left to cover: the cooldown ran out, an hour window closed, or the
                // gate below was paid. Otherwise the switcher card gets its label.
                //
                // Keyed on the story, not the decision: a dead cover's countdown moves every
                // second and the card says nothing about it, so keying on the decision would
                // be a system call a second for a label that never changes.
                val story = storyOf(decision)
                LaunchedEffect(story) {
                    if (story == null) standDown() else labelCard(decision)
                }
                BackHandler(enabled = true) { if (gateOpen) gateOpen = false else leave() }

                BlockScreen(
                    decision = decision,
                    nameOf = graph.labels::siteName,
                    minutes = minutesOf(
                        if (decision is BlockDecision.Dead) snapshot.reserveSeconds else snapshot.passSeconds,
                    ),
                    questions = snapshot.frictionCount,
                    gateOpen = gateOpen,
                    onOpenGate = { gateOpen = true },
                    onGateCancel = { gateOpen = false },
                    onGateSuccess = {
                        lifecycleScope.launch {
                            when (val d = decision) {
                                is BlockDecision.Dead -> graph.engineHost.useReserve()
                                is BlockDecision.AppBlocked -> graph.engineHost.useSitePass(d.id)
                                BlockDecision.None -> Unit
                            }
                            standDown()
                        }
                    },
                )
            }
        }
    }

    /** A cover for a second app, delivered to the task that is already up. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        blockedId = intent.getStringExtra(EXTRA_ID)
    }

    override fun onStart() {
        super.onStart()
        isShowing = true
        // This page is a task, so the switcher can bring it back long after the service that
        // put it up died. With no service behind it the countdown stands still and the cover
        // never lifts.
        val graph = (application as MediaBatteryApp).graph
        if (graph.prefs.onboarded) BatteryService.start(this)
    }

    /**
     * Stopping is not leaving. The switcher stops this page while its card is being looked
     * at, and so does the screen going off, so neither may finish it. What finishes the cover
     * is its reason lifting, the back gesture, or the controller seeing the user land
     * somewhere else.
     */
    override fun onStop() {
        super.onStop()
        isShowing = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (live === this) live = null
    }

    /**
     * Back. The one place the user must not end up is the app under the cover, so the home
     * screen goes in front first and the cover's task goes with it: no dead card left in the
     * switcher, and no way through to what it was covering.
     */
    private fun leave() {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(home) }
        dropTask()
    }

    /**
     * The cover's reason lifted, so it steps aside and the app underneath comes back up,
     * which is what a paid pass buys.
     *
     * A plain finish, not finishAndRemoveTask. The task holds this one page, so finishing
     * takes the card with it, and dropping the task by hand would land the user on the home
     * screen instead of where they were.
     */
    private fun standDown() {
        if (isFinishing || isDestroyed) return
        finish()
    }

    /**
     * The cover is done and the app under it is not where the user should land: they asked to
     * leave, or they are already somewhere else. The task goes with the page, so the switcher
     * keeps no card for something that is over.
     */
    private fun dropTask() {
        if (isFinishing || isDestroyed) return
        finishAndRemoveTask()
    }

    private fun labelCard(decision: BlockDecision) {
        val graph = (application as MediaBatteryApp).graph
        val label = when (decision) {
            is BlockDecision.AppBlocked ->
                getString(R.string.block_app_title, graph.labels.siteName(decision.id))
            is BlockDecision.Dead -> getString(R.string.block_dead_title)
            BlockDecision.None -> return
        }
        val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityManager.TaskDescription.Builder().setLabel(label).build()
        } else {
            @Suppress("DEPRECATION")
            ActivityManager.TaskDescription(label)
        }
        setTaskDescription(description)
    }

    companion object {
        const val EXTRA_ID = "blockedId"

        /** True between start and stop, which is what the controller reads as "it is up". */
        @Volatile
        var isShowing: Boolean = false
            private set

        /** The live page, so the controller can take a stale cover down. */
        @Volatile
        private var live: BlockActivity? = null

        /**
         * Built once by the controller and reused on every launch; only the id varies.
         *
         * `NEW_TASK` is mandatory from a service context and is also what is wanted: the
         * cover gets a task of its own, on top of the app it covers rather than inside it.
         * The task affinity in the manifest keeps that task apart from the Media Battery one,
         * so the cover can never drag the gauge or the settings page into view. `singleTask`
         * plus no `CLEAR_TASK` means a second cover reuses the page already up (see
         * [onNewIntent]) instead of rebuilding the card. `NO_ANIMATION` lands it at once: a
         * cover that slides in can be read past for a third of a second.
         */
        fun intent(context: Context): Intent = Intent(context, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        /** Takes the cover down from outside, card and all. Safe when there is none. */
        fun dismiss() {
            val page = live ?: return
            page.runOnUiThread { page.dropTask() }
        }

        private fun minutesOf(seconds: Int): Int = maxOf(1, Math.round(seconds / 60.0).toInt())

        /** What the cover is about. The card's label depends on nothing else. */
        private fun storyOf(decision: BlockDecision): String? = when (decision) {
            is BlockDecision.Dead -> "dead"
            is BlockDecision.AppBlocked -> "app:${decision.id}"
            BlockDecision.None -> null
        }
    }
}
