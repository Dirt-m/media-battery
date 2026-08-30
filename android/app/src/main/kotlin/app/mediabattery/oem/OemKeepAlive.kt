package app.mediabattery.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import app.mediabattery.R

/**
 * Vendors that ship a kill list of their own on top of Android's battery exemption
 * (autostart lists, protected apps, sleeping apps). On those phones a service that is exempt
 * by the platform's rules still gets frozen, and the fix is always the same: find the
 * vendor's list and let this app through. See dontkillmyapp.com.
 *
 * Shown as one card, only on a phone that has such a list, naming the setting in that
 * vendor's words. Nothing here is detectable, so nothing can be marked done, and ignoring it
 * costs a background drain, not the app.
 */
object OemKeepAlive {

    /** A vendor with a list of its own, and the screens known to open it. */
    data class Vendor(
        val id: String,
        @get:StringRes val line: Int,
        /** Best first. Each is tried in turn; app details is the fallback under all of them. */
        val targets: List<ComponentName>,
    )

    private val VENDORS: List<Vendor> = listOf(
        Vendor(
            id = "xiaomi",
            line = R.string.oem_xiaomi,
            targets = listOf(
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ),
        ),
        Vendor(
            id = "huawei",
            line = R.string.oem_huawei,
            targets = listOf(
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            ),
        ),
        Vendor(
            id = "oppo",
            line = R.string.oem_oppo,
            targets = listOf(
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ),
        ),
        Vendor(
            id = "vivo",
            line = R.string.oem_vivo,
            targets = listOf(
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ),
        ),
        Vendor(
            id = "samsung",
            line = R.string.oem_samsung,
            targets = listOf(
                component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            ),
        ),
        Vendor(
            id = "oneplus",
            line = R.string.oem_oneplus,
            targets = listOf(
                component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            ),
        ),
    )

    /** The vendor this phone is, or null on a phone with nothing extra to switch off. */
    fun current(manufacturer: String = Build.MANUFACTURER): Vendor? {
        val name = manufacturer.lowercase()
        return VENDORS.firstOrNull { name.contains(it.id) }
            // Realme ships ColorOS, so it gets the oppo entry.
            ?: VENDORS.firstOrNull { it.id == "oppo" }?.takeIf { name.contains("realme") }
    }

    /**
     * Where to send the user, best first; the caller stops on the first that opens. Several
     * of these screens resolve and then refuse to start (not exported), which only shows up
     * on the launch. The last is this app's own details page, which every Android has.
     */
    fun intents(context: Context, vendor: Vendor): List<Intent> {
        val out = ArrayList<Intent>(vendor.targets.size + 1)
        for (target in vendor.targets) {
            val intent = Intent().setComponent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) != null) out.add(intent)
        }
        out.add(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
        return out
    }

    private fun component(pkg: String, cls: String) = ComponentName(pkg, cls)
}
