package com.pause.app.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.pause.app.domain.model.ActiveSession
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/** Small seam so the session manager can be tested without a DataStore. */
interface SessionPersistence {
    suspend fun read(): ActiveSession?
    suspend fun write(session: ActiveSession)
    suspend fun clear()
}

@Singleton
class DataStoreSessionPersistence @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SessionPersistence {

    override suspend fun read(): ActiveSession? {
        val preferences = dataStore.data
            .catch { throwable ->
                if (throwable is IOException) emit(emptyPreferences()) else throw throwable
            }
            .first()
        val packageName = preferences[Keys.PACKAGE_NAME] ?: return null
        val appLabel = preferences[Keys.APP_LABEL] ?: return null
        val limitMinutes = preferences[Keys.LIMIT_MINUTES] ?: return null
        val endsAt = preferences[Keys.ENDS_AT] ?: return null
        if (packageName.isBlank() || limitMinutes <= 0 || endsAt <= 0L) return null
        return ActiveSession(
            packageName = packageName,
            appLabel = appLabel,
            limitMinutes = limitMinutes,
            endsAtEpochMillis = endsAt,
        )
    }

    override suspend fun write(session: ActiveSession) {
        dataStore.edit { preferences ->
            preferences[Keys.PACKAGE_NAME] = session.packageName
            preferences[Keys.APP_LABEL] = session.appLabel
            preferences[Keys.LIMIT_MINUTES] = session.limitMinutes
            preferences[Keys.ENDS_AT] = session.endsAtEpochMillis
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(Keys.PACKAGE_NAME)
            preferences.remove(Keys.APP_LABEL)
            preferences.remove(Keys.LIMIT_MINUTES)
            preferences.remove(Keys.ENDS_AT)
        }
    }

    private object Keys {
        val PACKAGE_NAME = stringPreferencesKey("active_session_package")
        val APP_LABEL = stringPreferencesKey("active_session_label")
        val LIMIT_MINUTES = intPreferencesKey("active_session_limit_minutes")
        val ENDS_AT = longPreferencesKey("active_session_ends_at")
    }
}
