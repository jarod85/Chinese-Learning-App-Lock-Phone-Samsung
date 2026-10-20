package com.hanzilock.sync

import com.hanzilock.core.Settings
import com.hanzilock.data.Example
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything one device has that your other devices need, as it uploads it to the sync repository
 * (devices/<device>.json.gz). Each device writes only its own file and merges the others' into its data.
 */
@Serializable
data class SyncSnapshot(
    val format: String = FORMAT,
    val version: Int = 1,
    val device: String,
    val deviceName: String,
    val exportedAt: Long,
    /** When a session was last finished on any device this one knows of (unlocks the others). */
    val completedAt: Long = 0,
    val settings: Settings.Shared? = null,
    /** Words practised, edited, switched off, added, or in your own sets (bundled words come with the app). */
    val words: List<SyncWord> = emptyList(),
    /** Your own sets with their words, and bundled sets you switched on or off. */
    val sets: List<SyncSet> = emptyList(),
    val sessions: List<SyncSession> = emptyList(),
    val attempts: List<SyncAttempt> = emptyList(),
    val stories: List<SyncStory> = emptyList(),
    val deletions: List<SyncDeletion> = emptyList(),
) {
    companion object {
        const val FORMAT = "lingolock-sync"
    }
}

@Serializable
data class SyncWord(
    val lang: String,
    val term: String,
    val traditional: String? = null,
    val reading: String = "",
    val meanings: List<String> = emptyList(),
    val altMeanings: List<String> = emptyList(),
    val examples: List<Example> = emptyList(),
    val forms: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val source: String = "user",
    val userEdited: Boolean = false,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    /** When the content (not the progress) last changed. */
    val updatedAt: Long = 0,
    val box: Int = 0,
    val dueAt: Long = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    /** When it was last practised: the newest progress wins. */
    val lastSeenAt: Long = 0,
)

@Serializable
data class SyncSet(
    val key: String,
    val lang: String,
    val name: String,
    val level: String? = null,
    val sort: Int = 0,
    val custom: Boolean = true,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Only for your own sets; bundled sets come with the app. */
    val members: List<SyncMember> = emptyList(),
)

@Serializable
data class SyncMember(val term: String, val addedAt: Long = 0)

@Serializable
data class SyncSession(
    val uid: String,
    val kind: String,
    val lang: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val outcome: String? = null,
    val wins: Int = 0,
    val losses: Int = 0,
)

@Serializable
data class SyncAttempt(
    val uid: String,
    val sessionUid: String? = null,
    val lang: String,
    val term: String,
    val at: Long,
    val win: Boolean,
    val failedPart: String? = null,
    val heard: String? = null,
    val meaningAnswer: String? = null,
    val sentenceAnswer: String? = null,
    val feedback: String? = null,
    val gradedBy: String? = null,
)

@Serializable
data class SyncStory(
    val uid: String,
    val title: String,
    val titleEn: String = "",
    val source: String,
    val url: String? = null,
    val level: String? = null,
    val chars: Int = 0,
    val pages: Int = 1,
    val page: Int = 0,
    /** The story's text, translations and questions, as stored (JSON). */
    val body: String,
    val finishedAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** Something deleted on a device: a word ("lang\tterm"), a set (its key), a set's word, or a story (its uid). */
@Serializable
data class SyncDeletion(val kind: String, val key: String, val at: Long)

val SyncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}
