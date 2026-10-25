package com.hanzilock.core

import com.hanzilock.platform.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
class Settings(private val prefs: Prefs) {
    private val _changes = MutableStateFlow(0L)

    /** Increments after every change. */
    val changes: StateFlow<Long> = _changes

    private fun edit(block: Prefs.Editor.() -> Unit) {
        prefs.edit(block)
        _changes.update { it + 1 }
    }

    /** For the settings your devices share (see [Shared]): also notes when they changed. */
    private fun editShared(block: Prefs.Editor.() -> Unit) = edit {
        block()
        putLong("shared_settings_at", System.currentTimeMillis())
    }

    // ---- lock schedule ------------------------------------------------------------------------

    var lockEnabled: Boolean
        get() = prefs.getBoolean("lock_enabled", false)
        set(v) = edit { putBoolean("lock_enabled", v) }

    /** Daily reset times in minutes after midnight, sorted. */
    var resetTimes: List<Int>
        get() = prefs.getString("reset_times", null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.sorted()
            ?: DEFAULT_RESET_TIMES
        set(v) = editShared { putString("reset_times", v.distinct().sorted().joinToString(",")) }

    /** Remembered for the "N times a day between X and Y" helper. */
    var windowStart: Int
        get() = prefs.getInt("window_start", 8 * 60)
        set(v) = editShared { putInt("window_start", v) }

    var windowEnd: Int
        get() = prefs.getInt("window_end", 20 * 60)
        set(v) = editShared { putInt("window_end", v) }

    var wordsPerSession: Int
        get() = prefs.getInt("words_per_session", 5).coerceIn(1, 50)
        set(v) = editShared { putInt("words_per_session", v.coerceIn(1, 50)) }

    var completionRule: CompletionRule
        get() = enumOr(prefs.getString("completion_rule", null), CompletionRule.ATTEMPTED)
        set(v) = editShared { putString("completion_rule", v.name) }

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
        set(v) = editShared { putInt("speech_attempts", v.coerceIn(1, 5)) }

    /** Start the pronunciation step in "type the pinyin" mode (e.g. at work). */
    var preferTypedPinyin: Boolean
        get() = prefs.getBoolean("prefer_typed_pinyin", false)
        set(v) = edit { putBoolean("prefer_typed_pinyin", v) }

    var requireTones: Boolean
        get() = prefs.getBoolean("require_tones", true)
        set(v) = editShared { putBoolean("require_tones", v) }

    /** Offer "I was right" when the offline meaning check says no. */
    var allowMeaningOverride: Boolean
        get() = prefs.getBoolean("allow_meaning_override", true)
        set(v) = editShared { putBoolean("allow_meaning_override", v) }

    /** The set last chosen for endless practice in [lang] (0 = none). Set ids differ per device, so not shared. */
    fun endlessSetId(lang: String): Long = prefs.getLong("endless_set_$lang", 0L)

    fun setEndlessSetId(lang: String, setId: Long) = edit { putLong("endless_set_$lang", setId) }

    // ---- Claude ---------------------------------------------------------------------------------

    var claudeApiKey: String
        get() = prefs.getString("claude_api_key", "").orEmpty()
        set(v) = edit { putString("claude_api_key", v.trim()) }

    var claudeModel: String
        get() = prefs.getString("claude_model", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLAUDE_MODEL
        set(v) = editShared { putString("claude_model", v.trim()) }

    var gradingMode: GradingMode
        get() = enumOr(prefs.getString("grading_mode", null), GradingMode.AUTO)
        set(v) = editShared { putString("grading_mode", v.name) }

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
        set(v) = editShared { putBoolean("allow_skip_pronunciation", v) }

    // ---- languages --------------------------------------------------------------------------------

    /** The language practice sessions use (zh, ja, ko, es, fr, it, ...). */
    var activeLanguage: String
        get() = prefs.getString("active_language", null) ?: "zh"
        set(v) = editShared { putString("active_language", v) }

    /** Languages added from the app (not bundled). */
    var addedLanguages: List<LanguageProfile>
        get() = Languages.decode(prefs.getString("added_languages", null))
        set(v) = editShared { putString("added_languages", Languages.encode(v)) }

    // ---- stories ----------------------------------------------------------------------------------

    /** HSK level of the last story Claude wrote for you (0 = not chosen yet). */
    var storyLevel: Int
        get() = prefs.getInt("story_level", 0)
        set(v) = editShared { putInt("story_level", v) }

    /** Read stories aloud slowly. */
    var storySlow: Boolean
        get() = prefs.getBoolean("story_slow", false)
        set(v) = editShared { putBoolean("story_slow", v) }

    // ---- misc -----------------------------------------------------------------------------------

    /** Only used to migrate installs from before word sets existed. */
    val legacyRegistryVersion: Int get() = prefs.getInt("registry_version", 0)

    /** JSON of the session in progress, so it survives the app being killed. */
    var activeSession: String?
        get() = prefs.getString("active_session", null)
        set(v) = edit { if (v == null) remove("active_session") else putString("active_session", v) }

    // ---- sync between your devices ----------------------------------------------------------------

    /** When a session was last finished on any of your devices (merged by sync); it unlocks here too. */
    var sharedCompletedAt: Long
        get() = prefs.getLong("shared_completed_at", 0L)
        set(v) = edit { putLong("shared_completed_at", v) }

    /** The private GitHub repository ("owner/name") your devices sync through; empty = sync off. */
    var syncRepo: String
        get() = prefs.getString("sync_repo", "").orEmpty()
        set(v) = edit { putString("sync_repo", v.trim()) }

    /** A GitHub token that can read and write [syncRepo] (stored like the Claude key). */
    var syncToken: String
        get() = prefs.getString("sync_token", "").orEmpty()
        set(v) = edit { putString("sync_token", v.trim()) }

    /** This device's own file in the sync repository (made up once). */
    val syncDeviceId: String
        get() = prefs.getString("sync_device_id", null) ?: java.util.UUID.randomUUID().toString().also { id ->
            edit { putString("sync_device_id", id) }
        }

    var syncDeviceName: String
        get() = prefs.getString("sync_device_name", "").orEmpty()
        set(v) = edit { putString("sync_device_name", v.trim()) }

    var syncLastAt: Long
        get() = prefs.getLong("sync_last_at", 0L)
        set(v) = edit { putLong("sync_last_at", v) }

    /** For each other device, the version (git sha) of its file that was last merged here. */
    var syncSeen: Map<String, String>
        get() = runCatching {
            Json.decodeFromString<Map<String, String>>(prefs.getString("sync_seen", null) ?: "{}")
        }.getOrDefault(emptyMap())
        set(v) = edit { putString("sync_seen", Json.encodeToString(v)) }

    /** Git sha and content hash of this device's file as last uploaded. */
    var syncOwnSha: String?
        get() = prefs.getString("sync_own_sha", null)
        set(v) = edit { putString("sync_own_sha", v) }

    var syncOwnHash: String?
        get() = prefs.getString("sync_own_hash", null)
        set(v) = edit { putString("sync_own_hash", v) }

    /** The settings that are the same on all your devices: languages, schedule, sessions, checks, Claude model, stories. */
    @Serializable
    data class Shared(
        val at: Long,
        val activeLanguage: String,
        val addedLanguages: String? = null,
        val resetTimes: List<Int>,
        val windowStart: Int,
        val windowEnd: Int,
        val wordsPerSession: Int,
        val completionRule: String,
        val speechAttempts: Int,
        val requireTones: Boolean,
        val allowMeaningOverride: Boolean,
        val allowSkipPronunciation: Boolean,
        val gradingMode: String,
        val claudeModel: String? = null,
        val storyLevel: Int,
        val storySlow: Boolean,
    )

    fun shared() = Shared(
        at = prefs.getLong("shared_settings_at", 0L),
        activeLanguage = activeLanguage,
        addedLanguages = prefs.getString("added_languages", null),
        resetTimes = resetTimes,
        windowStart = windowStart,
        windowEnd = windowEnd,
        wordsPerSession = wordsPerSession,
        completionRule = completionRule.name,
        speechAttempts = speechAttempts,
        requireTones = requireTones,
        allowMeaningOverride = allowMeaningOverride,
        allowSkipPronunciation = allowSkipPronunciation,
        gradingMode = gradingMode.name,
        claudeModel = prefs.getString("claude_model", null),
        storyLevel = storyLevel,
        storySlow = storySlow,
    )

    /** Takes another device's newer [s] (keeping its time, so it isn't sent back as a change). */
    fun applyShared(s: Shared) = edit {
        putString("active_language", s.activeLanguage)
        putString("added_languages", s.addedLanguages)
        putString("reset_times", s.resetTimes.distinct().sorted().joinToString(","))
        putInt("window_start", s.windowStart)
        putInt("window_end", s.windowEnd)
        putInt("words_per_session", s.wordsPerSession)
        putString("completion_rule", s.completionRule)
        putInt("speech_attempts", s.speechAttempts)
        putBoolean("require_tones", s.requireTones)
        putBoolean("allow_meaning_override", s.allowMeaningOverride)
        putBoolean("allow_skip_pronunciation", s.allowSkipPronunciation)
        putString("grading_mode", s.gradingMode)
        putString("claude_model", s.claudeModel)
        putInt("story_level", s.storyLevel)
        putBoolean("story_slow", s.storySlow)
        putLong("shared_settings_at", s.at)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    companion object {
        val DEFAULT_RESET_TIMES = listOf(8 * 60, 13 * 60, 19 * 60)
        const val DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"
    }
}
