package com.cwbridge.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cwbridge.android.bridge.LogBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Accessibility: taps, paste, send text, Enter, Ctrl+T (Shizuku-backed). */
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

    /**
     * Capture the screen. Android 11+ only — [Build.VERSION_CODES.R].
     * The callback fires with null when the system refuses the capture.
     *
     * Both ScreenshotResult and TakeScreenshotCallback are nested inside
     * AccessibilityService, and AccessibilityService.getExecutor() is a hidden
     * (non-SDK) method, so we supply our own single-thread executor and retire
     * it once the callback lands.
     */
    fun screenshot(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "cwbridge-screenshot")
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                exec,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        exec.shutdown()
                        callback(bitmapFrom(screenshot))
                    }

                    override fun onFailure(errorCode: Int) {
                        exec.shutdown()
                        val why = when (errorCode) {
                            1 -> "INTERNAL_ERROR"
                            2 -> "NO_ACCESSIBILITY_ACCESS"
                            3 -> "INTERVAL_TOO_SHORT"
                            4 -> "INVALID_DISPLAY"
                            5 -> "INVALID_WINDOW"
                            else -> "code=$errorCode"
                        }
                        LogBuffer.w("A11y", "takeScreenshot failed: $why (FLAG_SECURE apps like Roblox cannot be captured)")
                        callback(null)
                    }
                },
            )
        } catch (t: Throwable) {
            exec.shutdown()
            LogBuffer.w("A11y", "takeScreenshot threw: ${t.message}")
            callback(null)
        }
    }

    private fun bitmapFrom(result: AccessibilityService.ScreenshotResult): Bitmap? = try {
        val hwBitmap = result.hardwareBuffer
        val wrapped = hwBitmap?.let { Bitmap.wrapHardwareBuffer(it, result.colorSpace) }
        // A hardware bitmap cannot be compressed directly; make it software.
        if (wrapped != null && wrapped.config == Bitmap.Config.HARDWARE) {
            val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
            wrapped.recycle()
            copy
        } else {
            wrapped
        }.also { hwBitmap?.close() }
    } catch (t: Throwable) {
        LogBuffer.w("A11y", "bitmapFrom: ${t.message}")
        null
    }

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

    fun sendText(text: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cwbridge", text))
        LogBuffer.i("A11y", "sendText clipboard set len=${text.length}")
        return pasteClipboard().also { LogBuffer.i("A11y", "sendText paste ok=$it") }
    }

    fun pressEnter(): Boolean {
        LogBuffer.i("A11y", "pressEnter")
        if (ShizukuShell.isReady()) {
            val ok = ShizukuShell.pressEnter()
            LogBuffer.i("A11y", "pressEnter via Shizuku ok=$ok")
            if (ok) return true
        }
        return injectKey(KeyEvent.KEYCODE_ENTER).also { LogBuffer.i("A11y", "pressEnter inject=$it") }
    }

    fun pressCtrlT(): Boolean {
        LogBuffer.i("A11y", "pressCtrlT")
        if (ShizukuShell.isReady()) {
            if (ShizukuShell.pressCtrlT()) {
                LogBuffer.i("A11y", "pressCtrlT Shizuku OK")
                return true
            }
            LogBuffer.w("A11y", "pressCtrlT Shizuku exhausted normal+hold")
        } else {
            LogBuffer.w("A11y", "Shizuku not ready — ${ShizukuShell.statusLine()}")
        }
        // Local hold: Ctrl DOWN, T DOWN/UP, Ctrl UP
        if (injectCtrlChord(KeyEvent.KEYCODE_T)) {
            LogBuffer.i("A11y", "pressCtrlT local hold inject OK")
            return true
        }
        LogBuffer.e("A11y", "pressCtrlT FAILED")
        return false
    }


    fun injectCtrlChord(keyCode: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        fun ev(action: Int, code: Int, metaState: Int, whenMs: Long) =
            KeyEvent(downTime, whenMs, action, code, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
        var t = downTime
        val events = listOf(
            ev(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, meta, t),
            ev(KeyEvent.ACTION_DOWN, keyCode, meta, t + 20),
            ev(KeyEvent.ACTION_UP, keyCode, meta, t + 40),
            ev(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, t + 50),
        )
        for (e in events) {
            if (!injectKeyEvent(e)) {
                LogBuffer.w("A11y", "injectCtrlChord failed on key=${e.keyCode} action=${e.action}")
                return false
            }
            try { Thread.sleep(15) } catch (_: InterruptedException) {}
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
        method.invoke(im, event, 0) as? Boolean ?: false
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
