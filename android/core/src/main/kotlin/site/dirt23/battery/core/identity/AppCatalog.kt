package site.dirt23.battery.core.identity

/**
 * Package name to canonical id, and back.
 *
 * The ids are the ones the engine stores in `siteModes` and `sitePasses`, and they are the
 * extension's platform ids from `platforms.js`, so the Android YouTube and youtube.com in
 * a browser are one tracked thing on a shared battery.
 *
 * Several packages can map to one id (a lite build, a rename, a regional split); the user
 * picked "Instagram", not an APK. Anything not in the table gets `app:<package>`, a prefix
 * that keeps a user's pick from colliding with a built-in id added in a later version.
 */
object AppCatalog {
    /** Prefix for an id not in the table. */
    const val CUSTOM_PREFIX = "app:"

    /** Ids mirror the extension's platform ids. Order is what [builtInIds] reports. */
    private val BUILT_INS: List<Pair<String, List<String>>> = listOf(
        "youtube" to listOf("com.google.android.youtube"),
        "instagram" to listOf("com.instagram.android", "com.instagram.lite"),
        "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "x" to listOf("com.twitter.android", "com.x.android"),
        "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
        "reddit" to listOf("com.reddit.frontpage"),
        "twitch" to listOf("tv.twitch.android.app"),
        "linkedin" to listOf("com.linkedin.android"),
    )

    private val idByPackage: Map<String, String> =
        BUILT_INS.flatMap { (id, packages) -> packages.map { it to id } }.toMap()

    private val packagesById: Map<String, List<String>> = BUILT_INS.toMap()

    /** Every built-in id, in table order. */
    fun builtInIds(): List<String> = BUILT_INS.map { it.first }

    /** True when [id] names a built-in rather than a user's own pick. */
    fun isBuiltIn(id: String): Boolean = packagesById.containsKey(id)

    /** The canonical id for a package. Never null: an unknown package gets a custom id. */
    fun idFor(packageName: String): String =
        idByPackage[packageName] ?: (CUSTOM_PREFIX + packageName)

    /**
     * Which packages carry this id. A built-in can name several, a custom id names one, and
     * an id that is neither names nothing.
     */
    fun packagesFor(id: String): List<String> = when {
        packagesById.containsKey(id) -> packagesById.getValue(id)
        id.startsWith(CUSTOM_PREFIX) && id.length > CUSTOM_PREFIX.length ->
            listOf(id.removePrefix(CUSTOM_PREFIX))
        else -> emptyList()
    }
}
