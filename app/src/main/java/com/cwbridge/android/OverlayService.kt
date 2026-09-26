package com.cwbridge.android

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Floating status dot. Tap shows logs; long-press schedules Ctrl+T in 3s. */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var logsView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var logsVisible = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ctrlTCountdown: Runnable? = null
    private var logRefreshPending = false

    private val robloxLogListener: () -> Unit = {
        if (!logsVisible) return@robloxLogListener
        if (logRefreshPending) return@robloxLogListener
        logRefreshPending = true
        mainHandler.postDelayed({
            logRefreshPending = false
            if (logsVisible) refreshLogsText()
        }, 120)
    }

    private val statusListener: (OverlayState, String) -> Unit = { state, _ ->
        applyColor(state)
        if (logsVisible) refreshLogsText()
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP -> stopSelf()
                ACTION_REFRESH -> {
                    applyColor(BridgeStatus.state)
                    if (logsVisible) refreshLogsText()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ContextCompat.registerReceiver(
            this, stopReceiver,
            IntentFilter().apply {
                addAction(ACTION_STOP)
                addAction(ACTION_REFRESH)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        BridgeStatus.addListener(statusListener)
        RobloxLogBuffer.addListener(robloxLogListener)
        showBubble()
    }

    override fun onDestroy() {
        cancelCtrlTCountdown()
        BridgeStatus.removeListener(statusListener)
        RobloxLogBuffer.removeListener(robloxLogListener)
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        hideLogs()
        bubbleView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        bubbleView = null
        super.onDestroy()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showBubble() {
        if (bubbleView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24; y = 200
        }
        bubbleParams = params
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        var longPressFired = false
        val longPressRunnable = Runnable {
            longPressFired = true
            scheduleCtrlTIn3s()
        }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y
                    moved = false; longPressFired = false
                    mainHandler.postDelayed(longPressRunnable, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        moved = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    params.x = startX + dx; params.y = startY + dy
                    try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressFired) onBubbleTap()
                    true
                }
                else -> false
            }
        }
        windowManager?.addView(view, params)
        bubbleView = view
        applyColor(BridgeStatus.state)
    }

    private fun onBubbleTap() { if (logsVisible) hideLogs() else showLogs() }

    fun scheduleCtrlTIn3s() {
        cancelCtrlTCountdown()
        Toast.makeText(this, "Ctrl+T in 3s — focus the target app now", Toast.LENGTH_SHORT).show()
        LogBuffer.i("Overlay", "Ctrl+T scheduled in 3s — ${ShizukuShell.statusLine()}")
        var left = 3
        val tick = object : Runnable {
            override fun run() {
                if (left > 0) {
                    Toast.makeText(this@OverlayService, "Ctrl+T in ${left}s…", Toast.LENGTH_SHORT).show()
                    left--
                    mainHandler.postDelayed(this, 1000)
                } else {
                    ctrlTCountdown = null
                    val ok = when {
                        ShizukuShell.isReady() -> ShizukuShell.pressCtrlT()
                        TapService.instance != null -> TapService.instance!!.pressCtrlT()
                        else -> {
                            LogBuffer.w("Overlay", "Ctrl+T: no Shizuku and no TapService")
                            false
                        }
                    }
                    Toast.makeText(
                        this@OverlayService,
                        if (ok) "Ctrl+T sent"
                        else "Ctrl+T failed — ${ShizukuShell.statusLine()}",
                        Toast.LENGTH_LONG,
                    ).show()
                    LogBuffer.i("Overlay", "Ctrl+T result=$ok ${ShizukuShell.statusLine()}")
                }
            }
        }
        ctrlTCountdown = tick
        mainHandler.post(tick)
    }

    private fun cancelCtrlTCountdown() {
        ctrlTCountdown?.let { mainHandler.removeCallbacks(it) }
        ctrlTCountdown = null
    }

    private fun showLogs() {
        if (logsView != null) { refreshLogsText(); return }
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_logs_panel, null)
        val bp = bubbleParams
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bp?.x ?: 24) + 56; y = bp?.y ?: 200
        }
        logsView = view
        view.findViewById<Button>(R.id.btnOverlayCtrlT)?.setOnClickListener { scheduleCtrlTIn3s() }
        windowManager?.addView(view, params)
        logsVisible = true
        refreshLogsText()
    }

    private fun refreshLogsText() {
        val tv = logsView?.findViewById<TextView>(R.id.overlayLogsText) ?: return
        val last = RobloxLogBuffer.last(25)
        if (last.isEmpty()) { tv.text = "(no console lines yet)"; return }
        val sb = SpannableStringBuilder()
        last.forEachIndexed { i, line ->
            if (i > 0) sb.append("\n\n")
            val color = when (line.level) {
                ConsoleLevel.CATWEB -> Color.parseColor("#E8F1FF")
                ConsoleLevel.ERROR -> Color.parseColor("#F07178")
                ConsoleLevel.WARN -> Color.parseColor("#E6C07B")
                ConsoleLevel.INFO -> Color.parseColor("#7FD4FF")
            }
            val start = sb.length
            sb.append(line.text)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = sb
    }

    private fun hideLogs() {
        logsView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        logsView = null; logsVisible = false
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
        fun start(context: Context) {
            if (!canDrawOverlays(context)) return
            context.startService(Intent(context, OverlayService::class.java))
        }
        fun stop(context: Context) {
            context.sendBroadcast(Intent(ACTION_STOP).setPackage(context.packageName))
            context.stopService(Intent(context, OverlayService::class.java))
        }
        fun refresh(context: Context) {
            context.sendBroadcast(Intent(ACTION_REFRESH).setPackage(context.packageName))
        }
    }
}
