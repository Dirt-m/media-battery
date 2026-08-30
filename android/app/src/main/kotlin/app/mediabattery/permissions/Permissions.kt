package app.mediabattery.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * The grants the app runs on, and the settings screens that hand them over.
 *
 * Usage access is the only one it cannot work without: it names the app in front. Overlay
 * decides how the block lands, notifications decide whether the charge reading is visible
 * while you are elsewhere. Notification access lets the app hear a tracked app playing, and
 * the battery exemption keeps the service alive while the phone is idle. The accessibility
 * grant is a build difference and lives in the flavor capabilities.
 */
object Permissions {

    /**
     * Usage access is an app op, not a runtime permission, so it is read through
     * AppOpsManager. MODE_DEFAULT means the op defers to the permission, which is the one
     * case where the PACKAGE_USAGE_STATS grant is worth checking.
     */
    // unsafeCheckOpNoThrow is deprecated on the newest platform, where the replacement
    // wants an attribution tag. This form reads the same op on every level the app
    // supports, so it stays until minSdk moves.
    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid,
            context.packageName,
        )
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT ->
                context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                    PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Below API 33 a posted notification needs no grant at all. */
    fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Notification access is the only way to get at the media session list. The listener
     * itself reads nothing, see `MbNotificationListenerService`.
     */
    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** False while the system may doze the service. Reading it needs no grant. */
    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun notificationAccessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    /** The package uri lands on this app's row instead of the full app list. */
    fun overlayIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
}
