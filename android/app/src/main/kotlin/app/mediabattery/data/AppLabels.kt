package app.mediabattery.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import site.dirt23.battery.core.identity.AppCatalog
import site.dirt23.battery.core.model.Snapshot

/**
 * Names and icons for installed packages, with a small bounded cache.
 *
 * Loading an icon hits the package manager and decodes a drawable, and the app list draws
 * dozens of them while scrolling, so both are memoized. The cache is an insertion ordered
 * map trimmed from the front, close enough to least recently used at a few hundred entries,
 * and bounded so a long scroll cannot grow it without end.
 */
class AppLabels(context: Context) {
    private val pm: PackageManager = context.packageManager
    private val labels = LinkedHashMap<String, String>()
    private val icons = LinkedHashMap<String, Drawable?>()

    /** The user visible name, falling back to the package when the app is gone. */
    @Synchronized
    fun label(pkg: String): String = labels.getOrPut(pkg) {
        trim(labels)
        runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrNull() ?: pkg
    }

    @Synchronized
    fun icon(pkg: String): Drawable? {
        if (icons.containsKey(pkg)) return icons[pkg]
        trim(icons)
        val d = runCatching { pm.getApplicationInfo(pkg, 0).loadIcon(pm) }.getOrNull()
        icons[pkg] = d
        return d
    }

    /**
     * The app a snapshot says is draining, named. Null when there is no local app to name,
     * which is how a drain mirrored from another device looks from here.
     *
     * An id can carry several packages (a lite build, a rename), so the installed one wins.
     */
    fun draining(snap: Snapshot): String? {
        val id = snap.drainingId ?: return null
        val packages = AppCatalog.packagesFor(id)
        val pkg = packages.firstOrNull { installed(it) } ?: packages.firstOrNull()
        return pkg?.let { label(it) } ?: snap.drainingName ?: id
    }

    /**
     * A tracked id, named for a list. The installed app's own name when there is one; a
     * built-in the phone does not have keeps its id ("youtube"), and a custom id falls back
     * to its package.
     */
    fun siteName(id: String): String {
        val named = AppCatalog.packagesFor(id).firstNotNullOfOrNull { pkg ->
            label(pkg).takeIf { it != pkg }
        }
        return named ?: id.removePrefix(AppCatalog.CUSTOM_PREFIX).replaceFirstChar { it.uppercase() }
    }

    private fun installed(pkg: String): Boolean =
        runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess

    /** Every launchable app, the only ones the picker offers. */
    fun launchable(): List<ApplicationInfo> {
        val main = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(main, 0)
                .mapNotNull { it.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
        }.getOrDefault(emptyList())
    }

    private fun <T> trim(map: LinkedHashMap<String, T>) {
        while (map.size >= MAX) {
            val oldest = map.keys.firstOrNull() ?: return
            map.remove(oldest)
        }
    }

    private companion object {
        const val MAX = 300
    }
}
