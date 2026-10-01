package site.dirt23.battery.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.security.SecureRandom
import kotlin.math.max
import kotlin.math.min

/**
 * The cross device sync engine, ported from the extension's sync.js.
 *
 * Charge is one shared account, and only the device in use writes it (single writer by
 * engagement). Followers hold one parked conditional GET that the server answers the moment a
 * write lands, so the mirror updates instantly and an idle profile costs about one 304 a
 * minute. Everything is end to end encrypted from one secret code, so the server stores opaque
 * blobs and does no merging, and every timestamp is anchored to the server clock so device
 * clock skew cannot corrupt the delta math.
 *
 * Battery state is only ever touched through [SyncAdapter], which is where the lower-wins rule
 * lives.
 *
 * Concurrency: like the original, this expects a confined caller. Public methods and the
 * coroutines they launch should share one dispatcher (the app's service scope, or the test
 * scheduler). It is not lock free.
 */
class SyncEngine(
    private val adapter: SyncAdapter,
    private val transport: HttpTransport,
    private val scope: CoroutineScope,
    private val crypto: SyncCrypto = SyncCrypto,
) {

    // --- published state ---------------------------------------------------------

    private val _status = MutableStateFlow(SyncStatus())

    /** What sync is doing, for the UI. */
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * True while a charge push has not been acknowledged. The stop draining push is the one
     * that must not be lost: a dropped goodbye used to strand a permanently draining anchor on
     * the server. A process on its way out checks this and calls [flushPendingPush].
     */
    val hasPendingPush: Boolean get() = dirty

    // --- engine state ------------------------------------------------------------

    private var keysCache: SyncCrypto.Keys? = null
    private var keysForCode: String? = null

    private var chargeVer: Ver = Ver.Unknown
    private var settingsVer: Ver = Ver.Unknown

    @Volatile private var engaged = false
    @Volatile private var active = false
    @Volatile private var dirty = false
    @Volatile private var screenOn = true

    private val chargeLock = Mutex()
    private var chargePending = false
    private val settingsLock = Mutex()
    private var settingsPending = false

    private var pushJob: Job? = null
    private var watchJob: Job? = null
    private var chargeRetry: Job? = null
    private var settingsRetry: Job? = null

    private var lastNudgeTs = 0L
    private var lastSyncTs = 0L
    private var lastSettingsPullTs = 0L
    private var backoffIdx = 0
    private var retryAfterMs = 0L

    // --- lifecycle ---------------------------------------------------------------

    /**
     * The app came up. Catch up on both documents, then follow or write depending on whether
     * anything is engaged right now: a service can start with a tracked app already in the
     * foreground, which the extension's tab model could not.
     */
    fun start() {
        if (active) return
        active = true
        engaged = adapter.isEngaged()
        setStatus(if (hasCode()) SyncState.SYNCED else SyncState.OFF)
        if (!hasCode()) return
        scope.launch {
            runCatching { pullCharge(); pullSettings() }
            if (engaged) startPush() else startWatch()
        }
    }

    /**
     * The app is going away. An unacknowledged push survives in [hasPendingPush] and is the
     * caller's to flush.
     */
    fun stop() {
        active = false
        stopWatch()
        stopPush()
    }

    /**
     * The single writer handoff. Becoming engaged: adopt the shared anchor, then own it.
     * Releasing: flush the final charge, which retries until it lands, and stop writing.
     */
    fun onEngagementChange(engaged: Boolean) {
        if (!hasCode() || engaged == this.engaged) return
        this.engaged = engaged
        if (engaged) {
            stopWatch() // the writer owns its state; no parked read should linger
            setStatus(SyncState.SYNCING)
            scope.launch {
                try {
                    pullCharge()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Best effort: what matters is adopting before writing.
                }
                // The engagement can end while the pull is in flight. Arming the push loop
                // then leaves an idle device overwriting the shared anchor every ten seconds
                // with nothing to clear it.
                if (!this@SyncEngine.engaged) return@launch
                startPush()
                runCatching { pushCharge() }
            }
        } else {
            stopPush()
            scope.launch { runCatching { pushCharge() } }
            if (active) startWatch() // back to following
        }
    }

    /**
     * The screen lit or went dark. The follower's watch runs only while the screen is on: it
     * exists to keep a reading fresh, and nobody is reading a dark screen. The resumed watch's
     * first round is a conditional GET that catches up whatever was written overnight, and
     * lower wins keeps that safe. The writer side is untouched, so a screen off drain (audio)
     * keeps pushing.
     */
    fun onScreenChange(on: Boolean) {
        if (on == screenOn) return
        screenOn = on
        if (!hasCode()) return
        if (on) {
            if (active && !engaged) startWatch()
        } else if (!engaged) {
            stopWatch()
        }
    }

    /**
     * Fired by the periodic wake that survives timer throttling, so a background drain still
     * propagates while this device is the writer.
     */
    fun onHeartbeat() {
        if (!hasCode()) return
        if (!engaged) {
            // The wake outlives the process that armed it, so a stale one keeps waking a
            // device that is not draining. Disarm; startPush re-arms it.
            adapter.setHeartbeat(false)
            return
        }
        scope.launch { runCatching { pushCharge() } }
    }

    /** Settings keys changed locally: stamp them and push. */
    fun onSettingsChanged(vararg changedKeys: String) {
        if (!hasCode() || changedKeys.isEmpty()) return
        val meta = LinkedHashMap(cfg().settingsMeta)
        val now = adapter.sbNow()
        for (key in changedKeys) meta[key] = now
        adapter.saveConfig(ConfigPatch(settingsMeta = meta))
        scope.launch { runCatching { pushSettings() } }
    }

    /**
     * A discrete charge change while not the active writer (reserve, a one-off pass). Adoption
     * is lower wins, so a drop propagates while a raise only updates the server copy.
     */
    fun pushChargeSoon() {
        if (!hasCode()) return
        scope.launch { runCatching { pushCharge() } }
    }

    /** A UI nudge (a gauge polling once a second): pull, but throttled. */
    fun pullSoon() {
        if (!hasCode() || engaged) return
        val now = adapter.deviceNow()
        if (now - lastNudgeTs < NUDGE_MS) return
        lastNudgeTs = now
        scope.launch {
            runCatching { pullCharge() }
            maybePullSettings()
        }
    }

    /**
     * Settings have no watch of their own, and a foreground service never cycles the way the
     * extension's event page does, so a desktop settings change would otherwise go unseen for
     * the life of the process. A conditional GET at most every [SETTINGS_PULL_MS] bounds that.
     */
    private suspend fun maybePullSettings() {
        if (adapter.deviceNow() - lastSettingsPullTs < SETTINGS_PULL_MS) return
        lastSettingsPullTs = adapter.deviceNow()
        try {
            pullSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Backoff runs off the charge path; a failed settings pull waits for the next
            // window.
        }
    }

    /**
     * Push whatever is still unacknowledged, walking the backoff ladder until the server takes
     * it or the ladder runs out. Bounded because the caller is usually a process being torn
     * down. Returns true when nothing is left pending.
     */
    suspend fun flushPendingPush(): Boolean {
        if (!hasCode()) return !dirty
        for (i in 0..BACKOFF.size) {
            if (!dirty) return true
            chargeRetry?.cancel()
            chargeRetry = null
            runCatching { pushCharge() }
            if (!dirty) return true
            if (i < BACKOFF.size) delay(BACKOFF[min(i, BACKOFF.lastIndex)])
        }
        return !dirty
    }

    // --- UI actions --------------------------------------------------------------

    /** Turn sync on: mint a code, seed the server from this device, hand the code back. */
    suspend fun enable(): EnableResult {
        val bytes = ByteArray(SyncCrypto.CODE_BYTES)
        SecureRandom().nextBytes(bytes)
        val code = crypto.base32Encode(bytes)
        adapter.saveConfig(ConfigPatch(syncCode = code))
        forgetKeys()
        try {
            activate()
            setStatus(SyncState.SYNCED, on = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            noteOffline(e)
        }
        return EnableResult(crypto.formatCode(code), code, serverBase())
    }

    /**
     * Join an existing battery from a pasted code. Any 26 valid characters decode, so a typo'd
     * code must not silently seed a fresh empty profile and report itself synced: joining only
     * succeeds against a profile the server already has, and anything short of that
     * confirmation rolls the sync config back. A charge the pull already adopted stands, since
     * lower wins means it only ever lowered.
     */
    suspend fun link(rawInput: String): LinkResult {
        val raw = crypto.base32Decode(rawInput)
        if (raw.size != SyncCrypto.CODE_BYTES) return LinkResult.BAD_CODE
        val code = crypto.base32Encode(raw)
        val prev = cfg()
        adapter.saveConfig(ConfigPatch(syncCode = code))
        forgetKeys()
        try {
            setStatus(SyncState.SYNCING)
            pullCharge()
            when (chargeVer) {
                // A confirmed 404: the server has never heard of this profile.
                is Ver.Absent -> {
                    rollback(prev)
                    return LinkResult.NO_PROFILE
                }
                // Nothing confirmed either way, so the join is unconfirmed. pullCharge throws
                // on every real error, so this is a guard rather than a path.
                is Ver.Unknown -> throw IOException("unconfirmed")
                is Ver.At -> Unit
            }
            pullSettings()
            if (settingsVer !is Ver.At) pushSettings()
            if (active && !engaged) startWatch()
            if (engaged) startPush()
            setStatus(SyncState.SYNCED, on = true)
            return LinkResult.OK
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            rollback(prev)
            return LinkResult.OFFLINE
        }
    }

    /** Point at a different server. Versions are per server, so they go. */
    suspend fun setServer(url: String) {
        val cleaned = url.trim().trimEnd('/')
        adapter.saveConfig(ConfigPatch(serverUrl = cleaned.ifEmpty { DEFAULT_SERVER }))
        resetVersions()
        if (!hasCode()) return
        try {
            activate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            noteOffline(e)
        }
    }

    /** Turn sync off on this device. The profile on the server is untouched. */
    fun unlink() {
        stopPush()
        stopWatch()
        chargeRetry?.cancel(); chargeRetry = null
        settingsRetry?.cancel(); settingsRetry = null
        dirty = false
        engaged = false
        active = false
        forgetKeys()
        keysCache = null
        adapter.saveConfig(
            ConfigPatch(
                clearSyncCode = true,
                serverOffset = 0,
                settingsMeta = emptyMap(),
                foreignSettings = emptyMap(),
            )
        )
        lastSyncTs = 0
        setStatus(SyncState.OFF, on = false)
    }

    fun defaultServer(): String = DEFAULT_SERVER

    // --- config and keys ---------------------------------------------------------

    private fun cfg(): SyncConfig = adapter.getConfig()

    private fun hasCode(): Boolean = !cfg().syncCode.isNullOrEmpty()

    private fun serverBase(): String =
        (cfg().serverUrl?.takeIf { it.isNotEmpty() } ?: DEFAULT_SERVER).trimEnd('/')

    private fun keys(): SyncCrypto.Keys {
        val code = cfg().syncCode ?: throw IllegalStateException("no code")
        if (keysForCode != code) {
            keysCache = crypto.deriveKeys(code)
            keysForCode = code
        }
        return keysCache!!
    }

    private fun forgetKeys() {
        keysForCode = null
        resetVersions()
    }

    private fun resetVersions() {
        chargeVer = Ver.Unknown
        settingsVer = Ver.Unknown
    }

    /**
     * Restore the config a failed [link] replaced, and go back to whatever was running.
     *
     * The sync config only. Settings the pull already adopted stay, because every step of
     * [link] after its `pullSettings` is built not to throw, so a rollback can only happen
     * before adoption. Adding a throwing step after adoption breaks that.
     */
    private fun rollback(prev: SyncConfig) {
        adapter.saveConfig(
            ConfigPatch(
                syncCode = prev.syncCode,
                clearSyncCode = prev.syncCode == null,
                settingsMeta = prev.settingsMeta,
                foreignSettings = prev.foreignSettings,
            )
        )
        forgetKeys()
        if (prev.syncCode != null) scope.launch { runCatching { activate() } }
        else setStatus(SyncState.OFF, on = false)
    }

    /**
     * Seed the server clock offset, then reconcile both documents: adopt what the server has,
     * or create it from this device if the profile is new.
     */
    private suspend fun activate() {
        setStatus(SyncState.SYNCING)
        // Time is best effort; the offset lands inside http and the next request re-anchors.
        runCatching { http("GET", "/v1/time") }
        pullCharge()
        if (chargeVer !is Ver.At) pushCharge()
        pullSettings()
        if (settingsVer !is Ver.At) pushSettings()
        if (active && !engaged) startWatch()
        if (engaged) startPush()
    }

    // --- status ------------------------------------------------------------------

    private fun setStatus(state: SyncState, error: String? = null, on: Boolean = hasCode()) {
        val s = SyncStatus(on = on, state = state, lastSyncTs = lastSyncTs, error = error)
        _status.value = s
        adapter.setStatus(s)
    }

    private fun markSynced() {
        backoffIdx = 0
        lastSyncTs = adapter.sbNow()
        setStatus(SyncState.SYNCED)
    }

    private fun noteOffline(err: Throwable): Long {
        setStatus(SyncState.OFFLINE, err.message ?: "offline")
        val delay = backoffDelay()
        backoffIdx = min(backoffIdx + 1, BACKOFF.lastIndex)
        return delay
    }

    /** The ladder, floored by whatever a 429 asked for. A Retry-After is spent once. */
    private fun backoffDelay(): Long {
        val base = BACKOFF[min(backoffIdx, BACKOFF.lastIndex)]
        val asked = retryAfterMs
        retryAfterMs = 0
        return max(base, asked)
    }

    // --- transport ---------------------------------------------------------------

    /**
     * One HTTP round trip. Learns the server clock offset from every response, including a 304
     * (X-Server-Time is restamped after a parked watch), and returns the status, the parsed
     * ETag version, and the body where one exists.
     *
     * The offset is server time minus the moment the response is handled, which is only right
     * when the response was handled as it arrived. Doze can hold a response for hours; read
     * then, it would set this clock hours behind, and the anchor in it would project without
     * its night of recharge, be adopted (lower wins), and get pushed to every device on the
     * first engagement. So a reading is trusted only when the round trip was short enough to
     * vouch for it: the park the request asked for ([parkedMs]) plus a slack for the network. A
     * rejected reading costs nothing; the next prompt response re-anchors.
     */
    private suspend fun http(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = HttpReq.DEFAULT_TIMEOUT_MS,
        parkedMs: Long = 0L,
    ): Resp {
        val h = LinkedHashMap<String, String>()
        h["Authorization"] = "Bearer " + keys().authToken
        h.putAll(headers)
        val t0 = adapter.deviceNow()
        val r = transport.exec(HttpReq(method, serverBase() + path, h, body, timeoutMs))
        val rtt = adapter.deviceNow() - t0
        if (rtt <= parkedMs + TIME_SLACK_MS) {
            r.header("X-Server-Time")?.trim()?.toLongOrNull()?.let {
                adapter.setServerOffset(it - adapter.deviceNow())
            }
        }
        if (r.status == 429) retryAfterMs = max(retryAfterMs, parseRetryAfter(r.header("Retry-After")))
        // A body only where the engine uses one: a 200 GET, and a 409, whose body is the
        // winner's document so the loser merges without a re-GET.
        val wantBody = (method == "GET" && r.status == 200) || r.status == 409
        return Resp(r.status, parseEtag(r.header("ETag")), if (wantBody) r.body else null)
    }

    // --- charge (lower charge always wins) ---------------------------------------

    /**
     * Pull the charge anchor. With a known version the request carries If-None-Match and a 304
     * means nothing changed (no body, no decrypt). With [wait] the server parks the request
     * until a write lands or the wait runs out, which is what makes a follower's mirror update
     * the moment the writer pushes.
     */
    private suspend fun pullCharge(wait: Boolean = false) {
        if (!hasCode()) return
        val known = chargeVer as? Ver.At
        var path = "/v1/blob/" + keys().routingId + "/charge"
        val headers = LinkedHashMap<String, String>()
        if (known != null) headers["If-None-Match"] = quote(known.v)
        // Nothing to park on without a version: the server has no baseline to compare to.
        val parked = wait && known != null
        if (parked) path += "?wait=$WATCH_WAIT_S"

        val r = http(
            "GET", path, headers,
            timeoutMs = if (wait) HttpReq.WATCH_TIMEOUT_MS else HttpReq.DEFAULT_TIMEOUT_MS,
            parkedMs = if (parked) WATCH_WAIT_S * 1000L else 0L,
        )
        if (r.status == 404) { chargeVer = Ver.Absent; return }
        if (r.status == 304) { markSynced(); return } // unchanged since our version
        // Auth cannot heal on retry, so it goes in the status, but it still throws: the watch
        // loop applies backoff in its catch, and a plain return would re-issue the GET every
        // pace round forever. link treats the throw as an unconfirmed join and rolls back.
        if (r.status == 403) { setStatus(SyncState.OFFLINE, "auth"); throw SyncHttpException(403, "auth") }
        if (r.status != 200) throw SyncHttpException(r.status) // 429 and 5xx: callers back off

        markSynced() // reached the server; followers rarely push, so mark it here too
        chargeVer = verOf(r.version)
        val plain = r.body?.let { crypto.decryptDoc(keys().aesKey, AnchorWire.DOC, it) } ?: return
        val anchor = AnchorWire.decode(plain) ?: return
        // Lower wins, in the adapter: it adopts only if the remote implies a charge at or
        // below ours. If ours is lower it keeps ours, and we overwrite the server.
        if (adapter.maybeAdoptAnchor(anchor).overwrite) pushCharge()
    }

    /**
     * Push the local anchor, overwriting the server copy. The engaged writer's job, plus
     * one-off user actions. Last write wins: on a version clash we take the server's version
     * and re-write our value.
     *
     * Dirty until the server acknowledges it, and any failure schedules a retry whether or not
     * this device is still engaged.
     */
    private suspend fun pushCharge() {
        if (!hasCode()) return
        // Single flight. A second caller marks the work as still wanted and leaves rather
        // than queueing; the holder re-runs once on release, coalescing any number of
        // concurrent asks into one more push.
        if (!chargeLock.tryLock()) { chargePending = true; return }
        dirty = true
        try {
            pushChargeLocked()
        } finally {
            chargeLock.unlock()
            if (chargePending) { chargePending = false; pushCharge() }
        }
    }

    private suspend fun pushChargeLocked() {
        try {
            val k = keys()
            val path = "/v1/blob/${k.routingId}/charge"
            val body = crypto.encryptDoc(k.aesKey, AnchorWire.DOC, AnchorWire.encode(adapter.getAnchor()))
            repeat(CAS_ATTEMPTS) {
                val known = chargeVer as? Ver.At
                val r = if (known == null) {
                    http("PUT", path, mapOf("If-None-Match" to "*"), body)
                } else {
                    http("PUT", path, mapOf("If-Match" to quote(known.v)), body)
                }
                if (r.status == 200) {
                    chargeVer = Ver.At(if (known == null) 1 else known.v + 1)
                    dirty = false
                    markSynced()
                    return
                }
                // 412: it already exists, so take its version and compare-and-swap instead.
                // 409: someone else wrote first, so take their version and overwrite.
                // 404: the profile is gone (the server garbage collects idle ones), so the
                // next attempt creates it fresh rather than retrying a CAS forever.
                if (known == null && r.status == 412) { chargeVer = verOf(r.version); return@repeat }
                if (known != null && r.status == 409) { chargeVer = verOf(r.version); return@repeat }
                if (known != null && r.status == 404) { chargeVer = Ver.Absent; return@repeat }
                if (r.status == 403) { setStatus(SyncState.OFFLINE, "auth"); return } // no retry heals auth
                retryChargeSoon() // unexpected status: transient, try again later
                return
            }
            retryChargeSoon() // the CAS kept clashing; back off and retry
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            noteOffline(e)
            retryChargeSoon()
        }
    }

    /**
     * Retry an unacknowledged charge push on the backoff schedule. Each retry re-reads the
     * anchor, so whatever finally lands is current.
     */
    private fun retryChargeSoon() {
        if (chargeRetry?.isActive == true) return
        val delayMs = backoffDelay()
        chargeRetry = scope.launch {
            delay(delayMs)
            chargeRetry = null
            if (dirty && hasCode()) runCatching { pushCharge() }
        }
    }

    // --- settings (per key last write wins, merged) -------------------------------

    private fun localSettingsDoc(): Map<String, SettingsEntry> {
        val c = cfg()
        return SettingsWire.localDoc(adapter.getSettings(), c.settingsMeta, c.foreignSettings)
    }

    /** Hand the merged values to the adapter and record the timestamps they carried. */
    private fun adoptSettings(merged: Map<String, SettingsEntry>) {
        val meta = LinkedHashMap(cfg().settingsMeta)
        val values = LinkedHashMap<String, JsonElement>()
        for ((key, entry) in merged) {
            values[key] = entry.value
            meta[key] = entry.ts
        }
        adapter.saveConfig(
            ConfigPatch(
                settingsMeta = meta,
                // Keys this platform does not maintain are held verbatim, so the next push
                // re-emits them instead of erasing another platform's settings.
                foreignSettings = SettingsWire.foreignOf(merged, adapter.getSettings().keys),
            )
        )
        adapter.applyRemoteSettings(values)
    }

    private suspend fun pullSettings() {
        if (!hasCode()) return
        lastSettingsPullTs = adapter.deviceNow()
        val headers = LinkedHashMap<String, String>()
        (settingsVer as? Ver.At)?.let { headers["If-None-Match"] = quote(it.v) }
        val r = http("GET", "/v1/blob/" + keys().routingId + "/settings", headers)
        if (r.status == 404) { settingsVer = Ver.Absent; return }
        if (r.status == 304) return // unchanged since our version
        if (r.status != 200) return
        settingsVer = verOf(r.version)
        val plain = r.body?.let { crypto.decryptDoc(keys().aesKey, SettingsWire.DOC, it) } ?: return
        val remote = SettingsWire.decodeDoc(plain) ?: return
        val local = localSettingsDoc()
        val merged = SettingsWire.mergeSettings(local, remote)
        if (!SettingsWire.sameDoc(merged, local)) adoptSettings(merged)   // remote had newer keys
        if (!SettingsWire.sameDoc(merged, remote)) pushSettings()          // local had newer keys
    }

    private suspend fun pushSettings() {
        if (!hasCode()) return
        if (!settingsLock.tryLock()) { settingsPending = true; return }
        try {
            pushSettingsLocked()
        } finally {
            settingsLock.unlock()
            if (settingsPending) { settingsPending = false; pushSettings() }
        }
    }

    private suspend fun pushSettingsLocked() {
        try {
            val k = keys()
            val path = "/v1/blob/${k.routingId}/settings"
            repeat(CAS_ATTEMPTS) {
                // Rebuilt every attempt, so what lands carries the values of that moment.
                val doc = localSettingsDoc()
                val body = crypto.encryptDoc(k.aesKey, SettingsWire.DOC, SettingsWire.encodeDoc(doc))
                val known = settingsVer as? Ver.At
                val r = if (known == null) {
                    http("PUT", path, mapOf("If-None-Match" to "*"), body)
                } else {
                    http("PUT", path, mapOf("If-Match" to quote(known.v)), body)
                }
                if (r.status == 200) {
                    settingsVer = Ver.At(if (known == null) 1 else known.v + 1)
                    markSynced()
                    return
                }
                if (known == null && r.status == 412) { settingsVer = verOf(r.version); return@repeat }
                if (known != null && r.status == 404) { settingsVer = Ver.Absent; return@repeat }
                if (known != null && r.status == 409) {
                    // Merge the winner's document in and retry, so no one's change is lost.
                    settingsVer = verOf(r.version)
                    val plain = r.body?.let { crypto.decryptDoc(k.aesKey, SettingsWire.DOC, it) }
                    val remote = plain?.let { SettingsWire.decodeDoc(it) }
                    if (remote != null) {
                        val merged = SettingsWire.mergeSettings(doc, remote)
                        if (!SettingsWire.sameDoc(merged, doc)) adoptSettings(merged)
                    }
                    return@repeat
                }
                if (r.status == 403) { setStatus(SyncState.OFFLINE, "auth"); return }
                retrySettingsSoon()
                return
            }
            retrySettingsSoon()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            noteOffline(e)
            retrySettingsSoon()
        }
    }

    private fun retrySettingsSoon() {
        if (settingsRetry?.isActive == true) return
        val delayMs = backoffDelay()
        settingsRetry = scope.launch {
            delay(delayMs)
            settingsRetry = null
            if (hasCode()) runCatching { pushSettings() }
        }
    }

    // --- loops --------------------------------------------------------------------

    private fun startPush() {
        if (pushJob?.isActive == true) return
        pushJob = scope.launch {
            while (isActive) {
                delay(PUSH_MS)
                pushCharge()
                // pullSoon skips engaged devices, so this is the writer's settings cadence.
                maybePullSettings()
            }
        }
        // A throttled timer would stop a background drain from pushing; the wake still fires.
        adapter.setHeartbeat(true)
    }

    private fun stopPush() {
        pushJob?.cancel()
        pushJob = null
        adapter.setHeartbeat(false)
    }

    /**
     * The follower's watch loop, one parked request at a time. Each round is a conditional GET
     * the server holds until a write lands (instant mirror) or the wait expires (a 304 about
     * once a minute). The pace floor keeps the loop from spinning whatever the server answers.
     */
    private fun startWatch() {
        stopWatch() // one loop at a time: cancel any parked GET so the old loop exits
        if (!screenOn) return // nothing to show a dark screen; onScreenChange resumes it
        watchJob = scope.launch {
            while (isActive && active && !engaged && screenOn && hasCode()) {
                // Alongside the round rather than after it, so a round longer than the floor
                // pays nothing for it.
                val paced = launch { delay(WATCH_PACE_MS) }
                try {
                    pullCharge(wait = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: TransportTimeout) {
                    // The parked GET came back with nothing to say, which on a watch is a
                    // normal empty round. The ladder stays put, so a flaky radio cannot
                    // degrade the loop into slow polling.
                } catch (e: Throwable) {
                    delay(noteOffline(e))
                }
                maybePullSettings()
                paced.join()
                // No document yet (404: a fresh code, or a profile the server dropped):
                // nothing to park on, so check back at the watch cadence instead of spinning.
                if (chargeVer !is Ver.At) delay(WATCH_WAIT_S * 1000L)
            }
        }
    }

    private fun stopWatch() {
        watchJob?.cancel()
        watchJob = null
    }

    // --- small helpers -------------------------------------------------------------

    private fun verOf(v: Int?): Ver = if (v == null) Ver.Unknown else Ver.At(v)

    private fun quote(v: Int): String = "\"$v\""

    /** What the engine knows about a document's server version. */
    private sealed interface Ver {
        /** Never asked, or the answer did not say. */
        data object Unknown : Ver

        /** A confirmed 404. link leans on this: no profile is not the same as no answer. */
        data object Absent : Ver

        data class At(val v: Int) : Ver
    }

    private class Resp(val status: Int, val version: Int?, val body: ByteArray?)

    companion object {
        const val DEFAULT_SERVER = "https://sb.dirt23.site"

        /** How often the engaged writer pushes charge. */
        const val PUSH_MS = 10_000L

        /** How long the server may park a watch before answering 304. Its cap is 50. */
        const val WATCH_WAIT_S = 45

        /** Floor between watch rounds, so nothing can ever spin. */
        const val WATCH_PACE_MS = 3_000L

        /**
         * How much longer than its park a round trip may take and still vouch for the server
         * clock it carries (see [http]). Network latency fits with room to spare; a response
         * held through a sleep does not.
         */
        const val TIME_SLACK_MS = 15_000L

        /** Minimum gap between UI nudged pulls. */
        const val NUDGE_MS = 5_000L

        /** The most a settings change on another device stays unseen while sync runs. */
        const val SETTINGS_PULL_MS = 5 * 60_000L

        /** Retry schedule after a failed request. */
        val BACKOFF = longArrayOf(5_000, 15_000, 30_000)

        /** How many times a write re-reads the version and tries again before backing off. */
        const val CAS_ATTEMPTS = 4

        fun parseEtag(h: String?): Int? {
            if (h == null) return null
            val s = h.trim().removePrefix("W/").replace("\"", "")
            return s.toIntOrNull()
        }

        /** The most a Retry-After may park anything. The reference server asks for 5s. */
        const val RETRY_AFTER_CAP_MS = 60_000L

        /**
         * Seconds, or an HTTP date we do not bother parsing. Zero means no opinion. Capped so
         * a wild value from a misbehaving proxy cannot park the loop indefinitely.
         */
        fun parseRetryAfter(h: String?): Long {
            val secs = h?.trim()?.toLongOrNull() ?: return 0
            return min(max(0L, secs) * 1000L, RETRY_AFTER_CAP_MS)
        }
    }
}

/** The server answered, and the answer was a failure. */
class SyncHttpException(val status: Int, message: String = "http $status") : IOException(message)

/** What [SyncEngine.enable] hands back: the code to show, the code to store, and the server. */
data class EnableResult(val code: String, val rawCode: String, val serverUrl: String)

/** What [SyncEngine.link] made of a pasted code. */
enum class LinkResult {
    /** Joined a profile the server already had. */
    OK,

    /** The code did not decode to 16 bytes, so it was never a code. */
    BAD_CODE,

    /** A confirmed 404: nothing has ever synced under that code. */
    NO_PROFILE,

    /** The join was never confirmed, so nothing was changed. */
    OFFLINE,
}
