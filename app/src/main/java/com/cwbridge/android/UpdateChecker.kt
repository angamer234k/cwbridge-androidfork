package com.cwbridge.android

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Checks for app updates from GitHub releases.
 * Only downloads and installs with explicit user permission.
 */
class UpdateChecker(
    private val context: Context
) {
    private val okHttpClient = OkHttpClient()
    private val gson = Gson()
    
    companion object {
        private const val GITHUB_REPO = "angamer234k/cwbridge-androidfork"
        private const val RELEASES_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    }

    data class GitHubRelease(
        val tag_name: String,
        val name: String,
        val body: String,
        val assets: List<GitHubAsset>,
        val published_at: String
    )

    data class GitHubAsset(
        val name: String,
        val browser_download_url: String
    )

    /** Outcome of a check, so the UI can tell "up to date" from "check failed". */
    sealed class Result {
        data class Available(val release: GitHubRelease, val latestVersion: String) : Result()
        object UpToDate : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Check GitHub for a newer release. Never throws — failures come back as
     * [Result.Failed] so the caller can show a real message.
     */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val currentVersion = getCurrentVersion()
        try {
            val request = Request.Builder()
                .url(RELEASES_API)
                .get()
                .addHeader("Accept", "application/vnd.github+json")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = "HTTP ${response.code}"
                    LogBuffer.e("UpdateChecker", "check failed: $reason")
                    return@withContext Result.Failed(reason)
                }

                val body = response.body?.string()
                    ?: return@withContext Result.Failed("empty response")

                val release = try {
                    gson.fromJson(body, GitHubRelease::class.java)
                } catch (t: Throwable) {
                    return@withContext Result.Failed("bad JSON: ${t.message}")
                } ?: return@withContext Result.Failed("no release data")

                if (release.tag_name.isBlank()) {
                    return@withContext Result.Failed("release has no tag")
                }

                val latestVersion = release.tag_name.removePrefix("v")
                LogBuffer.i("UpdateChecker", "latest=$latestVersion current=$currentVersion")

                return@withContext if (isNewerVersion(latestVersion, currentVersion)) {
                    Result.Available(release, latestVersion)
                } else {
                    LogBuffer.i("UpdateChecker", "up to date: $currentVersion")
                    Result.UpToDate
                }
            }
        } catch (t: Throwable) {
            val reason = t.message ?: t::class.java.simpleName
            LogBuffer.e("UpdateChecker", "check failed: $reason")
            Result.Failed(reason)
        }
    }

    /**
     * Pick the main app APK. Debug builds must not install the release APK over
     * a differently-signed debug build, so prefer a debug asset when we are one.
     */
    fun findApkAsset(release: GitHubRelease, preferDebug: Boolean = isDebugBuild()): GitHubAsset? {
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null

        // The release ships the helper alongside the main app, and both APKs
        // contain "debug". Offering the helper here would push a different
        // package (com.cwbridge.helper) at users of the main app, so exclude it.
        val mainApks = apks.filterNot { it.name.contains("helper", ignoreCase = true) }
        val pool = if (mainApks.isNotEmpty()) mainApks else apks

        val debug = pool.filter { it.name.contains("debug", ignoreCase = true) }
        return when {
            preferDebug && debug.isNotEmpty() -> debug.first()
            pool.any { it.name.contains("android", ignoreCase = true) } ->
                pool.first { it.name.contains("android", ignoreCase = true) }
            else -> pool.first()
        }
    }

    private fun isDebugBuild(): Boolean {
        return try {
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Compare version strings to see if latest is newer than current.
     *
     * Version names look like "2.10.5-android" in releases and
     * "2.10.5-android-debug" in debug builds, so non-numeric suffixes are
     * stripped before comparing. A bare "1.0.0" or anything unparseable falls
     * back to a semver-ish numeric comparison.
     */
    fun isNewerVersion(latest: String, current: String): Boolean {
        val a = numericParts(latest)
        val b = numericParts(current)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val pa = a.getOrNull(i) ?: 0
            val pb = b.getOrNull(i) ?: 0
            if (pa > pb) return true
            if (pa < pb) return false
        }
        return false
    }

    /** Keep only the leading dotted-numeric prefix: "2.10.5-android" -> [2,10,5]. */
    private fun numericParts(version: String): List<Int> {
        val core = version.trim().removePrefix("v")
        val head = core.takeWhile { it.isDigit() || it == '.' }
        return head.split('.').mapNotNull { it.toIntOrNull() }
    }

    /**
     * Show update dialog to user and request permission to download.
     * Posts to the main thread because this is called after a suspend check.
     */
    fun showUpdateDialog(release: GitHubRelease, apkAsset: GitHubAsset) {
        val versionName = release.tag_name
        val notes = release.body.orEmpty().take(300)
        val message = buildString {
            append("Version ").append(versionName).append(" is available.\n")
            if (notes.isNotBlank()) append("\n").append(notes)
        }
        onMain {
            android.app.AlertDialog.Builder(context)
                .setTitle("Update available")
                .setMessage(message)
                .setPositiveButton("Download") { _, _ -> requestDownloadPermission(apkAsset) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Run [block] on the main thread; safe to call from a background coroutine. */
    private fun onMain(block: () -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else handler.post(block)
    }

    /**
     * Request explicit permission before downloading
     */
    private fun requestDownloadPermission(apkAsset: GitHubAsset) {
        android.app.AlertDialog.Builder(context)
            .setTitle("Download Update")
            .setMessage("Download ${apkAsset.name}? This will use your mobile data.")
            .setPositiveButton("Confirm Download") { _, _ ->
                downloadAndInstall(apkAsset)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Download APK using the system DownloadManager, then prompt to install.
     * On Android 10+ setDestinationInExternalPublicDir can throw for apps
     * without legacy storage, so fall back to a plain cache destination.
     */
    private fun downloadAndInstall(apkAsset: GitHubAsset) {
        try {
            val request = DownloadManager.Request(Uri.parse(apkAsset.browser_download_url))
                .setTitle("CWBridge Update")
                .setDescription("Downloading ${apkAsset.name}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = try {
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    apkAsset.name,
                )
                downloadManager.enqueue(request)
            } catch (t: Throwable) {
                // Scoped storage refused the public dir — download without a target.
                LogBuffer.w("UpdateChecker", "public dir unavailable: ${t.message}")
                request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, apkAsset.name)
                downloadManager.enqueue(request)
            }

            LogBuffer.i("UpdateChecker", "Download started: ${apkAsset.name} (ID: $downloadId)")

            onMain {
                android.app.AlertDialog.Builder(context)
                    .setTitle("Download started")
                    .setMessage(
                        "The update will appear in your notifications.\n" +
                            "Tap it to install once the download finishes.",
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        } catch (t: Throwable) {
            val reason = t.message ?: t::class.java.simpleName
            LogBuffer.e("UpdateChecker", "Download failed: $reason")
            onMain {
                android.widget.Toast.makeText(
                    context,
                    "Download failed: $reason",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Current versionName, e.g. "2.10.5-android-debug". Null-safe on API 33+. */
    fun getCurrentVersion(): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "0"
        } catch (t: Throwable) {
            LogBuffer.w("UpdateChecker", "version lookup failed: ${t.message}")
            "0"
        }
    }
}
