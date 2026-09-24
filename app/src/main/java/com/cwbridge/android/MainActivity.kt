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
    private var bridgeRunning = false

    private val logListener: (LogBuffer.Line) -> Unit = { line ->
        runOnUiThread { appendLog(line) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logcatReader = LogcatReader(this)

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
        LogBuffer.i("CWBridge", "session start version=2.7.2-android")
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
        LogBuffer.removeListener(logListener)
        super.onDestroy()
    }

    private fun toggleBridge() {
        if (bridgeRunning) {
            bridgeRunning = false
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
            LogBuffer.i(
                "CWBridge",
                "bridge running services=weather,translate,fetch,playerinfo,datastore,qr logcat=${logcatReader.hasPermission()}",
            )
            LogBuffer.i("CWBridge", "stays idle while Roblox is closed; resumes when the window is attached")
            LogBuffer.i("CWBridge", "Roblox tip: use Tap % / Tap px — game UI has no a11y nodes")
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
        val xStr = binding.tapXPercent.text?.toString().orEmpty()
        val yStr = binding.tapYPercent.text?.toString().orEmpty()
        val x = xStr.toFloatOrNull()
        val y = yStr.toFloatOrNull()
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
        val xStr = binding.tapXPx.text?.toString().orEmpty()
        val yStr = binding.tapYPx.text?.toString().orEmpty()
        val x = xStr.toFloatOrNull()
        val y = yStr.toFloatOrNull()
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
                "READ_LOGS is a privileged permission.\n\n" +
                    "On a debug device:\n\n" +
                    "adb shell pm grant $pkg android.permission.READ_LOGS\n\n" +
                    "Without it, CWBridge still keeps a process-local log buffer.",
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
                    "Running. For Roblox use Tap % or Tap px — game UI has no a11y nodes."
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
