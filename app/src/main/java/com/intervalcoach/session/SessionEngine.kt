package com.intervalcoach.session

import kotlin.math.ceil

fun interface MonotonicClock { fun now(): Long }

data class SessionInterval(val activity: String, val durationSeconds: Int, val instruction: String = "")
data class SessionSnapshot(
    val workoutId: Long, val workoutName: String, val intervals: List<SessionInterval>,
    val index: Int = 0, val remainingMs: Long = intervals.first().durationSeconds * 1000L,
    val targetMs: Long = 0L, val paused: Boolean = false, val complete: Boolean = false,
    val tenSpoken: Boolean = false, val countdownSpoken: Set<Int> = emptySet()
) {
    val current get() = intervals[index]
    val next get() = intervals.getOrNull(index + 1)
}

sealed interface SessionCue {
    data class Announce(val interval: SessionInterval, val next: Boolean) : SessionCue
    data object TenSeconds : SessionCue
    data class Count(val number: Int) : SessionCue
    data object Done : SessionCue
}

data class EngineUpdate(val snapshot: SessionSnapshot?, val cues: List<SessionCue> = emptyList())

class SessionEngine(private val clock: MonotonicClock) {
    var snapshot: SessionSnapshot? = null
        private set
    fun start(workoutId: Long, name: String, intervals: List<SessionInterval>): EngineUpdate {
        require(intervals.isNotEmpty() && intervals.all { it.durationSeconds > 0 })
        val first = intervals.first()
        snapshot = SessionSnapshot(workoutId, name, intervals, targetMs = clock.now() + first.durationSeconds * 1000L)
        return EngineUpdate(snapshot, listOf(SessionCue.Announce(first, false)))
    }
    fun restore(saved: SessionSnapshot): EngineUpdate {
        snapshot = saved
        return tick() // Catch up from the persisted monotonic target without repeating prior cues.
    }
    fun tick(): EngineUpdate {
        var s = snapshot ?: return EngineUpdate(null)
        if (s.paused || s.complete) return EngineUpdate(s)
        val now = clock.now()
        val cues = mutableListOf<SessionCue>()
        // Advance at the original target, not at the late tick time, to avoid drift.
        while (now >= s.targetMs && !s.complete) {
            val next = s.intervals.getOrNull(s.index + 1)
            if (next == null) {
                s = s.copy(remainingMs = 0, complete = true)
                cues += SessionCue.Done
            } else {
                s = s.copy(index = s.index + 1, remainingMs = next.durationSeconds * 1000L,
                    targetMs = s.targetMs + next.durationSeconds * 1000L, tenSpoken = false, countdownSpoken = emptySet())
                if (now < s.targetMs) cues += SessionCue.Announce(next, true)
            }
        }
        if (!s.complete) {
            val remaining = (s.targetMs - now).coerceAtLeast(0)
            val seconds = ceil(remaining / 1000.0).toInt()
            // Warn only after the interval has actually had time to start; skip on intervals <=10 s.
            if (s.current.durationSeconds > 10 && seconds <= 10 && !s.tenSpoken) {
                cues += SessionCue.TenSeconds
                s = s.copy(tenSpoken = true)
            }
            if (s.current.durationSeconds > 3 && seconds in 1..3 && seconds !in s.countdownSpoken) {
                cues += SessionCue.Count(seconds)
                s = s.copy(countdownSpoken = s.countdownSpoken + seconds)
            }
            s = s.copy(remainingMs = remaining)
        }
        snapshot = s
        return EngineUpdate(s, cues)
    }
    fun pause(): EngineUpdate {
        val s = snapshot ?: return EngineUpdate(null)
        if (!s.paused && !s.complete) snapshot = s.copy(remainingMs = (s.targetMs - clock.now()).coerceAtLeast(0), paused = true)
        return EngineUpdate(snapshot)
    }
    fun resume(): EngineUpdate {
        val s = snapshot ?: return EngineUpdate(null)
        if (s.paused && !s.complete) snapshot = s.copy(targetMs = clock.now() + s.remainingMs, paused = false)
        return EngineUpdate(snapshot)
    }
    fun next(): EngineUpdate = navigate(1)
    fun previous(): EngineUpdate = navigate(-1)
    private fun navigate(direction: Int): EngineUpdate {
        val s = snapshot ?: return EngineUpdate(null)
        if (s.complete) return EngineUpdate(s)
        val index = (s.index + direction).coerceIn(s.intervals.indices)
        if (direction > 0 && index == s.index) {
            snapshot = s.copy(complete = true, remainingMs = 0)
            return EngineUpdate(snapshot, listOf(SessionCue.Done))
        }
        val item = s.intervals[index]
        snapshot = s.copy(index = index, remainingMs = item.durationSeconds * 1000L,
            targetMs = clock.now() + item.durationSeconds * 1000L, paused = false,
            tenSpoken = false, countdownSpoken = emptySet())
        return EngineUpdate(snapshot, listOf(SessionCue.Announce(item, direction > 0)))
    }
    fun stop(): EngineUpdate { snapshot = null; return EngineUpdate(null) }
}

fun spokenDuration(seconds: Int): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    val parts = mutableListOf<String>()
    if (minutes > 0) parts += "$minutes ${if (minutes == 1) "minute" else "minutes"}"
    if (rest > 0) parts += "$rest ${if (rest == 1) "second" else "seconds"}"
    return parts.joinToString(" and ")
}
fun announcement(interval: SessionInterval, next: Boolean): String =
    "${if (next) "Next exercise. " else ""}${spokenDuration(interval.durationSeconds)} ${interval.activity.trim().lowercase()}${interval.instruction.trim().takeIf { it.isNotEmpty() }?.let { ", ${it.lowercase()}" } ?: ""}."
