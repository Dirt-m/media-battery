package app.mediabattery.detect

import site.dirt23.battery.core.identity.AppCatalog

/**
 * Answers whether charge is being spent right now, and on what.
 *
 * Two ways to spend it. Watching: the app in front is tracked, the screen is on, the lock
 * screen is not up, and the engine says time on that app counts (not switched off, not
 * behind a cover the user has not paid to pass). Listening: a tracked app is playing sound,
 * which counts with the screen off and the phone locked.
 *
 * The app in front wins when both hold, so the drain is named after what the user is doing
 * rather than what happens to be making noise underneath it.
 *
 * Pure; the timing lives upstream in the host.
 */
object EngagementResolver {
    fun engagedId(
        foregroundPackage: String?,
        interactive: Boolean,
        locked: Boolean,
        playingPackages: Set<String>,
        isTracked: (String) -> Boolean,
        usable: (String) -> Boolean,
    ): String? {
        watching(foregroundPackage, interactive, locked, isTracked, usable)?.let { return it }
        for (pkg in playingPackages) {
            if (!isTracked(pkg)) continue
            val id = AppCatalog.idFor(pkg)
            if (usable(id)) return id
        }
        return null
    }

    private fun watching(
        foregroundPackage: String?,
        interactive: Boolean,
        locked: Boolean,
        isTracked: (String) -> Boolean,
        usable: (String) -> Boolean,
    ): String? {
        val pkg = foregroundPackage ?: return null
        if (!interactive || locked) return null
        if (!isTracked(pkg)) return null
        val id = AppCatalog.idFor(pkg)
        return if (usable(id)) id else null
    }
}
