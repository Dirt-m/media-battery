package site.dirt23.battery.core.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI

/**
 * [HttpTransport] over HttpURLConnection, which keeps :core free of a networking dependency.
 *
 * Cancellation: a follower's watch parks for most of a minute and has to be abandoned at
 * once, so the blocking call runs inside [runInterruptible] and the connection is also
 * disconnected from the cancelling side, since a socket read does not always answer an
 * interrupt.
 *
 * Redirects are off. Following one would hand the bearer token to whatever host answered.
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : HttpTransport {

    override suspend fun exec(req: HttpReq): HttpResp {
        val conn = open(req)
        return try {
            runInterruptible(io) { perform(conn, req) }
        } catch (t: Throwable) {
            // Cancellation lands here too, and this disconnect is what unblocks a parked
            // read. On success the connection is left alone so it can be pooled.
            runCatching { conn.disconnect() }
            throw t
        }
    }

    private fun open(req: HttpReq): HttpURLConnection {
        val conn = URI(req.url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = req.method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = req.timeoutMs
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        for ((k, v) in req.headers) conn.setRequestProperty(k, v)
        if (req.body != null) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(req.body.size)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
        }
        return conn
    }

    private fun perform(conn: HttpURLConnection, req: HttpReq): HttpResp {
        // Connect separately so its failures stay plain IOExceptions. A refused port, an
        // unreachable host, or a connect timeout never reached the server; mapping those to
        // TransportTimeout made the watch loop treat a down server as a normal empty round,
        // retrying at the pace floor forever with no backoff and no offline status.
        conn.connect()
        try {
            req.body?.let { body -> conn.outputStream.use { it.write(body) } }
            val status = conn.responseCode
            // An error status puts the body on errorStream, and the engine merges from a
            // 409 body, so both sides are read.
            val stream: InputStream? =
                if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes() }
            val headers = LinkedHashMap<String, String>()
            for ((name, values) in conn.headerFields) {
                if (name != null && values.isNotEmpty()) headers[name] = values.last()
            }
            return HttpResp(status, headers, body)
        } catch (e: SocketTimeoutException) {
            throw TransportTimeout("read timed out", e)
        } catch (e: SocketException) {
            // Past the connect, a reset or a closed pipe means what a timeout means: the
            // server said nothing, so there is nothing to back off from.
            throw TransportTimeout(e.message ?: "connection dropped", e)
        } catch (e: IOException) {
            throw e
        }
    }
}
