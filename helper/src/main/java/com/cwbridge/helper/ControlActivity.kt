package com.cwbridge.helper

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Full-screen view of a discovered bridge's existing web panel. */
class ControlActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        findViewById<TextView>(R.id.controlUrl).text = url
        findViewById<TextView>(R.id.btnClosePanel).setOnClickListener { finish() }
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
