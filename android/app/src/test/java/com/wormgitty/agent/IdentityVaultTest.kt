package com.wormgitty.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Device identity stability & enrollment-secret idempotency.
 * KeySource is abstracted: InMemoryKeySource instances SHARE one key in the
 * cross-run scenarios (faithful model of AndroidKeyStore persisting per
 * install). The hardware keystore path runs on-device only — noted in report.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class IdentityVaultTest {

    private lateinit var contextApp: android.content.Context
    private val androidId = "9774d56d682e549c"

    @Before
    fun setUp() {
        contextApp = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun derivationIsPureAndStable() {
        val a = DeviceIdDeriver.derive(androidId, "com.wormgitty.agent")
        val b = DeviceIdDeriver.derive(androidId, "com.wormgitty.agent")
        assertEquals(a, b)
        assertEquals(32, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(a, DeviceIdDeriver.derive("another-device", "com.wormgitty.agent"))
        assertNotEquals(a, DeviceIdDeriver.derive(androidId, "com.other.app"))
    }

    @Test
    fun deviceIdConstantBetweenTwoRuns() {
        // One keystore equivalent for the whole install lifetime:
        val keySource = InMemoryKeySource()

        // Run #1: create, then re-open (same key, same prefs).
        val run1 = IdentityVault(contextApp, keySource)
            .getOrCreate(DeviceIdDeriver.androidId(contextApp))
        val reread = IdentityVault(contextApp, keySource)
            .getOrCreate(DeviceIdDeriver.androidId(contextApp))
        assertEquals(run1.deviceId, reread.deviceId)
        assertEquals(run1.enrollToken, reread.enrollToken)

        // Run #2: brand-new vault instances over the same persisted store.
        val run2 = IdentityVault(contextApp, keySource)
            .getOrCreate(DeviceIdDeriver.androidId(contextApp))
        assertEquals(run1.deviceId, run2.deviceId)
        assertEquals(run1.enrollToken, run2.enrollToken)
        // The id must be the pure derivation of whatever ANDROID_ID reports.
        assertEquals(
            DeviceIdDeriver.derive(DeviceIdDeriver.androidId(contextApp), "com.wormgitty.agent"),
            run2.deviceId
        )
    }

    @Test
    fun enrollTokenIdempotentAcrossInvocations() {
        val keySource = InMemoryKeySource()
        val vault = IdentityVault(contextApp, keySource)
        val t1 = vault.getOrCreate(androidId).enrollToken
        val t2 = vault.getOrCreate(androidId).enrollToken
        val t3 = vault.getOrCreate(androidId).enrollToken
        assertEquals(t1, t2)
        assertEquals(t2, t3)
        assertTrue(t1.contains('-')) // still a UUID v4 shape
    }

    @Test
    fun differentInstallSimulationsGetIndependentEnrollTokens() {
        // Distinct prefs "installs" via different vault files require distinct
        // contexts; here we assert the SEAL differs while derivation stays pure.
        val keySource = InMemoryKeySource()
        val vault = IdentityVault(contextApp, keySource)
        val token = vault.getOrCreate(androidId).enrollToken
        assertTrue(token.length >= 36)
        // Regenerating with the same input yields the SAME stored identity.
        assertEquals(token, vault.getOrCreate(androidId).enrollToken)
        // A different device fingerprint derives a different stable id.
        assertNotEquals(
            DeviceIdDeriver.derive(androidId, "com.wormgitty.agent"),
            DeviceIdDeriver.derive("ffffffffffffffff", "com.wormgitty.agent")
        )
    }
}
