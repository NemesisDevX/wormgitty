package com.wormgitty.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProjectionConsentTest {

    private lateinit var consent: ProjectionConsent
    private val keySource = InMemoryKeySource()

    @Before
    fun setUp() {
        consent = ProjectionConsent(
            ApplicationProvider.getApplicationContext(), keySource
        )
    }

    @Test
    fun saveThenLoadReturnsSameGrantShape() {
        val g = ProjectionConsent.Grant(resultCode = -1, dataBytes = byteArrayOf(1, 2, 3))
        assertTrue(consent.save(g))
        val back = consent.load()
        assertNotNull(back)
        assertEquals(g.resultCode, back!!.resultCode)
        assertTrue(consent.hasConsent())
    }

    @Test
    fun emptyStoreHasNoConsent() {
        assertFalse(consent.hasConsent())
        assertNull(consent.load())
    }

    @Test
    fun clearRemovesConsent() {
        consent.save(ProjectionConsent.Grant(-1, byteArrayOf(9)))
        assertTrue(consent.hasConsent())
        consent.clear()
        assertFalse(consent.hasConsent())
    }

    @Test
    fun corruptBlobFallsBackToNoConsent() {
        consent.save(ProjectionConsent.Grant(-1, byteArrayOf(7)))
        // Sabotage the encrypted blob.
        val prefs = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getSharedPreferences(ProjectionConsent.PREFS_FILE, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(ProjectionConsent.KEY_DATA, "not-base64!!!").commit()
        assertNull(consent.load())
        assertFalse(consent.hasConsent())
    }

    @Test
    fun savedAtStampPresentAfterSave() {
        val before = System.currentTimeMillis()
        consent.save(ProjectionConsent.Grant(-1, byteArrayOf(4)))
        assertTrue(consent.savedAtMs() >= before)
    }
}
