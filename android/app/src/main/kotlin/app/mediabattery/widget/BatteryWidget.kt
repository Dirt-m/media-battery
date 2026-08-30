package app.mediabattery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.mediabattery.MainActivity
import app.mediabattery.MediaBatteryApp
import app.mediabattery.R
import site.dirt23.battery.core.model.Snapshot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The charge on the home screen.
 *
 * Classic RemoteViews, not Glance: two pieces of text and a color are not worth a whole
 * Compose-for-widgets dependency.
 *
 * Pushed, never polled. `updatePeriodMillis` is zero and there is no alarm; the running
 * service hands it a snapshot whenever the reading changes, the same cadence the ongoing
 * notification uses. With the service not running the widget keeps what it last showed.
 *
 * That cadence is a minute, so the seconds sit at :59 while it drains and :00 while it
 * climbs.
 *
 * No buttons: reserve costs the friction gate wherever it is spent, and a home screen
 * shortcut past it would undo the point of the app.
 */
class BatteryWidget : AppWidgetProvider() {

    /** The system's own ask, on placement or a reboot. */
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val graph = (context.applicationContext as? MediaBatteryApp)?.graph ?: return
        render(context, manager, ids, graph.engineHost.snapshot.value)
    }

    companion object {
        /** The word under the reading; the same three the quick settings tile uses. */
        fun word(context: Context, snapshot: Snapshot): String = when {
            snapshot.depleted -> context.getString(R.string.tile_blocked)
            snapshot.draining -> context.getString(R.string.tile_draining)
            else -> context.getString(R.string.tile_recharging)
        }

        /** Push a reading out. A no-op when no widget is placed. */
        fun push(context: Context, snapshot: Snapshot) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, BatteryWidget::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return
            render(context, manager, ids, snapshot)
        }

        private fun render(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            snapshot: Snapshot,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_battery)
            views.setTextViewText(R.id.widget_charge, clock(snapshot.charge))
            views.setTextColor(R.id.widget_charge, context.getColor(colorOf(snapshot)))
            views.setTextViewText(R.id.widget_state, word(context, snapshot).uppercase())
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            runCatching { manager.updateAppWidget(ids, views) }
        }

        /** The gauge's colors: green above a fifth, red while going down or gone. */
        fun colorOf(snapshot: Snapshot): Int {
            val cap = if (snapshot.effCapacity > 0) snapshot.effCapacity else snapshot.capacity
            val pct = if (cap > 0) min(100.0, snapshot.charge / cap * 100.0) else 0.0
            return when {
                snapshot.depleted -> R.color.sb_red
                pct > 20 -> R.color.sb_green
                snapshot.draining -> R.color.sb_red
                else -> R.color.sb_amber
            }
        }

        private fun clock(seconds: Double): String {
            val s = max(0.0, seconds).roundToInt()
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
