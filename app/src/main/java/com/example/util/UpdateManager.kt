package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val latestVersion: String = "",
    val releaseNotes: String = "",
    val downloadUrl: String = "",
    val releaseUrl: String = "https://github.com/Fazer000/VPN-Xray-Vless-Client/releases/latest",
    val isUpdateAvailable: Boolean = false,
    val errorMessage: String? = null
)

sealed class DownloadState {
    object Idle : DownloadState()
    data class Progress(val percent: Int) : DownloadState()
    data class Finished(val apkFile: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class UpdateManager(private val context: Context) {

    private val repositoryUrl = "https://api.github.com/repos/Fazer000/VPN-Xray-Vless-Client/releases/latest"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdates(): AppUpdateInfo = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(repositoryUrl)
                .header("User-Agent", "XrayVPN-AppUpdater/1.0")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext AppUpdateInfo(
                    errorMessage = "Не удалось проверить обновления (HTTP ${response.code})"
                )
            }

            val body = response.body?.string() ?: ""
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "").removePrefix("v").trim()
            val releaseNotes = json.optString("body", "")
            val htmlUrl = json.optString("html_url", "https://github.com/Fazer000/VPN-Xray-Vless-Client/releases/latest")

            var apkUrl = ""
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }

            val currentVersion = BuildConfig.VERSION_NAME
            val isAvailable = isNewerVersion(currentVersion, tagName)

            AppUpdateInfo(
                currentVersion = currentVersion,
                latestVersion = if (tagName.isNotEmpty()) tagName else currentVersion,
                releaseNotes = releaseNotes,
                downloadUrl = apkUrl,
                releaseUrl = htmlUrl,
                isUpdateAvailable = isAvailable
            )
        } catch (e: Exception) {
            e.printStackTrace()
            AppUpdateInfo(
                errorMessage = "Ошибка проверки обновлений: ${e.localizedMessage}"
            )
        }
    }

    fun downloadApk(url: String): Flow<DownloadState> = flow {
        emit(DownloadState.Progress(0))
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "XrayVPN-AppUpdater/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful || response.body == null) {
                emit(DownloadState.Error("Ошибка скачивания: HTTP ${response.code}"))
                return@flow
            }

            val apkFile = File(context.cacheDir, "update_release.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }

            val body = response.body!!
            val contentLength = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            val percent = ((totalRead * 100) / contentLength).toInt()
                            emit(DownloadState.Progress(percent))
                        }
                    }
                }
            }

            emit(DownloadState.Finished(apkFile))
        } catch (e: Exception) {
            emit(DownloadState.Error("Ошибка скачивания: ${e.localizedMessage}"))
        }
    }.flowOn(Dispatchers.IO)

    fun installApk(file: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, file)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        if (latest.isBlank()) return false
        try {
            val currParts = current.split(".").mapNotNull { it.toIntOrNull() }
            val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(currParts.size, latestParts.size)
            for (i in 0 until maxLen) {
                val c = currParts.getOrElse(i) { 0 }
                val l = latestParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (e: Exception) {
            return current != latest
        }
        return false
    }
}
