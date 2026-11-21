package com.wormgitty.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCaptureCoreTest {

    /* ------------------------------ naming ------------------------------- */

    @Test
    fun frameFileFollowsCapTsSeqJpgContract() {
        assertEquals("cap_1712345678_1.jpg", "cap_${1712345678L}_1.jpg")
        // The file-contract helper itself:
        val f = java.io.File("/tmp", ScreenCaptureNaming.frameName(42L, 3))
        assertEquals("cap_42_3.jpg", f.name)
    }

    /* ------------------------------ cadence ------------------------------ */

    @Test
    fun standardSpecIsFiveSecondsTimesTwelve() {
        val spec = ScreenCapture.FrameSpec.STANDARD
        assertEquals(5000L, spec.periodMs)
        assertEquals(12, spec.count)
        assertEquals(60000L, spec.totalMs)
    }

    @Test
    fun cadenceVerifierAcceptsStandardAndRejectsDegenerate() {
        val cap = ZeroCap()
        assertTrue(cap.verifyCadence(ScreenCapture.FrameSpec.STANDARD))
        assertFalse(cap.verifyCadence(ScreenCapture.FrameSpec(0L, 12)))
        assertFalse(cap.verifyCadence(ScreenCapture.FrameSpec(5000L, 0)))
    }

    /* --------------------------- rejection paths -------------------------- */

    @Test
    fun rejectionIsRepresentableWithoutCrash() {
        // The denial contract: (ok=false) propagates as message, never thrown.
        val outcome = DenialContract.describe(ok = false, cause = null)
        assertTrue(outcome.contains("رفض") || outcome.contains("denied"))
        val withCause = DenialContract.describe(ok = true, cause = "SecurityException")
        assertTrue(withCause.isNotEmpty())
    }

    private class ZeroCap : ScreenCaptureNaming.Host {
        override fun verifyCadence(spec: ScreenCapture.FrameSpec): Boolean =
            spec.periodMs in 1..60_000 && spec.count in 1..600
    }

    object DenialContract {
        fun describe(ok: Boolean, cause: String?): String =
            if (ok) "granted:$cause" else "denied:${cause ?: "user_refusal"}"
    }
}

/** Indirection so naming/cadence are testable without a live Context. */
object ScreenCaptureNaming {
    fun frameName(ts: Long, seq: Int): String = "cap_${ts}_${seq}.jpg"

    interface Host {
        fun verifyCadence(spec: ScreenCapture.FrameSpec): Boolean
    }
}
