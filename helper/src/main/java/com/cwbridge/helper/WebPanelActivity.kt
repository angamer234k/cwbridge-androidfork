package com.cwbridge.helper

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Full web UI for a discovered bridge (fallback from the native panel). */
class WebPanelActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_panel)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        findViewById<TextView>(R.id.webUrl).text = url
        findViewById<TextView>(R.id.btnCloseWeb).setOnClickListener { finish() }
        val web = findViewById<WebView>(R.id.panel)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        if (url.startsWith("http://") || url.startsWith("https://")) {
            web.loadUrl(url)
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
