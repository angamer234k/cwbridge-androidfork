package com.cwbridge.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that exposes click-by-text and coordinate taps for CatWeb / Roblox.
 *
 * Roblox (and most game engines) draw to a surface with an empty accessibility tree,
 * so clickByText usually finds nothing. Use clickAt / clickAtPercent instead —
 * dispatchGesture works against screen coordinates regardless of the node tree.
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

        return gestureTap(bounds.centerX().toFloat(), bounds.centerY().toFloat(), "text=\"$label\"")
    }

    /**
     * Tap at absolute screen pixels. Works on Roblox / game surfaces where the
     * accessibility tree is empty.
     */
    fun clickAt(x: Float, y: Float): Boolean {
        return gestureTap(x, y, "px")
    }

    /**
     * Tap at a percentage of the physical screen (0–100 for both axes).
     * Prefer this for Roblox so positions survive resolution changes.
     */
    fun clickAtPercent(xPercent: Float, yPercent: Float): Boolean {
        val dm = resources.displayMetrics
        val x = (xPercent.coerceIn(0f, 100f) / 100f) * dm.widthPixels
        val y = (yPercent.coerceIn(0f, 100f) / 100f) * dm.heightPixels
        LogBuffer.i(
            "A11y",
            "percent (${xPercent}%, ${yPercent}%) → px (${x.toInt()}, ${y.toInt()}) " +
                "screen=${dm.widthPixels}x${dm.heightPixels}",
        )
        return gestureTap(x, y, "percent")
    }

    private fun gestureTap(x: Float, y: Float, tag: String): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(gesture, null, null)
        LogBuffer.i("A11y", "GESTURE_TAP $tag at=(${x.toInt()},${y.toInt()}) ok=$ok")
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
