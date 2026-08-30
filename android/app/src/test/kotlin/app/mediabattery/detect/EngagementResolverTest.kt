package app.mediabattery.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The engagement model is one pure function. Pinned here: what counts, what does not, and
 * which app the drain gets named after when two of them count.
 */
class EngagementResolverTest {
    private val youtube = "com.google.android.youtube"
    private val spotify = "com.spotify.music"

    private fun resolve(
        foreground: String? = null,
        interactive: Boolean = true,
        locked: Boolean = false,
        playing: Set<String> = emptySet(),
        tracked: Set<String> = setOf(youtube),
        usable: (String) -> Boolean = { true },
    ): String? = EngagementResolver.engagedId(
        foregroundPackage = foreground,
        interactive = interactive,
        locked = locked,
        playingPackages = playing,
        isTracked = { it in tracked },
        usable = usable,
    )

    @Test
    fun `a tracked app in front counts`() {
        assertEquals("youtube", resolve(foreground = youtube))
    }

    @Test
    fun `a dark screen ends the watching`() {
        assertNull(resolve(foreground = youtube, interactive = false))
    }

    @Test
    fun `sound counts with the screen off and the phone locked`() {
        assertEquals(
            "youtube",
            resolve(interactive = false, locked = true, playing = setOf(youtube)),
        )
    }

    @Test
    fun `an untracked app playing counts for nobody`() {
        assertNull(resolve(interactive = false, playing = setOf(spotify)))
    }

    @Test
    fun `an app switched off does not drain while it plays`() {
        assertNull(resolve(playing = setOf(youtube), usable = { false }))
    }

    @Test
    fun `the app in front names the drain when something else is playing`() {
        val tracked = setOf(youtube, spotify)
        assertEquals(
            "youtube",
            resolve(foreground = youtube, playing = setOf(spotify), tracked = tracked),
        )
    }

    @Test
    fun `sound carries the drain when the app in front is not tracked`() {
        val tracked = setOf(youtube)
        assertEquals(
            "youtube",
            resolve(foreground = spotify, playing = setOf(youtube), tracked = tracked),
        )
    }
}
