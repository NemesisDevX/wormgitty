package com.wormgitty.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Operator screen input via the accessibility service (phase-6).
 * Coordinates arrive normalized (0..1) and are multiplied by the real
 * display size; anything outside 0..1 is rejected before touching the
 * service so a bad payload can never tap somewhere unintended.
 */
object InputOps {

    private fun dims(context: Context): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun svc(): WormA11yService? =
        WormA11yService.instance?.takeIf { WormA11yService.connected }

    private fun norm(v: Double?): Float? =
        v?.takeIf { it in 0.0..1.0 }?.toFloat()

    fun tap(context: Context, x: Double?, y: Double?): Pair<String, String> {
        val (w, h) = dims(context)
        val nx = norm(x) ?: return "failed" to "bad_x"
        val ny = norm(y) ?: return "failed" to "bad_y"
        return gesture(context, Path().apply { moveTo(nx * w, ny * h) }, 0, 80)
    }

    fun swipe(
        context: Context,
        x1: Double?, y1: Double?, x2: Double?, y2: Double?, ms: Long?
    ): Pair<String, String> {
        val (w, h) = dims(context)
        val a = norm(x1) ?: return "failed" to "bad_x1"
        val b = norm(y1) ?: return "failed" to "bad_y1"
        val c = norm(x2) ?: return "failed" to "bad_x2"
        val d = norm(y2) ?: return "failed" to "bad_y2"
        val dur = (ms ?: 300L).coerceIn(80L, 5000L)
        return gesture(
            context,
            Path().apply { moveTo(a * w, b * h); lineTo(c * w, d * h) },
            0, dur
        )
    }

    private fun gesture(
        context: Context, path: Path, startMs: Long, durMs: Long
    ): Pair<String, String> {
        val s = svc() ?: return "failed" to "no_a11y"
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, startMs, durMs))
            .build()
        val done = CountDownLatch(1)
        val ok = arrayOf(false)
        val dispatched = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok[0] = true; done.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                done.countDown()
            }
        }, null)
        if (!dispatched) return "failed" to "dispatch_rejected"
        return if (done.await(6, TimeUnit.SECONDS) && ok[0]) "executed" to "gesture"
        else "failed" to "gesture_cancelled"
    }

    /**
     * input_text: ACTION_SET_TEXT on the focused node; falls back to
     * clipboard + ACTION_PASTE when the node refuses the direct write.
     */
    fun text(context: Context, value: String?): Pair<String, String> {
        if (value.isNullOrEmpty() || value.length > 4000) return "failed" to "bad_text"
        val s = svc() ?: return "failed" to "no_a11y"
        val root = s.rootInActiveWindow ?: return "failed" to "no_window"
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "failed" to "no_focus"
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value
            )
        }
        if (focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return "executed" to "set_text"
        }
        // Clipboard + paste fallback for fields that reject SET_TEXT.
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(
                android.content.ClipData.newPlainText("wg", value)
            )
            if (focus.performAction(AccessibilityNodeInfo.ACTION_PASTE)) "executed" to "paste"
            else "failed" to "paste_rejected"
        }.getOrElse { "failed" to "paste:${it.message?.take(40)}" }
    }
}
