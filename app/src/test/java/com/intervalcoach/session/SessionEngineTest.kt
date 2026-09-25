package com.intervalcoach.session

import org.junit.Assert.*
import org.junit.Test

class SessionEngineTest {
    private var now = 100_000L
    private val engine = SessionEngine { now }
    private fun intervals(vararg seconds: Int) = seconds.mapIndexed { index, duration -> SessionInterval(if (index % 2 == 0) "Walk" else "Run", duration) }
    @Test fun singleIntervalAndCompletion() {
        assertEquals(1, engine.start(1, "One", intervals(5)).cues.size)
        now += 5_000
        assertEquals(listOf(SessionCue.Done), engine.tick().cues)
        assertTrue(engine.snapshot!!.complete)
    }
    @Test fun severalIntervalsAdvanceWithoutDrift() {
        engine.start(1, "Several", intervals(5, 7, 9))
        now += 5_250
        val update = engine.tick()
        assertEquals(1, update.snapshot!!.index)
        assertEquals(6_750, update.snapshot.remainingMs)
        assertEquals(listOf(SessionCue.Announce(SessionInterval("Run", 7), true)), update.cues)
        now += 16_000
        assertEquals(listOf(SessionCue.Done), engine.tick().cues)
    }
    @Test fun pauseResumeAndCueBoundary() {
        engine.start(1, "Pause", intervals(20))
        now += 9_900
        engine.pause()
        now += 30_000
        assertTrue(engine.tick().cues.isEmpty())
        assertEquals(10_100, engine.snapshot!!.remainingMs)
        engine.resume()
        now += 100
        assertEquals(listOf(SessionCue.TenSeconds), engine.tick().cues)
        assertTrue(engine.tick().cues.isEmpty())
    }
    @Test fun tenAndThreeTwoOneAreOnceEach() {
        engine.start(1, "Cues", intervals(20))
        now += 10_000
        assertEquals(listOf(SessionCue.TenSeconds), engine.tick().cues)
        for (n in 3 downTo 1) {
            now = 100_000L + (20 - n) * 1000L
            assertEquals(listOf(SessionCue.Count(n)), engine.tick().cues)
            assertTrue(engine.tick().cues.isEmpty())
        }
    }
    @Test fun shortIntervalsSkipCrowdedCues() {
        engine.start(1, "Short", intervals(10, 2))
        assertTrue(engine.tick().cues.isEmpty())
        now += 7_000
        assertEquals(listOf(SessionCue.Count(3)), engine.tick().cues)
        now += 3_000
        assertEquals(1, engine.tick().snapshot!!.index)
        assertTrue(engine.tick().cues.isEmpty())
    }
    @Test fun nextPreviousStopAndStaleCueReset() {
        engine.start(1, "Navigation", intervals(20, 20))
        now += 10_000
        engine.tick()
        assertEquals(1, engine.next().snapshot!!.index)
        assertEquals(20_000, engine.snapshot!!.remainingMs)
        assertEquals(0, engine.previous().snapshot!!.index)
        assertEquals(20_000, engine.snapshot!!.remainingMs)
        assertTrue(engine.tick().cues.isEmpty())
        assertNull(engine.stop().snapshot)
    }
    @Test fun skipAndPauseNearCueBoundariesDoNotReplayOldCues() {
        engine.start(1, "Boundaries", intervals(20, 20))
        now += 9_999
        engine.next()
        now += 10_000
        assertEquals(listOf(SessionCue.TenSeconds), engine.tick().cues)
        engine.pause()
        now += 10_000
        assertTrue(engine.tick().cues.isEmpty())
        engine.resume()
        assertTrue(engine.tick().cues.isEmpty())
        now += 7_000
        assertEquals(listOf(SessionCue.Count(3)), engine.tick().cues)
    }
    @Test fun tooShortForCountdownAndPreviousAtFirst() {
        engine.start(1, "Tiny", intervals(2))
        assertEquals(listOf(SessionCue.Announce(SessionInterval("Walk", 2), false)), engine.previous().cues)
        now += 1_000
        assertTrue(engine.tick().cues.isEmpty())
        now += 1_000
        assertEquals(listOf(SessionCue.Done), engine.tick().cues)
    }
    @Test fun restoreKeepsCueFlagsAndCatchesUp() {
        engine.start(1, "Restore", intervals(20, 20))
        now += 10_000
        engine.tick()
        val saved = engine.snapshot!!
        val restored = SessionEngine { now }
        assertTrue(restored.restore(saved).cues.isEmpty())
        now += 10_000
        assertEquals(listOf(SessionCue.Announce(SessionInterval("Run", 20), true)), restored.tick().cues)
    }
    @Test fun announcementGrammarAndCustomText() {
        assertEquals("1 minute walk.", announcement(SessionInterval("Walk", 60), false))
        assertEquals("Next exercise. 2 minutes and 1 second hill climb, steady.", announcement(SessionInterval("Hill climb", 121, "Steady"), true))
        assertEquals("30 seconds", spokenDuration(30))
    }
}
