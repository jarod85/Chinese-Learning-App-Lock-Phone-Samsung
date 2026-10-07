package com.hanzilock.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** When does a lock session count as done? */
enum class CompletionRule(val label: String) {
    ATTEMPTED("After N words, right or wrong"),
    CORRECT("Only after N correct answers"),
}

enum class GradingMode(val label: String) {
    AUTO("Claude when online, offline otherwise"),
    OFFLINE("Always offline"),
}

/** All preferences. Reads are in-memory and cheap, so the accessibility service can call them freely. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hanzilock", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0L)

    /** Increments after every change. */
    val changes: StateFlow<Long> = _changes

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _changes.update { it + 1 }
    }

    // ---- lock schedule ------------------------------------------------------------------------

    var lockEnabled: Boolean
        get() = prefs.getBoolean("lock_enabled", false)
        set(v) = edit { putBoolean("lock_enabled", v) }

    /** Daily reset times in minutes after midnight, sorted. */
    var resetTimes: List<Int>
        get() = prefs.getString("reset_times", null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.sorted()
            ?: DEFAULT_RESET_TIMES
        set(v) = edit { putString("reset_times", v.distinct().sorted().joinToString(",")) }

    /** Remembered for the "N times a day between X and Y" helper. */
    var windowStart: Int
        get() = prefs.getInt("window_start", 8 * 60)
        set(v) = edit { putInt("window_start", v) }

    var windowEnd: Int
        get() = prefs.getInt("window_end", 20 * 60)
        set(v) = edit { putInt("window_end", v) }

    var wordsPerSession: Int
        get() = prefs.getInt("words_per_session", 5).coerceIn(1, 50)
        set(v) = edit { putInt("words_per_session", v.coerceIn(1, 50)) }

    var completionRule: CompletionRule
        get() = enumOr(prefs.getString("completion_rule", null), CompletionRule.ATTEMPTED)
        set(v) = edit { putString("completion_rule", v.name) }

    var lastCompletedAt: Long
        get() = prefs.getLong("last_completed_at", 0L)
        set(v) = edit { putLong("last_completed_at", v) }

    /** "Lock now" was pressed; stays locked until a session is finished. */
    var manualLock: Boolean
        get() = prefs.getBoolean("manual_lock", false)
        set(v) = edit { putBoolean("manual_lock", v) }

    var pausedUntil: Long
        get() = prefs.getLong("paused_until", 0L)
        set(v) = edit { putLong("paused_until", v) }

    // ---- allowed apps & priority email ---------------------------------------------------------

    /** Apps usable while locked; null until you first edit the list (defaults apply). */
    var allowedPackages: Set<String>?
        get() = prefs.getStringSet("allowed_packages", null)?.toSet()
        set(v) = edit { if (v == null) remove("allowed_packages") else putStringSet("allowed_packages", v.toSet()) }

    var emailPackages: Set<String>?
        get() = prefs.getStringSet("email_packages", null)?.toSet()
        set(v) = edit { if (v == null) remove("email_packages") else putStringSet("email_packages", v.toSet()) }

    var emailRules: List<String>
        get() = EmailRules.parse(prefs.getString("email_rules", "").orEmpty())
        set(v) = edit { putString("email_rules", v.joinToString("\n")) }

    var emailPassMinutes: Int
        get() = prefs.getInt("email_pass_minutes", 10).coerceIn(1, 60)
        set(v) = edit { putInt("email_pass_minutes", v.coerceIn(1, 60)) }

    // ---- quiz ---------------------------------------------------------------------------------

    var speechAttempts: Int
        get() = prefs.getInt("speech_attempts", 2).coerceIn(1, 5)
        set(v) = edit { putInt("speech_attempts", v.coerceIn(1, 5)) }

    /** Start the pronunciation step in "type the pinyin" mode (e.g. at work). */
    var preferTypedPinyin: Boolean
        get() = prefs.getBoolean("prefer_typed_pinyin", false)
        set(v) = edit { putBoolean("prefer_typed_pinyin", v) }

    var requireTones: Boolean
        get() = prefs.getBoolean("require_tones", true)
        set(v) = edit { putBoolean("require_tones", v) }

    /** Offer "I was right" when the offline meaning check says no. */
    var allowMeaningOverride: Boolean
        get() = prefs.getBoolean("allow_meaning_override", true)
        set(v) = edit { putBoolean("allow_meaning_override", v) }

    // ---- Claude ---------------------------------------------------------------------------------

    var claudeApiKey: String
        get() = prefs.getString("claude_api_key", "").orEmpty()
        set(v) = edit { putString("claude_api_key", v.trim()) }

    var claudeModel: String
        get() = prefs.getString("claude_model", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLAUDE_MODEL
        set(v) = edit { putString("claude_model", v.trim()) }

    var gradingMode: GradingMode
        get() = enumOr(prefs.getString("grading_mode", null), GradingMode.AUTO)
        set(v) = edit { putString("grading_mode", v.name) }

    /** Words in your own sets up to this id have had their missing details filled in by Claude. */
    var filledUpToWordId: Long
        get() = prefs.getLong("filled_up_to_word_id", 0L)
        set(v) = edit { putLong("filled_up_to_word_id", v) }

    // ---- PIN ------------------------------------------------------------------------------------

    var pinHash: String?
        get() = prefs.getString("pin_hash", null)
        set(v) = edit { putString("pin_hash", v) }

    var pinSalt: String?
        get() = prefs.getString("pin_salt", null)
        set(v) = edit { putString("pin_salt", v) }

    var pinFailures: Int
        get() = prefs.getInt("pin_failures", 0)
        set(v) = edit { putInt("pin_failures", v) }

    var pinLockedUntil: Long
        get() = prefs.getLong("pin_locked_until", 0L)
        set(v) = edit { putLong("pin_locked_until", v) }

    /** Alphabetic languages can't be tested by typing a reading; allow "can't talk - skip". */
    var allowSkipPronunciation: Boolean
        get() = prefs.getBoolean("allow_skip_pronunciation", true)
        set(v) = edit { putBoolean("allow_skip_pronunciation", v) }

    // ---- languages --------------------------------------------------------------------------------

    /** The language practice sessions use (zh, ja, ko, es, fr, it, ...). */
    var activeLanguage: String
        get() = prefs.getString("active_language", null) ?: "zh"
        set(v) = edit { putString("active_language", v) }

    /** Languages added from the app (not bundled). */
    var addedLanguages: List<LanguageProfile>
        get() = Languages.decode(prefs.getString("added_languages", null))
        set(v) = edit { putString("added_languages", Languages.encode(v)) }

    // ---- misc -----------------------------------------------------------------------------------

    /** Only used to migrate installs from before word sets existed. */
    val legacyRegistryVersion: Int get() = prefs.getInt("registry_version", 0)

    /** JSON of the session in progress, so it survives the app being killed. */
    var activeSession: String?
        get() = prefs.getString("active_session", null)
        set(v) = edit { if (v == null) remove("active_session") else putString("active_session", v) }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    companion object {
        val DEFAULT_RESET_TIMES = listOf(8 * 60, 13 * 60, 19 * 60)
        const val DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"
    }
}
