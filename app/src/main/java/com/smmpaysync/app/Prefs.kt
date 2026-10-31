package com.smmpaysync.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class Prefs(context: Context) {

    private val sp: SharedPreferences = try {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Some low-end phones have broken Keystore; fall back so the app still works.
        context.getSharedPreferences("fallback_prefs", Context.MODE_PRIVATE)
    }

    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) { sp.edit().putString("url", v).apply() }

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) { sp.edit().putString("token", v).apply() }

    var secret: String
        get() = sp.getString("secret", "") ?: ""
        set(v) { sp.edit().putString("secret", v).apply() }

    var lastSync: Long
        get() = sp.getLong("last_sync", 0L)
        set(v) { sp.edit().putLong("last_sync", v).apply() }

    var lastResult: String
        get() = sp.getString("last_result", "") ?: ""
        set(v) { sp.edit().putString("last_result", v).apply() }

    var keepAlive: Boolean
        get() = sp.getBoolean("keep_alive", true)
        set(v) { sp.edit().putBoolean("keep_alive", v).apply() }

    val isConfigured: Boolean
        get() = url.isNotBlank() && token.isNotBlank() && secret.isNotBlank()

    fun clear() {
        sp.edit().clear().apply()
    }
}
