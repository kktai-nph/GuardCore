package com.parentalcontrol.core.updater

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.JsonObject
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
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
     * Parse update metadata from a remote JSON endpoint.
     * Schema example:
     * {
     *   "child": { "versionCode": 2, "versionName": "1.1", "apkUrl": "https://...", "changelog": "..." },
     *   "parent": { "versionCode": 2, "versionName": "1.1", "apkUrl": "https://...", "changelog": "..." }
     * }
     */
    suspend fun checkUpdateFromJsonUrl(url: String, isChildApp: Boolean): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP error ${response.code}"))
            }

            val body = response.body?.string().orEmpty()
            val parsed = parseUpdateJson(body, isChildApp)
            Result.success(parsed)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check update from URL: $url", e)
            Result.failure(e)
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

            if (apkDownloadUrl.isNullOrBlank()) {
                apkDownloadUrl = "https://github.com/$owner/$repo/releases/download/$tagName/$targetApkName"
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
            val request = Request.Builder().url(url).build()
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

    private fun parseUpdateJson(jsonStr: String, isChildApp: Boolean): UpdateInfo? {
        val root = JsonParser.parseString(jsonStr).asJsonObject
        val key = if (isChildApp) "child" else "parent"
        if (!root.has(key)) return null

        val target = root.getAsJsonObject(key)
        val vCode = target.get("versionCode")?.asInt ?: 1
        val vName = target.get("versionName")?.asString ?: "1.0"
        val apkUrl = target.get("apkUrl")?.asString ?: return null
        val changelog = target.get("changelog")?.asString.orEmpty()
        val isMandatory = target.get("isMandatory")?.asBoolean ?: false
        val sha256 = target.get("sha256")?.asString

        return UpdateInfo(
            versionCode = vCode,
            versionName = vName,
            apkUrl = apkUrl,
            changelog = changelog,
            isMandatory = isMandatory,
            sha256 = sha256
        )
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
     * Download the APK with progress streaming callback (0 - 100%).
     */
    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (percent: Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(apkUrl).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Download failed HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty response body"))
            val totalBytes = body.contentLength()

            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            val destFile = File(downloadDir, "update_${System.currentTimeMillis()}.apk")

            body.byteStream().use { input: InputStream ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastReportedPercent = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        if (totalBytes > 0) {
                            val percent = ((totalRead * 100) / totalBytes).toInt()
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }

            onProgress(100)
            Result.success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download APK failed", e)
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
            // Fallback to standard installer if silent failed
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
        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        context.startActivity(intent)
    }
}
