package com.lanfileserver.app

import android.content.Context
import androidx.core.content.edit
import java.security.SecureRandom

object AppPreferences {
    private const val PREFS_NAME = "hotspot_file_server"
    private const val KEY_TREE_URI = "tree_uri"
    private const val KEY_PORT = "port"
    private const val KEY_PIN = "pin"

    const val DEFAULT_PORT = 8080

    fun treeUri(context: Context): String? =
        prefs(context).getString(KEY_TREE_URI, null)

    fun port(context: Context): Int =
        prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun pin(context: Context): String {
        val stored = prefs(context).getString(KEY_PIN, null)
        if (!stored.isNullOrBlank()) return stored

        val generated = generatePin()
        prefs(context).edit { putString(KEY_PIN, generated) }
        return generated
    }

    fun saveTreeUri(context: Context, uri: String) {
        prefs(context).edit { putString(KEY_TREE_URI, uri) }
    }

    fun saveConfiguration(context: Context, port: Int, pin: String) {
        prefs(context).edit {
            putInt(KEY_PORT, port)
            putString(KEY_PIN, pin)
        }
    }

    fun generatePin(): String =
        (SecureRandom().nextInt(900_000) + 100_000).toString()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
