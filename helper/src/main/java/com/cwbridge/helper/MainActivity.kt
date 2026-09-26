package com.cwbridge.helper

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
                        logTip("On this phone: allow USB access when prompted. Unplug/replug if no prompt.")
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
        binding.btnResetKeys.setOnClickListener { resetKeysWithConfirm() }
        binding.btnDiagnostics.setOnClickListener { runDiagnostics() }
        binding.btnGrantLogs.setOnClickListener { grantLogsOnly() }
        binding.btnLaunchTarget.setOnClickListener { launchTarget() }
        binding.btnStartShizuku.setOnClickListener { startShizuku() }
        binding.btnHelp.setOnClickListener { showHelp() }
        binding.btnCopyLogs.setOnClickListener { copyLogs() }
        binding.btnShareLogs.setOnClickListener { shareLogs() }
        binding.btnClearLogs.setOnClickListener {
            binding.logView.text = ""
            log("Log cleared.")
        }

        val filter = IntentFilter().apply {
            addAction(OtgAdbPusher.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        refreshUsb()
        log("Helper 1.1.2 — crash-safe logging + diagnostics")
        log("Stuck? Tap Help / common fixes, or Run diagnostics.")
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun requireTarget(): UsbDevice? {
        val device = selected
        if (device == null) {
            Toast.makeText(this, "Connect target via OTG first", Toast.LENGTH_SHORT).show()
            log("No target device.")
            logTip("1) Target: Developer options → USB debugging ON\n2) Use a data OTG cable (not charge-only)\n3) This phone = USB host\n4) Tap Refresh USB")
            refreshUsb()
            return null
        }
        if (!pusher.hasPermission(device)) {
            pusher.requestPermission(device)
            Toast.makeText(this, "Allow USB access, then try again", Toast.LENGTH_LONG).show()
            logTip("Accept the USB permission dialog on THIS phone.")
            return null
        }
        return device
    }

    private fun refreshUsb() {
        val devices = pusher.listDevices()
        if (devices.isEmpty()) {
            selected = null
            binding.usbState.text = "USB: no device — plug OTG to target"
            binding.usbState.setTextColor(0xFFC4A574.toInt())
            log("No USB devices")
            logTip("Cable must support data. Target needs USB debugging. Some OEMs need USB debugging (Security settings).")
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
                log("—— Push update ——")
                val apk = withContext(Dispatchers.IO) {
                    ReleaseDownloader.downloadLatestCwbridge(File(cacheDir, "apk-cache")) { msg ->
                        log(msg)
                    }.file
                }
                log("APK ready: ${apk.absolutePath} (${apk.length()} bytes)")
                withContext(Dispatchers.IO) {
                    pusher.push(apk, device) { msg -> log(msg) }
                }
                log("SUCCESS — CWBridge updated + READ_LOGS granted")
                logTip("On target: open CWBridge → enable Accessibility (CWBridge Tap) → Start bridge.")
                Toast.makeText(this@MainActivity, "Success", Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                handleFailure("Push", t)
            } finally {
                binding.btnPush.isEnabled = true
            }
        }
    }

    private fun grantLogsOnly() {
        val device = requireTarget() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("—— Grant READ_LOGS ——")
                val out = pusher.grantReadLogs(device) { msg -> log(msg) }
                withContext(Dispatchers.Main) {
                    log("grant result: ${out.ifBlank { "ok" }}")
                    Toast.makeText(this@MainActivity, "READ_LOGS grant sent", Toast.LENGTH_SHORT).show()
                    logTip("In CWBridge on target, logcat line should say OK. Then Start bridge.")
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { handleFailure("Grant READ_LOGS", t) }
            }
        }
    }

    private fun startShizuku() {
        val device = requireTarget() ?: return
        binding.btnStartShizuku.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("—— Start Shizuku ——")
                val out = pusher.startShizuku(device) { msg -> log(msg) }
                withContext(Dispatchers.Main) {
                    log("Shizuku start finished")
                    Toast.makeText(
                        this@MainActivity,
                        "Shizuku start sent — check Shizuku on target",
                        Toast.LENGTH_LONG,
                    ).show()
                    if (out.isNotBlank()) log("raw: ${out.take(300)}")
                    logTip("Open Shizuku on target — status should say service is running. Survives until reboot.")
                }
            } catch (e: OtgAdbPusher.ShizukuNotInstalledException) {
                withContext(Dispatchers.Main) {
                    log("FAIL: ${e.message}")
                    showShizukuInstallHelp()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { handleFailure("Start Shizuku", t) }
            } finally {
                withContext(Dispatchers.Main) { binding.btnStartShizuku.isEnabled = true }
            }
        }
    }

    private fun showShizukuInstallHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Shizuku not installed")
            .setMessage(
                "Install Shizuku on the *target* device, open it once (creates start.sh), " +
                    "then tap Start Shizuku again.\n\n" +
                    "Package: moe.shizuku.privileged.api",
            )
            .setPositiveButton("Play Store") { _, _ ->
                openUrl("https://play.google.com/store/apps/details?id=${OtgAdbPusher.SHIZUKU_PKG}")
            }
            .setNeutralButton("GitHub") { _, _ ->
                openUrl("https://github.com/RikkaApps/Shizuku/releases")
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun launchTarget() {
        val device = requireTarget() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("—— Launch CWBridge on target ——")
                val out = pusher.launchTarget(device) { msg -> log(msg) }
                withContext(Dispatchers.Main) {
                    log("launch: ${out.take(200)}")
                    logTip("If nothing opens: install via Push update first, or check package on diagnostics.")
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { handleFailure("Launch", t) }
            }
        }
    }

    private fun resetKeysWithConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset ADB keys?")
            .setMessage(
                "Clears this helper ADB keypair. On the *target*, also:\n" +
                    "Developer options → Revoke USB debugging authorizations.\n\n" +
                    "Then unplug, replug, and accept the RSA prompt again.",
            )
            .setPositiveButton("Reset") { _, _ ->
                pusher.resetAdbKeys { msg -> log(msg) }
                Toast.makeText(this, "ADB keys cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHelp() {
        val items = arrayOf(
            "Setup checklist",
            "USB / OTG not detected",
            "ADB timeout / unauthorized",
            "Push or install failed",
            "READ_LOGS / logcat",
            "Shizuku",
            "What CWBridge needs after install",
            "Open GitHub releases",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Help / common fixes")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showHelpDetail(
                        "Setup checklist",
                        "1. Install this Helper on the phone with the OTG cable.\n" +
                            "2. On TARGET: enable Developer options + USB debugging.\n" +
                            "3. Connect phone (host) to target with a data cable.\n" +
                            "4. Accept USB permission on this phone.\n" +
                            "5. Accept USB debugging on the TARGET.\n" +
                            "6. Tap Push update.\n" +
                            "7. On target: enable CWBridge Tap, Start bridge.",
                    )
                    1 -> showHelpDetail("USB / OTG not detected", "Use a data cable. This phone = USB host. Unlock target. Tap Refresh USB.")
                    2 -> showHelpDetail("ADB timeout / unauthorized", "Unlock target, accept RSA prompt, or Reset ADB keys + Revoke authorizations on target.")
                    3 -> showHelpDetail("Push or install failed", "Run diagnostics. Free space on target. Internet on this phone for GitHub download.")
                    4 -> showHelpDetail("READ_LOGS / logcat", "After Push, READ_LOGS is granted. Or Grant READ_LOGS only. Package: com.cwbridge.android.debug")
                    5 -> showHelpDetail("Shizuku", "Install Shizuku on TARGET, open once. Helper → Start Shizuku.")
                    6 -> showHelpDetail("After install on target", "Accessibility on, overlay on, logcat OK, Start bridge. Green = listening.")
                    7 -> openUrl("https://github.com/angamer234k/cwbridge-androidfork/releases")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showHelpDetail(title: String, body: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                copyText("$title\n\n$body")
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .show()
        log("Help: $title")
    }

    private fun runDiagnostics() {
        try { binding.btnDiagnostics.isEnabled = false } catch (_: Throwable) {}
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("—— DIAGNOSTICS ——")
                log("Host: ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}")
                log("(Target checks need USB permission)")

                val device = selected
                if (device == null || !pusher.hasPermission(device)) {
                    log("[WARN] No USB target with permission")
                    logTip("Connect OTG, Refresh USB, allow permission, run diagnostics again.")
                } else {
                    try {
                        val installed = pusher.isTargetInstalled(device) { msg -> log(msg) }
                        if (installed) {
                            log("[OK] Main app on target (${OtgAdbPusher.TARGET_PKG})")
                        } else {
                            log("[FAIL] Main app NOT on target")
                            logTip("Tap Push update to install.")
                        }
                        val grantProbe = pusher.shellOnDevice(
                            device,
                            "dumpsys package ${OtgAdbPusher.TARGET_PKG} | grep -i READ_LOGS || true",
                        ) { msg -> log(msg) }
                        log("READ_LOGS probe: ${grantProbe.trim().ifBlank { "(no line)" }.take(200)}")
                        if (grantProbe.contains("granted=true", ignoreCase = true) ||
                            grantProbe.contains("granted", ignoreCase = true)
                        ) {
                            log("[OK] READ_LOGS looks granted")
                        } else if (installed) {
                            log("[WARN] READ_LOGS unclear — try Grant READ_LOGS only")
                        }
                        val shizukuPath = pusher.shellOnDevice(
                            device,
                            "pm path ${OtgAdbPusher.SHIZUKU_PKG}",
                        ) { msg -> log(msg) }
                        if (shizukuPath.contains("package:")) {
                            log("[OK] Shizuku installed on target")
                        } else {
                            log("[WARN] Shizuku NOT on target (optional)")
                        }
                        val props = pusher.shellOnDevice(
                            device,
                            "getprop ro.product.model; getprop ro.build.version.release",
                        ) { msg -> log(msg) }
                        log("Target: ${props.trim().replace("\n", " / ").take(80)}")
                    } catch (t: Throwable) {
                        log("[FAIL] Target check: ${t.message}")
                        suggestForError(t.message ?: "")
                    }
                }

                withContext(Dispatchers.Main) {
                    try {
                        val usbManager = getSystemService(USB_SERVICE) as UsbManager
                        val deviceList = usbManager.deviceList
                        if (deviceList.isNotEmpty()) {
                            log("[OK] USB device: ${deviceList.values.first().deviceName}")
                            val d = deviceList.values.first()
                            if (pusher.hasPermission(d)) log("[OK] USB permission granted")
                            else log("[WARN] USB permission NOT granted — accept the prompt")
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
                                log("[WARN] No ADB keys — will generate on next connect")
                            }
                        } else {
                            log("[WARN] ADB key dir missing — will create on connect")
                        }
                    } catch (t: Throwable) {
                        log("[WARN] Host USB/key check: ${t.message}")
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
                            logTip("Check internet on this phone. Downloads need network.")
                        }
                    } catch (t: Throwable) {
                        log("[FAIL] GitHub check: ${t.message}")
                        logTip("Wi-Fi/data required to download the main APK for Push update.")
                    }
                }

                log("—— DIAGNOSTICS COMPLETE ——")
                log("Still stuck? Copy/Share logs and open Help / common fixes.")
            } catch (t: Throwable) {
                handleFailure("Diagnostics", t)
            } finally {
                withContext(Dispatchers.Main) {
                    try { binding.btnDiagnostics.isEnabled = true } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun handleFailure(action: String, t: Throwable) {
        val msg = t.message ?: t.javaClass.simpleName
        log("FAIL ($action): $msg")
        suggestForError(msg)
        try {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, "$action failed — see log tips", Toast.LENGTH_LONG).show()
            }
        } catch (_: Throwable) {}
    }

    private fun suggestForError(msg: String) {
        val m = msg.lowercase()
        when {
            m.contains("permission") || m.contains("usb") ->
                logTip("Allow USB on this phone. Unplug/replug. Target: USB debugging ON.")
            m.contains("timeout") || m.contains("handshake") || m.contains("unauthorized") ->
                logTip("Unlock target, accept RSA prompt, or Reset ADB keys + Revoke authorizations on target.")
            m.contains("stall") || m.contains("install") ->
                logTip("Retry Push. Free space on target. Stable cable. Helper auto-retries once.")
            m.contains("network") || m.contains("github") || m.contains("download") || m.contains("unable to resolve") ->
                logTip("This phone needs internet to download the APK from GitHub Releases.")
            m.contains("interface") || m.contains("adb") ->
                logTip("Target may not be in ADB mode. Enable USB debugging; try another cable.")
            m.contains("shizuku") ->
                logTip("Install Shizuku on target, open once, then Start Shizuku again.")
            m.contains("thread") || m.contains("hierarchy") || m.contains("view") ->
                logTip("Internal UI threading glitch — update helper; if it persists, share logs.")
            else ->
                logTip("Run diagnostics. Open Help / common fixes. Copy logs if you need support.")
        }
    }

    private fun logTip(text: String) {
        log("→ TIP: $text")
    }

    private fun copyLogs() {
        copyText(binding.logView.text?.toString().orEmpty())
        Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val text = binding.logView.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "CWBridge Helper logs")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share logs"))
    }

    private fun copyText(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cwbridge-helper", text))
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
        }
    }

    /** Always safe from any thread — never crash on view access. */
    private fun log(msg: String) {
        val line = msg
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendLogLine(line)
        } else {
            try {
                runOnUiThread { appendLogLine(line) }
            } catch (_: Throwable) {
                Handler(Looper.getMainLooper()).post { appendLogLine(line) }
            }
        }
    }

    private fun appendLogLine(msg: String) {
        try {
            if (!::binding.isInitialized) return
            if (isFinishing || isDestroyed) return
            binding.logView.append(msg + "\n")
            binding.logScroll.post {
                try {
                    binding.logScroll.fullScroll(ScrollView.FOCUS_DOWN)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            // never crash the helper over a log line
        }
    }
}
