package com.parentalcontrol.core.updater

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

object AutoUpdateManager {

    private const val TAG = "AutoUpdateManager"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    /**
     * Check updates directly from GitHub Releases API:
     * https://api.github.com/repos/{owner}/{repo}/releases/latest
     */
    suspend fun checkUpdateFromGitHub(
        owner: String,
        repo: String,
        isChildApp: Boolean
    ): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "SafeShield-OTA-Updater")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("GitHub API HTTP ${response.code}"))
            }

            val body = response.body?.string().orEmpty()
            val root = JsonParser.parseString(body).asJsonObject

            val tagName = root.get("tag_name")?.asString ?: "1.0"
            val versionName = tagName.removePrefix("v").trim()
            val changelog = root.get("body")?.asString.orEmpty()
            val versionCode = parseVersionNameToCode(versionName)

            val targetApkName = if (isChildApp) "Child_SafeShield.apk" else "Parent_SafeShield.apk"
            var apkDownloadUrl: String? = null

            val assets = root.getAsJsonArray("assets")
            if (assets != null) {
                for (elem in assets) {
                    val assetObj = elem.asJsonObject
                    val name = assetObj.get("name")?.asString.orEmpty()
                    if (name.equals(targetApkName, ignoreCase = true) ||
                        (isChildApp && name.contains("child", ignoreCase = true)) ||
                        (!isChildApp && name.contains("parent", ignoreCase = true))) {
                        apkDownloadUrl = assetObj.get("browser_download_url")?.asString
                        break
                    }
                }
            }

            // Fallback to raw repository CDN if release asset is not directly uploaded
            if (apkDownloadUrl.isNullOrBlank()) {
                apkDownloadUrl = "https://raw.githubusercontent.com/$owner/$repo/main/apks/$targetApkName"
            }

            val updateInfo = UpdateInfo(
                versionCode = versionCode,
                versionName = versionName,
                apkUrl = apkDownloadUrl,
                changelog = changelog
            )

            Result.success(updateInfo)
        } catch (e: Exception) {
            Log.e(TAG, "GitHub update check failed", e)
            Result.failure(e)
        }
    }

    private fun parseVersionNameToCode(versionName: String): Int {
        return try {
            val parts = versionName.split(".")
            if (parts.size >= 2) {
                val major = parts[0].toIntOrNull() ?: 1
                val minor = parts[1].toIntOrNull() ?: 0
                val patch = if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else 0
                major * 100 + minor * 10 + patch
            } else {
                versionName.toIntOrNull() ?: 1
            }
        } catch (e: Exception) {
            2
        }
    }

    /**
     * Check Telegram group/channel messages for latest OTA broadcast format:
     * CRT_OTA:{"childVersionCode":2,"childVersionName":"1.1","childApkUrl":"...","changelog":"..."}
     */
    suspend fun checkUpdateFromTelegram(botToken: String, chatId: String, isChildApp: Boolean): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) {
            return@withContext Result.failure(Exception("Bot token is empty"))
        }

        try {
            val url = "https://api.telegram.org/bot$botToken/getUpdates?limit=20"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "SafeShield-OTA-Updater")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Telegram API HTTP ${response.code}"))
            }

            val body = response.body?.string().orEmpty()
            val root = JsonParser.parseString(body).asJsonObject
            if (!root.has("ok") || !root.get("ok").asBoolean) {
                return@withContext Result.failure(Exception("Telegram response not ok"))
            }

            val results = root.getAsJsonArray("result") ?: return@withContext Result.success(null)
            var latestUpdateInfo: UpdateInfo? = null

            for (i in results.size() - 1 downTo 0) {
                val elem = results[i].asJsonObject
                val msg = if (elem.has("message")) elem.getAsJsonObject("message")
                          else if (elem.has("channel_post")) elem.getAsJsonObject("channel_post")
                          else null

                val rawText = if (msg != null && msg.has("text")) msg.get("text").asString
                              else if (msg != null && msg.has("caption")) msg.get("caption").asString
                              else null

                if (rawText != null) {
                    val cleanText = rawText.replace("`", "").trim()
                    val otaIdx = cleanText.indexOf("CRT_OTA:")
                    if (otaIdx != -1) {
                        val jsonPart = cleanText.substring(otaIdx + 8).trim()
                        val update = parseOtaPayload(jsonPart, isChildApp)
                        if (update != null) {
                            latestUpdateInfo = update
                            break
                        }
                    }
                }
            }

            Result.success(latestUpdateInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check update from Telegram", e)
            Result.failure(e)
        }
    }

    private fun parseOtaPayload(jsonStr: String, isChildApp: Boolean): UpdateInfo? {
        return try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val codeKey = if (isChildApp) "childVersionCode" else "parentVersionCode"
            val nameKey = if (isChildApp) "childVersionName" else "parentVersionName"
            val urlKey = if (isChildApp) "childApkUrl" else "parentApkUrl"

            if (!root.has(urlKey)) return null

            val vCode = if (root.has(codeKey)) root.get(codeKey).asInt else root.get("versionCode")?.asInt ?: 1
            val vName = if (root.has(nameKey)) root.get(nameKey).asString else root.get("versionName")?.asString ?: "1.0"
            val apkUrl = root.get(urlKey).asString
            val changelog = root.get("changelog")?.asString.orEmpty()
            val isMandatory = root.get("isMandatory")?.asBoolean ?: false

            UpdateInfo(
                versionCode = vCode,
                versionName = vName,
                apkUrl = apkUrl,
                changelog = changelog,
                isMandatory = isMandatory
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing CRT_OTA payload: $jsonStr", e)
            null
        }
    }

    /**
     * Download the APK with real-time live progress streaming callback.
     * @param onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long)
     */
    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val urlsToTry = mutableListOf(apkUrl)

            // Auto-generate standard GitHub release direct binary URLs and CDN URLs
            if (apkUrl.contains("github.com", ignoreCase = true)) {
                val apkName = if (apkUrl.contains("child", ignoreCase = true)) "Child_SafeShield.apk" else "Parent_SafeShield.apk"
                val rawCdnUrl = "https://raw.githubusercontent.com/kktai-nph/GuardCore/main/apks/$apkName"
                if (!urlsToTry.contains(rawCdnUrl)) {
                    urlsToTry.add(rawCdnUrl)
                }
            }

            var lastException: Exception? = null

            for (currentUrl in urlsToTry) {
                try {
                    Log.i(TAG, "Initiating real binary APK download from: $currentUrl")
                    val request = Request.Builder()
                        .url(currentUrl)
                        .header("User-Agent", "SafeShield-OTA-Updater")
                        .header("Accept", "*/*")
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        response.close()
                        lastException = Exception("HTTP ${response.code} from $currentUrl")
                        continue
                    }

                    val body = response.body ?: throw Exception("Empty response body from $currentUrl")
                    var totalBytes = body.contentLength()
                    if (totalBytes <= 0) {
                        totalBytes = 27 * 1024 * 1024L // Approximate ~27MB default if Content-Length omitted
                    }

                    val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
                    if (!downloadDir.exists()) downloadDir.mkdirs()

                    val destFile = File(downloadDir, "SafeShield_Update_${System.currentTimeMillis()}.apk")

                    body.byteStream().use { input: InputStream ->
                        FileOutputStream(destFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            var totalRead = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead

                                val percent = ((totalRead * 100) / totalBytes).toInt().coerceIn(1, 99)
                                onProgress(percent, totalRead, totalBytes)
                            }
                            output.flush()
                        }
                    }

                    if (destFile.exists() && destFile.length() > 500 * 1024) {
                        onProgress(100, destFile.length(), destFile.length())
                        Log.i(TAG, "Successfully downloaded real APK: ${destFile.absolutePath} (${destFile.length()} bytes)")
                        return@withContext Result.success(destFile)
                    } else {
                        throw Exception("Downloaded file is incomplete (${destFile.length()} bytes)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Download attempt failed for $currentUrl: ${e.message}")
                    lastException = e
                }
            }

            Result.failure(lastException ?: Exception("Download failed"))
        } catch (e: Exception) {
            Log.e(TAG, "Download APK failed entirely", e)
            Result.failure(e)
        }
    }

    /**
     * Install APK:
     * - If Device Owner: Silent background install via PackageInstaller.
     * - If Standard: Triggers system package installer via FileProvider.
     *
     * @return true if silent installation initiated, false if user prompt intent launched.
     */
    fun installApk(context: Context, apkFile: File): Result<Boolean> {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val isDeviceOwner = dpm?.isDeviceOwnerApp(context.packageName) == true

            if (isDeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Log.i(TAG, "Device Owner detected: Performing silent background install via PackageInstaller")
                performSilentInstall(context, apkFile)
                Result.success(true)
            } else {
                Log.i(TAG, "Standard mode: Launching FileProvider package installer intent")
                launchStandardInstaller(context, apkFile)
                Result.success(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Install APK failed", e)
            try {
                launchStandardInstaller(context, apkFile)
                Result.success(false)
            } catch (fallbackEx: Exception) {
                Result.failure(fallbackEx)
            }
        }
    }

    private fun performSilentInstall(context: Context, apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        apkFile.inputStream().use { input ->
            session.openWrite("package_update", 0, apkFile.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }

        val intent = Intent(context, SilentInstallReceiver::class.java).apply {
            action = SilentInstallReceiver.ACTION_INSTALL_COMMIT
            putExtra("session_id", sessionId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        )

        session.commit(pendingIntent.intentSender)
        session.close()
    }

    private fun launchStandardInstaller(context: Context, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            Log.i(TAG, "Install permission not granted, opening Settings")
            val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(settingsIntent)
            return
        }

        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        context.startActivity(intent)
    }
}
