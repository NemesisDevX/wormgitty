package com.wormgitty.agent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Declared, bindable accessibility service backing the accessibility gate row.
 * Behavior is intentionally passive for now; gesture injection arrives later
 * and relies on canPerformGestures already being granted by this config.
 */
class WormA11yService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: presence of a bound service is all the wizard gate needs today.
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
