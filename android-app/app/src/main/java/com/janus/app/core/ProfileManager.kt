package com.janus.app.core

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class ProfileManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("janus_user_profile", Context.MODE_PRIVATE)

    init {
        if (!prefs.contains("uuid")) {
            val newUuid = UUID.randomUUID().toString()
            prefs.edit()
                .putString("uuid", newUuid)
                .putString("username", "")
                .putString("device_name", "Janus Android")
                .putBoolean("onboarding_completed", false)
                .apply()
        }
    }

    fun getUuid(): String = prefs.getString("uuid", "") ?: ""

    fun getUsername(): String = prefs.getString("username", "") ?: ""

    fun setUsername(name: String) {
        val clean = name.trim()
        val currentDevName = getDeviceName()
        val devName = if (clean.isNotBlank() && (currentDevName.isBlank() || currentDevName.contains("Janus Android"))) "$clean's Android" else currentDevName
        prefs.edit()
            .putString("username", clean)
            .putString("device_name", devName)
            .putBoolean("onboarding_completed", clean.isNotBlank())
            .apply()
    }

    fun getDeviceName(): String = prefs.getString("device_name", "Janus Android") ?: "Janus Android"

    fun setDeviceName(name: String) {
        prefs.edit().putString("device_name", name.trim()).apply()
    }

    fun isOnboardingCompleted(): Boolean = prefs.getBoolean("onboarding_completed", false) && getUsername().isNotBlank()

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean("onboarding_completed", completed).apply()
    }
}
