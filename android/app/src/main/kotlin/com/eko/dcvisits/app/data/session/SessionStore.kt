package com.eko.dcvisits.app.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.eko.dcvisits.app.data.net.ApiClient
import com.eko.dcvisits.app.data.net.UserDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/** The signed-in principal + device binding. Never backed up (see backup_rules.xml). */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val deviceId: String,
    val user: UserDto,
)

class SessionStore(private val context: Context) {

    private object Keys {
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
        val DEVICE = stringPreferencesKey("device_id")
        val USER = stringPreferencesKey("user_json")
    }

    val sessionFlow: Flow<Session?> = context.dataStore.data.map { p -> toSession(p) }

    suspend fun current(): Session? = toSession(context.dataStore.data.first())

    suspend fun save(session: Session) {
        context.dataStore.edit { p ->
            p[Keys.ACCESS] = session.accessToken
            p[Keys.REFRESH] = session.refreshToken
            p[Keys.DEVICE] = session.deviceId
            p[Keys.USER] = ApiClient.json.encodeToString(UserDto.serializer(), session.user)
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private fun toSession(p: Preferences): Session? {
        val access = p[Keys.ACCESS] ?: return null
        val refresh = p[Keys.REFRESH] ?: return null
        val device = p[Keys.DEVICE] ?: return null
        val userJson = p[Keys.USER] ?: return null
        val user = try {
            ApiClient.json.decodeFromString(UserDto.serializer(), userJson)
        } catch (_: Exception) {
            return null
        }
        return Session(access, refresh, device, user)
    }
}
