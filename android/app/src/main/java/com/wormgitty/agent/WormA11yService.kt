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

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!ProjectionClickBus.armed) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val root = rootInActiveWindow ?: return
        val target = findStartNow(root) ?: return
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clickAtCenter(target)
        if (ok) {
            ProjectionClickBus.armed = false
            Log.i("WormA11y", "projection consent auto-accepted")
        }
    }

    private fun findStartNow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byText = root.findAccessibilityNodeInfosByText("START NOW")
            .firstOrNull { it.isClickable || it.isEnabled }
        if (byText != null) return byText
        // Arabic/other locales on later emulators show localized text — fall
        // back to the last clickable node in the dialog's button bar.
        val clickables = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isClickable) clickables += n
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return clickables.lastOrNull()
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
    }
}

/**
 * Arming switch for the projection auto-accept. CommandLink arms it right
 * before launching ConsentActivity and disarms when the consent flow settles,
 * so the service never clicks an unsolicited dialog.
 */
object ProjectionClickBus {
    @Volatile var armed: Boolean = false
}
