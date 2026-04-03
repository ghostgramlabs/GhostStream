package com.ghoststream.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ghoststream.core.model.AppSettings
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class DataStoreSettingsRepository(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SettingsRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { context.preferencesDataStoreFile("ghoststream_settings.preferences_pb") },
    )

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[SETTINGS_KEY]
                ?.let { encoded ->
                    runCatching { json.decodeFromString(AppSettings.serializer(), encoded) }.getOrNull()
                }
                ?: AppSettings()
        }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            val current = preferences[SETTINGS_KEY]
                ?.let { encoded ->
                    runCatching { json.decodeFromString(AppSettings.serializer(), encoded) }.getOrNull()
                }
                ?: AppSettings()
            preferences[SETTINGS_KEY] = json.encodeToString(
                AppSettings.serializer(),
                transform(current),
            )
        }
    }

    override suspend fun markOnboardingCompleted() {
        update { current -> current.copy(onboardingCompleted = true) }
    }

    private companion object {
        val SETTINGS_KEY = stringPreferencesKey("app_settings_json")
    }
}
