package com.example.besharp.data.preferences

import android.content.Context

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

object AppearancePreferences {
    const val PREFERENCES_NAME = "besharp_preferences"
    const val KEY_THEME_MODE = "appearance_theme_mode"
    const val KEY_HIGH_LEGIBILITY_FONT = "appearance_high_legibility_font"

    fun themeMode(context: Context): AppThemeMode =
        preferences(context).getString(KEY_THEME_MODE, null)
            ?.let { stored -> runCatching { AppThemeMode.valueOf(stored) }.getOrNull() }
            ?: AppThemeMode.SYSTEM

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        preferences(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun highLegibilityFont(context: Context): Boolean =
        preferences(context).getBoolean(KEY_HIGH_LEGIBILITY_FONT, false)

    fun setHighLegibilityFont(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_HIGH_LEGIBILITY_FONT, enabled).apply()
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
