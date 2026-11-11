package com.autolyrics.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.autolyrics.MainActivity
import com.autolyrics.R
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks this fork's GitHub Releases for a newer version, shows a phone
 * notification, and downloads the new APK for installing.
 */
object UpdateChecker {

    const val REPO = "wardy-uk1/Auto-Lyrics"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREFS = "auto_lyrics_prefs"
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val KEY_LATEST = "update_latest_version"
    private const val KEY_LATEST_APK = "update_latest_apk_url"
    private const val KEY_LATEST_PAGE = "update_latest_page_url"
    private const val KEY_NOTIFIED = "update_notified_version"
    private const val KEY_DISMISSED = "update_dismissed_version"
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 2001
    const val EXTRA_START_UPDATE = "start_update"

    /** How often to check automatically. */
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val APK_NAME = Regex("""^auto-lyrics-[0-9][0-9A-Za-z.\-]*\.apk$""")

    data class Release(
        @SerializedName("tag_name") val tagName: String?,
        @SerializedName("html_url") val htmlUrl: String?,
        @SerializedName("draft") val draft: Boolean?,
        @SerializedName("prerelease") val prerelease: Boolean?,
        @SerializedName("assets") val assets: List<Asset>?
    )

    data class Asset(
        @SerializedName("name") val name: String?,
        @SerializedName("browser_download_url") val url: String?,
        @SerializedName("size") val size: Long?
    )

    data class UpdateInfo(val version: String, val apkUrl: String, val pageUrl: String)

    /**
     * Compares version strings like "2.1.0" and "v2.0.10" (numeric parts only;
     * a "-beta" style suffix counts as older than the plain version).
     * Returns >0 if a is newer, <0 if b is newer, 0 if equal.
     */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String): Pair<List<Int>, Boolean> {
            val clean = v.trim().removePrefix("v").removePrefix("V")
            val main = clean.substringBefore('-')
            val nums = main.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            return nums to clean.contains('-')
        }
        val (pa, preA) = parts(a)
        val (pb, preB) = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return when {
            preA == preB -> 0
            preA -> -1
            else -> 1
        }
    }

    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    /** Fetches the latest release; returns it only if it's newer than this app. Blocking (call off the main thread). */
    fun fetchNewer(context: Context): UpdateInfo? {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AutoLyrics-UpdateChecker")
            .build()
        val release = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            gson.fromJson(response.body?.string(), Release::class.java)
        } ?: return null
        if (release.draft == true || release.prerelease == true) return null

        val version = release.tagName?.removePrefix("v") ?: return null
        val apk = release.assets.orEmpty().firstOrNull { a ->
            val n = a.name ?: return@firstOrNull false
            APK_NAME.matches(n) && !n.contains("-debug")
        }?.url ?: return null

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .putString(KEY_LATEST, version)
            .putString(KEY_LATEST_APK, apk)
            .putString(KEY_LATEST_PAGE, release.htmlUrl ?: "https://github.com/$REPO/releases/latest")
            .apply()

        return if (compareVersions(version, installedVersion(context)) > 0) {
            UpdateInfo(version, apk, release.htmlUrl ?: "")
        } else null
    }

    /** The newest version found by the last check, if it's newer than this app and not dismissed. */
    fun pendingUpdate(context: Context, includeDismissed: Boolean = false): UpdateInfo? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_LATEST, null) ?: return null
        val apk = prefs.getString(KEY_LATEST_APK, null) ?: return null
        if (compareVersions(version, installedVersion(context)) <= 0) return null
        if (!includeDismissed && prefs.getString(KEY_DISMISSED, null) == version) return null
        return UpdateInfo(version, apk, prefs.getString(KEY_LATEST_PAGE, "") ?: "")
    }

    fun dismiss(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DISMISSED, version).apply()
    }

    fun isCheckDue(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > CHECK_INTERVAL_MS
    }

    /** Background check (blocking): fetches, and posts a notification once per new version. */
    fun checkAndNotify(context: Context) {
        val info = try { fetchNewer(context) } catch (_: Exception) { null } ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_NOTIFIED, null) == info.version) return
        if (showNotification(context, info)) {
            prefs.edit().putString(KEY_NOTIFIED, info.version).apply()
        }
    }

    private fun showNotification(context: Context, info: UpdateInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Tells you when a new version of Auto Lyrics is available" }
            )
        }

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_START_UPDATE, true)
        }
        val pending = PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyrics)
            .setContentTitle("Auto Lyrics ${info.version} is available")
            .setContentText("Tap to download and install the update")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Downloads the APK into the app's cache (blocking). [onProgress] gets 0-100.
     * Returns the file, or null on failure.
     */
    fun downloadApk(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File? {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }               // remove older downloads
        val out = File(dir, "auto-lyrics-${info.version}.apk")
        val request = Request.Builder().url(info.apkUrl).header("User-Agent", "AutoLyrics-UpdateChecker").build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val total = body.contentLength()
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                        }
                    }
                }
            }
            out
        } catch (_: Exception) {
            out.delete()
            null
        }
    }
}
