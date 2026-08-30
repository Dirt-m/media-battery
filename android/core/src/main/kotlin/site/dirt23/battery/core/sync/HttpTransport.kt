package site.dirt23.battery.core.sync

import java.io.IOException
import java.util.TreeMap

/**
 * One HTTP round trip: GET and PUT, a few headers, an opaque body, a per request read timeout.
 * [HttpResp] carries everything the engine needs, so no transport interprets a status code.
 * An interface because the tests drive the engine through a fake, and the app may prefer its
 * own HTTP client.
 */
interface HttpTransport {
    /**
     * Perform [req]. Must be cancellable: cancelling has to abandon a parked watch without
     * waiting out the read timeout.
     *
     * Throws [TransportTimeout] when the request got no answer at all (read timeout, reset
     * connection, radio that came and went). The watch loop treats that as a normal empty
     * round, so a flaky link cannot walk the backoff ladder and degrade into polling. Any
     * other [IOException] does walk it.
     */
    suspend fun exec(req: HttpReq): HttpResp
}

/** A request. [body] is null for GET; [timeoutMs] is the read timeout, not a total budget. */
class HttpReq(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        /** Enough for a small blob over a slow link. */
        const val DEFAULT_TIMEOUT_MS = 20_000

        /** For a parked watch. The server holds at most 50s, so this fires only if it never did. */
        const val WATCH_TIMEOUT_MS = 60_000
    }
}

/**
 * A response. [body] only where the engine asks for one: a 200 GET, and a 409, whose body is
 * the winner's document so the loser merges without a re-GET.
 */
class HttpResp(
    val status: Int,
    headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    private val lookup: TreeMap<String, String> =
        TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply { putAll(headers) }

    /** Case insensitive, like HTTP header names, since servers disagree on casing. */
    fun header(name: String): String? = lookup[name]
}

/** The request got no answer, which is not a failure the server sent. See [HttpTransport.exec]. */
class TransportTimeout(message: String, cause: Throwable? = null) : IOException(message, cause)
