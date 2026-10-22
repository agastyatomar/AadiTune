package com.zionhuang.music.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import org.json.JSONObject
import java.io.File

object Updater {
    private const val RELEASES_API = "https://api.github.com/repos/agastyatomar/NovaTune/releases/latest"

    private val client = HttpClient() {
        expectSuccess = true
    }
    var lastCheckTime = -1L
        private set

    suspend fun getLatestVersionName(): Result<String> = runCatching {
        val json = latestReleaseJson()
        val versionName = json.getString("name")
        lastCheckTime = System.currentTimeMillis()
        versionName
    }

    private suspend fun latestReleaseJson(): JSONObject =
        JSONObject(client.get(RELEASES_API).bodyAsText())

    /** Browser-download URL of the newest installable (auto-signed debug) APK asset, or null. */
    suspend fun getLatestApkUrl(): Result<String?> = runCatching {
        val json = latestReleaseJson()
        val assets = json.getJSONArray("assets")
        (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { it.getString("name").endsWith("-foss-debug.apk") }
            .maxByOrNull { it.getLong("size") }
            ?.getString("browser_download_url")
    }

    /** Downloads the newest APK and hands it to the system package installer (one tap to confirm). */
    suspend fun downloadAndInstall(context: Context): Result<Unit> = runCatching {
        val url = getLatestApkUrl().getOrNull().orEmpty()
        if (url.isEmpty()) throw Exception("No APK asset available in latest release")
        val bytes = client.get(url).readBytes()
        val file = File(context.cacheDir, "NovaTune-update.apk")
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        context.startActivity(intent)
        Unit
    }
}
