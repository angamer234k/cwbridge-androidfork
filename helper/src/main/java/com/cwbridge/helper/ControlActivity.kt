package com.cwbridge.helper

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native quick panel for a discovered CWBridge device.
 * Hits the same /api endpoints the web UI uses. Full panel still available.
 */
class ControlActivity : AppCompatActivity() {

    private lateinit var client: BridgeClient
    private lateinit var baseUrl: String
    private var pollJob: Job? = null

    private lateinit var statusText: TextView
    private lateinit var toastLine: TextView
    private lateinit var consoleView: TextView
    private lateinit var servicesList: LinearLayout
    private lateinit var shotView: ImageView
    private lateinit var invokeInput: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control)

        baseUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            Toast.makeText(this, "No bridge URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        client = BridgeClient(baseUrl)

        statusText = findViewById(R.id.statusText)
        toastLine = findViewById(R.id.toastLine)
        consoleView = findViewById(R.id.consoleView)
        servicesList = findViewById(R.id.servicesList)
        shotView = findViewById(R.id.shotView)
        invokeInput = findViewById(R.id.invokeInput)

        findViewById<TextView>(R.id.controlUrl).text = baseUrl
        findViewById<TextView>(R.id.btnClosePanel).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnFullPanel).setOnClickListener {
            startActivity(
                Intent(this, WebPanelActivity::class.java)
                    .putExtra(WebPanelActivity.EXTRA_URL, baseUrl),
            )
        }

        findViewById<MaterialButton>(R.id.btnToggleBridge).setOnClickListener {
            runAction { client.toggleBridge() }
        }
        findViewById<MaterialButton>(R.id.btnRestartRoblox).setOnClickListener {
            runAction { client.control("restart-roblox") }
        }
        findViewById<MaterialButton>(R.id.btnEnter).setOnClickListener {
            runAction { client.control("enter") }
        }
        findViewById<MaterialButton>(R.id.btnScreenshot).setOnClickListener { takeShot() }
        findViewById<MaterialButton>(R.id.btnInvoke).setOnClickListener { sendInvoke() }
        invokeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendInvoke()
                true
            } else {
                false
            }
        }

        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                refreshQuiet()
                delay(2000)
            }
        }
    }

    override fun onPause() {
        pollJob?.cancel()
        pollJob = null
        super.onPause()
    }

    private fun refreshAll() {
        lifecycleScope.launch {
            try {
                val status = withContext(Dispatchers.IO) { client.status() }
                val logs = withContext(Dispatchers.IO) { client.logs(36) }
                val services = withContext(Dispatchers.IO) { client.services() }
                bindStatus(status)
                consoleView.text = if (logs.isEmpty()) "(no lines)" else logs.joinToString("\n")
                bindServices(services)
            } catch (t: Throwable) {
                statusText.text = "Offline — ${t.message}"
                toastLine.text = "Cannot reach $baseUrl"
            }
        }
    }

    private suspend fun refreshQuiet() {
        try {
            val status = withContext(Dispatchers.IO) { client.status() }
            val logs = withContext(Dispatchers.IO) { client.logs(36) }
            bindStatus(status)
            consoleView.text = if (logs.isEmpty()) "(no lines)" else logs.joinToString("\n")
        } catch (_: Throwable) {
        }
    }

    private fun bindStatus(s: BridgeClient.Status) {
        val a11y = if (s.accessibility) "a11y ok" else "a11y off"
        val shizuku = if (s.shizuku) "Shizuku ok" else "Shizuku off"
        val cat = if (s.catwebReady) "CatWeb ready" else "CatWeb wait"
        statusText.text = "${s.state} · ${s.versionName}\n$a11y · $shizuku · $cat\n${s.detail}".trim()
    }

    private fun bindServices(list: List<BridgeClient.ServiceItem>) {
        servicesList.removeAllViews()
        if (list.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No services"
                setTextColor(0xFF8B909A.toInt())
                textSize = 13f
            }
            servicesList.addView(empty)
            return
        }
        val pad = (6 * resources.displayMetrics.density).toInt()
        for (svc in list) {
            val row = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                isAllCaps = false
                text = buildString {
                    append(svc.name)
                    if (!svc.enabled) append(" (off)")
                    append(" · run")
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = pad }
                setOnClickListener {
                    runAction { client.runService(svc.id) }
                }
            }
            servicesList.addView(row)
        }
    }

    private fun sendInvoke() {
        val raw = invokeInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        runAction { client.invoke(raw) }
    }

    private fun takeShot() {
        lifecycleScope.launch {
            try {
                toastLine.text = "Screenshot…"
                val bytes = withContext(Dispatchers.IO) { client.screenshotJpeg() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    shotView.setImageBitmap(bmp)
                    shotView.visibility = View.VISIBLE
                    toastLine.text = "Screenshot ${bytes.size} B"
                } else {
                    toastLine.text = "Bad image data"
                }
            } catch (t: Throwable) {
                toastLine.text = "Screenshot failed: ${t.message}"
            }
        }
    }

    private fun runAction(block: () -> String) {
        lifecycleScope.launch {
            try {
                val msg = withContext(Dispatchers.IO) { block() }
                toastLine.text = msg
                delay(300)
                refreshAll()
            } catch (t: Throwable) {
                toastLine.text = t.message ?: "failed"
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
