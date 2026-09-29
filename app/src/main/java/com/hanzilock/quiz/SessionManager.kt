package com.hanzilock.quiz

import com.hanzilock.core.CompletionRule
import com.hanzilock.core.LockEngine
import com.hanzilock.core.Settings
import com.hanzilock.data.AppJson
import com.hanzilock.data.QuizPart
import com.hanzilock.data.WordRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

enum class SessionKind { LOCK, PRACTICE }

@Serializable
data class ActiveSession(
    val id: Long,
    val kind: SessionKind,
    val target: Int,
    val rule: CompletionRule,
    val queue: List<Long>,
    val index: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val startedAt: Long,
) {
    val attempted: Int get() = wins + losses
    val isComplete: Boolean
        get() = when (rule) {
            CompletionRule.ATTEMPTED -> attempted >= target
            CompletionRule.CORRECT -> wins >= target
        }
    val currentWordId: Long? get() = if (isComplete) null else queue.getOrNull(index)
}

/** The outcome of one word, recorded when you move on to the next one. */
data class WordOutcome(
    val wordId: Long,
    val win: Boolean,
    val failedPart: QuizPart?,
    val heard: String?,
    val meaningAnswer: String?,
    val sentenceAnswer: String?,
    val feedback: String?,
    val gradedBy: String,
)

/**
 * The practice session in progress. It is persisted, so killing the app or restarting the phone
 * resumes the same words instead of dealing a fresh (easier) hand.
 */
class SessionManager(
    private val settings: Settings,
    private val words: WordRepository,
    private val lockEngine: LockEngine,
) {
    private val _active = MutableStateFlow(load())
    val active: StateFlow<ActiveSession?> = _active

    private fun load(): ActiveSession? =
        settings.activeSession?.let { runCatching { AppJson.decodeFromString<ActiveSession>(it) }.getOrNull() }

    private fun save(s: ActiveSession?) {
        settings.activeSession = s?.let { AppJson.encodeToString(it) }
        _active.value = s
    }

    sealed interface Start {
        data class Running(val session: ActiveSession) : Start
        /** The registry has no enabled words. */
        data object NoWords : Start
        /** A lock session was requested but nothing is due (any more). */
        data object NotNeeded : Start
    }

    /** Returns the running session (upgraded to a lock session if needed) or deals a new one. */
    @Synchronized
    fun startOrResume(kind: SessionKind, now: Long = System.currentTimeMillis()): Start {
        _active.value?.let { s ->
            if (!s.isComplete) {
                val upgraded = if (kind == SessionKind.LOCK && s.kind != SessionKind.LOCK) s.copy(kind = SessionKind.LOCK) else s
                if (upgraded != s) save(upgraded)
                return Start.Running(upgraded)
            }
            finish("completed", now)   // finished but not closed (e.g. app killed on the last word)
        }
        if (kind == SessionKind.LOCK && !lockEngine.isLockDue(now)) return Start.NotNeeded
        val wanted = settings.wordsPerSession
        val picked = words.pickForSession(now, wanted, emptySet())
        if (picked.isEmpty()) return Start.NoWords
        val id = words.createSession(kind.name.lowercase(), now)
        val session = ActiveSession(
            id = id,
            kind = kind,
            target = minOf(wanted, picked.size),
            rule = settings.completionRule,
            queue = picked.map { it.id }.shuffled(),
            startedAt = now,
        )
        save(session)
        return Start.Running(session)
    }

    /** Logs the word's result (a loss is recorded, then we move on to another word). */
    @Synchronized
    fun record(outcome: WordOutcome, now: Long = System.currentTimeMillis()): ActiveSession? {
        val s = _active.value ?: return null
        words.get(outcome.wordId)?.let { word ->
            words.recordAttempt(
                word, s.id, outcome.win, outcome.failedPart, outcome.heard, outcome.meaningAnswer,
                outcome.sentenceAnswer, outcome.feedback, outcome.gradedBy, now,
            )
        }
        var next = s.copy(
            index = s.index + 1,
            wins = s.wins + if (outcome.win) 1 else 0,
            losses = s.losses + if (outcome.win) 0 else 1,
        )
        if (!next.isComplete && next.index >= next.queue.size) {
            // "Get N right" mode ran out of words: deal a different word, or recycle an earlier one.
            val fresh = words.pickForSession(now, 1, next.queue.toSet()).map { it.id }
            val extra = fresh.ifEmpty {
                next.queue.distinct().filter { it != outcome.wordId }.shuffled().take(1).ifEmpty { listOf(outcome.wordId) }
            }
            next = next.copy(queue = next.queue + extra)
        }
        save(next)
        return next
    }

    /** Moves past a word that no longer exists (deleted mid-session) without scoring it. */
    @Synchronized
    fun skipCurrent(now: Long = System.currentTimeMillis()): ActiveSession? {
        val s = _active.value ?: return null
        var next = s.copy(index = s.index + 1)
        if (!next.isComplete && next.index >= next.queue.size) {
            val fresh = words.pickForSession(now, 1, next.queue.toSet()).map { it.id }
            next = if (fresh.isEmpty()) {
                // Nothing left to deal: shrink the goal to what can still be reached.
                next.copy(target = if (next.rule == CompletionRule.ATTEMPTED) next.attempted else next.wins)
            } else {
                next.copy(queue = next.queue + fresh)
            }
        }
        save(next)
        return next
    }

    /** Ends the session; a finished lock session unlocks the phone until the next reset time. */
    @Synchronized
    fun finish(outcome: String, now: Long = System.currentTimeMillis()) {
        val s = _active.value
        if (s != null) {
            words.finishSession(s.id, outcome, now)
            if (s.kind == SessionKind.LOCK || lockEngine.isLockDue(now)) lockEngine.markCompleted(now)
        }
        save(null)
    }

    /** Master-PIN escape: counts as skipped, unlocks until the next reset time. */
    @Synchronized
    fun skipWithPin(now: Long = System.currentTimeMillis()) {
        _active.value?.let { words.finishSession(it.id, "pin_skip", now) }
        save(null)
        lockEngine.markCompleted(now)
    }

    /** Leaves a voluntary practice session (lock sessions can't be abandoned). */
    @Synchronized
    fun abandonPractice(now: Long = System.currentTimeMillis()) {
        val s = _active.value ?: return
        if (s.kind != SessionKind.PRACTICE) return
        words.finishSession(s.id, if (s.attempted > 0) "partial" else "abandoned", now)
        save(null)
    }
}
