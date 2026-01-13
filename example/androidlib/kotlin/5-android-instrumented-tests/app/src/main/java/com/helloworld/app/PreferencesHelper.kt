package com.helloworld.app

import android.content.Context

object PreferencesHelper {
    private const val PREFS_FILE = "demo_prefs"

    fun writeFlag(context: Context, key: String, value: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, value).apply()
    }

    fun readFlag(context: Context, key: String, default: Boolean = false): Boolean {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        return prefs.getBoolean(key, default)
    }
}
