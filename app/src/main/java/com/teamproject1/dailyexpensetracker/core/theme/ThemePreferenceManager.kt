package com.teamproject1.dailyexpensetracker.core.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "theme_prefs")
private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")

enum class ThemeMode { LIGHT, DARK, AUTOMATIC }

/**
 * Backs the real Light/Dark/Automatic setting — Settings previously just
 * stated "follows system automatically" as a static note with no actual
 * control. Defaults to AUTOMATIC (matches the original always-follow-
 * system behavior) until the user explicitly picks Light or Dark.
 */
class ThemePreferenceManager(private val context: Context) {

    val themeMode = context.themeDataStore.data.map { prefs ->
        when (prefs[THEME_MODE_KEY]) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK" -> ThemeMode.DARK
            else -> ThemeMode.AUTOMATIC
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { prefs -> prefs[THEME_MODE_KEY] = mode.name }
    }
}
