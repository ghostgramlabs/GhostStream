package com.ghostgramlabs.directserve

import android.app.Application
import com.ghostgramlabs.directserve.localization.AppLanguages
import com.ghostgramlabs.directserve.localization.LocaleManager
import com.ghostgramlabs.directserve.state.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class GhostStreamApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        runBlocking {
            val settings = container.settingsRepository.settings.first()
            val languageTag = settings.languageTag ?: AppLanguages.detectSupportedDeviceLanguage().tag
            LocaleManager.applyLanguageTag(languageTag)
        }
    }
}
