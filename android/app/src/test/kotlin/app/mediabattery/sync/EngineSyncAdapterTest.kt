package app.mediabattery.sync

import app.mediabattery.engine.SettingsView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import site.dirt23.battery.core.model.HourRule
import site.dirt23.battery.core.model.RuleAction
import site.dirt23.battery.core.model.RuleScope
import site.dirt23.battery.core.model.SiteMode
import site.dirt23.battery.core.model.Snapshot
import site.dirt23.battery.core.sync.SettingsEntry
import site.dirt23.battery.core.sync.SettingsWire

/**
 * What this phone puts on the wire, pinned. The settings document has to match the
 * extension's bytes: `1800.0` where it writes `1800` is a different document. The rest is
 * about what a push may erase, since erasing a mode can loosen a limit on the other device.
 */
class EngineSyncAdapterTest {

    @Test
    fun `the eight keys travel in the extension's shapes`() {
        val out = SettingsBridge.emit(snapshot(), view(), passengers())
        assertEquals(
            """{"rechargePerMin":5,"capacity":1800,"warnSeconds":300,""" +
                """"enabledSites":{"youtube":"block","instagram":false,"app:com.example.thing":"block","c123":"block"},""" +
                """"customSites":[{"id":"c123","name":"News","host":"news.example"}],""" +
                """"hourRules":[{"id":"r1","from":"9:00","to":"17:30","action":"block",""" +
                """"scope":"only","sites":["youtube"]}],"hideYtSidebar":true,"showTimeLeft":false}""",
            JsonObject(out).toString(),
        )
    }

    @Test
    fun `showTimeLeft is spoken here, not carried`() {
        val arrived = SettingsBridge.ingest(
            mapOf("showTimeLeft" to JsonPrimitive(true)),
            localModes = emptyMap(),
            carried = Passengers(),
        )
        assertEquals(true, arrived.patch.showTimeLeft)
    }

    @Test
    fun `every rule action carries its own field and nothing else`() {
        val rules = listOf(
            HourRule("a", "00:00", "06:00", RuleAction.RECHARGE, percent = 50),
            HourRule("b", "06:00", "09:00", RuleAction.CAPACITY, minutes = 20),
            HourRule("c", "09:00", "12:00", RuleAction.OFF, RuleScope.EXCEPT, listOf("x")),
        )
        val out = SettingsBridge.emit(snapshot(), view(rules = rules), Passengers())
        assertEquals(
            """[{"id":"a","from":"00:00","to":"06:00","action":"recharge","percent":50},""" +
                """{"id":"b","from":"06:00","to":"09:00","action":"capacity","minutes":20},""" +
                """{"id":"c","from":"09:00","to":"12:00","action":"off","scope":"except","sites":["x"]}]""",
            out.getValue("hourRules").toString(),
        )
    }

    @Test
    fun `an app mode rides the wire like any site`() {
        val modes = mapOf(
            "app:com.example.thing" to SiteMode.BLOCK,
            "youtube" to SiteMode.ON,
        )
        val out = SettingsBridge.emit(snapshot(), view(modes = modes), Passengers())
        assertEquals(
            """{"app:com.example.thing":"block","youtube":true}""",
            out.getValue("enabledSites").toString(),
        )
    }

    @Test
    fun `an app mode arriving from the wire is adopted, not carried`() {
        val arrived = SettingsBridge.ingest(
            mapOf("enabledSites" to obj("""{"app:com.example.thing":"block","youtube":false}""")),
            localModes = emptyMap(),
            carried = Passengers(),
        )
        assertEquals(
            mapOf("app:com.example.thing" to SiteMode.BLOCK, "youtube" to SiteMode.OFF),
            arrived.patch.siteModes,
        )
        assertTrue(arrived.passengers.siteModes.isEmpty())
    }

    @Test
    fun `a wire that omits a local mode does not delete it`() {
        // Absence means an unpatched extension pruned it, or it never synced. Either way
        // the next push puts it back.
        val arrived = SettingsBridge.ingest(
            mapOf("enabledSites" to obj("""{"youtube":true}""")),
            localModes = mapOf("app:com.example.thing" to SiteMode.BLOCK, "instagram" to SiteMode.OFF),
            carried = Passengers(),
        )
        assertEquals(
            mapOf(
                "app:com.example.thing" to SiteMode.BLOCK,
                "instagram" to SiteMode.OFF,
                "youtube" to SiteMode.ON,
            ),
            arrived.patch.siteModes,
        )
    }

    @Test
    fun `an id this phone has never heard of survives a round trip`() {
        val arrived = SettingsBridge.ingest(
            mapOf(
                "enabledSites" to obj("""{"youtube":false,"c123":"block","mastodon":true}"""),
                "customSites" to obj("""[{"id":"c123","name":"News","host":"news.example"}]"""),
                "hideYtSidebar" to JsonPrimitive(true),
            ),
            localModes = mapOf("app:com.example.thing" to SiteMode.BLOCK),
            carried = Passengers(),
        )
        // Own picks untouched, the built-in adopted, the rest just carried.
        assertEquals(
            mapOf("app:com.example.thing" to SiteMode.BLOCK, "youtube" to SiteMode.OFF),
            arrived.patch.siteModes,
        )
        assertNull(arrived.patch.hideYtSidebar)
        assertNull(arrived.patch.customEntries)

        val out = SettingsBridge.emit(
            snapshot(),
            view(modes = arrived.patch.siteModes!!),
            arrived.passengers,
        )
        assertEquals(
            """{"app:com.example.thing":"block","youtube":false,"c123":"block","mastodon":true}""",
            out.getValue("enabledSites").toString(),
        )
        assertEquals(
            """[{"id":"c123","name":"News","host":"news.example"}]""",
            out.getValue("customSites").toString(),
        )
        assertEquals("true", out.getValue("hideYtSidebar").toString())
    }

    @Test
    fun `a key this version does not know is re-emitted verbatim`() {
        val own = SettingsBridge.emit(snapshot(), view(), passengers())
        val remote = mapOf(
            "somethingNewer" to SettingsEntry(obj("""{"deep":[1,2,3]}"""), 9_000L),
        )
        val merged = SettingsWire.mergeSettings(SettingsWire.localDoc(own, emptyMap(), emptyMap()), remote)
        val foreign = SettingsWire.foreignOf(merged, own.keys)
        assertEquals(setOf("somethingNewer"), foreign.keys)

        // Encoded and read back: same value, same timestamp, nothing decoded on the way.
        val doc = SettingsWire.decodeDoc(
            SettingsWire.encodeDoc(SettingsWire.localDoc(own, emptyMap(), foreign))
        )
        assertEquals(remote.getValue("somethingNewer"), doc?.get("somethingNewer"))
    }

    @Test
    fun `rule times are stored the way they were written`() {
        val arrived = SettingsBridge.ingest(
            mapOf("hourRules" to obj("""[{"id":"r1","from":"9:00","to":"17:30","action":"block","scope":"all","sites":[]}]""")),
            localModes = emptyMap(),
            carried = Passengers(),
        )
        val raw = arrived.patch.hourRules!!.single()!!
        assertEquals("9:00", raw.from)
        assertEquals("17:30", raw.to)
    }

    @Test
    fun `numbers come back as numbers`() {
        val arrived = SettingsBridge.ingest(
            mapOf(
                "rechargePerMin" to obj("7.5"),
                "capacity" to obj("2400"),
                "warnSeconds" to obj("120"),
            ),
            localModes = emptyMap(),
            carried = Passengers(),
        )
        assertEquals(7.5, arrived.patch.rechargePerMin!!, 0.0)
        assertEquals(2400.0, arrived.patch.capacity!!, 0.0)
        assertEquals(120, arrived.patch.warnSeconds)
    }

    // --- fixtures -------------------------------------------------------------------

    private fun obj(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun snapshot(): Snapshot = Snapshot(
        charge = 900.0,
        capacity = 1800,
        effCapacity = 1800,
        rechargePerMin = 5.0,
        warnSeconds = 300,
        showTimeLeft = false,
        reserveSeconds = 300,
        passSeconds = 300,
        rechargePaused = false,
        cooldownRemaining = 0.0,
        depleted = false,
        depletedAt = null,
        depletionSeq = 0,
        draining = false,
        drainingId = null,
        drainingName = null,
    )

    private fun view(
        modes: Map<String, SiteMode> = linkedMapOf(
            "youtube" to SiteMode.BLOCK,
            "instagram" to SiteMode.OFF,
            "app:com.example.thing" to SiteMode.BLOCK,
        ),
        rules: List<HourRule> = listOf(
            HourRule("r1", "9:00", "17:30", RuleAction.BLOCK, RuleScope.ONLY, listOf("youtube")),
        ),
    ): SettingsView = SettingsView(siteModes = modes, hourRules = rules)

    private fun passengers(): Passengers = Passengers(
        customSites = obj("""[{"id":"c123","name":"News","host":"news.example"}]"""),
        hideYtSidebar = JsonPrimitive(true),
        siteModes = mapOf("c123" to JsonPrimitive("block")),
    )
}
