package app.mediabattery.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import app.mediabattery.data.TrackedAppsStore
import app.mediabattery.detect.EngagementResolver
import app.mediabattery.detect.UsageEventWalker
import app.mediabattery.engine.SettingsView
import app.mediabattery.engine.Transition
import app.mediabattery.permissions.Permissions
import site.dirt23.battery.core.SbClock
import site.dirt23.battery.core.model.RuleAction
import site.dirt23.battery.core.model.SiteMode
import site.dirt23.battery.core.rules.Rules
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Reconstructs what happened while the service was not running (a kill, a reboot, an app
 * update). The usage event log kept running, so on start the gap is walked back out of it
 * and handed to the engine as the transitions it missed, in order, each stamped with the
 * moment it happened. The engine works on timestamp deltas, so a replayed hour settles the
 * same as a lived one.
 *
 * Two details keep it exact rather than approximate. The walk starts two hours before the
 * window, so the state at the window's first instant is known rather than guessed. And the
 * timeline is cut at every block or off rule edge as well as at every event, since the
 * engine only re-reads whether time counts at a transition.
 *
 * Passes are not reconstructed: a pass that was live during the gap is forgotten and that
 * stretch counts as covered, which under drains rather than over drains.
 *
 * Screen off playback is the one thing the walk cannot see. The media sessions the live path
 * listens to went with the process and the log keeps no reliable trace of playback, so that
 * time recharges.
 */
class UsageEventsReplayer(
    private val context: Context,
    private val tracked: TrackedAppsStore,
    private val clock: SbClock,
) {
    /** Null when usage access is missing: nothing can be read, so the gap simply recharges. */
    fun replay(sinceMs: Long, settings: SettingsView): List<Transition>? {
        if (!Permissions.hasUsageAccess(context)) return null
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null

        val now = clock.wallNow()
        // Zero is "never ran before", not "replay everything": the first run, or a backup
        // restore that brought the tracked list but not these device protected prefs,
        // must not charge a day of usage the battery never covered.
        if (sinceMs <= 0L) return emptyList()
        val windowStart = maxOf(sinceMs, now - MAX_WINDOW_MS).coerceAtMost(now)
        if (now - windowStart < MIN_WINDOW_MS) return emptyList()

        val events = read(usm, windowStart - SEED_LOOKBACK_MS, now) ?: return null
        val zone = clock.zone()

        val walker = UsageEventWalker()
        var index = 0
        // The seed leg: state at the window's first instant, nothing reported.
        while (index < events.size && events[index].at < windowStart) {
            val e = events[index]
            walker.feed(e.type, e.pkg, e.at)
            index++
        }

        val marks = (events.drop(index).map { it.at } + ruleEdges(settings, windowStart, now, zone))
            .filter { it in windowStart..now }
            .distinct()
            .sorted()

        val out = ArrayList<Transition>()
        var last: String? = null
        for (mark in marks) {
            while (index < events.size && events[index].at <= mark) {
                val e = events[index]
                walker.feed(e.type, e.pkg, e.at)
                index++
            }
            val id = engagedAt(walker, settings, mark, zone)
            if (id != last) {
                out.add(Transition(id, mark))
                last = id
            }
        }
        return out
    }

    /**
     * Who was draining at [atMs], resolved the way the live host resolves it. Playback has no
     * replayed counterpart, see the class doc.
     */
    private fun engagedAt(
        walker: UsageEventWalker,
        settings: SettingsView,
        atMs: Long,
        zone: ZoneId,
    ): String? = EngagementResolver.engagedId(
        foregroundPackage = walker.packageName,
        interactive = walker.interactive,
        locked = walker.locked,
        playingPackages = emptySet(),
        isTracked = tracked::isTracked,
        usable = { id -> usableAt(settings, id, atMs, zone) },
    )

    /** Did time on this app count at that moment? Off first, then the covers. */
    private fun usableAt(settings: SettingsView, id: String, atMs: Long, zone: ZoneId): Boolean {
        val mode = settings.siteModes[id] ?: SiteMode.ON
        if (mode == SiteMode.OFF) return false
        val effects = Rules.siteEffectsAt(settings.hourRules, id, atMs, zone)
        if (effects.off) return false
        return mode != SiteMode.BLOCK && !effects.blocked
    }

    /** Every instant in the window where a block or off window opens or closes. */
    private fun ruleEdges(settings: SettingsView, fromMs: Long, toMs: Long, zone: ZoneId): List<Long> {
        val rules = settings.hourRules.filter { it.action == RuleAction.BLOCK || it.action == RuleAction.OFF }
        if (rules.isEmpty()) return emptyList()
        val firstDay = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate().minusDays(1)
        val days = ((toMs - fromMs) / 86_400_000L).toInt() + 3
        val out = ArrayList<Long>()
        for (rule in rules) {
            for (minutes in listOfNotNull(Rules.parseHM(rule.from), Rules.parseHM(rule.to))) {
                val time = LocalTime.of(minutes / 60, minutes % 60)
                for (d in 0 until days) {
                    out.add(ZonedDateTime.of(firstDay.plusDays(d.toLong()), time, zone).toInstant().toEpochMilli())
                }
            }
        }
        return out
    }

    private fun read(usm: UsageStatsManager, fromMs: Long, toMs: Long): List<RawEvent>? {
        val stream = runCatching { usm.queryEvents(fromMs, toMs) }.getOrNull() ?: return null
        val out = ArrayList<RawEvent>()
        val event = UsageEvents.Event()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(event)
            out.add(RawEvent(event.eventType, event.packageName, event.timeStamp))
        }
        out.sortBy { it.at }
        return out
    }

    private data class RawEvent(val type: Int, val pkg: String?, val at: Long)

    private companion object {
        const val MAX_WINDOW_MS = 24 * 60 * 60 * 1000L
        const val SEED_LOOKBACK_MS = 2 * 60 * 60 * 1000L

        /** Below this the gap is scheduling noise, not an outage worth walking. */
        const val MIN_WINDOW_MS = 2_000L
    }
}
