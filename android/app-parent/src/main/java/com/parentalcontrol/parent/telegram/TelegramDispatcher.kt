package com.parentalcontrol.parent.telegram

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object TelegramDispatcher {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun sendWireMessage(
        botToken: String,
        chatId: String,
        wireText: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Bot Token or Chat ID is missing"))
        }

        try {
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val payload = JsonObject().apply {
                addProperty("chat_id", chatId)
                addProperty("text", wireText)
                addProperty("disable_notification", true)
            }

            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.close()
                Result.success(true)
            } else {
                val code = response.code
                response.close()
                Result.failure(Exception("Telegram API error HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
