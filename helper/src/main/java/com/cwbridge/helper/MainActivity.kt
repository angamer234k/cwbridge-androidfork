package com.cwbridge.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cwbridge.helper.databinding.ActivityMainBinding
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

        val filter = IntentFilter().apply {
            addAction(OtgAdbPusher.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        refreshUsb()
        log("Helper 1.0.5 \u2014 APK cache + stall reconnect + diagnostics")
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun refreshUsb() {
        val devices = pusher.listDevices()
        if (devices.isEmpty()) {
            selected = null
            binding.usbState.text = "USB: no device \u2014 plug OTG tablet"
            binding.usbState.setTextColor(0xFFC4A574.toInt())
            log("No USB devices")
            return
        }
        val device = devices.first()
        selected = device
        if (!pusher.hasPermission(device)) {
            binding.usbState.text = "USB: ${device.deviceName} (need permission)"
            binding.usbState.setTextColor(0xFFC4A574.toInt())
            log("Requesting USB permission\u2026")
            pusher.requestPermission(device)
        } else {
            binding.usbState.text = "USB: ${device.deviceName} ready"
            binding.usbState.setTextColor(0xFF8FAD86.toInt())
            log("USB ready: vid=${device.vendorId} pid=${device.productId}")
        }
    }

    private fun startPush() {
        val device = selected
        if (device == null) {
            Toast.makeText(this, "Connect tablet via OTG first", Toast.LENGTH_SHORT).show()
            refreshUsb()
            return
        }
        if (!pusher.hasPermission(device)) {
            pusher.requestPermission(device)
            Toast.makeText(this, "Allow USB access, then tap Push again", Toast.LENGTH_LONG).show()
            return
        }
        binding.btnPush.isEnabled = false
        lifecycleScope.launch {
            try {
                log("\u2014\u2014 push start \u2014\u2014")
                val apkCache = File(cacheDir, "apk-cache")
                val apk = withContext(Dispatchers.IO) {
                    ReleaseDownloader.downloadLatestCwbridge(apkCache) { msg ->
                        runOnUiThread { log(msg) }
                    }.file
                }
                withContext(Dispatchers.IO) {
                    pusher.push(apk, device) { msg -> runOnUiThread { log(msg) } }
                }
                log("SUCCESS \u2014 CWBridge updated + READ_LOGS granted")
                Toast.makeText(this@MainActivity, "Success", Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                log("FAIL: ${t.message}")
                Toast.makeText(this@MainActivity, "Failed: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnPush.isEnabled = true
            }
        }
    }

    private fun runDiagnostics() {
        lifecycleScope.launch(Dispatchers.IO) {
            log("\u2014\u2014 DIAGNOSTICS \u2014\u2014")
            
            // Check if main app is installed
            val packageName = "com.cwbridge.android.debug"
            val isInstalled = try {
                packageManager.getPackageInfo(packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            
            withContext(Dispatchers.Main) {
                if (isInstalled) {
                    log("[OK] Main app is installed ($packageName)")
                } else {
                    log("[FAIL] Main app NOT installed ($packageName)")
                }
            }
            
            // Check USB debugging authorization
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList
            
            withContext(Dispatchers.Main) {
                if (deviceList.isNotEmpty()) {
                    log("[OK] USB device detected: ${deviceList.values.first().deviceName}")
                    val device = deviceList.values.first()
                    val hasPerm = pusher.hasPermission(device)
                    if (hasPerm) {
                        log("[OK] USB permission granted")
                    } else {
                        log("[WARN] USB permission NOT granted - tap Refresh USB")
                    }
                } else {
                    log("[WARN] No USB devices connected")
                }
            }
            
            // Check ADB keys
            withContext(Dispatchers.Main) {
                val adbDir = File("${cacheDir.parent}/adb")
                if (adbDir.exists()) {
                    val keyFiles = adbDir.listFiles(FileFilter { file -> file.name.endsWith(".pub") || file.name.endsWith(".key") })
                    if (keyFiles != null && keyFiles.isNotEmpty()) {
                        log("[OK] ADB keys exist (${keyFiles.size} files)")
                    } else {
                        log("[WARN] No ADB keys found - try Reset ADB keys")
                    }
                } else {
                    log("[WARN] ADB directory not found")
                }
            }
            
            // Check if we can download the APK
            withContext(Dispatchers.IO) {
                try {
                    val apkCache = File(cacheDir, "apk-cache")
                    apkCache.mkdirs()
                    val release = ReleaseDownloader.getLatestRelease()
                    if (release != null) {
                        log("[OK] Can reach GitHub releases (latest: ${release.tag_name})")
                        val apkAsset = ReleaseDownloader.findApkAsset(release)
                        if (apkAsset != null) {
                            log("[OK] APK asset found: ${apkAsset.name}")
                        } else {
                            log("[WARN] No APK asset in release")
                        }
                    } else {
                        log("[FAIL] Cannot reach GitHub releases")
                    }
                } catch (t: Throwable) {
                    log("[FAIL] GitHub check failed: ${t.message}")
                }
            }
            
            withContext(Dispatchers.Main) {
                log("\u2014\u2014 DIAGNOSTICS COMPLETE \u2014\u2014")
            }
        }
    }

    private fun log(msg: String) {
        binding.logView.append(msg + "\n")
    }
}
