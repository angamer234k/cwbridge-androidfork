package com.cwbridge.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Accessibility: taps, paste, send text, Enter, Ctrl+T. */
class TapService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        LogBuffer.i("A11y", "service connected: CWBridge Tap")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { LogBuffer.w("A11y", "service interrupted") }

    override fun onDestroy() {
        if (instance === this) instance = null
        LogBuffer.i("A11y", "service disconnected")
        super.onDestroy()
    }

    fun clickByText(query: String): Boolean {
        val root = rootInActiveWindow ?: run {
            LogBuffer.w("A11y", "no active window"); return false
        }
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        val target = findClickable(root, q) ?: run {
            LogBuffer.w("A11y", "no clickable node matching \"$query\""); return false
        }
        val label = (target.text ?: target.contentDescription)?.toString() ?: query
        val bounds = Rect(); target.getBoundsInScreen(bounds)
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            LogBuffer.i("A11y", "ACTION_CLICK text=\"$label\" bounds=$bounds"); return true
        }
        return gestureTap(bounds.centerX().toFloat(), bounds.centerY().toFloat(), "text=\"$label\"")
    }

    fun clickAt(x: Float, y: Float): Boolean = gestureTap(x, y, "px")

    fun clickAtPercent(xPercent: Float, yPercent: Float): Boolean {
        val dm = resources.displayMetrics
        val x = (xPercent.coerceIn(0f, 100f) / 100f) * dm.widthPixels
        val y = (yPercent.coerceIn(0f, 100f) / 100f) * dm.heightPixels
        return gestureTap(x, y, "percent")
    }

    fun pasteClipboard(): Boolean {
        val root = rootInActiveWindow ?: return false.also { LogBuffer.w("A11y", "ACTION_PASTE no root") }
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val ok = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            LogBuffer.i("A11y", "ACTION_PASTE focused ok=$ok"); if (ok) return true
        }
        val editable = findEditable(root)
        if (editable != null) {
            val ok = editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            LogBuffer.i("A11y", "ACTION_PASTE editable ok=$ok"); if (ok) return true
        }
        LogBuffer.w("A11y", "ACTION_PASTE unavailable"); return false
    }

    /** Clipboard + paste — does NOT press Enter. */
    fun sendText(text: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cwbridge", text))
        LogBuffer.i("A11y", "sendText clipboard set len=${text.length}")
        return pasteClipboard().also { LogBuffer.i("A11y", "sendText paste ok=$it") }
    }

    fun pressEnter(): Boolean {
        LogBuffer.i("A11y", "pressEnter")
        return injectKey(KeyEvent.KEYCODE_ENTER).also { LogBuffer.i("A11y", "pressEnter result=$it") }
    }

    fun pressCtrlT(): Boolean {
        LogBuffer.i("A11y", "pressCtrlT")
        return injectCtrlChord(KeyEvent.KEYCODE_T).also { LogBuffer.i("A11y", "pressCtrlT result=$it") }
    }

    fun injectCtrlChord(keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val events = listOf(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
            KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
        )
        for (e in events) {
            if (!injectKeyEvent(e)) return false
            try { Thread.sleep(8) } catch (_: InterruptedException) {}
        }
        return true
    }

    fun injectKey(keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        if (!injectKeyEvent(down)) return false
        try { Thread.sleep(8) } catch (_: InterruptedException) {}
        return injectKeyEvent(up)
    }

    private fun injectKeyEvent(event: KeyEvent): Boolean = try {
        val im = getSystemService(INPUT_SERVICE) as InputManager
        val method = InputManager::class.java.getDeclaredMethod(
            "injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(im, event, 0) as? Boolean ?: true
    } catch (t: Throwable) {
        LogBuffer.w("A11y", "injectKeyEvent failed: ${t.javaClass.simpleName}: ${t.message}"); false
    }

    private fun gestureTap(x: Float, y: Float, tag: String): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        LogBuffer.i("A11y", "GESTURE_TAP $tag at=(${x.toInt()},${y.toInt()}) ok=$ok"); return ok
    }

    private fun findClickable(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val text = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
        if (node.isClickable && text.lowercase().contains(query)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickable(child, query)?.let { return it }
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditable(child)?.let { return it }
        }
        return null
    }

    companion object {
        @Volatile var instance: TapService? = null
            private set
        fun isConnected(): Boolean = instance != null
    }
}
