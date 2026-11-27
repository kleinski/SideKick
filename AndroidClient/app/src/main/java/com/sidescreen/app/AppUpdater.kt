package com.sidescreen.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private fun updDiag(msg: String) = DiagLog.log("UP", msg)

/**
 * GitHub Releases API updater for SideKick (Android).
 * Queries `kleinski/SideKick/releases/latest` and offers a direct APK download when a newer
 * SemVer release is published (works completely independent of Google Play).
 */
class AppUpdater(
    private val activity: AppCompatActivity,
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var checking = false

    fun checkForUpdate(interactive: Boolean = false) {
        if (checking) return
        if (!interactive) {
            // Silent background check at most once every 24h when idle
            val prefs = activity.getSharedPreferences("app_prefs", AppCompatActivity.MODE_PRIVATE)
            val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
            if (System.currentTimeMillis() - lastCheck < 24L * 60L * 60L * 1000L) return
        }

        checking = true
        if (interactive) {
            Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show()
        }

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { fetchLatestRelease() }
                activity.getSharedPreferences("app_prefs", AppCompatActivity.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .apply()

                val current = currentVersionName()
                if (isNewerVersion(result.version, current)) {
                    showUpdateAvailableDialog(result, current)
                } else if (interactive) {
                    showUpToDateDialog(current)
                }
            } catch (e: Exception) {
                updDiag("Update check failed: ${e.message}")
                if (interactive) {
                    Toast.makeText(
                        activity,
                        "Update check failed: ${e.message ?: "network error"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                checking = false
            }
        }
    }

    private fun currentVersionName(): String =
        try {
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activity.packageManager.getPackageInfo(
                        activity.packageName,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getPackageInfo(activity.packageName, 0)
                }
            (info.versionName ?: "0.0.0").removeSuffix("-debug")
        } catch (_: Exception) {
            "0.0.0"
        }

    private data class ReleaseResult(
        val version: String,
        val title: String,
        val releaseUrl: String,
        val apkUrl: String?,
    )

    private fun fetchLatestRelease(): ReleaseResult {
        val conn =
            (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "SideKick-Android")
            }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("GitHub HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").trim()
            val version = tag.removePrefix("v").removePrefix("V")
            val title = json.optString("name", "SideKick $tag").ifBlank { "SideKick $tag" }
            val htmlUrl = json.optString("html_url", RELEASES_URL)
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith("-android-release.apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", null)
                        break
                    }
                }
            }
            return ReleaseResult(version, title, htmlUrl, apkUrl)
        } finally {
            conn.disconnect()
        }
    }

    private fun showUpToDateDialog(current: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("You're up to date!")
            .setMessage("SideKick v$current is currently the newest version available.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showUpdateAvailableDialog(
        release: ReleaseResult,
        current: String,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("Update Available: v${release.version}")
            .setMessage(
                "Installed: v$current\nLatest: ${release.title}\n\nDownload the new APK from GitHub Releases to update SideKick.",
            )
            .setPositiveButton("Download APK") { _, _ ->
                openUrl(release.apkUrl ?: release.releaseUrl)
            }
            .setNeutralButton("View Release") { _, _ ->
                openUrl(release.releaseUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(activity, "Open $url in your browser", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/kleinski/SideKick/releases/latest"
        private const val RELEASES_URL = "https://github.com/kleinski/SideKick/releases"
        private const val KEY_LAST_CHECK = "last_github_update_check_ms"

        internal fun isNewerVersion(
            remote: String,
            local: String,
        ): Boolean {
            val r = remote.split(".").mapNotNull { it.toIntOrNull() }
            val l = local.split(".").mapNotNull { it.toIntOrNull() }
            if (r.isEmpty() || l.isEmpty()) return false
            val n = maxOf(r.size, l.size)
            for (i in 0 until n) {
                val rv = if (i < r.size) r[i] else 0
                val lv = if (i < l.size) l[i] else 0
                if (rv != lv) return rv > lv
            }
            return false
        }
    }
}
