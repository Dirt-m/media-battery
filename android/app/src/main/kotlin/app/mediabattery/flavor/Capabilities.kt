package app.mediabattery.flavor

import android.content.Context
import android.content.Intent
import app.mediabattery.detect.FastForegroundDetector

/**
 * What this build can do that the other one cannot.
 *
 * The sideload build ships an accessibility service and asks for the battery exemption
 * directly; the Play build may do neither. Rather than branch on a flavor name, each source
 * set declares its own `FlavorCapabilities` and everything in main sees only this
 * interface, so the play sources contain no accessibility reference at all and a reviewer
 * can check that by reading them.
 */
interface Capabilities {
    /** Push based foreground detection, or null in a build that has none. */
    fun fastDetector(context: Context): FastForegroundDetector?

    /** Is the fast path turned on? Null when this build ships no service to turn on. */
    fun accessibilityEnabled(context: Context): Boolean?

    /** Where the user turns it on; null for the same reason. */
    fun accessibilityIntent(): Intent?

    /** The battery exemption: asked for directly in full, found in a list in play. */
    fun batteryExemptionIntent(context: Context): Intent
}
