package com.hanzilock.core

import java.time.LocalTime
import java.time.ZonedDateTime

/** Daily reset times are stored as minutes after midnight (e.g. 480 = 08:00). */
object ScheduleMath {
    private fun at(date: java.time.LocalDate, minutes: Int, zone: java.time.ZoneId): ZonedDateTime =
        date.atTime(LocalTime.of(minutes / 60, minutes % 60)).atZone(zone)

    /** The latest reset at or before [now] (today, or yesterday's last one), or null if none are set. */
    fun mostRecentReset(times: List<Int>, now: ZonedDateTime): ZonedDateTime? {
        if (times.isEmpty()) return null
        val sorted = times.sortedDescending()
        for (daysBack in 0L..1L) {
            val date = now.toLocalDate().minusDays(daysBack)
            sorted.map { at(date, it, now.zone) }.firstOrNull { !it.isAfter(now) }?.let { return it }
        }
        return null
    }

    /** The first reset strictly after [now]. */
    fun nextReset(times: List<Int>, now: ZonedDateTime): ZonedDateTime? {
        if (times.isEmpty()) return null
        val sorted = times.sorted()
        for (daysAhead in 0L..1L) {
            val date = now.toLocalDate().plusDays(daysAhead)
            sorted.map { at(date, it, now.zone) }.firstOrNull { it.isAfter(now) }?.let { return it }
        }
        return null
    }

    /** [count] times spread evenly from [firstMinute] to [lastMinute] (both included). */
    fun evenlySpaced(count: Int, firstMinute: Int, lastMinute: Int): List<Int> {
        val first = firstMinute.coerceIn(0, 24 * 60 - 1)
        val last = lastMinute.coerceIn(first, 24 * 60 - 1)
        if (count <= 1) return listOf(first)
        return (0 until count).map { first + (last - first) * it / (count - 1) }.distinct()
    }

    fun format(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
}
