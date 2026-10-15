package com.tacresq.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.tacresq.app.data.model.Session
import com.tacresq.app.data.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.sessionDataStore by preferencesDataStore(name = "tacresq_session")

/**
 * Keeps the login (token + user) across app restarts.
 * TODO before release: encrypt the token at rest (Android Keystore).
 */
class SessionStore(private val context: Context) {
    private val gson = Gson()
    private val tokenKey = stringPreferencesKey("token")
    private val userKey = stringPreferencesKey("user_json")

    val session: Flow<Session?> = context.sessionDataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            val token = prefs[tokenKey]
            val userJson = prefs[userKey]
            if (token == null || userJson == null) {
                null
            } else {
                runCatching { gson.fromJson(userJson, User::class.java) }
                    .getOrNull()
                    ?.let { user -> Session(token, user) }
            }
        }

    suspend fun save(session: Session) {
        context.sessionDataStore.edit { prefs ->
            prefs[tokenKey] = session.token
            prefs[userKey] = gson.toJson(session.user)
        }
    }

    suspend fun clear() {
        context.sessionDataStore.edit { prefs -> prefs.clear() }
    }
}
