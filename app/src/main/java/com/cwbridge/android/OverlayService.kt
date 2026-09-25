package com.cwbridge.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs

/** Floating status dot. Tap shows last 10 [FLog::CreatorOutput] lines. */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var logsView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var logsVisible = false

    private val statusListener: (OverlayState, String) -> Unit = { state, _ -> applyColor(state) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
        BridgeStatus.addListener(statusListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> applyColor(BridgeStatus.state)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        BridgeStatus.removeListener(statusListener)
        hideLogs()
        bubbleView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        bubbleView = null
        super.onDestroy()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun showBubble() {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 200
        }
        bubbleParams = params
        bubbleView = view

        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = params.x
                    originY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = originX + dx
                    params.y = originY + dy
                    try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleTap()
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(view, params)
        applyColor(BridgeStatus.state)
    }

    private fun onBubbleTap() {
        if (logsVisible) hideLogs() else showLogs()
    }

    private fun showLogs() {
        if (logsView != null) {
            refreshLogsText()
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_logs_panel, null)
        val bp = bubbleParams
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bp?.x ?: 24) + 56
            y = bp?.y ?: 200
        }
        logsView = view
        view.setOnClickListener { hideLogs() }
        windowManager?.addView(view, params)
        logsVisible = true
        refreshLogsText()
    }

    private fun refreshLogsText() {
        val tv = logsView?.findViewById<TextView>(R.id.overlayLogsText) ?: return
        val last = RobloxLogBuffer.last(10)
        tv.text = if (last.isEmpty()) {
            "(no [FLog::CreatorOutput] yet)\n\nStart Roblox + CatWeb so console lines appear here."
        } else {
            last.joinToString("\n\n") { "\u2022 $it" }
        }
        tv.movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
    }

    private fun hideLogs() {
        logsView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        logsView = null
        logsVisible = false
    }

    private fun applyColor(state: OverlayState) {
        val dot = bubbleView?.findViewById<View>(R.id.overlayDot) ?: return
        val colorRes = when (state) {
            OverlayState.IDLE -> R.color.muted
            OverlayState.WAITING -> R.color.warn
            OverlayState.ACTIVE -> R.color.ok
            OverlayState.ERROR -> R.color.danger
        }
        val color = ContextCompat.getColor(this, colorRes)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(3, 0x40FFFFFF)
        }
        bubbleView?.contentDescription = "CWBridge ${state.name}: ${BridgeStatus.detail}"
    }

    companion object {
        const val ACTION_STOP = "com.cwbridge.android.OVERLAY_STOP"
        const val ACTION_REFRESH = "com.cwbridge.android.OVERLAY_REFRESH"

        fun canDrawOverlays(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
            else true

        fun start(context: Context) {
            if (!canDrawOverlays(context)) return
            try {
                context.startService(Intent(context, OverlayService::class.java))
            } catch (t: Throwable) {
                LogBuffer.e("Overlay", "start failed: ${t.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, OverlayService::class.java).apply { action = ACTION_STOP }
                )
            } catch (_: Exception) {}
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun refresh(context: Context) {
            if (!canDrawOverlays(context)) return
            try {
                context.startService(
                    Intent(context, OverlayService::class.java).apply { action = ACTION_REFRESH }
                )
            } catch (_: Exception) {}
        }
    }
}
