package com.ghostgramlabs.directserve.localization

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LocaleManager {
    fun applyLanguageTag(languageTag: String?) {
        val canonicalTag = AppLanguages.canonicalize(languageTag)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(canonicalTag))
    }

    fun currentLanguageTag(context: Context): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        val tag = locales.toLanguageTags().takeIf { it.isNotBlank() } ?: context.resources.configuration.locales[0]?.toLanguageTag()
        return AppLanguages.canonicalize(tag)
    }
}
