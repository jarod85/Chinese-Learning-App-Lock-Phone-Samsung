package com.hanzilock.core

import java.time.Instant
import java.time.ZoneId

/**
 * Decides whether the phone should currently be gated.
 *
 * The lock is due when a daily reset time has passed since the last finished session - so if
 * several reset times pass while the phone is off, one session clears them all.
 */
class LockEngine(
    private val settings: Settings,
    private val pinSet: () -> Boolean,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    data class Status(
        val enabled: Boolean,
        val due: Boolean,
        val manual: Boolean,
        val pausedUntil: Long?,
        val nextResetAt: Long?,
        val lastCompletedAt: Long,
    )

    fun isLockDue(now: Long = System.currentTimeMillis()): Boolean {
        if (!pinSet()) return false          // never lock without a way out
        if (settings.manualLock) return true
        if (!settings.lockEnabled) return false
        if (settings.pausedUntil > now) return false
        val last = ScheduleMath.mostRecentReset(settings.resetTimes, Instant.ofEpochMilli(now).atZone(zone()))
            ?: return false
        return settings.lastCompletedAt < last.toInstant().toEpochMilli()
    }

    fun nextResetAt(now: Long = System.currentTimeMillis()): Long? =
        ScheduleMath.nextReset(settings.resetTimes, Instant.ofEpochMilli(now).atZone(zone()))?.toInstant()?.toEpochMilli()

    fun status(now: Long = System.currentTimeMillis()) = Status(
        enabled = settings.lockEnabled,
        due = isLockDue(now),
        manual = settings.manualLock,
        pausedUntil = settings.pausedUntil.takeIf { it > now },
        nextResetAt = nextResetAt(now),
        lastCompletedAt = settings.lastCompletedAt,
    )

    fun markCompleted(now: Long = System.currentTimeMillis()) {
        settings.lastCompletedAt = now
        settings.manualLock = false
    }

    fun lockNow() {
        settings.manualLock = true
    }

    fun pauseUntil(until: Long) {
        settings.pausedUntil = until
        settings.manualLock = false
    }

    fun resume() {
        settings.pausedUntil = 0
    }

    /** Turning the lock on doesn't lock immediately; the first session is at the next reset time. */
    fun setEnabled(enabled: Boolean, now: Long = System.currentTimeMillis()) {
        if (enabled && !settings.lockEnabled) settings.lastCompletedAt = maxOf(settings.lastCompletedAt, now)
        settings.lockEnabled = enabled
        if (!enabled) settings.manualLock = false
    }

    /** Changing the schedule shouldn't surprise-lock you for a time that already passed today. */
    fun updateSchedule(times: List<Int>, now: Long = System.currentTimeMillis()) {
        val wasDue = isLockDue(now)
        settings.resetTimes = times
        if (!wasDue) settings.lastCompletedAt = maxOf(settings.lastCompletedAt, now)
    }
}
