package com.cwbridge.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that exposes click-by-text for CatWeb / Roblox windows.
 * Enable under Settings → Accessibility → Installed apps → CWBridge Tap.
 */
class TapService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        LogBuffer.i("A11y", "service connected: CWBridge Tap")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window tree is pulled on demand via rootInActiveWindow.
    }

    override fun onInterrupt() {
        LogBuffer.w("A11y", "service interrupted")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        LogBuffer.i("A11y", "service disconnected")
        super.onDestroy()
    }

    /** Click the first clickable node whose text or contentDescription contains [query]. */
    fun clickByText(query: String): Boolean {
        val root = rootInActiveWindow ?: run {
            LogBuffer.w("A11y", "no active window")
            return false
        }
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false

        val target = findClickable(root, q)
        if (target == null) {
            LogBuffer.w("A11y", "no clickable node matching \"$query\"")
            return false
        }

        val label = (target.text ?: target.contentDescription)?.toString() ?: query
        val bounds = Rect()
        target.getBoundsInScreen(bounds)

        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            LogBuffer.i("A11y", "ACTION_CLICK text=\"$label\" bounds=$bounds")
            return true
        }

        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(gesture, null, null)
        LogBuffer.i("A11y", "GESTURE_TAP text=\"$label\" at=($cx,$cy) ok=$ok")
        return ok
    }

    private fun findClickable(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val text = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
        if (node.isClickable && text.lowercase().contains(query)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findClickable(child, query)
            if (hit != null) return hit
        }
        return null
    }

    companion object {
        @Volatile
        var instance: TapService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
