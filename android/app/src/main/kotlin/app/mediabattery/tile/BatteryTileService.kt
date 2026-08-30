package app.mediabattery.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.mediabattery.MainActivity
import app.mediabattery.MediaBatteryApp
import app.mediabattery.R
import app.mediabattery.service.BatteryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import site.dirt23.battery.core.model.Snapshot
import kotlin.math.ceil
import kotlin.math.max

/**
 * The charge, one swipe away: the reading in minutes and one word for what it is doing.
 * Tapping it opens the app rather than toggling anything, since a battery you can switch
 * off from the shade is no battery.
 *
 * It only follows the snapshot while the shade is open, which quick settings tells us.
 */
class BatteryTileService : TileService() {
    private var jobs: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val graph = (application as MediaBatteryApp).graph
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        jobs = scope
        scope.launch { graph.engineHost.snapshot.collect(::render) }
    }

    override fun onStopListening() {
        jobs?.cancel()
        jobs = null
        super.onStopListening()
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(snapshot: Snapshot) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.notif_minutes, minutesOf(snapshot.charge))
        tile.subtitle = word(snapshot)
        // Active means the service is running; nothing about the charge belongs in a state
        // that renders as on or off.
        tile.state = if (BatteryService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.contentDescription = "${tile.label}, ${tile.subtitle}"
        tile.updateTile()
    }

    private fun word(snapshot: Snapshot): String = when {
        snapshot.depleted -> getString(R.string.tile_blocked)
        snapshot.draining -> getString(R.string.tile_draining)
        else -> getString(R.string.tile_recharging)
    }

    private fun minutesOf(seconds: Double): Int = max(1.0, ceil(max(0.0, seconds) / 60.0)).toInt()
}
