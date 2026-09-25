package com.cwbridge.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

        val filter = IntentFilter().apply {
            addAction(OtgAdbPusher.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        refreshUsb()
        log("Helper 1.0.1 — if stuck on ADB connect, Reset keys + revoke on tablet")
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun refreshUsb() {
        val devices = pusher.listDevices()
        if (devices.isEmpty()) {
            selected = null
            binding.usbState.text = "USB: no device — plug OTG tablet"
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
                log("—— push start ——")
                val apk = withContext(Dispatchers.IO) {
                    ReleaseDownloader.downloadLatestCwbridge(cacheDir) { msg ->
                        runOnUiThread { log(msg) }
                    }.file
                }
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

    private fun log(msg: String) {
        binding.logView.append(msg + "\n")
    }
}
