package site.dirt23.battery.core.sync

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import site.dirt23.battery.core.engine.AdoptResult
import site.dirt23.battery.core.model.Anchor
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The engine end to end against a fake server. These are protocol tests: who writes, who
// watches, what happens when the two collide, and what a bad network costs.
//
// Virtual time throughout. The loops are infinite, so tests advance the clock by a measured
// amount and never wait for idle.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncEngineTest {

    private val code = FakeSyncAdapter.TEST_CODE
    private val routing = Wire.keys(code).routingId

    private fun TestScope.newEngine(a: FakeSyncAdapter, t: FakeServerTransport) =
        SyncEngine(a, t, backgroundScope)

    private fun watchGets(t: FakeServerTransport) =
        t.requests.filter { it.method == "GET" && it.url.contains("wait=") }

    // --- ported from tests/sync.test.js -------------------------------------------

    @Test
    fun `a heartbeat while not engaged disarms the alarm`() = runTest {
        val a = FakeSyncAdapter()
        val e = newEngine(a, FakeServerTransport())
        e.onHeartbeat()
        assertEquals(listOf(false), a.heartbeats)
    }

    @Test
    fun `a 403 pull surfaces broken auth in the status`() = runTest {
        val t = FakeServerTransport()
        t.before = { _, _ -> HttpResp(403) }
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)
        e.start()
        runCurrent()
        assertEquals(SyncState.OFFLINE, e.status.value.state)
        assertEquals("auth", e.status.value.error)
    }

    // --- versions and preconditions -----------------------------------------------

    @Test
    fun `a create that loses the race falls back to compare and swap`() = runTest {
        val t = FakeServerTransport()
        // Another device got there first, so this device's create cannot win.
        t.seed(routing, "charge", Wire.anchorBlob(code, Anchor(charge = 10.0, asOf = 1)), version = 7)
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.pushChargeSoon()
        runCurrent()

        val puts = t.puts()
        assertEquals(2, puts.size)
        assertEquals("*", puts[0].headers["If-None-Match"])   // create, refused with 412
        assertEquals("\"7\"", puts[1].headers["If-Match"])     // then swap against what is there
        assertEquals(8, t.docs.getValue("$routing/charge").version)
        assertEquals(a.nextAnchor.charge, Wire.readAnchor(code, t.docs.getValue("$routing/charge").blob)!!.charge)
        assertFalse(e.hasPendingPush)
    }

    @Test
    fun `every write states a precondition, so the server never has to send 428`() = runTest {
        val t = FakeServerTransport()
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.pushChargeSoon()          // creates
        runCurrent()
        e.pushChargeSoon()          // swaps
        runCurrent()
        e.onSettingsChanged("capacity")
        runCurrent()

        assertTrue(t.puts().isNotEmpty())
        for (p in t.puts()) {
            assertTrue(
                p.headers["If-None-Match"] == "*" || p.headers["If-Match"] != null,
                "a write with no precondition: ${p.headers}",
            )
        }
        assertFalse(t.requests.isEmpty())
    }

    @Test
    fun `a settings conflict merges from the 409 body without a re-GET`() = runTest {
        val t = FakeServerTransport()
        val a = FakeSyncAdapter()
        t.seed(routing, "settings", Wire.settingsBlob(code, mapOf("capacity" to SettingsEntry(JsWire.num(1800.0), 1))))
        val e = newEngine(a, t)

        e.start()
        runCurrent()
        val getsAfterPull = t.requests.count { it.method == "GET" }
        val putsAfterPull = t.puts().size // the catch-up push, which is not what is on test here

        // Another device writes while this one was not looking, so the next push clashes.
        t.seed(
            routing, "settings",
            Wire.settingsBlob(
                code,
                linkedMapOf(
                    "capacity" to SettingsEntry(JsWire.num(1800.0), 1),
                    "warnSeconds" to SettingsEntry(JsWire.num(300.0), 9_999),
                ),
            ),
            version = 5,
        )
        e.onSettingsChanged("capacity")
        runCurrent()

        val puts = t.puts().drop(putsAfterPull)
        assertEquals(2, puts.size)
        assertEquals("\"2\"", puts[0].headers["If-Match"]) // stale, so the server refuses it
        assertEquals("\"5\"", puts[1].headers["If-Match"]) // the version came out of the 409
        assertEquals(getsAfterPull, t.requests.count { it.method == "GET" }) // no re-GET
        // The winner's key was merged in, not lost.
        assertTrue(a.appliedSettings.last().containsKey("warnSeconds"))
        val stored = Wire.readSettings(code, t.docs.getValue("$routing/settings").blob)!!
        assertEquals(9_999L, stored.getValue("warnSeconds").ts)
    }

    @Test
    fun `a key this device does not maintain is re-emitted verbatim`() = runTest {
        val t = FakeServerTransport()
        t.seed(
            routing, "settings",
            Wire.settingsBlob(
                code,
                linkedMapOf("androidTrackedApps" to SettingsEntry(JsWire.num(2.0), 42)),
            ),
        )
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.start()
        runCurrent()

        // Learned, stored, and carried into the document this device publishes.
        assertEquals(42L, a.conf.foreignSettings.getValue("androidTrackedApps").ts)
        val stored = Wire.readSettings(code, t.docs.getValue("$routing/settings").blob)!!
        assertEquals(42L, stored.getValue("androidTrackedApps").ts)
        assertTrue(stored.containsKey("capacity"))
    }

    // --- single flight ---------------------------------------------------------------

    @Test
    fun `concurrent pushes collapse into one in flight and one more after it`() = runTest {
        val t = FakeServerTransport()
        t.latencyMs = 5
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.pushChargeSoon()
        e.pushChargeSoon()
        e.pushChargeSoon()
        advanceTimeBy(1_000)
        runCurrent()

        // Three asks, one write in flight at a time, and exactly one coalesced follow-up.
        assertEquals(2, t.puts().size)
    }

    // --- the follower's watch loop ----------------------------------------------------

    @Test
    fun `the watch loop parks conditionally and never rounds faster than the pace floor`() = runTest {
        val t = FakeServerTransport()
        t.seed(routing, "charge", Wire.anchorBlob(code, Anchor(charge = 500.0, asOf = 1, writer = "other")))
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.start()
        runCurrent()

        val first = watchGets(t).first()
        assertTrue(first.url.contains("wait=${SyncEngine.WATCH_WAIT_S}"))
        assertEquals("\"1\"", first.headers["If-None-Match"])
        assertEquals(HttpReq.WATCH_TIMEOUT_MS, first.timeoutMs)

        advanceTimeBy(3 * SyncEngine.WATCH_PACE_MS + 1)
        // One round at t=0 and one per pace floor after it, however fast the server answers.
        assertEquals(4, watchGets(t).size)
    }

    @Test
    fun `a read timeout is an empty round, not a step down the ladder`() = runTest {
        val t = FakeServerTransport()
        t.seed(routing, "charge", Wire.anchorBlob(code, Anchor(charge = 500.0, asOf = 1, writer = "other")))
        t.before = { req, _ ->
            if (req.url.contains("wait=")) throw TransportTimeout("read timed out") else null
        }
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.start()
        runCurrent()
        advanceTimeBy(3 * SyncEngine.WATCH_PACE_MS + 1)

        // Still one round per pace floor. On the backoff ladder there would be two by now.
        assertEquals(4, watchGets(t).size)
        assertNotEquals(SyncState.OFFLINE, e.status.value.state)
    }

    @Test
    fun `the watch parks while the screen is dark and resumes when it lights`() = runTest {
        val t = FakeServerTransport()
        t.seed(routing, "charge", Wire.anchorBlob(code, Anchor(charge = 500.0, asOf = 1, writer = "other")))
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.start()
        runCurrent()
        advanceTimeBy(SyncEngine.WATCH_PACE_MS + 1)
        val lit = watchGets(t).size
        assertTrue(lit >= 1)

        // Dark: no further requests, however long the night.
        e.onScreenChange(false)
        advanceTimeBy(20 * SyncEngine.WATCH_PACE_MS)
        assertEquals(lit, watchGets(t).size)

        // Lit again: the first round goes out at once, and it is the conditional parked
        // GET, so a night of writes answers immediately.
        e.onScreenChange(true)
        runCurrent()
        assertEquals(lit + 1, watchGets(t).size)
    }

    @Test
    fun `a dark screen leaves the writer pushing`() = runTest {
        val t = FakeServerTransport()
        val a = FakeSyncAdapter()
        a.engaged = true
        val e = newEngine(a, t)

        e.start()
        runCurrent()
        e.onScreenChange(false)
        val before = t.requests.count { it.method == "PUT" && it.url.contains("/charge") }
        advanceTimeBy(SyncEngine.PUSH_MS + 1)
        // Screen off audio is a real drain, so it still propagates.
        assertTrue(t.requests.count { it.method == "PUT" && it.url.contains("/charge") } > before)
    }

    @Test
    fun `a service that starts in the dark opens no watch`() = runTest {
        val t = FakeServerTransport()
        t.seed(routing, "charge", Wire.anchorBlob(code, Anchor(charge = 500.0, asOf = 1, writer = "other")))
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.onScreenChange(false)
        e.start()
        runCurrent()
        advanceTimeBy(5 * SyncEngine.WATCH_PACE_MS)
        assertEquals(0, watchGets(t).size)
        // The one shot catch up still ran: the server's anchor reached the adapter.
        assertTrue(a.offeredAnchors.isNotEmpty())

        e.onScreenChange(true)
        runCurrent()
        assertTrue(watchGets(t).isNotEmpty())
    }

    @Test
    fun `a 429 waits out Retry-After before the next round`() = runTest {
        val t = FakeServerTransport()
        t.seed(routing, "charge", Wire.anchorBlob(code, Anchor(charge = 500.0, asOf = 1, writer = "other")))
        t.before = { req, _ ->
            if (req.url.contains("wait=")) HttpResp(429, mapOf("Retry-After" to "20")) else null
        }
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.start()
        runCurrent()
        assertEquals(1, watchGets(t).size)

        // 20s beats the first rung of the ladder, so the loop honors the server's number.
        advanceTimeBy(19_000)
        assertEquals(1, watchGets(t).size)
        advanceTimeBy(1_500)
        assertEquals(2, watchGets(t).size)
    }

    @Test
    fun `a follower overwrites the server when its own charge is lower`() = runTest {
        val t = FakeServerTransport()
        t.seed(routing, "charge", Wire.anchorBlob(code, Anchor(charge = 1500.0, asOf = 1, writer = "other")))
        val a = FakeSyncAdapter()
        a.adoptResult = AdoptResult(adopted = false, overwrite = true)
        val e = newEngine(a, t)

        e.pullSoon()
        runCurrent()

        assertEquals(1, a.offeredAnchors.size)
        assertEquals(1, t.puts().size)
        assertEquals(a.nextAnchor.charge, Wire.readAnchor(code, t.docs.getValue("$routing/charge").blob)!!.charge)
    }

    @Test
    fun `an adopted anchor is not written back`() = runTest {
        val t = FakeServerTransport()
        t.seed(routing, "charge", Wire.anchorBlob(code, Anchor(charge = 100.0, asOf = 1, writer = "other")))
        val a = FakeSyncAdapter()
        a.adoptResult = AdoptResult(adopted = true)
        val e = newEngine(a, t)

        e.pullSoon()
        runCurrent()

        assertEquals(1, a.offeredAnchors.size)
        assertTrue(t.puts().isEmpty())
    }

    // --- the writer's goodbye ---------------------------------------------------------

    @Test
    fun `the stop push retries on the ladder until the server acknowledges it`() = runTest {
        val t = FakeServerTransport()
        var attempts = 0
        t.before = { req, _ ->
            if (req.method == "PUT") {
                attempts++
                if (attempts < 3) HttpResp(500) else null
            } else {
                null
            }
        }
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.onEngagementChange(true)
        runCurrent()
        assertEquals(listOf(true), a.heartbeats)

        e.onEngagementChange(false)
        runCurrent()
        assertTrue(e.hasPendingPush, "the goodbye has not landed yet")
        assertEquals(listOf(true, false), a.heartbeats)

        advanceTimeBy(SyncEngine.BACKOFF[0] + 100)
        assertFalse(e.hasPendingPush, "the retry landed it")
        assertEquals(3, attempts)
    }

    @Test
    fun `flushPendingPush walks the ladder and reports whether it landed`() = runTest {
        val t = FakeServerTransport()
        var attempts = 0
        t.before = { req, _ ->
            if (req.method == "PUT") {
                attempts++
                if (attempts < 3) HttpResp(500) else null
            } else {
                null
            }
        }
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.pushChargeSoon()
        runCurrent()
        assertTrue(e.hasPendingPush)

        assertTrue(e.flushPendingPush())
        assertFalse(e.hasPendingPush)
    }

    @Test
    fun `flushPendingPush gives up rather than hanging a dying process`() = runTest {
        val t = FakeServerTransport()
        t.before = { req, _ -> if (req.method == "PUT") HttpResp(500) else null }
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.pushChargeSoon()
        runCurrent()
        assertFalse(e.flushPendingPush())
        assertTrue(e.hasPendingPush)
    }

    // --- clock anchoring ---------------------------------------------------------------

    @Test
    fun `X-Server-Time is read off every response, including a 304`() = runTest {
        val t = FakeServerTransport(serverTimeMs = { 1_700_000_050_000L })
        t.seed(routing, "charge", Wire.anchorBlob(code, Anchor(charge = 500.0, asOf = 1, writer = "other")))
        val a = FakeSyncAdapter()
        a.deviceNow = 1_700_000_000_000L
        val e = newEngine(a, t)

        e.start()
        runCurrent()
        a.offsets.clear()

        // The next watch round is answered 304, which carries no body and still re-anchors.
        advanceTimeBy(SyncEngine.WATCH_PACE_MS + 100)
        assertTrue(a.offsets.isNotEmpty(), "a 304 must still route the server clock")
        assertEquals(50_000L, a.offsets.last())
    }

    @Test
    fun `a response held through a sleep does not move the clock`() = runTest {
        // The server answered the parked watch at 23:00, Doze held the response, and the
        // phone reads it eight hours later. Trusting it would set the clock eight hours
        // behind; the prompt settings pull right after it anchors as usual.
        val eightHours = 8 * 3600_000L
        val a = FakeSyncAdapter()
        a.deviceNow = 1_700_000_000_000L
        var serverNow = a.deviceNow + 50_000L
        val t = FakeServerTransport(serverTimeMs = { serverNow })
        t.seed(routing, "charge", Wire.anchorBlob(code, Anchor(charge = 500.0, asOf = 1, writer = "other")))
        val e = newEngine(a, t)

        e.start()
        runCurrent()
        a.offsets.clear()
        t.before = { req, _ ->
            serverNow = a.deviceNow + 50_000L // stamped when the server answers
            if (req.url.contains("wait=")) a.deviceNow += eightHours // read after the sleep
            null
        }

        advanceTimeBy(SyncEngine.WATCH_PACE_MS + 100)
        assertTrue(watchGets(t).size >= 2, "the watch round ran")
        assertTrue(a.offsets.all { it == 50_000L }, "the held reading must be dropped: ${a.offsets}")
        assertEquals(50_000L, a.offset)
    }

    @Test
    fun `a parked watch answered within its park still anchors the clock`() = runTest {
        // The park itself is expected latency: a reading that arrives inside wait plus slack
        // is as good as a prompt one.
        val a = FakeSyncAdapter()
        a.deviceNow = 1_700_000_000_000L
        var serverNow = a.deviceNow + 50_000L
        val t = FakeServerTransport(serverTimeMs = { serverNow })
        t.seed(routing, "charge", Wire.anchorBlob(code, Anchor(charge = 500.0, asOf = 1, writer = "other")))
        val e = newEngine(a, t)

        e.start()
        runCurrent()
        a.offsets.clear()
        val park = SyncEngine.WATCH_WAIT_S * 1000L + 2_000L
        t.before = { req, _ ->
            serverNow = a.deviceNow + 50_000L
            if (req.url.contains("wait=")) a.deviceNow += park
            null
        }

        advanceTimeBy(SyncEngine.WATCH_PACE_MS + 100)
        assertTrue(watchGets(t).size >= 2, "the watch round ran")
        assertContains(a.offsets, 50_000L - park, "a full park plus network is a trusted reading")
    }

    @Test
    fun `a plain request that took longer than the slack does not move the clock`() = runTest {
        val t = FakeServerTransport(serverTimeMs = { 1_700_000_050_000L })
        val a = FakeSyncAdapter()
        a.deviceNow = 1_700_000_000_000L
        val e = newEngine(a, t)
        t.before = { _, _ -> a.deviceNow += SyncEngine.TIME_SLACK_MS + 1; null }

        e.start()
        runCurrent()
        assertTrue(a.offsets.isEmpty(), "every round trip overran the slack")
    }

    // --- joining ------------------------------------------------------------------------

    @Test
    fun `a code that is not sixteen bytes is refused before anything is touched`() = runTest {
        val t = FakeServerTransport()
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        assertEquals(LinkResult.BAD_CODE, e.link("nope"))
        assertEquals(code, a.conf.syncCode)
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun `joining a profile the server has never seen rolls the config back`() = runTest {
        val t = FakeServerTransport() // nothing seeded: every GET is a 404
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        assertEquals(LinkResult.NO_PROFILE, e.link(FakeSyncAdapter.OTHER_CODE))
        assertEquals(code, a.conf.syncCode)
        assertTrue(t.puts().isEmpty(), "a failed join must not seed a fresh profile")
    }

    @Test
    fun `a join that never got an answer rolls the config back`() = runTest {
        val t = FakeServerTransport()
        t.before = { _, _ -> throw IOException("network down") }
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        assertEquals(LinkResult.OFFLINE, e.link(FakeSyncAdapter.OTHER_CODE))
        assertEquals(code, a.conf.syncCode)
    }

    @Test
    fun `a confirmed join adopts the profile and seeds only what is missing`() = runTest {
        val other = FakeSyncAdapter.OTHER_CODE
        val otherRouting = Wire.keys(other).routingId
        val t = FakeServerTransport()
        t.seed(otherRouting, "charge", Wire.anchorBlob(other, Anchor(charge = 42.0, asOf = 5, writer = "other")))
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        assertEquals(LinkResult.OK, e.link(other))
        runCurrent()

        assertEquals(other, a.conf.syncCode)
        assertEquals(42.0, a.offeredAnchors.single().charge)
        // The charge document was there, so only settings got seeded.
        assertEquals(listOf("$otherRouting/settings"), t.puts().map { it.url.substringAfter("/v1/blob/") })
        assertEquals(SyncState.SYNCED, e.status.value.state)
        assertTrue(e.status.value.on)
    }

    @Test
    fun `enable mints a code, seeds both documents, and hands the code back`() = runTest {
        val t = FakeServerTransport()
        val a = FakeSyncAdapter(code = null)
        val e = newEngine(a, t)

        val res = e.enable()
        runCurrent()

        assertEquals(26, res.rawCode.length)
        assertEquals(res.rawCode, res.code.replace(" ", ""))
        assertEquals(res.rawCode, a.conf.syncCode)
        val seeded = Wire.keys(res.rawCode).routingId
        assertTrue(t.docs.containsKey("$seeded/charge"))
        assertTrue(t.docs.containsKey("$seeded/settings"))
        assertEquals(SyncState.SYNCED, e.status.value.state)
    }

    @Test
    fun `unlink stops everything and forgets the code`() = runTest {
        val t = FakeServerTransport()
        t.seed(routing, "charge", Wire.anchorBlob(code, Anchor(charge = 500.0, asOf = 1, writer = "other")))
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.start()
        runCurrent()
        val before = t.requests.size

        e.unlink()
        advanceTimeBy(60_000)

        assertNull(a.conf.syncCode)
        assertEquals(SyncState.OFF, e.status.value.state)
        assertFalse(e.status.value.on)
        assertEquals(before, t.requests.size, "nothing may keep talking to the server")
        assertContains(a.heartbeats, false)
    }

    @Test
    fun `a profile the server dropped is recreated by the next push`() = runTest {
        // The server garbage collects idle profiles; a client holding a version then meets
        // 409 with no ETag on its CAS and must fall back to creating, not retry forever.
        val t = FakeServerTransport()
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.pushChargeSoon() // creates version 1
        runCurrent()
        assertEquals(1, t.docs.getValue("$routing/charge").version)

        t.docs.clear() // the profile is gone
        e.pushChargeSoon()
        runCurrent()

        val doc = t.docs.getValue("$routing/charge")
        assertEquals(1, doc.version)
        assertEquals(a.nextAnchor.charge, Wire.readAnchor(code, doc.blob)!!.charge)
        assertFalse(e.hasPendingPush)
    }

    @Test
    fun `engagement ending during the handoff pull leaves no push loop behind`() = runTest {
        val t = FakeServerTransport()
        t.seed(routing, "charge", Wire.anchorBlob(code, Anchor(charge = 10.0, asOf = 1)))
        t.latencyMs = 1_000
        val a = FakeSyncAdapter()
        val e = newEngine(a, t)

        e.onEngagementChange(true)   // the becoming-engaged pull parks on the latency
        advanceTimeBy(100)
        e.onEngagementChange(false)  // and the engagement ends before it comes back
        advanceTimeBy(120_000)
        runCurrent()

        // The late pull must not arm the writer's loop on a device that is idle again: no
        // ten second cadence of PUTs, no heartbeat left armed.
        assertTrue(t.puts().size <= 2, "an idle device kept writing: ${t.puts().size} puts")
        assertFalse(a.heartbeats.lastOrNull() == true)
    }

    @Test
    fun `sync off does nothing at all`() = runTest {
        val t = FakeServerTransport()
        val a = FakeSyncAdapter(code = null)
        val e = newEngine(a, t)

        e.start()
        e.onEngagementChange(true)
        e.pushChargeSoon()
        e.pullSoon()
        e.onSettingsChanged("capacity")
        advanceTimeBy(60_000)

        assertTrue(t.requests.isEmpty())
        assertEquals(SyncState.OFF, e.status.value.state)
    }
}
