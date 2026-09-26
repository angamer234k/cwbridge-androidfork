package com.cwbridge.android

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

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

    /**
     * Check for updates and return the latest release info if newer than current version
     */
    suspend fun checkForUpdate(currentVersion: String): GitHubRelease? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_API)
                .get()
                .addHeader("Accept", "application/vnd.github+json")
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                LogBuffer.e("UpdateChecker", "Failed to check for updates: HTTP ${response.code}")
                return@withContext null
            }
            
            val release = gson.fromJson(response.body?.string(), GitHubRelease::class.java)
            val latestVersion = release.tag_name.removePrefix("v")
            
            if (isNewerVersion(latestVersion, currentVersion)) {
                LogBuffer.i("UpdateChecker", "Update available: $latestVersion (current: $currentVersion)")
                release
            } else {
                LogBuffer.i("UpdateChecker", "Up to date: $currentVersion")
                null
            }
        } catch (e: Exception) {
            LogBuffer.e("UpdateChecker", "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * Compare version strings to see if latest is newer than current
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split("[.-]".toRegex())
        val currentParts = current.split("[.-]".toRegex())
        
        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val latestPart = latestParts.getOrNull(i)?.toIntOrNull() ?: 0
            val currentPart = currentParts.getOrNull(i)?.toIntOrNull() ?: 0
            
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }

    /**
     * Find the APK asset in the release
     */
    fun findApkAsset(release: GitHubRelease): GitHubAsset? {
        return release.assets.find { 
            it.name.endsWith(".apk") || it.name.endsWith(".apk.debug")
        }
    }

    /**
     * Show update dialog to user - if yes, download and install directly
     */
    fun showUpdateDialog(release: GitHubRelease, apkAsset: GitHubAsset) {
        val versionName = release.tag_name
        val releaseNotes = release.body.take(200) + if (release.body.length > 200) "..." else ""
        
        android.app.AlertDialog.Builder(context)
            .setTitle("Update Available")
            .setMessage("Version $versionName is available.\n\n$releaseNotes")
            .setPositiveButton("Update Now") { _, _ ->
                downloadAndInstall(apkAsset)
            }
            .setNegativeButton("Not Now", null)
            .show()
    }

    /**
     * Download APK using system DownloadManager
     */
    private fun downloadAndInstall(apkAsset: GitHubAsset) {
        try {
            val request = DownloadManager.Request(Uri.parse(apkAsset.browser_download_url))
                .setTitle("CWBridge Update")
                .setDescription("Downloading ${apkAsset.name}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    apkAsset.name
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)
            
            LogBuffer.i("UpdateChecker", "Download started: ${apkAsset.name} (ID: $downloadId)")
            
            // Show installation instructions when download completes
            android.app.AlertDialog.Builder(context)
                .setTitle("Download Started")
                .setMessage("The update will appear in your notifications. Tap it to install after download completes.")
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            LogBuffer.e("UpdateChecker", "Download failed: ${e.message}")
            android.widget.Toast.makeText(
                context,
                "Download failed: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Get current app version from package info
     */
    fun getCurrentVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName
        } catch (e: Exception) {
            "1.0.0"
        }
    }
}
