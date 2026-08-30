package app.mediabattery.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import app.mediabattery.block.BlockDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import site.dirt23.battery.core.identity.AppCatalog

/**
 * Which apps are making sound.
 *
 * An hour of a media app with the screen off is an hour of that app, so the battery has to
 * hear it. Every app with a play button owns a media session, and a session says which
 * package it belongs to and whether it is playing.
 *
 * Starting counts immediately, stopping waits ten seconds: a seek, an ad break, and a
 * buffer stall all read as "not playing" for a moment. The grace costs at most ten seconds
 * of drain on a real pause.
 *
 * Everything here runs on the main thread (the session callbacks are delivered to it and
 * the controllers are touched from it), so the sets below need no locking.
 */
class MediaPlaybackMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isTracked: (String) -> Boolean,
    private val onChanged: (Set<String>, Long) -> Unit,
) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val component = ComponentName(context, MbNotificationListenerService::class.java)

    private val controllers = mutableListOf<MediaController>()
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private val leaving = mutableMapOf<String, Job>()

    /** Packages heard playing, oldest first, after the grace. */
    private val playing = LinkedHashSet<String>()

    private var watcher: Job? = null
    private var bound = false

    // The last cover we acted on, so a pause is sent once per cover rather than once a tick.
    private var coverKey: String? = null
    private var wasDepleted = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        rebind(list.orEmpty())
    }

    /**
     * Start listening. Call it on the main thread.
     *
     * Notification access is what makes the sessions readable and the user may grant it at
     * any time, so the binding follows the listener service: first attempt now, rebind on
     * every connect after that.
     */
    fun start() {
        if (watcher != null) return
        watcher = scope.launch {
            MbNotificationListenerService.connected.collect { bind() }
        }
    }

    fun stop() {
        watcher?.cancel()
        watcher = null
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        bound = false
        rebind(emptyList())
        leaving.values.forEach { it.cancel() }
        leaving.clear()
        if (playing.isNotEmpty()) {
            playing.clear()
            publish()
        }
    }

    /**
     * The cover just went up, or the battery just died. Silence what it covers, once.
     *
     * Without this a dead battery is a cover over a phone still playing the podcast. A dead
     * battery pauses every tracked app, since it covers all of them and can arrive with the
     * screen off; one app behind a cover pauses that app alone. Once per edge, never on a
     * tick, so a user who hits play again behind a cover is allowed to hear it.
     */
    fun onCover(decision: BlockDecision, depleted: Boolean) {
        val key = when (decision) {
            is BlockDecision.Dead -> DEAD
            is BlockDecision.AppBlocked -> decision.id
            BlockDecision.None -> null
        }
        val covered = key != null && key != coverKey
        val died = depleted && !wasDepleted
        if (covered || died) pause(if (key == null || key == DEAD) null else key)
        coverKey = key
        wasDepleted = depleted
    }

    /**
     * Attach to the session list, or let go of it. Access can be revoked at any time, and a
     * monitor that cannot hear must not keep insisting something is playing: the failure
     * path drops every controller, draining the set through the same grace a real pause
     * would.
     */
    private fun bind() {
        val manager = sessions ?: return
        val ok = runCatching {
            if (!bound) {
                manager.addOnActiveSessionsChangedListener(sessionsChanged, component, handler)
                bound = true
            }
            rebind(manager.getActiveSessions(component))
        }.isSuccess
        if (!ok) {
            // Let go of whatever half succeeded: an add that landed before the session
            // read threw would stay registered while bound says otherwise.
            if (bound) runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChanged) }
            bound = false
            rebind(emptyList())
        }
    }

    /** The live sessions changed. Follow every one of them, drop the ones that went away. */
    private fun rebind(list: List<MediaController>) {
        for ((controller, callback) in callbacks) runCatching { controller.unregisterCallback(callback) }
        callbacks.clear()
        controllers.clear()
        controllers.addAll(list)
        for (controller in list) {
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = recompute()
                override fun onSessionDestroyed() = recompute()
            }
            runCatching { controller.registerCallback(callback, handler) }
            callbacks[controller] = callback
        }
        recompute()
    }

    private fun recompute() {
        val heard = LinkedHashSet<String>()
        for (controller in controllers) {
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                heard.add(controller.packageName)
            }
        }

        var moved = false
        for (pkg in heard) {
            leaving.remove(pkg)?.cancel()
            if (playing.add(pkg)) moved = true
        }
        for (pkg in playing.toList()) {
            if (pkg in heard || pkg in leaving) continue
            leaving[pkg] = scope.launch {
                delay(LEAVE_GRACE_MS)
                leaving.remove(pkg)
                if (playing.remove(pkg)) publish()
            }
        }
        if (moved) publish()
    }

    private fun publish() = onChanged(LinkedHashSet(playing), System.currentTimeMillis())

    /** Pause one tracked id, or every tracked app when [id] is null. */
    private fun pause(id: String?) {
        for (controller in controllers) {
            val pkg = controller.packageName
            if (!isTracked(pkg)) continue
            if (id != null && AppCatalog.idFor(pkg) != id) continue
            if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) continue
            runCatching { controller.transportControls.pause() }
        }
    }

    private companion object {
        const val LEAVE_GRACE_MS = 10_000L

        /** The dead battery's cover has no one app behind it. */
        const val DEAD = "dead"
    }
}
