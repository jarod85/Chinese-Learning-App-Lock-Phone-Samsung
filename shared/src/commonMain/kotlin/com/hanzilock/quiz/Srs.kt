package com.hanzilock.quiz

/**
 * Leitner-style spaced repetition. Box 0 = never tested. A miss sends the word back to box 1
 * (due again in 10 minutes, so it can come back in the next lock session); each win moves it
 * one box up.
 */
object Srs {
    private val INTERVAL_MINUTES = longArrayOf(
        0, 10, 4 * 60, 24 * 60, 3 * 1440, 7 * 1440, 16 * 1440, 35 * 1440, 75 * 1440,
    )
    val MAX_BOX = INTERVAL_MINUTES.lastIndex

    /** Returns the new box and the time the word is due again. */
    fun schedule(box: Int, correct: Boolean, now: Long): Pair<Int, Long> {
        val next = if (correct) maxOf(box + 1, 2).coerceAtMost(MAX_BOX) else 1
        return next to now + INTERVAL_MINUTES[next] * 60_000L
    }
}
