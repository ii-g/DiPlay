package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class SessionFlapGuardTest {
    private var now = 0L
    private var stored = emptyList<Long>()
    private fun guard() = SessionFlapGuard({ stored }, { stored = it }, { now })

    @Test fun thirdDeathWithinTenMinutesTrips() {
        val guard = guard()
        assertFalse(guard.recordDeath())
        now += 60_000
        assertFalse(guard.recordDeath())
        now += 60_000
        assertTrue(guard.recordDeath())
        assertTrue(guard.isTripped())
        assertEquals(listOf(0L, 60_000L, 120_000L), stored)
    }

    @Test fun deathsOlderThanTenMinutesAgeOut() {
        val guard = guard()
        guard.recordDeath()
        now += 60_000
        guard.recordDeath()
        now = 10 * 60_000L
        assertFalse(guard.isTripped())
        assertFalse(guard.recordDeath())
        now += 60_000
        assertFalse(guard.recordDeath())
        now += 60_000
        assertTrue(guard.recordDeath())
    }

    @Test fun aDeathExactlyTenMinutesOldIsGone() {
        val guard = guard()
        guard.recordDeath()
        now = 10 * 60_000L
        assertFalse(guard.isTripped())
        assertFalse(guard.recordDeath())
        assertEquals(listOf(600_000L), stored)
    }

    @Test fun duplicateReportsOfOneLossCountOnce() {
        val guard = guard()
        assertFalse(guard.recordDeath())
        now += 1_000
        assertFalse(guard.recordDeath())
        assertEquals(listOf(0L), stored)
        now += 1_000
        assertFalse(guard.recordDeath())
        assertEquals(listOf(0L, 2_000L), stored)
    }

    @Test fun duplicateReportAfterTheTripStillReadsTripped() {
        val guard = guard()
        repeat(3) { guard.recordDeath(); now += 60_000 }
        assertTrue(guard.isTripped())
        now = 121_000
        assertTrue(guard.recordDeath())
        assertEquals(3, stored.size)
    }

    @Test fun manualRetryClearsTheTrip() {
        val guard = guard()
        repeat(3) { guard.recordDeath(); now += 60_000 }
        assertTrue(guard.isTripped())
        guard.clear()
        assertFalse(guard.isTripped())
        assertTrue(stored.isEmpty())
        assertFalse(guard.recordDeath())
    }

    @Test fun aNewGuardOverTheSameStoreSeesTheTrip() {
        val guard = guard()
        repeat(3) { guard.recordDeath(); now += 60_000 }
        assertTrue(SessionFlapGuard({ stored }, { stored = it }, { now }).isTripped())
    }

    @Test fun futureTimestampsDoNotTrip() {
        stored = listOf(now + 60_000L, now + 120_000L, now + 180_000L)
        assertFalse(guard().isTripped())
    }

    @Test fun persistedDeathsSurviveANewGuardInstance() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        val clock = object { var now = 0L }
        val first = SessionFlapGuard.fromContext(app) { clock.now }
        repeat(3) { first.recordDeath(); clock.now += 3_000 }
        assertTrue(first.isTripped())
        assertEquals("0,3000,6000", prefs.getString("session_flap_death_millis", null))
        val recreated = SessionFlapGuard.fromContext(app) { clock.now }
        assertTrue(recreated.isTripped())
        recreated.clear()
        assertFalse(SessionFlapGuard.fromContext(app) { clock.now }.isTripped())
        assertEquals("", prefs.getString("session_flap_death_millis", null))
    }

    @Test fun malformedStoredValueIsIgnored() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putString("session_flap_death_millis", "not-a-time,,123abc").apply()
        val guard = SessionFlapGuard.fromContext(app) { now }
        assertFalse(guard.isTripped())
        assertFalse(guard.recordDeath())
    }
}
