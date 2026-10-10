package com.parentalcontrol.parent.engine

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.parentalcontrol.core.CrtParentEngine

data class PairedChild(
    val deviceId: String,
    val childName: String,
    val ed25519Pubkey: String,
    val x25519Pubkey: String,
    val addedAt: Long
)

object ParentEngineHolder {

    private const val PREFS_NAME = "crt_parent_prefs"
    private const val KEY_SECRET_HEX = "secret_hex"
    private const val KEY_X_SECRET_HEX = "x_secret_hex"
    private const val KEY_BOT_TOKEN = "telegram_bot_token"
    private const val KEY_CHAT_ID = "telegram_chat_id"
    private const val KEY_PAIRED_CHILDREN = "paired_children_json"

    private val gson = Gson()

    @Volatile
    private var engineInstance: CrtParentEngine? = null

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getEngine(context: Context): CrtParentEngine? {
        if (engineInstance == null) {
            val prefs = getPreferences(context)
            val secretHex = prefs.getString(KEY_SECRET_HEX, null)
            val xSecretHex = prefs.getString(KEY_X_SECRET_HEX, null)

            try {
                val engine = CrtParentEngine(secretHex, xSecretHex)
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

    fun getPairedChildren(context: Context): List<PairedChild> {
        val json = getPreferences(context).getString(KEY_PAIRED_CHILDREN, null) ?: return emptyList()
        val type = object : TypeToken<List<PairedChild>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addPairedChild(context: Context, child: PairedChild) {
        val current = getPairedChildren(context).toMutableList()
        current.removeAll { it.deviceId == child.deviceId }
        current.add(child)
        val json = gson.toJson(current)
        getPreferences(context).edit().putString(KEY_PAIRED_CHILDREN, json).apply()
    }

    fun removePairedChild(context: Context, deviceId: String) {
        val current = getPairedChildren(context).toMutableList()
        current.removeAll { it.deviceId == deviceId }
        val json = gson.toJson(current)
        getPreferences(context).edit().putString(KEY_PAIRED_CHILDREN, json).apply()
    }
}
