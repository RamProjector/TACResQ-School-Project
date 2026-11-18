package com.tacresq.app.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "tacresq_settings")
private val themeModeKey = stringPreferencesKey("theme_mode")

fun Context.observeThemeMode(): Flow<ThemeMode> = settingsDataStore.data
    .catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }
    .map { preferences ->
        preferences[themeModeKey]
            ?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } }
            ?: ThemeMode.SYSTEM
    }

suspend fun Context.persistThemeMode(mode: ThemeMode) {
    settingsDataStore.edit { preferences ->
        preferences[themeModeKey] = mode.name
    }
}
