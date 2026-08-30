package app.mediabattery.detect

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading of the event stream, used by both the live detector and the replayer. */
class UsageEventWalkerTest {
    private val youtube = "com.google.android.youtube"
    private val spotify = "com.spotify.music"

    @Test
    fun `the app in front is the last one to resume`() {
        val walker = UsageEventWalker()
        walker.feed(UsageEvents.Event.ACTIVITY_RESUMED, youtube, 0L)
        assertEquals(youtube, walker.packageName)
        walker.feed(UsageEvents.Event.ACTIVITY_PAUSED, youtube, 1L)
        assertNull(walker.packageName)
    }

    @Test
    fun `a pause of some other app leaves the one in front alone`() {
        val walker = UsageEventWalker()
        walker.feed(UsageEvents.Event.ACTIVITY_RESUMED, youtube, 0L)
        walker.feed(UsageEvents.Event.ACTIVITY_PAUSED, spotify, 1L)
        assertEquals(youtube, walker.packageName)
    }

    @Test
    fun `an event the walker does not read moves nothing`() {
        // An event type outside the reading must move neither the app in front nor the
        // screen and lock facts.
        val walker = UsageEventWalker()
        walker.feed(UsageEvents.Event.ACTIVITY_RESUMED, youtube, 0L)
        walker.feed(UsageEvents.Event.FOREGROUND_SERVICE_START, spotify, 1L)
        assertEquals(youtube, walker.packageName)
        assertTrue(walker.interactive)
        assertFalse(walker.locked)
    }

    @Test
    fun `a dark screen and a lock are separate facts`() {
        val walker = UsageEventWalker()
        walker.feed(UsageEvents.Event.SCREEN_NON_INTERACTIVE, null, 0L)
        walker.feed(UsageEvents.Event.KEYGUARD_SHOWN, null, 1L)
        assertFalse(walker.interactive)
        assertTrue(walker.locked)
        walker.feed(UsageEvents.Event.KEYGUARD_HIDDEN, null, 2L)
        assertFalse(walker.interactive)
        assertFalse(walker.locked)
    }
}
