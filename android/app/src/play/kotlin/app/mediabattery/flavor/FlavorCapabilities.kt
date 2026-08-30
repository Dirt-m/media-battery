package app.mediabattery.flavor

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.mediabattery.detect.FastForegroundDetector

/**
 * The Play build, which has none of the extras.
 *
 * No accessibility service: the store reserves it for apps that exist to serve users with a
 * disability, and a battery is not that. So the poll is the only detector and blocks land a
 * fraction later.
 *
 * The battery exemption is the settings list rather than the direct request, for the same
 * reason: asking outright is only allowed for a short list of app types.
 */
object FlavorCapabilities : Capabilities {
    override fun fastDetector(context: Context): FastForegroundDetector? = null

    override fun accessibilityEnabled(context: Context): Boolean? = null

    override fun accessibilityIntent(): Intent? = null

    override fun batteryExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
