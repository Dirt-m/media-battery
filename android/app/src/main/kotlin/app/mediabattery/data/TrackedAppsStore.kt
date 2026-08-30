package app.mediabattery.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Which packages the user picked, device local.
 *
 * Only membership lives here. The mode of a tracked app (On, Off, Block) lives in the
 * engine's `siteModes` under the canonical id, because that is what syncs: a package list
 * is about this phone, a mode belongs on every device.
 *
 * The write is the same shape as the engine's state file: whole file, temp plus fsync plus
 * rename, so a kill mid write leaves the old list rather than half a new one.
 *
 * Every read runs through [neverTrack] as well as the file, so a package that became
 * untrackable after it was picked (it is the launcher now, or the keyboard) stops counting
 * without anything having to rewrite the file.
 */
class TrackedAppsStore(dir: File, private val neverTrack: NeverTrack) {
    private val file = File(dir, FILE_NAME)
    private val tmp = File(dir, "$FILE_NAME.tmp")

    private val _packages = MutableStateFlow(read())

    /** The picked packages as stored. Ask [isTracked] before acting on one. */
    val packages: StateFlow<Set<String>> = _packages.asStateFlow()

    /** The picked packages the guard still allows. */
    fun allowed(): Set<String> = _packages.value.filterTo(LinkedHashSet()) { !neverTrack.contains(it) }

    /** Is this package tracked right now? */
    fun isTracked(pkg: String): Boolean = pkg in _packages.value && !neverTrack.contains(pkg)

    /** Adds a package. False when [neverTrack] refuses it. */
    fun track(pkg: String): Boolean {
        if (neverTrack.contains(pkg)) return false
        if (pkg in _packages.value) return true
        write(_packages.value + pkg)
        return true
    }

    fun untrack(pkg: String) {
        if (pkg !in _packages.value) return
        write(_packages.value - pkg)
    }

    private fun write(next: Set<String>) {
        _packages.value = next
        val json = JSONObject().put("packages", JSONArray(next.toList())).toString()
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** A file that will not parse reads as no picks yet, never a crash on launch. */
    private fun read(): Set<String> {
        if (!file.isFile) return emptySet()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptySet()
        val arr = runCatching { JSONObject(text).optJSONArray("packages") }.getOrNull() ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    private companion object {
        const val FILE_NAME = "tracked.json"
    }
}
