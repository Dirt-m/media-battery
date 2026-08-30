package app.mediabattery.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.mediabattery.AppGraph
import app.mediabattery.MainActivity
import app.mediabattery.MediaBatteryApp
import app.mediabattery.R
import app.mediabattery.block.BlockDecision
import app.mediabattery.protect.Degradation
import app.mediabattery.protect.DegradationCopy
import app.mediabattery.widget.BatteryWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import site.dirt23.battery.core.model.Snapshot
import kotlin.math.ceil
import kotlin.math.max

/**
 * Foreground service that runs the battery. It has to keep counting while another app is in
 * front, and it has to notice that app within a fraction of a second; the notification
 * doubles as the charge reading.
 *
 * Start order is one rule: the replay reaches the engine first. The gap since the last run
 * is replayed out of the usage event log, and any live signal applied ahead of it (a screen
 * event, a media session, a sync pull adopting an anchor) settles the engine to now, after
 * which every replayed transition clamps to zero elapsed time and the whole gap silently
 * recharges. So live inputs are wired only after the replay is enqueued, and sync starts
 * only after it has been applied.
 */
class BatteryService : Service() {
    private lateinit var graph: AppGraph

    /**
     * The collectors this run owns, cancelled when it ends. Not on the application scope: a
     * killed and restarted service would leave the old ones running and handle every
     * snapshot twice.
     */
    private var jobs: CoroutineScope? = null
    private var lastNotificationText: String? = null
    private var lastWidgetKey: String? = null

    /**
     * True once [run] has wired the live inputs. Until then a resync must not feed the
     * engine: its screen event would land in the channel ahead of the replay.
     */
    private var live = false

    override fun onCreate() {
        super.onCreate()
        graph = (application as MediaBatteryApp).graph
        createChannel()
        graph.warner.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always first: the system gives a few seconds and no second chance. A resync
        // reuses the last text, since at a resting charge no snapshot change follows to
        // correct a "Recharging" this re-posted.
        startForegroundNow(lastNotificationText ?: getString(R.string.notif_recharging))
        running = true
        if (jobs == null) {
            Log.i(TAG, "service run starting")
            jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            run()
        } else {
            // Already running: re-read the screen state and settle. A start from a
            // returning activity is a cheap moment to catch a missed screen event.
            Log.i(TAG, "service resync")
            resync()
        }
        return START_STICKY
    }

    /**
     * Re-read the hardware and hand it to the engine unconditionally. The screen monitor
     * only forwards changes it saw; this path is for the change it missed.
     */
    private fun resync() {
        // Still replaying: the startup path reads the screen itself in a moment.
        if (!live) return
        graph.screen.refresh()
        graph.engineHost.onScreen(
            graph.screen.interactive.value,
            graph.screen.locked.value,
            System.currentTimeMillis(),
        )
        graph.engineHost.refresh()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "service destroyed")
        running = false
        live = false
        graph.detectors.stop()
        graph.media.stop()
        graph.screen.stop()
        graph.blocks.stop()
        graph.warner.clear()
        graph.pill.clear()
        graph.watchdog.stopPeriodic()
        jobs?.cancel()
        jobs = null
        // Enqueued now, not after the settle: the sync scope is FIFO, so a stop that waited
        // landed after a quick restart's start() and killed the new run (start no-ops on the
        // stale active flag).
        graph.onSync { stop() }
        // On the application scope, not this run's, so the last settle and its save still
        // finish.
        graph.scope.launch {
            // The settle first, so the goodbye push carries the charge as it finally was.
            graph.engineHost.stop()
            graph.onSync {
                // A dropped "stopped draining" push leaves a permanently draining anchor on
                // the server, and every other device mirrors a drain that ended. Bounded,
                // because a process on its way out only gets a few seconds.
                withTimeoutOrNull(FLUSH_MS) { flushPendingPush() }
            }
        }
    }

    private fun run() {
        val scope = jobs ?: return
        graph.refreshNeverTrack()
        graph.watchdog.startPeriodic()

        scope.launch {
            val transitions = withContext(Dispatchers.Default) {
                graph.replayer.replay(graph.prefs.lastSeenMs, graph.engineHost.settings.value)
            }
            // First into the channel, so nothing live can outrun it.
            val replayed = graph.engineHost.replay(transitions.orEmpty())
            graph.engineHost.start()

            graph.media.start()
            graph.screen.onClockChanged = { graph.engineHost.onClockChanged() }
            graph.screen.onScreenChanged = { interactive, locked, at ->
                graph.engineHost.onScreen(interactive, locked, at)
                // The sync watch follows the screen: dark parks it, lit resumes it.
                graph.onSync { onScreenChange(interactive) }
            }
            graph.screen.start()
            graph.engineHost.onScreen(
                graph.screen.interactive.value,
                graph.screen.locked.value,
                System.currentTimeMillis(),
            )
            graph.detectors.start { pkg, at ->
                // The cover needs the raw package, not the decision: once it is up, the app
                // in front is the cover, so nothing else tells it the user walked off. The
                // pill needs it to tell an arrival from a snapshot merely changing.
                graph.blocks.onForeground(pkg)
                graph.pill.onForeground(pkg)
                graph.engineHost.onForeground(pkg, at)
            }
            live = true

            // The sync engine reaches the battery engine directly, not through the channel,
            // so its start waits for the replay to be applied: a pull adopting an anchor
            // settles the engine to now just as a live event would.
            replayed.await()
            graph.onSync {
                // Screen first, so a service that starts in the dark opens no watch.
                onScreenChange(graph.screen.interactive.value)
                start()
            }
        }

        scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                // Hardware screen state first: a missed SCREEN_ON leaves the cached value
                // false with the detector parked on it, and a settle that trusts the cache
                // heals nothing.
                graph.screen.refresh()
                // Self heal: whatever event was missed, one settle a minute bounds it. The
                // freeze this fixed left the app inert for as long as the process lived.
                graph.engineHost.refresh()
                // onHeartbeat checks engagement itself, keeping a background drain
                // propagating while timers throttle.
                graph.onSync { onHeartbeat() }
            }
        }

        scope.launch {
            combine(
                graph.engineHost.snapshot,
                graph.engineHost.decision,
                graph.watchdog.degradations,
                ::Triple,
            ).collect { (snapshot, decision, degradations) ->
                graph.blocks.update(decision)
                graph.media.onCover(decision, snapshot.depleted)
                updateNotification(snapshot, degradations)
                updateWidget(snapshot)
                val inUse = graph.screen.interactive.value && !graph.screen.locked.value
                graph.warner.onSnapshot(snapshot, decision != BlockDecision.None, inUse)
                graph.pill.onSnapshot(snapshot, decision != BlockDecision.None, inUse)
            }
        }
    }

    // --- The notification ---------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            description = getString(R.string.notif_channel_why)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun startForegroundNow(text: String) {
        lastNotificationText = text
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(text), type)
    }

    /**
     * The reading is in minutes, so the notification is rebuilt only when the words change.
     * A once a second rebuild would repost sixty times a minute to say the same thing, and
     * the shade animates every one.
     *
     * A critical degradation takes the line instead of the reading: a charge that says 12
     * min while usage access is off is wrong, and the notification is where the user will
     * see it.
     */
    private fun updateNotification(snapshot: Snapshot, degradations: Set<Degradation>) {
        // A dark screen shows no text (ambient display draws icons, not lines), and a
        // recharging battery rewords once a minute, so posting through the night is sixty
        // invisible shade updates an hour. lastNotificationText still names what the shade
        // shows, so the first publish after the screen lights sees the difference and posts.
        if (!graph.screen.interactive.value) return
        val text = degradations.firstOrNull { it.critical }?.let { getString(DegradationCopy.text(it)) }
            ?: describe(snapshot)
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(text))
    }

    /**
     * The home screen widget, on the same terms as the notification: pushed when its reading
     * would change, never on a timer. A degradation does not take it over the way it takes
     * the notification line; two words is no room for both.
     *
     * Color is in the key as well as the reading. It follows the fraction of the cap rather
     * than the minutes, so a capacity window opening can turn a green reading amber without
     * a digit moving.
     */
    private fun updateWidget(snapshot: Snapshot) {
        // Same as the notification: no pushes at a screen nobody sees, and the stale key
        // makes the wake's first publish repaint it.
        if (!graph.screen.interactive.value) return
        val key = "${minutesOf(snapshot.charge)}|${BatteryWidget.word(this, snapshot)}" +
            "|${BatteryWidget.colorOf(snapshot)}"
        if (key == lastWidgetKey) return
        lastWidgetKey = key
        BatteryWidget.push(this, snapshot)
    }

    private fun build(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun describe(snapshot: Snapshot): String {
        val minutes = getString(R.string.notif_minutes, minutesOf(snapshot.charge))
        return when {
            snapshot.depleted && snapshot.cooldownRemaining > 0 ->
                getString(R.string.notif_blocked_for, getString(R.string.notif_minutes, minutesOf(snapshot.cooldownRemaining)))

            snapshot.depleted -> getString(R.string.notif_blocked)
            snapshot.draining -> getString(R.string.notif_draining, minutes, drainingName(snapshot))
            snapshot.charge >= snapshot.effCapacity -> getString(R.string.notif_full)
            snapshot.rechargePaused -> getString(R.string.notif_charging_paused)
            else -> getString(R.string.notif_recharging_left, minutes)
        }
    }

    private fun drainingName(snapshot: Snapshot): String =
        graph.labels.draining(snapshot) ?: getString(R.string.notif_another_device)

    companion object {
        private const val TAG = "MediaBattery"
        private const val CHANNEL_ID = "battery_status"
        private const val NOTIFICATION_ID = 1

        /** The sync heartbeat's cadence, matching the extension's alarm. */
        private const val HEARTBEAT_MS = 60_000L

        /** How long the last push gets to land while the service is going away. */
        private const val FLUSH_MS = 5_000L

        /** True while the service is running. The quick settings tile reads this. */
        @Volatile
        var running: Boolean = false
            private set

        private fun minutesOf(seconds: Double): Int = max(1.0, ceil(max(0.0, seconds) / 60.0)).toInt()

        fun start(context: Context) {
            val intent = Intent(context, BatteryService::class.java)
            runCatching { context.startForegroundService(intent) }
        }
    }
}
