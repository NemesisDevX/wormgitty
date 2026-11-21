package com.wormgitty.agent

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AccessibilityGateTest {

    private lateinit var context: Context
    private lateinit var checker: SystemPermissionChecker

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        checker = SystemPermissionChecker()
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ""
        )
    }

    private fun setState(value: String) {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            value
        )
    }

    @Test
    fun disabledWhenNothingEnabled() {
        assertFalse(checker.states(context)[PermKey.ACCESSIBILITY]!!)
    }

    @Test
    fun enabledWhenWormServiceInColonList() {
        val flat = "${context.packageName}/${WormA11yService::class.java.name}"
        setState("other.pkg/other.Service:$flat")
        assertTrue(checker.states(context)[PermKey.ACCESSIBILITY]!!)
    }

    @Test
    fun disabledWhenOnlyForeignServicesEnabled() {
        setState("com.other.app/com.other.TheirService")
        assertFalse(checker.states(context)[PermKey.ACCESSIBILITY]!!)
    }

    @Test
    fun gateStaysClosedWithoutAccessibilityEvenIfOthersGreen() {
        val flat = "${context.packageName}/${WormA11yService::class.java.name}"
        val green = PermKey.entries.associateWith { true }
            .toMutableMap()
            .also { it[PermKey.ACCESSIBILITY] = checker.states(context)[PermKey.ACCESSIBILITY]!! }
        assertFalse(PermKey.gateOpen(green))
        setState("$flat")
        assertTrue(checker.states(context)[PermKey.ACCESSIBILITY]!!)
    }
}
