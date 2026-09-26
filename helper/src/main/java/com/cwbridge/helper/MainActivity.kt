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
                        selected = device
                        runOnUiThread {
                            log("USB permission granted for ${device.deviceName}")
                            refreshUsb()
                        }
                    } else {
                        runOnUiThread {
                            log("USB permission denied")
                            logTip("On this phone: allow USB access when prompted. Unplug/replug if no prompt.")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    runOnUiThread {
                        log("USB device attached")
                        refreshUsb()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    selected = null
                    runOnUiThread {
                        log("USB device detached")
                        refreshUsb()
                    }
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
        log("Helper 1.1 — self-serve tools + smarter errors")
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
            logTip("Cable must support data. Target needs USB debugging. Some OEMs need ‘USB debugging (Security settings)’.")
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
                        runOnUiThread { log(msg) }
                    }.file
                }
                log("APK ready: ${apk.absolutePath} (${apk.length()} bytes)")
                withContext(Dispatchers.IO) {
                    pusher.push(apk, device) { msg -> runOnUiThread { log(msg) } }
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
                val out = pusher.grantReadLogs(device) { msg -> runOnUiThread { log(msg) } }
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
                withContext(Dispatchers.Main) { log("—— Start Shizuku ——") }
                val out = pusher.startShizuku(device) { msg -> runOnUiThread { log(msg) } }
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
                val out = pusher.launchTarget(device) { msg -> runOnUiThread { log(msg) } }
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
                "Clears this helper’s ADB keypair. On the *target*, also:\n" +
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
                            "3. Connect phone (host) ↔ OTG ↔ target with a data cable.\n" +
                            "4. Accept USB permission on this phone.\n" +
                            "5. Accept ‘Allow USB debugging’ on the TARGET (check Always allow).\n" +
                            "6. Tap Push update.\n" +
                            "7. On target: enable CWBridge Tap (Accessibility), Start bridge.",
                    )
                    1 -> showHelpDetail(
                        "USB / OTG not detected",
                        "• Use a data cable, not charge-only.\n" +
                            "• This phone must act as USB host (OTG).\n" +
                            "• Try another cable/port.\n" +
                            "• Unplug, reboot both devices, replug.\n" +
                            "• Some hubs don’t pass ADB — plug target directly.\n" +
                            "• Tap Refresh USB after connecting.",
                    )
                    2 -> showHelpDetail(
                        "ADB timeout / unauthorized",
                        "• Unlock the TARGET screen when connecting.\n" +
                            "• Accept the RSA / USB debugging dialog on TARGET.\n" +
                            "• Target → Revoke USB debugging authorizations, then reconnect.\n" +
                            "• Helper → Reset ADB keys, then reconnect.\n" +
                            "• Xiaomi/HyperOS: enable USB debugging (Security settings).",
                    )
                    3 -> showHelpDetail(
                        "Push or install failed",
                        "• Run diagnostics first.\n" +
                            "• Ensure target has free storage.\n" +
                            "• Uninstall an old CWBridge on target if signature conflicts.\n" +
                            "• Retry Push (helper auto-retries once on stall).\n" +
                            "• Check this phone has internet (downloads APK from GitHub).\n" +
                            "• Copy/Share logs if you need to report the issue.",
                    )
                    4 -> showHelpDetail(
                        "READ_LOGS / logcat",
                        "• After Push, READ_LOGS is granted automatically.\n" +
                            "• Or use Grant READ_LOGS only.\n" +
                            "• In CWBridge, status should show logcat OK.\n" +
                            "• Grant is on the TARGET package com.cwbridge.android.debug.",
                    )
                    5 -> showHelpDetail(
                        "Shizuku",
                        "• Install Shizuku on TARGET, open it once.\n" +
                            "• Helper → Start Shizuku (needs OTG ADB).\n" +
                            "• Confirm in Shizuku app that the service is running.\n" +
                            "• After reboot, start Shizuku again.",
                    )
                    6 -> showHelpDetail(
                        "After install on target",
                        "1. Open CWBridge.\n" +
                            "2. Settings → Accessibility → enable CWBridge Tap.\n" +
                            "3. Allow display over other apps (overlay).\n" +
                            "4. Confirm logcat OK (or re-run Grant READ_LOGS).\n" +
                            "5. Tap Start bridge.\n" +
                            "6. Overlay dot: green = listening, yellow = waiting, red = error.",
                    )
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
        lifecycleScope.launch(Dispatchers.IO) {
            log("—— DIAGNOSTICS ——")
            log("Host: ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}")
            log("(Target checks need USB permission)")

            val device = selected
            if (device == null || !pusher.hasPermission(device)) {
                withContext(Dispatchers.Main) {
                    log("[WARN] No USB target with permission")
                    logTip("Connect OTG, Refresh USB, allow permission, run diagnostics again.")
                }
            } else {
                try {
                    val installed = pusher.isTargetInstalled(device) { msg ->
                        runOnUiThread { log(msg) }
                    }
                    withContext(Dispatchers.Main) {
                        if (installed) {
                            log("[OK] Main app on target (${OtgAdbPusher.TARGET_PKG})")
                        } else {
                            log("[FAIL] Main app NOT on target")
                            logTip("Tap Push update to install.")
                        }
                    }
                    val grantProbe = pusher.shellOnDevice(
                        device,
                        "dumpsys package ${OtgAdbPusher.TARGET_PKG} | grep -i READ_LOGS || true",
                    ) { msg -> runOnUiThread { log(msg) } }
                    withContext(Dispatchers.Main) {
                        log("READ_LOGS probe: ${grantProbe.trim().ifBlank { "(no line)" }.take(200)}")
                        if (grantProbe.contains("granted=true", ignoreCase = true) ||
                            grantProbe.contains("granted", ignoreCase = true)
                        ) {
                            log("[OK] READ_LOGS looks granted")
                        } else if (installed) {
                            log("[WARN] READ_LOGS unclear — try Grant READ_LOGS only")
                        }
                    }
                    val shizukuPath = pusher.shellOnDevice(
                        device,
                        "pm path ${OtgAdbPusher.SHIZUKU_PKG}",
                    ) { msg -> runOnUiThread { log(msg) } }
                    withContext(Dispatchers.Main) {
                        if (shizukuPath.contains("package:")) {
                            log("[OK] Shizuku installed on target")
                        } else {
                            log("[WARN] Shizuku NOT on target (optional)")
                        }
                    }
                    val props = pusher.shellOnDevice(
                        device,
                        "getprop ro.product.model; getprop ro.build.version.release",
                    ) { msg -> runOnUiThread { log(msg) } }
                    withContext(Dispatchers.Main) {
                        log("Target: ${props.trim().replace("\n", " / ").take(80)}")
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        log("[FAIL] Target check: ${t.message}")
                        suggestForError(t.message ?: "")
                    }
                }
            }

            withContext(Dispatchers.Main) {
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
                    logTip("Wi‑Fi/data required to download the main APK for Push update.")
                }
            }

            withContext(Dispatchers.Main) {
                log("—— DIAGNOSTICS COMPLETE ——")
                log("Still stuck? Copy/Share logs and open Help / common fixes.")
            }
        }
    }

    private fun handleFailure(action: String, t: Throwable) {
        val msg = t.message ?: t.javaClass.simpleName
        log("FAIL ($action): $msg")
        suggestForError(msg)
        Toast.makeText(this, "$action failed — see log tips", Toast.LENGTH_LONG).show()
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

    private fun log(msg: String) {
        runOnUiThread {
            binding.logView.append(msg + "\n")
            binding.logScroll.post {
                binding.logScroll.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }
}

