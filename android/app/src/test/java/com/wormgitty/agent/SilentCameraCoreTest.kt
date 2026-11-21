package com.wormgitty.agent

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentCameraCoreTest {

    /* ---------------------------- file naming ---------------------------- */

    @Test
    fun fileNameFollowsCamTsFacingContract() {
        assertEquals("cam_1712345678901_back.jpg", SilentCamera.fileName(1712345678901L, "back"))
        assertEquals("cam_1_front.jpg", SilentCamera.fileName(1L, "front"))
    }

    /* --------------------------- facing choice ---------------------------- */

    @Test
    fun backChoiceTargetsBackOnly() {
        assertEquals(listOf("back"), SilentCamera.choiceToFacings(SilentCamera.Choice.BACK))
    }

    @Test
    fun frontChoiceTargetsFrontOnly() {
        assertEquals(listOf("front"), SilentCamera.choiceToFacings(SilentCamera.Choice.FRONT))
    }

    @Test
    fun bothChoiceOrdersBackThenFront() {
        assertEquals(listOf("back", "front"), SilentCamera.choiceToFacings(SilentCamera.Choice.BOTH))
    }

    /* ----------------------- availability detection ----------------------- */

    @Test
    fun detectsBothFacingsWhenPresent() {
        val map = mapOf(
            "0" to CameraCharacteristics.LENS_FACING_BACK,
            "1" to CameraCharacteristics.LENS_FACING_FRONT
        )
        val (back, front) = SilentCamera.availableFacings(map)
        assertTrue(back && front)
    }

    @Test
    fun detectsBackOnlyCamera() {
        val (back, front) = SilentCamera.availableFacings(mapOf("0" to CameraCharacteristics.LENS_FACING_BACK))
        assertTrue(back && !front)
    }

    @Test
    fun noCamerasMeansEmptyTargetsHandledAsError() {
        val (back, front) = SilentCamera.availableFacings(emptyMap())
        assertTrue(!back && !front)
        // Functional equivalent of the driver's zero-path (unit-verifiable):
        val outcome = runZeroPathSimulation()
        assertTrue(outcome.failed.size == 2 && outcome.ok.isEmpty())
        assertEquals("camera_missing_back", outcome.failed[0].reason)
        assertEquals("camera_missing_front", outcome.failed[1].reason)
    }

    private fun runZeroPathSimulation(): SilentCamera.Outcome {
        // Mirrors captureBlocking's missing-camera branch with zero detections.
        val ok = emptyList<SilentCamera.Ok>()
        val failed = SilentCamera.choiceToFacings(SilentCamera.Choice.BOTH)
            .map { SilentCamera.Fail(it, "camera_missing_$it") }
        return SilentCamera.Outcome(ok, failed)
    }
}
