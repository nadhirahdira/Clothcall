package com.clothcall

import android.app.Application
import com.clothcall.data.db.AppDatabase
import com.clothcall.utils.PreferencesManager

class ClothCallApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        // Reset so the HomeScreen welcome TTS speaks once per fresh process launch,
        // not on every navigation back to HomeScreen within the same session.
        PreferencesManager(this).hasSpokenWelcome = false
    }
}
