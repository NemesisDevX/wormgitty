package com.wormgitty.agent

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Failure paths of the wizard gate: every deny must keep Next closed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GateDenialsTest {

    private lateinit var context: Context
    private lateinit var checker: SystemPermissionChecker

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        checker = SystemPermissionChecker()
    }

    private fun setA11y(grant: Boolean) {
        val value = if (grant) "${context.packageName}/${WormA11yService::class.java.name}" else ""
        Settings.Secure.putString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value
        )
    }

    @Test
    fun deniedOverlayKeepsNextDisabledEvenWithRestGreen() {
        setA11y(true)
        val withOverlay = mapOf(
            PermKey.OVERLAY to true,
            PermKey.ACCESSIBILITY to true,
            PermKey.BATTERY_OPT to true,
            PermKey.DEVICE_ADMIN to true
        )
        assertTrue(PermKey.gateOpen(withOverlay))
        val overlayDenied = withOverlay + (PermKey.OVERLAY to false)
        assertFalse("overlay denial must keep the gate shut", PermKey.gateOpen(overlayDenied))
    }

    @Test
    fun everySingleDenialOfRequiredRowBreaksTheGate() {
        val allGreen = PermKey.GATE_KEYS.associateWith { true }
        PermKey.GATE_KEYS.forEach { key ->
            val broken = allGreen + (key to false)
            assertFalse("deny $key must close the gate", PermKey.gateOpen(broken))
        }
    }

    @Test
    fun deniedAccessibilityUnderRobolectricClosesTheGate() {
        setA11y(false)
        val states = checker.states(context)
        assertFalse(states[PermKey.ACCESSIBILITY]!!)
        assertFalse(PermKey.gateOpen(states))
    }
}

/** Input walls around the name/phone fields. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InputWallsTest {

    @Test
    fun emptyNameBlocked() {
        assertFalse(NameValidator.isValid(""))
        assertFalse(NameValidator.isValid("   "))
    }

    @Test
    fun fortyOneCharsBlocked() {
        assertFalse(NameValidator.isValid("a".repeat(41)))
    }

    @Test
    fun invalidPhonesBlockedForGate() {
        listOf("+20", "01012", "abcdefghijklmnop").forEach {
            assertEquals("phone=$it", null, PhoneNormalizer.normalize(it))
        }
    }
}

/** Queue durability: each lock writes its own decodable line. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QueueDurabilityTest {

    @Test
    fun secondRegistrationAppendsSecondLine() = kotlinx.coroutines.runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val api = FakeRegistrationApi(ctx)
        api.register(sample("first", "+201012345678")).getOrThrow()
        api.register(sample("second", "+201098765432")).getOrThrow()
        val lines = api.queueFile().readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertEquals("first", Wire.decodeRegistration(lines[0])!!.name)
        assertEquals("second", Wire.decodeRegistration(lines[1])!!.name)
    }

    private fun sample(name: String, phone: String) = Registration(
        id = "x", name = name, phone = phone, createdAtMs = 7L,
        fingerprint = Fingerprint("a", "m", "mf", 34, "arm64-v8a"),
        constraints = Constraints(2048, true, 32)
    )
}

/** Simulated process kill: stale health.json stays readable and flags stale. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HealthStalenessTest {

    @Test
    fun killLeavesReadableStaleSnapshotThatStatusFlags() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val killAt = 1_700_000_000_000
        HealthStore.write(
            ctx, HealthState(HealthStore.STATE_RUNNING, killAt - 120_000, killAt)
        )
        // New process simulation: fresh read from disk only.
        val afterKill = HealthStore.read(ctx)
        assertTrue("snapshot must survive process kill", afterKill != null)
        assertTrue(
            "snapshot must count as stale two minutes later",
            HealthStore.isStale(afterKill!!, killAt + 10 * 60_000)
        )
        // At kill time itself the snapshot is fresh.
        assertFalse(HealthStore.isStale(afterKill, killAt + 1000))
        assertEquals(HealthStore.STATE_RUNNING, afterKill.state)
        assertEquals(killAt - 120_000, afterKill.startedAt)
    }

    @Test
    fun missingFileReportsAbsenceNotCrash() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(ctx.filesDir, "agent")
        dir.listFiles()?.forEach { it.delete() }
        assertEquals(null, HealthStore.read(ctx))
    }
}
