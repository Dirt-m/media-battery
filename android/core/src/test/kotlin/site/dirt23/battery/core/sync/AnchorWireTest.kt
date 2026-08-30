package site.dirt23.battery.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Happy paths are pinned byte for byte in InteropVectorsTest. This is the rejection bar,
// which has to match background.js:668: no numeric charge or asOf, not an anchor.
class AnchorWireTest {
    @Test
    fun `an anchor without a numeric asOf is not an anchor`() {
        assertNull(AnchorWire.decode("""{"charge":100,"draining":true}"""))
        assertNull(AnchorWire.decode("""{"charge":100,"asOf":null}"""))
        assertNull(AnchorWire.decode("""{"charge":100,"asOf":"1735689600000"}"""))
    }

    @Test
    fun `an anchor without a numeric charge is not an anchor`() {
        assertNull(AnchorWire.decode("""{"asOf":1735689600000}"""))
        assertNull(AnchorWire.decode("""{"charge":"100","asOf":1735689600000}"""))
    }

    @Test
    fun `unknown fields are ignored, known ones land`() {
        val a = AnchorWire.decode("""{"charge":9.5,"asOf":7,"someFutureField":1}""")!!
        assertEquals(9.5, a.charge)
        assertEquals(7L, a.asOf)
    }
}
