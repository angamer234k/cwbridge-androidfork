package com.cwbridge.android

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cwbridge.android.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logcatReader: LogcatReader
    private lateinit var invokeEngine: InvokeEngine
    private var bridgeRunning = false

    private val logListener: (LogBuffer.Line) -> Unit = { line ->
        runOnUiThread { appendLog(line) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        invokeEngine = InvokeEngine(applicationContext, lifecycleScope)
        logcatReader = LogcatReader(this) { raw -> invokeEngine.onExternalLog(raw) }

        binding.btnStartStop.setOnClickListener { toggleBridge() }
        binding.btnA11y.setOnClickListener { openAccessibilitySettings() }
        binding.btnLogcatHint.setOnClickListener { showAdbGrantHint() }
        binding.btnTap.setOnClickListener { performTapByText() }
        binding.btnTapPercent.setOnClickListener { performTapPercent() }
        binding.btnTapPx.setOnClickListener { performTapPx() }
        binding.btnClearLogs.setOnClickListener {
            LogBuffer.clear()
            binding.logView.text = ""
        }

        LogBuffer.addListener(logListener)
        LogBuffer.snapshot().forEach { appendLog(it) }
        LogBuffer.i("CWBridge", "session start version=2.8.0-android")
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        logcatReader.start(lifecycleScope)
    }

    override fun onPause() {
        logcatReader.stop()
        super.onPause()
    }

    override fun onDestroy() {
        invokeEngine.stop()
        LogBuffer.removeListener(logListener)
        super.onDestroy()
    }

    private fun toggleBridge() {
        if (bridgeRunning) {
            bridgeRunning = false
            invokeEngine.stop()
            LogBuffer.i("CWBridge", "bridge stopped")
        } else {
            if (!TapService.isConnected()) {
                LogBuffer.e("CWBridge", "refusing start: Accessibility service is off")
                Toast.makeText(this, "Enable CWBridge Tap first", Toast.LENGTH_SHORT).show()
                openAccessibilitySettings()
                refreshUi()
                return
            }
            bridgeRunning = true
            invokeEngine.start()
            // Sensible defaults matching the MacroDroid paste block; override via invoke|submit / focus
            invokeEngine.focusXPct = 50f
            invokeEngine.focusYPct = 50f
            invokeEngine.submitXPx = 730f
            invokeEngine.submitYPx = 1028f
            LogBuffer.i(
                "CWBridge",
                "bridge running invoke=on logcat=${logcatReader.hasPermission()}",
            )
            LogBuffer.i("CWBridge", "stays idle while Roblox is closed; watches invoke| in logcat")
            LogBuffer.i("CWBridge", "paste defaults focus=50,50 submit=730,1028 — change with invoke|focus / submit")
            if (!logcatReader.hasPermission()) {
                LogBuffer.w("CWBridge", "without READ_LOGS, only in-app test invokes work — grant via ADB")
            }
        }
        refreshUi()
    }

    private fun requireService(): TapService? {
        val service = TapService.instance
        if (service == null) {
            Toast.makeText(this, "CWBridge Tap is not connected", Toast.LENGTH_SHORT).show()
            LogBuffer.w("A11y", "tap requested but service offline")
        }
        return service
    }

    private fun performTapByText() {
        val query = binding.tapQuery.text?.toString().orEmpty()
        val service = requireService() ?: return
        val ok = service.clickByText(query)
        Toast.makeText(
            this,
            if (ok) "Tapped \"$query\"" else "No match for \"$query\" (use %/px for Roblox)",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun performTapPercent() {
        val service = requireService() ?: return
        val x = binding.tapXPercent.text?.toString()?.toFloatOrNull()
        val y = binding.tapYPercent.text?.toString()?.toFloatOrNull()
        if (x == null || y == null) {
            Toast.makeText(this, "Enter X% and Y% (0–100)", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = service.clickAtPercent(x, y)
        Toast.makeText(
            this,
            if (ok) "Tapped ${x}% ${y}%" else "Gesture failed",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun performTapPx() {
        val service = requireService() ?: return
        val x = binding.tapXPx.text?.toString()?.toFloatOrNull()
        val y = binding.tapYPx.text?.toString()?.toFloatOrNull()
        if (x == null || y == null) {
            Toast.makeText(this, "Enter X and Y pixels", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = service.clickAt(x, y)
        Toast.makeText(
            this,
            if (ok) "Tapped px ($x, $y)" else "Gesture failed",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun showAdbGrantHint() {
        val pkg = packageName
        MaterialAlertDialogBuilder(this)
            .setTitle("Grant READ_LOGS")
            .setMessage(
                "Required to see Roblox FLog invoke| lines.\n\n" +
                    "adb shell pm grant $pkg android.permission.READ_LOGS\n\n" +
                    "Without it, only the process-local buffer works.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun refreshUi() {
        val a11yOn = isAccessibilityEnabled()
        binding.a11yState.text = if (a11yOn) "on" else "off"
        binding.a11yState.setTextColor(
            ContextCompat.getColor(this, if (a11yOn) R.color.ok else R.color.danger),
        )

        val logsOn = logcatReader.hasPermission()
        binding.logcatState.text = if (logsOn) "granted" else "not granted (ADB)"
        binding.logcatState.setTextColor(
            ContextCompat.getColor(this, if (logsOn) R.color.ok else R.color.warn),
        )

        when {
            !bridgeRunning -> {
                binding.statusPill.text = getString(R.string.status_stopped)
                binding.statusPill.setTextColor(ContextCompat.getColor(this, R.color.muted))
                binding.statusDetail.text = "Enable CWBridge Tap, then start the bridge."
                binding.btnStartStop.text = "Start"
            }
            else -> {
                binding.statusPill.text = getString(R.string.status_running)
                binding.statusPill.setTextColor(ContextCompat.getColor(this, R.color.ok))
                binding.statusDetail.text =
                    "invoke| engine on. Roblox needs READ_LOGS. Try invoke|help"
                binding.btnStartStop.text = "Stop"
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (TapService.isConnected()) return true
        val expected = ComponentName(this, TapService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun appendLog(line: LogBuffer.Line) {
        val row = "${line.ts} ${line.level} ${line.tag}: ${line.msg}\n"
        binding.logView.append(row)
    }
}
