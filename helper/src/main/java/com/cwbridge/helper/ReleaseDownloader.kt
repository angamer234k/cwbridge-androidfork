package com.cwbridge.helper

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Pulls the latest CWBridge debug APK from GitHub Releases. */
object ReleaseDownloader {

    private const val OWNER = "angamer234k"
    private const val REPO = "cwbridge-androidfork"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class Result(val file: File, val tag: String, val assetName: String)

    fun downloadLatestCwbridge(destDir: File, log: (String) -> Unit): Result {
        destDir.mkdirs()
        log("Fetching $API")
        val metaReq = Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CWBridge-Helper")
            .build()
        client.newCall(metaReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("GitHub API ${resp.code}: no release yet? Publish one first.")
            }
            val body = resp.body?.string() ?: throw IllegalStateException("empty release body")
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "unknown")
            val assets = json.getJSONArray("assets")
            val asset = pickAsset(assets)
                ?: throw IllegalStateException("No cwbridge APK asset on release $tag")
            val name = asset.getString("name")
            val url = asset.getString("browser_download_url")
            log("Release $tag → $name")
            val out = File(destDir, name)
            val apkReq = Request.Builder()
                .url(url)
                .header("User-Agent", "CWBridge-Helper")
                .build()
            client.newCall(apkReq).execute().use { apkResp ->
                if (!apkResp.isSuccessful) throw IllegalStateException("download HTTP ${apkResp.code}")
                apkResp.body?.byteStream()?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("empty apk body")
            }
            log("Saved ${out.absolutePath} (${out.length()} bytes)")
            return Result(out, tag, name)
        }
    }

    private fun pickAsset(assets: JSONArray): JSONObject? {
        var fallback: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name").lowercase()
            if (!name.endsWith(".apk")) continue
            if (name.contains("helper")) continue
            if (name.contains("cwbridge")) return a
            if (fallback == null) fallback = a
        }
        return fallback
    }
}
