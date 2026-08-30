package site.dirt23.battery.core.sync

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import site.dirt23.battery.core.engine.AdoptResult
import site.dirt23.battery.core.model.Anchor

// The rig the sync tests drive the engine with: an adapter that records what the engine
// asked of it, and a transport that answers like the real server (versions, conditional
// GETs, create versus compare-and-swap) with seams for network failures.
//
// Only those two edges are replaced. None of the engine's own logic is stubbed.

/**
 * Holds a config, answers with a fixed anchor and settings unless a test overrides them, and
 * records every call so tests can assert on the conversation instead of on internals.
 */
class FakeSyncAdapter(
    code: String? = TEST_CODE,
    serverUrl: String = "http://sync.test",
) : SyncAdapter {

    var conf: SyncConfig = SyncConfig(syncCode = code, serverUrl = serverUrl)
        private set

    /** What this device would publish. */
    var nextAnchor: Anchor = Anchor(charge = 900.0, asOf = 1_000_000L, writer = "device-a")

    /** What maybeAdoptAnchor decides. Tests flip the overwrite flag to drive the follower. */
    var adoptResult: AdoptResult = AdoptResult(adopted = true)

    /** The settings this device maintains. */
    var ownSettings: Map<String, JsonElement> = linkedMapOf(
        "capacity" to JsWire.num(1800.0),
        "rechargePerMin" to JsWire.num(5.0),
    )

    var engaged = false
    var deviceNow = 1_700_000_000_000L
    var offset = 0L

    val offeredAnchors = mutableListOf<Anchor>()
    val appliedSettings = mutableListOf<Map<String, JsonElement>>()
    val heartbeats = mutableListOf<Boolean>()
    val statuses = mutableListOf<SyncStatus>()
    val offsets = mutableListOf<Long>()
    val savedPatches = mutableListOf<ConfigPatch>()

    override fun getConfig(): SyncConfig = conf

    override fun saveConfig(patch: ConfigPatch) {
        savedPatches += patch
        conf = conf.copy(
            syncCode = if (patch.clearSyncCode) null else patch.syncCode ?: conf.syncCode,
            serverUrl = patch.serverUrl ?: conf.serverUrl,
            serverOffset = patch.serverOffset ?: conf.serverOffset,
            settingsMeta = patch.settingsMeta ?: conf.settingsMeta,
            foreignSettings = patch.foreignSettings ?: conf.foreignSettings,
        )
    }

    override fun getAnchor(): Anchor = nextAnchor

    override fun maybeAdoptAnchor(anchor: Anchor): AdoptResult {
        offeredAnchors += anchor
        return adoptResult
    }

    override fun getSettings(): Map<String, JsonElement> = ownSettings

    override fun applyRemoteSettings(values: Map<String, JsonElement>) {
        appliedSettings += values
    }

    override fun isEngaged(): Boolean = engaged

    override fun sbNow(): Long = deviceNow + offset

    override fun deviceNow(): Long = deviceNow

    override fun setServerOffset(offsetMs: Long) {
        offset = offsetMs
        offsets += offsetMs
    }

    override fun setStatus(status: SyncStatus) {
        statuses += status
    }

    override fun setHeartbeat(on: Boolean) {
        heartbeats += on
    }

    companion object {
        /** A fixed valid code: 16 counting bytes. */
        val TEST_CODE: String = SyncCrypto.base32Encode(ByteArray(16) { it.toByte() })

        /** A second valid code, for the join tests. */
        val OTHER_CODE: String = SyncCrypto.base32Encode(ByteArray(16) { (it * 7 + 3).toByte() })
    }
}

/**
 * A blob store that behaves like server/handlers.go: versions as integer ETags, conditional
 * GETs answering 304, create versus compare-and-swap on the precondition header, and 409
 * handing back the winner's body so the loser merges without a re-GET.
 *
 * [before] runs on every request and may answer for the server or throw, which is how the
 * tests inject a timeout, a 429, a 403, or a dropped connection.
 */
class FakeServerTransport(
    private val serverTimeMs: () -> Long = { 1_700_000_050_000L },
) : HttpTransport {

    class Doc(var version: Int, var blob: ByteArray)

    val docs = LinkedHashMap<String, Doc>()
    val requests = mutableListOf<HttpReq>()

    /** Answer for the server (or throw) before it sees the request. Null falls through. */
    var before: (suspend (HttpReq, Int) -> HttpResp?)? = null

    /** Round trip latency, so single flight has overlapping calls to collapse. */
    var latencyMs = 0L

    /** A parked watch answers immediately with 304, so the pace floor is what gets tested. */
    override suspend fun exec(req: HttpReq): HttpResp {
        val n = requests.size
        requests += req
        if (latencyMs > 0) delay(latencyMs)
        before?.invoke(req, n)?.let { return stamp(it) }

        val path = req.url.substringAfter("//").substringAfter("/").substringBefore("?")
        if (path == "v1/time") return stamp(HttpResp(200, emptyMap(), "{\"now\":1}".toByteArray()))
        val key = path.removePrefix("v1/blob/")
        return stamp(if (req.method == "GET") get(req, key) else put(req, key))
    }

    private fun get(req: HttpReq, key: String): HttpResp {
        val d = docs[key] ?: return HttpResp(404)
        val inm = SyncEngine.parseEtag(req.headers["If-None-Match"])
        if (inm == d.version) return HttpResp(304, mapOf("ETag" to "\"${d.version}\""))
        return HttpResp(200, mapOf("ETag" to "\"${d.version}\""), d.blob)
    }

    private fun put(req: HttpReq, key: String): HttpResp {
        val createOnly = req.headers["If-None-Match"] == "*"
        val ifMatch = SyncEngine.parseEtag(req.headers["If-Match"])
        // The server's own rule: a client that forgets a precondition gets caught here
        // rather than in production.
        if (!createOnly && ifMatch == null) return HttpResp(428)
        val d = docs[key]
        if (createOnly) {
            if (d != null) return HttpResp(412, mapOf("ETag" to "\"${d.version}\""))
            docs[key] = Doc(1, req.body ?: ByteArray(0))
            return HttpResp(200, mapOf("ETag" to "\"1\""))
        }
        // The real server answers a CAS against a missing document with 409 and no ETag
        // (store.go's Conflict branch), not 404. That happens once the server has garbage
        // collected the profile.
        if (d == null) return HttpResp(409)
        if (ifMatch != d.version) {
            return HttpResp(409, mapOf("ETag" to "\"${d.version}\""), d.blob)
        }
        d.version += 1
        d.blob = req.body ?: ByteArray(0)
        return HttpResp(200, mapOf("ETag" to "\"${d.version}\""))
    }

    private fun stamp(r: HttpResp): HttpResp {
        val h = LinkedHashMap<String, String>()
        for (name in STAMPED) r.header(name)?.let { h[name] = it }
        h["X-Server-Time"] = serverTimeMs().toString()
        return HttpResp(r.status, h, r.body)
    }

    /** Seed a document as if another device had written it. */
    fun seed(routingId: String, doc: String, blob: ByteArray, version: Int = 1) {
        docs["$routingId/$doc"] = Doc(version, blob)
    }

    fun puts(): List<HttpReq> = requests.filter { it.method == "PUT" }

    private companion object {
        val STAMPED = listOf("ETag", "Retry-After", "Content-Type")
    }
}

/** Helpers for building the documents a test wants the server to already hold. */
object Wire {
    fun keys(code: String): SyncCrypto.Keys = SyncCrypto.deriveKeys(code)

    fun anchorBlob(code: String, a: Anchor): ByteArray =
        SyncCrypto.encryptDoc(keys(code).aesKey, AnchorWire.DOC, AnchorWire.encode(a))

    fun settingsBlob(code: String, entries: Map<String, SettingsEntry>): ByteArray =
        SyncCrypto.encryptDoc(keys(code).aesKey, SettingsWire.DOC, SettingsWire.encodeDoc(entries))

    fun readSettings(code: String, blob: ByteArray): Map<String, SettingsEntry>? =
        SyncCrypto.decryptDoc(keys(code).aesKey, SettingsWire.DOC, blob)
            ?.let { SettingsWire.decodeDoc(it) }

    fun readAnchor(code: String, blob: ByteArray): Anchor? =
        SyncCrypto.decryptDoc(keys(code).aesKey, AnchorWire.DOC, blob)?.let { AnchorWire.decode(it) }
}
