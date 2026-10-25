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
    val lang: String = "zh",
    val target: Int,
    val rule: CompletionRule,
    val queue: List<Long>,
    val index: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val startedAt: Long,
    /** Endless practice of this one set: words come only from it and the session never completes. */
    val setId: Long? = null,
) {
    val attempted: Int get() = wins + losses
    val endless: Boolean get() = setId != null
    val isComplete: Boolean
        get() = when {
            endless -> false
            rule == CompletionRule.ATTEMPTED -> attempted >= target
            else -> wins >= target
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

    private fun save(session: ActiveSession?): ActiveSession? {
        // An endless session only needs the words still to come and the last few it dealt.
        val s = session?.takeIf { it.endless && it.index > 2 * ENDLESS_GAP }
            ?.let { it.copy(queue = it.queue.drop(it.index - ENDLESS_GAP), index = ENDLESS_GAP) } ?: session
        settings.activeSession = s?.let { AppJson.encodeToString(it) }
        _active.value = s
        return s
    }

    sealed interface Start {
        data class Running(val session: ActiveSession) : Start
        /** No enabled set has any words for the active language. */
        data object NoWords : Start
        /** A lock session was requested but nothing is due (any more). */
        data object NotNeeded : Start
    }

    /**
     * Returns the running session (upgraded to a lock session if needed) or deals a new one.
     * [setId] asks for endless practice of that set. A running lock session always wins; any other
     * practice session that isn't the one asked for is ended first.
     */
    @Synchronized
    fun startOrResume(kind: SessionKind, now: Long = System.currentTimeMillis(), setId: Long? = null): Start {
        require(kind == SessionKind.PRACTICE || setId == null) { "Lock sessions can't be endless" }
        if (kind == SessionKind.LOCK && _active.value?.endless == true && !lockEngine.isLockDue(now)) return Start.NotNeeded
        _active.value?.let { s ->
            when {
                // Finished but not closed (e.g. app killed on the last word).
                s.isComplete -> finish("completed", now)
                // An endless session never completes, so it can't become a lock session.
                s.setId != setId && (kind == SessionKind.LOCK || s.kind == SessionKind.PRACTICE) -> endPractice(s, now)
                else -> {
                    val upgraded = if (kind == SessionKind.LOCK && s.kind != SessionKind.LOCK) s.copy(kind = SessionKind.LOCK) else s
                    if (upgraded != s) save(upgraded)
                    return Start.Running(upgraded)
                }
            }
        }
        if (kind == SessionKind.LOCK && !lockEngine.isLockDue(now)) return Start.NotNeeded
        val lang = if (setId != null) words.set(setId)?.lang ?: return Start.NoWords else settings.activeLanguage
        // Endless practice deals one word at a time, so a missed word can come back soon.
        val wanted = if (setId != null) 1 else settings.wordsPerSession
        val picked = words.pickForSession(lang, now, wanted, emptySet(), setId)
        if (picked.isEmpty()) return Start.NoWords
        val id = words.createSession(kind.name.lowercase(), lang, now)
        val session = ActiveSession(
            id = id,
            kind = kind,
            lang = lang,
            target = minOf(wanted, picked.size),
            rule = settings.completionRule,
            queue = picked.map { it.id }.shuffled(),
            startedAt = now,
            setId = setId,
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
            next = next.copy(queue = next.queue + anotherWord(s, outcome.wordId, now))
        }
        return save(next)
    }

    /**
     * "I don't know this one": moves past the current word without scoring it and deals another
     * in its place, so skipping never brings the unlock closer.
     */
    @Synchronized
    fun skipWord(now: Long = System.currentTimeMillis()): ActiveSession? {
        val s = _active.value ?: return null
        val current = s.currentWordId ?: return s
        val next = s.copy(index = s.index + 1, queue = s.queue + anotherWord(s, current, now))
        return save(next)
    }

    /**
     * A word not dealt yet in this session, or else an earlier one (only [current] if it's the only word).
     * Endless practice only keeps the last few words out, so the set cycles by spaced repetition.
     */
    private fun anotherWord(s: ActiveSession, current: Long, now: Long): Long {
        val recent = if (s.endless) s.queue.takeLast(ENDLESS_GAP) else s.queue
        return words.pickForSession(s.lang, now, 1, recent.toSet() + current, s.setId).firstOrNull()?.id
            ?: recent.distinct().filter { it != current }.randomOrNull()
            ?: current
    }

    /** Moves past a word that no longer exists (deleted mid-session) without scoring it. */
    @Synchronized
    fun skipCurrent(now: Long = System.currentTimeMillis()): ActiveSession? {
        val s = _active.value ?: return null
        var next = s.copy(index = s.index + 1)
        if (!next.isComplete && next.index >= next.queue.size) {
            val gone = setOfNotNull(s.currentWordId)
            val fresh = if (s.endless) {
                words.pickForSession(s.lang, now, 1, gone, s.setId).map { it.id }
            } else {
                words.pickForSession(s.lang, now, 1, next.queue.toSet()).map { it.id }
            }
            if (fresh.isEmpty() && s.endless) {
                // The set has nothing left to practise.
                endPractice(s, now)
                return null
            }
            next = if (fresh.isEmpty()) {
                // Nothing left to deal: shrink the goal to what can still be reached.
                next.copy(target = if (next.rule == CompletionRule.ATTEMPTED) next.attempted else next.wins)
            } else {
                next.copy(queue = next.queue + fresh)
            }
        }
        return save(next)
    }

    /** Ends the session; a finished lock session unlocks the phone until the next reset time. */
    @Synchronized
    fun finish(outcome: String, now: Long = System.currentTimeMillis()) {
        val s = _active.value
        if (s != null) {
            words.finishSession(s.id, outcome, now)
            if (s.kind == SessionKind.LOCK || lockEngine.isLockDue(now)) lockEngine.markCompleted(now, shared = outcome == "completed")
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
        endPractice(s, now)
    }

    private fun endPractice(s: ActiveSession, now: Long) {
        words.finishSession(s.id, if (s.attempted > 0) "partial" else "abandoned", now)
        save(null)
    }

    private companion object {
        /** How many of the last words endless practice keeps from coming straight back. */
        const val ENDLESS_GAP = 4
    }
}
