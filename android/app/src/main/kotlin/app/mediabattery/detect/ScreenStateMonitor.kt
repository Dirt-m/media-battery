package app.mediabattery.detect

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Is the screen on, and is the lock screen up?
 *
 * Both gate engagement: a phone in a pocket is showing whatever app was last open, and a
 * locked phone with the screen on is showing the lock screen. The usage event stream
 * carries the same two facts but only arrives when we poll, so the broadcasts are here to
 * make screen off immediate.
 *
 * The two clock broadcasts ride along because this is already the receiver: a wall clock or
 * time zone change is handed to the engine so it can settle the time that actually passed
 * and carry every stored stamp across the jump.
 */
class ScreenStateMonitor(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    private val _interactive = MutableStateFlow(readInteractive())
    val interactive: StateFlow<Boolean> = _interactive.asStateFlow()

    private val _locked = MutableStateFlow(readLocked())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Called on a wall clock or time zone change, on the main thread. */
    var onClockChanged: (() -> Unit)? = null

    /** Called whenever the screen or lock state moves, with the moment it moved. */
    var onScreenChanged: ((interactive: Boolean, locked: Boolean, atMs: Long) -> Unit)? = null

    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val at = System.currentTimeMillis()
                when (intent?.action) {
                    Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                        onClockChanged?.invoke()
                        return
                    }
                }
                refresh(at)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
        refresh(System.currentTimeMillis())
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /** Re-read both facts from the system and publish any change. */
    fun refresh(atMs: Long = System.currentTimeMillis()) {
        val i = readInteractive()
        val l = readLocked()
        val moved = i != _interactive.value || l != _locked.value
        _interactive.value = i
        _locked.value = l
        if (moved) onScreenChanged?.invoke(i, l, atMs)
    }

    private fun readInteractive(): Boolean = power?.isInteractive ?: true

    private fun readLocked(): Boolean = keyguard?.isKeyguardLocked ?: false
}
