package com.wormgitty.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistGateTest {

    private fun all(vararg flipped: PermKey) =
        PermKey.entries.associateWith { it in flipped }

    @Test
    fun gateBlockedWhenEverythingOff() {
        assertFalse(PermKey.gateOpen(all()))
    }

    @Test
    fun gateOpensExactlyWithFourRequiredPermissions() {
        val s = all(
            PermKey.OVERLAY, PermKey.ACCESSIBILITY,
            PermKey.BATTERY_OPT, PermKey.DEVICE_ADMIN
        )
        assertTrue(PermKey.gateOpen(s))
    }

    @Test
    fun gateBlocksWhenDeviceAdminMissing() {
        val s = all(PermKey.OVERLAY, PermKey.ACCESSIBILITY, PermKey.BATTERY_OPT)
        assertFalse(PermKey.gateOpen(s))
    }

    @Test
    fun gateIgnoresNonRequiredPermissions() {
        val s = all(PermKey.NOTIFICATIONS)
        assertFalse(PermKey.gateOpen(s))
        val partial = all(
            PermKey.NOTIFICATIONS, PermKey.ALL_FILES,
            PermKey.OVERLAY, PermKey.ACCESSIBILITY,
            PermKey.BATTERY_OPT, PermKey.DEVICE_ADMIN
        )
        assertTrue(PermKey.gateOpen(partial))
    }
}
