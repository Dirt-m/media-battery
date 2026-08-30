package site.dirt23.battery.core.sync

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UrlConnectionTransportTest {
    @Test
    fun `a refused connection is a real error, not an empty watch round`() {
        // Bound and immediately closed, so the port is valid and nothing listens on it.
        val port = ServerSocket(0).use { it.localPort }
        val t = UrlConnectionTransport(connectTimeoutMs = 2_000)
        val thrown = assertFailsWith<IOException> {
            runBlocking { t.exec(HttpReq("GET", "http://127.0.0.1:$port/v1/time", timeoutMs = 2_000)) }
        }
        // TransportTimeout is the watch loop's empty round. An unreachable server has to
        // land on the backoff ladder instead.
        assertTrue(thrown !is TransportTimeout, "a connect failure must not read as an empty round")
    }
}
