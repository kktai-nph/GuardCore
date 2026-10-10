package com.parentalcontrol.child.engine

import android.content.Context
import android.content.SharedPreferences
import com.parentalcontrol.core.CrtChildEngine
import java.io.File
import java.util.UUID

object ChildEngineHolder {

    private const val PREFS_NAME = "crt_child_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_SECRET_HEX = "secret_hex"
    private const val KEY_X_SECRET_HEX = "x_secret_hex"
    private const val KEY_BOT_TOKEN = "telegram_bot_token"
    private const val KEY_CHAT_ID = "telegram_chat_id"

    @Volatile
    private var engineInstance: CrtChildEngine? = null

    fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getEngine(context: Context): CrtChildEngine? {
        if (engineInstance == null) {
            val prefs = getPreferences(context)
            var deviceId = prefs.getString(KEY_DEVICE_ID, null)
            if (deviceId == null) {
                deviceId = "child_" + UUID.randomUUID().toString().replace("-", "").take(12)
                prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
            }

            val secretHex = prefs.getString(KEY_SECRET_HEX, null)
            val xSecretHex = prefs.getString(KEY_X_SECRET_HEX, null)
            val dbFile = File(context.filesDir, "child_policy.db").absolutePath

            try {
                val engine = CrtChildEngine(dbFile, deviceId, secretHex, xSecretHex)
                // Persist generated keys if first run
                if (secretHex == null) {
                    prefs.edit()
                        .putString(KEY_SECRET_HEX, engine.getSecretKey())
                        .putString(KEY_X_SECRET_HEX, engine.getX25519SecretKey())
                        .apply()
                }
                engineInstance = engine
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return engineInstance
    }

    fun getTelegramBotToken(context: Context): String {
        return getPreferences(context).getString(KEY_BOT_TOKEN, "8915785873:AAF7xDSUaQxpbcLIIcnlCx4D1kC628oBpqk") ?: "8915785873:AAF7xDSUaQxpbcLIIcnlCx4D1kC628oBpqk"
    }

    fun saveTelegramBotToken(context: Context, token: String) {
        getPreferences(context).edit().putString(KEY_BOT_TOKEN, token.trim()).apply()
    }

    fun getTelegramChatId(context: Context): String {
        return getPreferences(context).getString(KEY_CHAT_ID, "-5550682710") ?: "-5550682710"
    }

    fun saveTelegramChatId(context: Context, chatId: String) {
        getPreferences(context).edit().putString(KEY_CHAT_ID, chatId.trim()).apply()
    }
}
