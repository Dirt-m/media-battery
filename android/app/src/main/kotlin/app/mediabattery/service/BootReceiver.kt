package app.mediabattery.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mediabattery.data.AppPrefs

/**
 * Starts the service again after a reboot or an app update, but only once onboarding is
 * done, so a phone that was never set up gets no foreground notification. That flag lives in
 * device protected storage so it can be read at boot, before the first unlock.
 *
 * The battery state is not readable that early. Nothing is lost: the gap replays out of the
 * usage event log when the service does start.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        if (!AppPrefs(context).onboarded) return
        BatteryService.start(context)
    }
}
