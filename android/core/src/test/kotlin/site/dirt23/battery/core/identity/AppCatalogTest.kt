package site.dirt23.battery.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppCatalogTest {

    @Test
    fun `built in packages map to the extension's platform ids`() {
        assertEquals("youtube", AppCatalog.idFor("com.google.android.youtube"))
        assertEquals("instagram", AppCatalog.idFor("com.instagram.android"))
        assertEquals("tiktok", AppCatalog.idFor("com.zhiliaoapp.musically"))
        assertEquals("x", AppCatalog.idFor("com.twitter.android"))
        assertEquals("facebook", AppCatalog.idFor("com.facebook.katana"))
        assertEquals("reddit", AppCatalog.idFor("com.reddit.frontpage"))
        assertEquals("twitch", AppCatalog.idFor("tv.twitch.android.app"))
        assertEquals("linkedin", AppCatalog.idFor("com.linkedin.android"))
    }

    @Test
    fun `a lite or renamed build is the same tracked app`() {
        assertEquals(AppCatalog.idFor("com.instagram.android"), AppCatalog.idFor("com.instagram.lite"))
        assertEquals(AppCatalog.idFor("com.facebook.katana"), AppCatalog.idFor("com.facebook.lite"))
        assertEquals(AppCatalog.idFor("com.twitter.android"), AppCatalog.idFor("com.x.android"))
        assertEquals(AppCatalog.idFor("com.zhiliaoapp.musically"), AppCatalog.idFor("com.ss.android.ugc.trill"))
    }

    @Test
    fun `an unknown package gets a prefixed custom id`() {
        assertEquals("app:com.example.reader", AppCatalog.idFor("com.example.reader"))
        assertFalse(AppCatalog.isBuiltIn("app:com.example.reader"))
    }

    @Test
    fun `a custom id can never collide with a built in one`() {
        for (id in AppCatalog.builtInIds()) assertFalse(id.startsWith(AppCatalog.CUSTOM_PREFIX))
    }

    @Test
    fun `reverse lookup names every package that carries the id`() {
        assertEquals(listOf("com.instagram.android", "com.instagram.lite"), AppCatalog.packagesFor("instagram"))
        assertEquals(listOf("com.example.reader"), AppCatalog.packagesFor("app:com.example.reader"))
        assertEquals(emptyList(), AppCatalog.packagesFor("nothing"))
        assertEquals(emptyList(), AppCatalog.packagesFor(AppCatalog.CUSTOM_PREFIX))
    }

    @Test
    fun `every package round trips through its id`() {
        for (id in AppCatalog.builtInIds()) {
            assertTrue(AppCatalog.isBuiltIn(id))
            val packages = AppCatalog.packagesFor(id)
            assertTrue(packages.isNotEmpty())
            for (p in packages) assertEquals(id, AppCatalog.idFor(p))
        }
    }

    @Test
    fun `no package is claimed by two ids`() {
        val seen = mutableSetOf<String>()
        for (id in AppCatalog.builtInIds()) {
            for (p in AppCatalog.packagesFor(id)) assertTrue(seen.add(p), "$p is claimed twice")
        }
    }
}
