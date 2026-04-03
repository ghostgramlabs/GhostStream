package com.ghoststream.core.settings

import com.ghoststream.core.model.AppSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)
    suspend fun markOnboardingCompleted()
}

