package com.cwbridge.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cwbridge.helper.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileFilter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pusher: OtgAdbPusher
    private var selected: UsbDevice? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                OtgAdbPusher.ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (ok && device != null) {
                        log("USB permission granted for ${device.deviceName}")
                        selected = device
                        refreshUsb()
                    } else {
                        log("USB permission denied")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    log("USB device attached")
                    refreshUsb()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    log("USB device detached")
                    selected = null
                    refreshUsb()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pusher = OtgAdbPusher(this)

        binding.btnRefreshUsb.setOnClickListener { refreshUsb() }
        binding.btnPush.setOnClickListener { startPush() }
        binding.btnResetKeys.setOnClickListener {
            pusher.resetAdbKeys { msg -> log(msg) }
            Toast.makeText(this, "ADB keys cleared", Toast.LENGTH_SHORT).show()
        }
        binding.btnDiagnostics.setOnClickListener { runDiagnostics() }
        binding.btnGrantLogs.setOnClickListener { grantLogsOnly() }
        binding.btnLaunchTarget.setOnClickListener { launchTarget() }
        binding.btnStartShizuku.setOnClickListener { startShizuku() }

        val filter = IntentFilter().apply {
            addAction(OtgAdbPusher.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        refreshUsb()
        log("Helper 1.0.7 — Start Shizuku over OTG ADB")
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun requireTarget(): UsbDevice? {
        val device = selected
        if (device == null) {
            Toast.makeText(this, "Connect target device via OTG first", Toast.LENGTH_SHORT).show()
            refreshUsb()
            return null
        }
        if (!pusher.hasPermission(device)) {
            pusher.requestPermission(device)
            Toast.makeText(this, "Allow USB access, then try again", Toast.LENGTH_LONG).show()
            return null
        }
        return device
    }

    private fun refreshUsb() {
        val devices = pusher.listDevices()
        if (devices.isEmpty()) {
            selected = null
            binding.usbState.text = "USB: no device — plug OTG cable to target"
            binding.usbState.setTextColor(0xFFC4A574.toInt())
            log("No USB devices")
            return
        }
        val device = devices.first()
        selected = device
        if (!pusher.hasPermission(device)) {
            binding.usbState.text = "USB: ${device.deviceName} (need permission)"
            binding.usbState.setTextColor(0xFFC4A574.toInt())
            log("Requesting USB permission…")
            pusher.requestPermission(device)
        } else {
            binding.usbState.text = "USB: ${device.deviceName} ready"
            binding.usbState.setTextColor(0xFF8FAD86.toInt())
            log("USB ready: vid=${device.vendorId} pid=${device.productId}")
        }
    }

    private fun startPush() {
        val device = requireTarget() ?: return
        binding.btnPush.isEnabled = false
        lifecycleScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    ReleaseDownloader.downloadLatestCwbridge(File(cacheDir, "apk-cache")) { msg ->
                        runOnUiThread { log(msg) }
                    }.file
                }
                log("APK ready: ${apk.absolutePath} (${apk.length()} bytes)")
                withContext(Dispatchers.IO) {
                    pusher.push(apk, device) { msg -> runOnUiThread { log(msg) } }
                }
                log("SUCCESS — CWBridge updated + READ_LOGS granted")
                Toast.makeText(this@MainActivity, "Success", Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                log("FAIL: ${t.message}")
                Toast.makeText(this@MainActivity, "Failed: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnPush.isEnabled = true
            }
        }
    }

    private fun grantLogsOnly() {
        val device = requireTarget() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val out = pusher.grantReadLogs(device) { msg -> runOnUiThread { log(msg) } }
                withContext(Dispatchers.Main) {
                    log("grant result: ${out.ifBlank { "ok" }}")
                    Toast.makeText(this@MainActivity, "READ_LOGS grant sent", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { log("grant FAIL: ${t.message}") }
            }
        }
    }

    private fun startShizuku() {
        val device = requireTarget() ?: return
        binding.btnStartShizuku.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { log("—— Start Shizuku ——") }
                val out = pusher.startShizuku(device) { msg -> runOnUiThread { log(msg) } }
                withContext(Dispatchers.Main) {
                    log("Shizuku start finished")
                    Toast.makeText(
                        this@MainActivity,
                        "Shizuku start sent — check Shizuku app on target",
                        Toast.LENGTH_LONG,
                    ).show()
                    if (out.isNotBlank()) log("raw: ${out.take(300)}")
                }
            } catch (e: OtgAdbPusher.ShizukuNotInstalledException) {
                withContext(Dispatchers.Main) {
                    log("FAIL: ${e.message}")
                    showShizukuInstallHelp()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    log("Shizuku FAIL: ${t.message}")
                    Toast.makeText(this@MainActivity, "Failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { binding.btnStartShizuku.isEnabled = true }
            }
        }
    }

    private fun showShizukuInstallHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Shizuku not installed")
            .setMessage(
                "Install Shizuku on the *target* device, open it once (so start.sh is created), " +
                    "then come back and tap Start Shizuku.\n\n" +
                    "Play Store / GitHub: package moe.shizuku.privileged.api",
            )
            .setPositiveButton("Open Play Store") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=${OtgAdbPusher.SHIZUKU_PKG}"),
                        ),
                    )
                } catch (_: Exception) {
                    Toast.makeText(this, "Open Play Store and search Shizuku", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("GitHub releases") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/RikkaApps/Shizuku/releases"),
                        ),
                    )
                } catch (_: Exception) {}
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun launchTarget() {
        val device = requireTarget() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val out = pusher.launchTarget(device) { msg -> runOnUiThread { log(msg) } }
                withContext(Dispatchers.Main) { log("launch: ${out.take(200)}") }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { log("launch FAIL: ${t.message}") }
            }
        }
    }

    private fun runDiagnostics() {
        lifecycleScope.launch(Dispatchers.IO) {
            log("—— DIAGNOSTICS ——")
            log("(Install check runs on the USB *target* device, not this phone)")

            val device = selected
            if (device == null || !pusher.hasPermission(device)) {
                withContext(Dispatchers.Main) {
                    log("[WARN] No USB target with permission — connect device & allow USB")
                }
            } else {
                try {
                    val installed = pusher.isTargetInstalled(device) { msg ->
                        runOnUiThread { log(msg) }
                    }
                    withContext(Dispatchers.Main) {
                        if (installed) {
                            log("[OK] Main app installed on target (${OtgAdbPusher.TARGET_PKG})")
                        } else {
                            log("[FAIL] Main app NOT on target (${OtgAdbPusher.TARGET_PKG})")
                        }
                    }
                    val grantProbe = pusher.shellOnDevice(
                        device,
                        "dumpsys package ${OtgAdbPusher.TARGET_PKG} | grep -i READ_LOGS || true",
                    ) { msg -> runOnUiThread { log(msg) } }
                    withContext(Dispatchers.Main) {
                        log("READ_LOGS probe: ${grantProbe.trim().ifBlank { "(no line)" }.take(200)}")
                    }
                    val shizukuPath = pusher.shellOnDevice(
                        device,
                        "pm path ${OtgAdbPusher.SHIZUKU_PKG}",
                    ) { msg -> runOnUiThread { log(msg) } }
                    withContext(Dispatchers.Main) {
                        if (shizukuPath.contains("package:")) {
                            log("[OK] Shizuku installed on target")
                        } else {
                            log("[WARN] Shizuku NOT installed on target")
                        }
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        log("[FAIL] Target check: ${t.message}")
                    }
                }
            }

            withContext(Dispatchers.Main) {
                val usbManager = getSystemService(USB_SERVICE) as UsbManager
                val deviceList = usbManager.deviceList
                if (deviceList.isNotEmpty()) {
                    log("[OK] USB device detected: ${deviceList.values.first().deviceName}")
                    val d = deviceList.values.first()
                    if (pusher.hasPermission(d)) log("[OK] USB permission granted")
                    else log("[WARN] USB permission NOT granted")
                } else {
                    log("[WARN] No USB devices connected")
                }

                val adbDir = File(filesDir, "adbkey")
                if (adbDir.exists()) {
                    val keyFiles = adbDir.listFiles(
                        FileFilter { f -> f.name.contains("adbkey") },
                    )
                    if (keyFiles != null && keyFiles.isNotEmpty()) {
                        log("[OK] ADB keys exist (${keyFiles.size} files)")
                    } else {
                        log("[WARN] No ADB keys — try Reset ADB keys")
                    }
                } else {
                    log("[WARN] ADB key dir missing")
                }
            }

            withContext(Dispatchers.IO) {
                try {
                    val release = ReleaseDownloader.getLatestRelease()
                    if (release != null) {
                        log("[OK] GitHub releases (latest: ${release.tag_name})")
                        val apkAsset = ReleaseDownloader.findApkAsset(release)
                        if (apkAsset != null) log("[OK] APK asset: ${apkAsset.name}")
                        else log("[WARN] No APK asset in release")
                    } else {
                        log("[FAIL] Cannot reach GitHub releases")
                    }
                } catch (t: Throwable) {
                    log("[FAIL] GitHub check: ${t.message}")
                }
            }

            withContext(Dispatchers.Main) {
                log("—— DIAGNOSTICS COMPLETE ——")
            }
        }
    }

    private fun log(msg: String) {
        binding.logView.append(msg + "\n")
        binding.logScroll.post {
            binding.logScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
