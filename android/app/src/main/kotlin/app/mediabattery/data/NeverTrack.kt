package app.mediabattery.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/**
 * The packages this app must never cover, whatever the user picks.
 *
 * A safety rule with no switch. A cover over Settings can lock the user out of turning the
 * app off, and one over the keyboard or dialer can stop them typing or calling for help.
 * Enforced twice: the picker never offers these, and the decision path checks again before
 * anything gets covered.
 *
 * Resolved at runtime rather than hard coded, since the launcher, dialer, and keyboard are
 * the user's own choice and differ per device. Settings is resolved the same way and also
 * pinned by name, in case a device answers the Settings intent oddly.
 */
class NeverTrack(private val context: Context) {
    @Volatile
    private var packages: Set<String> = resolve()

    /** Re-resolve. Cheap; run on picker open and service start. */
    fun refresh() {
        packages = resolve()
    }

    fun contains(pkg: String): Boolean = pkg in packages

    /** The current set, for the picker's filter. */
    fun all(): Set<String> = packages

    private fun resolve(): Set<String> {
        val pm = context.packageManager
        val out = LinkedHashSet<String>()
        out.add(context.packageName)
        out.add(SETTINGS_PACKAGE)

        runCatching {
            pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { out.add(it) }
        }
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                ?.takeIf { it != "android" }
                ?.let { out.add(it) }
        }
        runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { out.add(it) }
        }
        runCatching {
            val imm = context.getSystemService(InputMethodManager::class.java)
            // Every enabled keyboard, not just the current one, so switching keyboards
            // cannot walk into a cover.
            imm?.enabledInputMethodList?.forEach { out.add(it.packageName) }
        }
        return out
    }

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
