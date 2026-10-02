package com.hanzilock.core

import com.hanzilock.data.AppJson
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * How a language is handled: which voice / speech recogniser locale to use, how its script is
 * shown, whether sentences have spaces between words, and what "reading" means (pinyin for
 * Chinese, kana for Japanese, nothing for alphabetic scripts).
 */
@Serializable
data class LanguageProfile(
    val code: String,
    val name: String,
    val native: String,
    /** BCP-47 tag for text-to-speech and speech recognition. */
    val locale: String,
    /** han | japanese | hangul | latin | cyrillic | arabic | other */
    val script: String = "latin",
    val spaced: Boolean = true,
    /** pinyin | kana | none */
    val reading: String = "none",
    val rtl: Boolean = false,
) {
    val javaLocale: Locale get() = Locale.forLanguageTag(locale)
    val isChinese: Boolean get() = code == "zh"
    val isJapanese: Boolean get() = code == "ja"

    /** Scripts with big square characters get the big word display. */
    val cjk: Boolean get() = script == "han" || script == "japanese" || script == "hangul"

    /** "pinyin" / "reading", or null when the script is read as written. */
    val readingLabel: String?
        get() = when (reading) {
            "pinyin" -> "pinyin"
            "kana" -> "reading"
            else -> null
        }

    /** Chinese and Japanese can be tested by typing the reading when you can't speak. */
    val canTypeReading: Boolean get() = reading == "pinyin" || reading == "kana"

    val label: String get() = if (native == name) name else "$name · $native"
}

/** A language that can be added from the app, and where its level sets are downloaded from. */
data class CatalogLanguage(val profile: LanguageProfile, val packRepo: String?)

/** The languages you're learning: the ones bundled with the app plus the ones you added. */
class Languages(private val settings: Settings) {
    @Volatile
    private var bundled: List<LanguageProfile> = listOf(CATALOG.getValue("zh").profile)

    fun setBundled(list: List<LanguageProfile>) {
        if (list.isNotEmpty()) bundled = list
    }

    /** Languages with word sets on this phone. */
    fun all(): List<LanguageProfile> = (bundled + settings.addedLanguages).distinctBy { it.code }

    fun get(code: String): LanguageProfile =
        all().firstOrNull { it.code == code } ?: CATALOG[code]?.profile
            ?: LanguageProfile(code, code, code, code)

    val active: LanguageProfile get() = get(settings.activeLanguage)

    fun add(profile: LanguageProfile) {
        settings.addedLanguages = (settings.addedLanguages.filter { it.code != profile.code } + profile)
    }

    /** Languages that can still be added from the catalog. */
    fun addable(): List<CatalogLanguage> {
        val have = all().map { it.code }.toSet()
        return CATALOG.values.filter { it.profile.code !in have }.sortedBy { it.profile.name }
    }

    companion object {
        private fun lang(
            code: String, name: String, native: String, locale: String,
            script: String = "latin", spaced: Boolean = true, reading: String = "none", rtl: Boolean = false,
            pack: String? = null,
        ) = code to CatalogLanguage(LanguageProfile(code, name, native, locale, script, spaced, reading, rtl), pack)

        /** Known languages. packRepo = a Bannerless-Studio A1-B1 word pack on GitHub. */
        val CATALOG: Map<String, CatalogLanguage> = mapOf(
            lang("zh", "Mandarin Chinese", "中文", "zh-CN", "han", spaced = false, reading = "pinyin"),
            lang("ja", "Japanese", "日本語", "ja-JP", "japanese", spaced = false, reading = "kana", pack = "japanese"),
            lang("ko", "Korean", "한국어", "ko-KR", "hangul", pack = "korean"),
            lang("es", "Spanish", "Español", "es-ES", pack = "spanish"),
            lang("fr", "French", "Français", "fr-FR"),
            lang("it", "Italian", "Italiano", "it-IT", pack = "italian"),
            lang("de", "German", "Deutsch", "de-DE", pack = "german"),
            lang("ru", "Russian", "Русский", "ru-RU", "cyrillic", pack = "russian"),
            lang("id", "Indonesian", "Bahasa Indonesia", "id-ID", pack = "indonesian"),
            lang("sw", "Swahili", "Kiswahili", "sw-KE", pack = "swahili"),
            lang("ur", "Urdu", "اردو", "ur-PK", "arabic", rtl = true, pack = "urdu"),
            lang("fa", "Persian", "فارسی", "fa-IR", "arabic", rtl = true, pack = "persian"),
            lang("pt", "Portuguese", "Português", "pt-BR"),
            lang("nl", "Dutch", "Nederlands", "nl-NL"),
            lang("sv", "Swedish", "Svenska", "sv-SE"),
            lang("pl", "Polish", "Polski", "pl-PL"),
            lang("tr", "Turkish", "Türkçe", "tr-TR"),
            lang("el", "Greek", "Ελληνικά", "el-GR", "other"),
            lang("he", "Hebrew", "עברית", "he-IL", "other", rtl = true),
            lang("ar", "Arabic", "العربية", "ar-SA", "arabic", rtl = true),
            lang("hi", "Hindi", "हिन्दी", "hi-IN", "other"),
            lang("vi", "Vietnamese", "Tiếng Việt", "vi-VN"),
            lang("th", "Thai", "ไทย", "th-TH", "other", spaced = false),
            lang("yue", "Cantonese", "粵語", "zh-HK", "han", spaced = false),
        )

        fun encode(list: List<LanguageProfile>): String = AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(LanguageProfile.serializer()), list)

        fun decode(text: String?): List<LanguageProfile> = text?.let {
            runCatching { AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(LanguageProfile.serializer()), it) }.getOrNull()
        }.orEmpty()
    }
}
