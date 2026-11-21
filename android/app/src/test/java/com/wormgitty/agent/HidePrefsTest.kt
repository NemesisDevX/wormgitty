package com.wormgitty.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HidePrefsTest {

    @Test
    fun flagSurvivesAcrossInstancesUntilConsumed() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val writer = HidePrefs(ctx)
        writer.setPendingHide(true)

        // Fresh instances stand in for a fresh process after finishAffinity().
        val reader = HidePrefs(ctx)
        assertTrue(reader.pendingHide())

        reader.clear()
        assertFalse(HidePrefs(ctx).pendingHide())
    }

    @Test
    fun defaultIsHideNotPending() = runBlocking {
        assertFalse(HidePrefs(ApplicationProvider.getApplicationContext()).pendingHide())
    }
}
