package app.mediabattery.warn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.mediabattery.MainActivity
import app.mediabattery.R
import site.dirt23.battery.core.model.Snapshot
import kotlin.math.ceil
import kotlin.math.max

/**
 * One warning before the battery runs out, ported from the extension's corner toast.
 *
 * Fires on the first drop under the warning threshold while the charge is draining, then
 * not again until the charge has climbed clear of the threshold with a minute to spare.
 * Without that margin a charge hovering on the line would warn, recover a second, and warn
 * again.
 *
 * Never fires over a cover. The re-arming still runs, so the warning lands later on an app
 * that is not covered rather than being lost.
 *
 * Its own channel at ordinary importance, separate from the silent ongoing reading. The
 * only notification the app ever makes a sound with.
 */
class LowChargeWarner(private val context: Context) {
    private var armed = true
    private var showing = false

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.warn_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.warn_channel_why) }
        manager()?.createNotificationChannel(channel)
    }

    /**
     * One snapshot. [covered] is true while the block screen is over the app in front;
     * [inUse] while the screen is on and unlocked.
     */
    fun onSnapshot(snapshot: Snapshot, covered: Boolean, inUse: Boolean) {
        if (snapshot.depleted) {
            // The cover says it all now.
            hide()
            return
        }
        val threshold = snapshot.warnSeconds
        if (threshold <= 0) {
            armed = true
            hide()
            return
        }
        if (snapshot.charge > threshold + REARM_MARGIN_SECONDS) {
            armed = true
            hide()
            return
        }
        if (covered) {
            hide()
            return
        }
        // A media drain with the screen off stays quiet: still armed, so it fires on the
        // settle after the screen comes back if the charge is still low. Otherwise a
        // podcast at night rings the phone.
        if (!inUse) {
            hide()
            return
        }
        if (!armed || snapshot.charge > threshold || !snapshot.draining) return
        armed = false
        show(max(1.0, ceil(snapshot.charge / 60.0)).toInt())
    }

    /** The service is going away; the warning must not outlive it. */
    fun clear() = hide()

    private fun show(minutes: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.warn_text, minutes))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        // A no-op without the notification grant.
        runCatching { manager()?.notify(NOTIFICATION_ID, notification) }
        showing = true
    }

    private fun hide() {
        if (!showing) return
        showing = false
        runCatching { manager()?.cancel(NOTIFICATION_ID) }
    }

    private fun manager(): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private companion object {
        const val CHANNEL_ID = "warnings"
        const val NOTIFICATION_ID = 2

        /** How far above the threshold the charge has to climb before it can warn again. */
        const val REARM_MARGIN_SECONDS = 60
    }
}
