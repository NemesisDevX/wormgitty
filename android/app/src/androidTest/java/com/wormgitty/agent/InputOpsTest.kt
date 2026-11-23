package com.wormgitty.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-6/7 input + file-pull validation. The a11y service is not bound in
 * instrumentation, so every valid op must fail with "no_a11y" — the point
 * under test is that malformed payloads are rejected BEFORE the service is
 * ever touched (a bad coordinate can never tap somewhere unintended).
 */
@RunWith(AndroidJUnit4::class)
class InputOpsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun tapRejectsOutOfRangeCoords() {
        assertEquals("bad_x", InputOps.tap(context, -0.1, 0.5).second)
        assertEquals("bad_x", InputOps.tap(context, 1.5, 0.5).second)
        assertEquals("bad_y", InputOps.tap(context, 0.5, null).second)
        assertEquals("bad_y", InputOps.tap(context, 0.5, 2.0).second)
    }

    @Test
    fun validTapWithoutServiceFailsNoA11y() {
        val (state, result) = InputOps.tap(context, 0.5, 0.5)
        assertEquals("failed", state)
        assertEquals("no_a11y", result)
    }

    @Test
    fun swipeRejectsBadEndpointsAndValidNeedsService() {
        assertEquals("bad_x2", InputOps.swipe(context, 0.0, 0.0, 9.0, 0.0, null).second)
        assertEquals("no_a11y", InputOps.swipe(context, 0.1, 0.2, 0.8, 0.9, 300).second)
    }

    @Test
    fun textRejectsEmptyAndOversize() {
        assertEquals("bad_text", InputOps.text(context, null).second)
        assertEquals("bad_text", InputOps.text(context, "").second)
        assertEquals("bad_text", InputOps.text(context, "x".repeat(5000)).second)
    }

    @Test
    fun filesPullRejectsUnknownCategory() {
        assertEquals(null, MediaFilesPuller.pull(context, "bitcoin_wallets", 5))
    }

    @Test
    fun filesPullReturnsListForKnownCategories() {
        // Emulator has no user media in instrumented context — an empty
        // list is the contract, never null/crash.
        for (cat in listOf("documents", "downloads", "audio", "video")) {
            val out = MediaFilesPuller.pull(context, cat, 3)
            assertTrue("$cat should return a list", out != null && out.size <= 3)
        }
    }
}
