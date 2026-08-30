package site.dirt23.battery.core.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The wire against the real server. Every other sync test here talks to a fake built from
 * reading server/handlers.go, which only proves the client matches our reading of it. This
 * one launches the actual Go server on a temporary database and checks versions, the two
 * preconditions, the 409 body, the parked watch, the clock stamp, and the size cap.
 *
 * Opt in, since it needs a Go toolchain and a listening port:
 *
 *     gradle :core:test -Dintegration=true --tests '*ServerIntegrationTest'
 *
 * Point it somewhere else with -DsbServerDir=/path/to/server.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerIntegrationTest {

    private val transport = UrlConnectionTransport()
    private val code = FakeSyncAdapter.TEST_CODE
    private val keys = Wire.keys(code)
    private var process: Process? = null
    private var base = ""

    @BeforeAll
    fun startServer() {
        val go = which("go")
        assumeTrue(go != null, "no go toolchain on this machine")
        val dir = File(System.getProperty("sbServerDir") ?: "../../server")
        assumeTrue(dir.isDirectory, "no server sources at ${dir.absolutePath}")

        val port = ServerSocket(0).use { it.localPort }
        val db = Files.createTempDirectory("sb-sync-it").resolve("sync.db")
        base = "http://127.0.0.1:$port"
        process = ProcessBuilder(go!!, "run", ".", "-addr", "127.0.0.1:$port", "-db", db.toString())
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()

        // go run compiles first, so give it room before deciding it is not coming up.
        val up = runBlocking {
            var ok = false
            repeat(120) {
                if (!ok) {
                    ok = runCatching { get("/v1/time").status == 200 }.getOrDefault(false)
                    if (!ok) delay(500)
                }
            }
            ok
        }
        assumeTrue(up, "the server did not come up")
    }

    @AfterAll
    fun stopServer() {
        process?.destroy()
        process?.waitFor()
    }

    @Test
    fun `create, compare and swap, and the conflict that hands back the winner's body`() = runBlocking {
        val doc = path("charge")
        val first = "one".toByteArray()

        val created = put(doc, first, mapOf("If-None-Match" to "*"))
        assertEquals(200, created.status)
        assertEquals(1, SyncEngine.parseEtag(created.header("ETag")))

        // A second create is refused, and the refusal carries the version to swap against.
        val again = put(doc, "two".toByteArray(), mapOf("If-None-Match" to "*"))
        assertEquals(412, again.status)
        assertEquals(1, SyncEngine.parseEtag(again.header("ETag")))

        val swapped = put(doc, "two".toByteArray(), mapOf("If-Match" to "\"1\""))
        assertEquals(200, swapped.status)
        assertEquals(2, SyncEngine.parseEtag(swapped.header("ETag")))

        // A stale swap loses and comes back with the current version and body, so the loser
        // can merge without a second round trip.
        val clash = put(doc, "three".toByteArray(), mapOf("If-Match" to "\"1\""))
        assertEquals(409, clash.status)
        assertEquals(2, SyncEngine.parseEtag(clash.header("ETag")))
        assertEquals("two", String(clash.body!!))
    }

    @Test
    fun `a write with no precondition is refused, which is why the engine always sends one`() = runBlocking {
        val r = put(path("settings"), "x".toByteArray(), emptyMap())
        assertEquals(428, r.status)
    }

    @Test
    fun `a conditional GET on the current version answers 304 with no body`() = runBlocking {
        val doc = path("settings")
        val v = SyncEngine.parseEtag(put(doc, "hello".toByteArray(), mapOf("If-None-Match" to "*")).header("ETag"))
        val fresh = get(doc)
        assertEquals(200, fresh.status)
        assertEquals("hello", String(fresh.body!!))

        val unchanged = get(doc, mapOf("If-None-Match" to "\"$v\""))
        assertEquals(304, unchanged.status)
        assertTrue(unchanged.body == null || unchanged.body!!.isEmpty())
    }

    @Test
    fun `a parked watch is answered the moment a write lands`() = runBlocking {
        val doc = path("charge")
        val v = SyncEngine.parseEtag(get(doc).header("ETag"))
        val started = System.currentTimeMillis()

        val parked = async {
            get("$doc?wait=20", mapOf("If-None-Match" to "\"$v\""), HttpReq.WATCH_TIMEOUT_MS)
        }
        delay(300)
        assertEquals(200, put(doc, "woken".toByteArray(), mapOf("If-Match" to "\"$v\"")).status)

        val answer = withTimeout(10_000) { parked.await() }
        assertEquals(200, answer.status)
        assertEquals("woken", String(answer.body!!))
        // Well under the 20s park, so the write is what woke it.
        assertTrue(System.currentTimeMillis() - started < 15_000)
    }

    @Test
    fun `every response carries a fresh server clock`() = runBlocking {
        val r = get(path("charge"))
        val stamped = r.header("X-Server-Time")?.toLongOrNull()
        assertNotNull(stamped, "no X-Server-Time")
        assertTrue(abs(stamped - System.currentTimeMillis()) < 60_000)
    }

    @Test
    fun `a body over the cap is refused rather than stored`() = runBlocking {
        val doc = path("settings")
        val v = SyncEngine.parseEtag(get(doc).header("ETag")) ?: 1
        val huge = ByteArray(9_000) { 'x'.code.toByte() }
        assertEquals(413, put(doc, huge, mapOf("If-Match" to "\"$v\"")).status)
    }

    // --- plumbing -------------------------------------------------------------------

    private fun path(doc: String) = "/v1/blob/${keys.routingId}/$doc"

    private suspend fun get(
        path: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = HttpReq.DEFAULT_TIMEOUT_MS,
    ) = transport.exec(HttpReq("GET", base + path, auth(headers), null, timeoutMs))

    private suspend fun put(path: String, body: ByteArray, headers: Map<String, String>) =
        transport.exec(HttpReq("PUT", base + path, auth(headers), body))

    private fun auth(headers: Map<String, String>) =
        linkedMapOf("Authorization" to "Bearer ${keys.authToken}").apply { putAll(headers) }

    private fun which(cmd: String): String? {
        for (dir in (System.getenv("PATH") ?: "").split(File.pathSeparator)) {
            val f = File(dir, cmd)
            if (f.canExecute()) return f.absolutePath
        }
        return null
    }
}
