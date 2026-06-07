package com.clothcall.utils

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("clothcall_prefs", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var isOutMode: Boolean
        get() = prefs.getBoolean(KEY_OUT_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_OUT_MODE, value).apply()

    var selectedGarmentId: Int
        get() = prefs.getInt(KEY_SELECTED_GARMENT, -1)
        set(value) = prefs.edit().putInt(KEY_SELECTED_GARMENT, value).apply()

    var hasSpokenWelcome: Boolean
        get() = prefs.getBoolean(KEY_HAS_SPOKEN_WELCOME, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_SPOKEN_WELCOME, value).apply()

    val hasApiKey: Boolean
        get() = apiKey.isNotBlank()

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_OUT_MODE = "out_mode"
        private const val KEY_SELECTED_GARMENT = "selected_garment_id"
        private const val KEY_HAS_SPOKEN_WELCOME = "has_spoken_welcome"
    }
}
