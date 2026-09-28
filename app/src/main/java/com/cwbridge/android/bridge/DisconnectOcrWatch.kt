package com.cwbridge.android.bridge

import android.content.Context
import com.cwbridge.android.TapService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Every [INTERVAL_MS], OCR a center 750x750 window for Disconnected / Reconnect.
 * - Reconnect visible → tap it
 * - Disconnected without Reconnect → bump failsafe, relaunch Roblox
 * - failsafe > 5 → stop bridge + dismissable error
 */
object DisconnectOcrWatch {

    private const val INTERVAL_MS = 60_000L
    private const val SQUARE_PX = 750
    private const val MAX_FAILSAFE = 5

    @Volatile private var enabled = false
    private var job: Job? = null

    fun start(scope: CoroutineScope, context: Context) {
        stop()
        enabled = true
        val app = context.applicationContext
        job = scope.launch(Dispatchers.IO) {
            LogBuffer.i("OCR-DC", "watch on every ${INTERVAL_MS / 1000}s, square=${SQUARE_PX}px, maxFail=$MAX_FAILSAFE")
            while (isActive && enabled) {
                delay(INTERVAL_MS)
                if (!enabled) break
                try {
                    tick(app)
                } catch (t: Throwable) {
                    LogBuffer.w("OCR-DC", "tick: ${t.message}")
                }
            }
        }
    }

    fun stop() {
        enabled = false
        job?.cancel()
        job = null
    }

    private fun tick(context: Context) {
        val sq = ScreenOcr.centerSquarePct(context, SQUARE_PX)
        val x0 = sq[0]; val x1 = sq[1]; val y0 = sq[2]; val y1 = sq[3]
        LogBuffer.i("OCR-DC", "scan center ${SQUARE_PX}px X$x0-$x1 Y$y0-$y1")

        val reconnect = ScreenOcr.findText(context, "reconnect", x0, x1, y0, y1)
        if (reconnect != null) {
            LogBuffer.i("OCR-DC", "Reconnect found — tapping")
            val svc = TapService.instance
            if (svc != null) {
                svc.clickAt(reconnect.centerX, reconnect.centerY)
            } else if (com.cwbridge.android.ShizukuShell.isReady()) {
                com.cwbridge.android.ShizukuShell.exec(
                    "input tap ${reconnect.centerX.toInt()} ${reconnect.centerY.toInt()}",
                )
            }
            AntiDisconnect.noteActivity()
            return
        }

        val disconnected = ScreenOcr.findText(context, "disconnect", x0, x1, y0, y1)
        if (disconnected == null) {
            LogBuffer.i("OCR-DC", "no disconnect UI")
            return
        }

        // Dead disconnect — no Reconnect button
        val n = BridgeControl.bumpDisconnectFailsafe()
        LogBuffer.w("OCR-DC", "Disconnected w/o Reconnect — failsafe=$n/$MAX_FAILSAFE")
        if (n > MAX_FAILSAFE) {
            val msg =
                "Bridge stopped: Roblox disconnected $n times without a Reconnect button " +
                    "(failsafe limit $MAX_FAILSAFE). Open Roblox/CatWeb manually, then start the bridge again."
            LogBuffer.e("OCR-DC", msg)
            BridgeControl.stopBridgeWithError(msg)
            stop()
            return
        }
        // Force relaunch Roblox / CatWeb
        LogBuffer.w("OCR-DC", "relaunching Roblox (failsafe $n)")
        BridgeControl.restartRoblox(context)
        BridgeStatus.set(OverlayState.WAITING, "Relaunch after disconnect ($n/$MAX_FAILSAFE)")
    }
}
