package com.ghostgramlabs.directserve.localization

import java.util.Locale

data class AppLanguage(
    val tag: String,
    val englishName: String,
    val nativeName: String,
)

object AppLanguages {
    val supported = listOf(
        AppLanguage("en", "English", "English"),
        AppLanguage("es", "Spanish", "Español"),
        AppLanguage("fr", "French", "Français"),
        AppLanguage("pt", "Portuguese", "Português"),
        AppLanguage("de", "German", "Deutsch"),
        AppLanguage("it", "Italian", "Italiano"),
        AppLanguage("nl", "Dutch", "Nederlands"),
        AppLanguage("ru", "Russian", "Русский"),
        AppLanguage("tr", "Turkish", "Türkçe"),
        AppLanguage("ar", "Arabic", "العربية"),
        AppLanguage("hi", "Hindi", "हिन्दी"),
        AppLanguage("ml", "Malayalam", "മലയാളം"),
        AppLanguage("ta", "Tamil", "தமிழ்"),
        AppLanguage("te", "Telugu", "తెలుగు"),
        AppLanguage("ja", "Japanese", "日本語"),
        AppLanguage("ko", "Korean", "한국어"),
        AppLanguage("zh-CN", "Chinese (Simplified)", "简体中文"),
        AppLanguage("zh-TW", "Chinese (Traditional)", "繁體中文"),
        AppLanguage("id", "Indonesian", "Bahasa Indonesia"),
        AppLanguage("vi", "Vietnamese", "Tiếng Việt"),
        AppLanguage("th", "Thai", "ไทย"),
    )

    fun resolve(tag: String?): AppLanguage = supported.firstOrNull { it.tag == canonicalize(tag) } ?: supported.first()

    fun detectSupportedDeviceLanguage(): AppLanguage {
        val locale = Locale.getDefault()
        val exact = canonicalize(locale.toLanguageTag())
        return supported.firstOrNull { it.tag == exact }
            ?: supported.firstOrNull { it.tag == canonicalize(locale.language) }
            ?: supported.first()
    }

    fun canonicalize(tag: String?): String {
        if (tag.isNullOrBlank()) return "en"
        val locale = Locale.forLanguageTag(tag)
        val language = locale.language.ifBlank { "en" }
        val region = locale.country
        return when {
            language.equals("zh", ignoreCase = true) && region.equals("TW", ignoreCase = true) -> "zh-TW"
            language.equals("zh", ignoreCase = true) -> "zh-CN"
            else -> language.lowercase()
        }
    }
}
