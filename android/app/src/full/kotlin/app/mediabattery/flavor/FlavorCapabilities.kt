package app.mediabattery.flavor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.mediabattery.detect.AccessibilityForegroundDetector
import app.mediabattery.detect.FastForegroundDetector
import app.mediabattery.service.MbAccessibilityService

/**
 * The sideload build, which has the two things the store build may not: an accessibility
 * service to be told who came to the front, and permission to ask for the battery exemption
 * outright instead of pointing at a list.
 *
 * Everything accessibility is reached through here, so the play sources reference none of it.
 */
object FlavorCapabilities : Capabilities {
    override fun fastDetector(context: Context): FastForegroundDetector =
        AccessibilityForegroundDetector.install(context)

    /**
     * Read off the enabled list rather than asked of the running service: a service Android
     * has not got round to binding yet is still switched on.
     */
    override fun accessibilityEnabled(context: Context): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull().orEmpty()
        val component = ComponentName(context, MbAccessibilityService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    override fun accessibilityIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    override fun batteryExemptionIntent(context: Context): Intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
}
