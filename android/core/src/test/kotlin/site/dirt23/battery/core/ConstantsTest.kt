package site.dirt23.battery.core

import kotlin.test.Test
import kotlin.test.assertEquals

// Shared with the extension (background.js, projection.js). Pinned so a refactor can't
// drift them silently.
class ConstantsTest {
    @Test
    fun `wire constants match the extension`() {
        assertEquals(300, Constants.RESERVE_SECONDS)
        assertEquals(600, Constants.COOLDOWN_SECONDS)
        assertEquals(300, Constants.SITE_PASS_SECONDS)
        assertEquals(90_000L, Constants.DRAIN_HORIZON_MS)
        assertEquals(2.0, Constants.ADOPT_EPS_SECONDS)
    }

    @Test
    fun `defaults match the extension`() {
        assertEquals(1800.0, Constants.DEFAULT_CHARGE_SECONDS)
        assertEquals(1800, Constants.DEFAULT_CAPACITY_SECONDS)
        assertEquals(5.0, Constants.DEFAULT_RECHARGE_PER_MIN)
        assertEquals(300, Constants.DEFAULT_WARN_SECONDS)
    }
}
