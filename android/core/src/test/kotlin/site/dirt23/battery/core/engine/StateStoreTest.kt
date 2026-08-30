package site.dirt23.battery.core.engine

import site.dirt23.battery.core.Constants
import site.dirt23.battery.core.model.BatteryState
import site.dirt23.battery.core.model.CustomEntry
import site.dirt23.battery.core.model.HourRule
import site.dirt23.battery.core.model.RuleAction
import site.dirt23.battery.core.model.RuleScope
import site.dirt23.battery.core.model.SiteMode
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The state file: a save survives a load unchanged, and a damaged one still leaves the
 * battery running. Reads are forgiving the way the extension's storage overlay is, so one
 * junk value costs its own field. Text that is not JSON at all loads as nothing, and the
 * engine boots the defaults.
 */
class StateStoreTest {
    private fun tempDir(): File = Files.createTempDirectory("sb-state").toFile().also { it.deleteOnExit() }

    private fun fullState() = BatteryState(
        charge = 812.5,
        capacity = 1800.0,
        rechargePerMin = 7.5,
        warnSeconds = 240,
        siteModes = linkedMapOf(
            "youtube" to SiteMode.BLOCK,
            "reddit" to SiteMode.OFF,
            "instagram" to SiteMode.ON,
        ),
        customEntries = mutableListOf(CustomEntry(id = "foo.com", name = "Foo", host = "foo.com")),
        hourRules = listOf(
            HourRule(
                id = "r1", from = "9:00", to = "17:30", action = RuleAction.BLOCK,
                scope = RuleScope.EXCEPT, sites = listOf("reddit"),
            ),
            HourRule(id = "r2", from = "22:00", to = "07:00", action = RuleAction.RECHARGE, percent = 40),
            HourRule(id = "r3", from = "12:00", to = "13:00", action = RuleAction.CAPACITY, minutes = 15),
            HourRule(id = "r4", from = "01:00", to = "02:00", action = RuleAction.OFF),
        ),
        sitePasses = linkedMapOf("youtube" to T0 + 120_000),
        hideYtSidebar = true,
        showTimeLeft = false,
        depleted = true,
        depletedAt = T0 - 5000,
        depletionSeq = 9,
        lastTickTs = T0,
        remoteDraining = true,
        remoteDrainingTs = T0 - 1000,
        deviceId = "abc123",
        syncCode = "0123456789abcdefghjkmnpqrs",
        serverUrl = "https://sync.example",
        serverOffset = -1500,
        settingsMeta = linkedMapOf("capacity" to T0, "hourRules" to T0 - 10),
    )

    @Test
    fun `a full state round trips through the file`() {
        val store = FileStateStore(tempDir())
        val before = fullState()
        store.save(before)
        assertEquals(before, store.load())
    }

    @Test
    fun `an hour rule keeps its times verbatim`() {
        // Times aren't normalized on the way in, so a round trip must not normalize either.
        // "9:00" and "09:00" mean the same thing; keep whichever was stored.
        val store = FileStateStore(tempDir())
        store.save(fullState())
        val rule = store.load()!!.hourRules.first { it.id == "r1" }
        assertEquals("9:00", rule.from)
        assertEquals("17:30", rule.to)
    }

    @Test
    fun `only a capacity rule carries minutes, and never as an explicit null`() {
        // The validator drops a rule with no minutes, so an absent cap must not be written
        // as an explicit null.
        val dir = tempDir()
        FileStateStore(dir).save(fullState())
        val text = File(dir, "state.json").readText()
        assertFalse(text.contains("\"minutes\":null"))
        assertEquals(1, Regex("\"minutes\"").findAll(text).count())
    }

    @Test
    fun `a corrupted file loads as nothing and the engine boots the defaults`() {
        val dir = tempDir()
        File(dir, "state.json").writeText("{ this is not json")
        val store = FileStateStore(dir)
        assertNull(store.load())

        val engine = BatteryEngine(store, FakeClock(), guardClockJumps = false)
        val s = engine.stateForTest()
        assertEquals(Constants.DEFAULT_CAPACITY_SECONDS.toDouble(), s.capacity)
        assertEquals(Constants.DEFAULT_CHARGE_SECONDS, s.charge)
        assertFalse(s.depleted)
    }

    @Test
    fun `a missing file loads as nothing`() {
        assertNull(FileStateStore(tempDir()).load())
    }

    @Test
    fun `the temporary file does not survive a save`() {
        val dir = tempDir()
        FileStateStore(dir).save(fullState())
        assertTrue(File(dir, "state.json").isFile)
        assertFalse(File(dir, "state.json.tmp").exists())
    }

    @Test
    fun `site modes read the extension's wire values`() {
        // The extension stores false for off, "block" for blocked, true or nothing for
        // tracked. Anything else reads as tracked.
        val dir = tempDir()
        File(dir, "state.json").writeText(
            """{"enabledSites":{"youtube":"block","reddit":false,"x":"nonsense","tiktok":true}}""",
        )
        val s = FileStateStore(dir).load()!!
        assertEquals(SiteMode.BLOCK, s.modeOf("youtube"))
        assertEquals(SiteMode.OFF, s.modeOf("reddit"))
        assertEquals(SiteMode.ON, s.modeOf("x"))
        assertEquals(SiteMode.ON, s.modeOf("tiktok"))
        assertEquals(SiteMode.ON, s.modeOf("never-seen"))
    }

    @Test
    fun `corrupted numbers fall back to the defaults and then clamp`() {
        val dir = tempDir()
        File(dir, "state.json").writeText(
            """{"capacity":0,"rechargePerMin":"junk","charge":99999,"depleted":false,"depletedAt":null}""",
        )
        val engine = BatteryEngine(FileStateStore(dir), FakeClock(), guardClockJumps = false)
        val s = engine.stateForTest()
        assertEquals(Constants.CAPACITY_MIN_SECONDS.toDouble(), s.capacity) // floor, not zero
        assertEquals(Constants.DEFAULT_RECHARGE_PER_MIN, s.rechargePerMin) // not a number at all
        assertEquals(Constants.CAPACITY_MIN_SECONDS.toDouble(), s.charge) // clamped to the repair
    }

    @Test
    fun `a malformed hour rule is dropped rather than kept half working`() {
        val dir = tempDir()
        File(dir, "state.json").writeText(
            """{"hourRules":[
              {"id":"ok","from":"22:00","to":"07:00","action":"block","scope":"all","sites":[]},
              {"id":"same","from":"10:00","to":"10:00","action":"block"},
              {"id":"nocap","from":"10:00","to":"11:00","action":"capacity"}
            ]}""",
        )
        val s = FileStateStore(dir).load()!!
        assertEquals(listOf("ok"), s.hourRules.map { it.id })
    }

    @Test
    fun `an engine mints a device id on a first run and keeps it`() {
        val dir = tempDir()
        val first = BatteryEngine(FileStateStore(dir), FakeClock(), guardClockJumps = false)
        val id = first.stateForTest().deviceId
        assertTrue(!id.isNullOrEmpty())

        val second = BatteryEngine(FileStateStore(dir), FakeClock(), guardClockJumps = false)
        assertEquals(id, second.stateForTest().deviceId)
    }
}
