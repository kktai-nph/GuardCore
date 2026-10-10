package com.parentalcontrol.child.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.parentalcontrol.child.ChildApplication
import com.parentalcontrol.child.engine.ChildEngineHolder
import com.parentalcontrol.child.receiver.AdminReceiver
import com.parentalcontrol.child.ui.MainActivity
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class TelegramPollingService : Service() {

    companion object {
        private const val TAG = "TelegramPolling"
        private const val NOTIFICATION_ID = 9001
        private const val PREFS_NAME = "crt_polling_prefs"
        private const val KEY_LAST_UPDATE_ID = "last_update_id"

        fun start(context: Context) {
            val intent = Intent(context, TelegramPollingService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var lastUpdateId = 0L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        lastUpdateId = prefs.getLong(KEY_LAST_UPDATE_ID, 0L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        if (!isRunning) {
            isRunning = true
            serviceScope.launch {
                runPollingLoop()
            }
        }

        return START_STICKY
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ChildApplication.CHANNEL_ID_PERSISTENT)
            .setContentTitle("Child Protection Active")
            .setContentText("Listening for parent commands securely")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private suspend fun runPollingLoop() {
        var backoffDelayMs = 2000L

        while (isRunning) {
            val botToken = ChildEngineHolder.getTelegramBotToken(this)
            val chatId = ChildEngineHolder.getTelegramChatId(this)

            if (botToken.isBlank()) {
                // Not configured yet, wait and check again
                delay(5000)
                continue
            }

            try {
                val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=$lastUpdateId&timeout=30"
                val request = Request.Builder().url(url).build()

                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }

                if (response.isSuccessful) {
                    backoffDelayMs = 2000L // Reset backoff on success
                    val bodyString = response.body?.string().orEmpty()
                    processUpdatesJson(bodyString, botToken, chatId)
                } else {
                    Log.w(TAG, "Telegram API HTTP Error: ${response.code}")
                    delay(backoffDelayMs)
                    backoffDelayMs = (backoffDelayMs * 2).coerceAtMost(60000L)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Network connection issue during long-polling: ${e.message}")
                delay(backoffDelayMs)
                backoffDelayMs = (backoffDelayMs * 2).coerceAtMost(60000L)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in polling loop", e)
                delay(5000)
            }
        }
    }

    private fun processUpdatesJson(jsonStr: String, botToken: String, defaultChatId: String) {
        val engine = ChildEngineHolder.getEngine(this) ?: return
        val currentEpoch = System.currentTimeMillis() / 1000
        val batteryLevel = getBatteryLevel()

        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            if (!root.has("ok") || !root.get("ok").asBoolean) return

            val updates = root.getAsJsonArray("result") ?: return
            for (elem in updates) {
                val obj = elem.asJsonObject
                val updateId = obj.get("update_id").asLong
                lastUpdateId = (updateId + 1).coerceAtLeast(lastUpdateId)

                // Save lastUpdateId
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_UPDATE_ID, lastUpdateId)
                    .apply()

                val msgObj = if (obj.has("message")) {
                    obj.getAsJsonObject("message")
                } else if (obj.has("channel_post")) {
                    obj.getAsJsonObject("channel_post")
                } else null

                if (msgObj != null && msgObj.has("text")) {
                    val text = msgObj.get("text").asString
                    val fromChatId = msgObj.getAsJsonObject("chat")?.get("id")?.asString ?: defaultChatId

                    val isCurrentlyLocked = false // Evaluated dynamically
                    val result = engine.processWireMessage(text, currentEpoch.toULong(), batteryLevel.toUByte(), isCurrentlyLocked)

                    if (result != null) {
                        Log.i(TAG, "Successfully processed command: ${result.actionType} (ID: ${result.commandId})")

                        // 1. Dispatch Action
                        when (result.actionType) {
                            "LOCK_NOW" -> {
                                AdminReceiver.lockNow(applicationContext)
                                LockOverlayService.showLock(applicationContext, "Device locked by parent")
                            }
                            "UNLOCK_NOW" -> {
                                LockOverlayService.hideLock(applicationContext)
                            }
                            "PAUSE_DEVICE" -> {
                                LockOverlayService.showLock(applicationContext, "Device paused by parent")
                            }
                            "UPDATE_APP", "FORCE_UPDATE" -> {
                                triggerChildAutoUpdate(text)
                            }
                        }

                        // 2. Dispatch Signed ACK back to Telegram
                        sendAckToTelegram(botToken, fromChatId, result.ackJson)
                    } else if (text.contains("CRT_OTA:")) {
                        // Passive OTA Broadcast check
                        checkPassiveOtaBroadcast(text)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing update JSON", e)
        }
    }

    private fun checkPassiveOtaBroadcast(rawText: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val otaIdx = rawText.indexOf("CRT_OTA:")
                if (otaIdx == -1) return@launch
                val jsonPart = rawText.substring(otaIdx + 8).trim()
                val root = JsonParser.parseString(jsonPart).asJsonObject

                val codeKey = "childVersionCode"
                val urlKey = "childApkUrl"
                if (!root.has(urlKey)) return@launch

                val newCode = if (root.has(codeKey)) root.get(codeKey).asInt else root.get("versionCode")?.asInt ?: 1
                val currentCode = com.parentalcontrol.core.updater.AutoUpdateManager.getCurrentVersionCode(applicationContext)

                if (newCode > currentCode) {
                    val apkUrl = root.get(urlKey).asString
                    Log.i(TAG, "New OTA version available: $newCode (Current: $currentCode). Downloading from $apkUrl...")
                    val dlResult = com.parentalcontrol.core.updater.AutoUpdateManager.downloadApk(applicationContext, apkUrl) { percent ->
                        Log.d(TAG, "OTA Download progress: $percent%")
                    }
                    if (dlResult.isSuccess) {
                        val apkFile = dlResult.getOrThrow()
                        Log.i(TAG, "Downloaded update APK, launching installation...")
                        com.parentalcontrol.core.updater.AutoUpdateManager.installApk(applicationContext, apkFile)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Passive OTA error", e)
            }
        }
    }

    private fun triggerChildAutoUpdate(rawText: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val root = JsonParser.parseString(rawText).asJsonObject
                val payload = if (root.has("payload")) root.getAsJsonObject("payload") else root
                val apkUrl = if (payload.has("apk_url")) payload.get("apk_url").asString
                             else if (payload.has("apkUrl")) payload.get("apkUrl").asString
                             else null

                if (!apkUrl.isNullOrBlank()) {
                    Log.i(TAG, "Executing parent remote update command: $apkUrl")
                    val dlResult = com.parentalcontrol.core.updater.AutoUpdateManager.downloadApk(applicationContext, apkUrl) { percent ->
                        Log.d(TAG, "Remote update download: $percent%")
                    }
                    if (dlResult.isSuccess) {
                        val apkFile = dlResult.getOrThrow()
                        com.parentalcontrol.core.updater.AutoUpdateManager.installApk(applicationContext, apkFile)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed executing remote update command", e)
            }
        }
    }

    private fun sendAckToTelegram(botToken: String, chatId: String, ackJson: String) {
        if (botToken.isBlank() || chatId.isBlank()) return

        serviceScope.launch(Dispatchers.IO) {
            try {
                val sendUrl = "https://api.telegram.org/bot$botToken/sendMessage"
                val wireAck = "CRT_ACK:$ackJson"

                val payload = JsonObject().apply {
                    addProperty("chat_id", chatId)
                    addProperty("text", wireAck)
                    addProperty("disable_notification", true)
                }

                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(sendUrl).post(body).build()

                httpClient.newCall(request).execute().close()
                Log.i(TAG, "Dispatched ACK back to Telegram")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send ACK to Telegram", e)
            }
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
