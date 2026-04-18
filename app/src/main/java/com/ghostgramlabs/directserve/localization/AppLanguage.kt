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
        AppLanguage("af", "Afrikaans", "Afrikaans"),
        AppLanguage("ar", "Arabic", "العربية"),
        AppLanguage("zh-CN", "Chinese (Simplified)", "简体中文"),
        AppLanguage("zh-TW", "Chinese (Traditional)", "繁體中文"),
        AppLanguage("nl", "Dutch", "Nederlands"),
        AppLanguage("fr", "French", "Français"),
        AppLanguage("de", "German", "Deutsch"),
        AppLanguage("el", "Greek", "Ελληνικά"),
        AppLanguage("hi", "Hindi", "हिन्दी"),
        AppLanguage("id", "Indonesian", "Bahasa Indonesia"),
        AppLanguage("it", "Italian", "Italiano"),
        AppLanguage("ja", "Japanese", "日本語"),
        AppLanguage("ko", "Korean", "한국어"),
        AppLanguage("ml", "Malayalam", "മലയാളം"),
        AppLanguage("pt", "Portuguese", "Português"),
        AppLanguage("pt-BR", "Portuguese (Brazil)", "Português (Brasil)"),
        AppLanguage("ru", "Russian", "Русский"),
        AppLanguage("es", "Spanish", "Español"),
        AppLanguage("sv", "Swedish", "Svenska"),
        AppLanguage("ta", "Tamil", "தமிழ்"),
        AppLanguage("te", "Telugu", "తెలుగు"),
        AppLanguage("th", "Thai", "ไทย"),
        AppLanguage("tr", "Turkish", "Türkçe"),
        AppLanguage("vi", "Vietnamese", "Tiếng Việt"),
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
            language.equals("pt", ignoreCase = true) && region.equals("BR", ignoreCase = true) -> "pt-BR"
            language.equals("in", ignoreCase = true) -> "id"
            else -> language.lowercase()
        }
    }
}
