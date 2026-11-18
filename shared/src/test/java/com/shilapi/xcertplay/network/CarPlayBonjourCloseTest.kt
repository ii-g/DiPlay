package com.shilapi.xcertplay.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayBonjourCloseTest {
    @Test fun fastClosesFinishWithinBudgetWithPerActionTimings() {
        val lateFinishCalled = AtomicBoolean(false)
        val result = runBoundedCloses(
            closes = listOf({}, { Thread.sleep(50) }),
            timeoutMillis = 5_000L,
            threadName = "test-bounded-close-fast",
            onLateFinish = { lateFinishCalled.set(true) },
        )
        assertFalse(result.timedOut)
        assertEquals(2, result.perCloseMs.size)
        assertTrue("first close recorded as ${result.perCloseMs[0]}ms", result.perCloseMs[0] >= 0)
        assertTrue("50ms close recorded as ${result.perCloseMs[1]}ms", result.perCloseMs[1] >= 40)
        Thread.sleep(200)
        assertFalse("onLateFinish must not run when the closes finish in time", lateFinishCalled.get())
    }

    @Test fun slowCloseReturnsWithinBudgetAndReportsRealTimingsLate() {
        val lateTimings = AtomicReference<List<Long>?>(null)
        val late = CountDownLatch(1)
        val started = System.nanoTime()
        val result = runBoundedCloses(
            closes = listOf({ Thread.sleep(600) }),
            timeoutMillis = 100L,
            threadName = "test-bounded-close-slow",
            onLateFinish = { timings -> lateTimings.set(timings); late.countDown() },
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.timedOut)
        assertTrue(result.perCloseMs.isEmpty())
        assertTrue("bounded close took ${elapsedMs}ms", elapsedMs < 500)
        assertTrue("late finish never arrived", late.await(5, TimeUnit.SECONDS))
        val timings = lateTimings.get() ?: error("no late timings")
        assertEquals(1, timings.size)
        assertTrue("slow close recorded as ${timings[0]}ms", timings[0] >= 500)
    }

    @Test fun failingCloseDoesNotSkipRemainingCloses() {
        val secondRan = AtomicBoolean(false)
        val result = runBoundedCloses(
            closes = listOf({ throw IllegalStateException("boom") }, { secondRan.set(true) }),
            timeoutMillis = 5_000L,
            threadName = "test-bounded-close-failing",
            onLateFinish = { error("must not be called when the closes finish in time") },
        )
        assertFalse(result.timedOut)
        assertEquals(2, result.perCloseMs.size)
        assertTrue("second close never ran after the first threw", secondRan.get())
    }

    @Test fun hungCloseReturnsWithinItsCap() {
        // A stuck JmDNS close must never hold the teardown budget hostage: the bounded
        // wait returns after JMDNS_CLOSE_TIMEOUT_MILLIS even when the close never
        // finishes (here modelled by a sleep far longer than any test run).
        val started = System.nanoTime()
        val result = runBoundedCloses(
            closes = listOf({ Thread.sleep(30_000) }),
            timeoutMillis = JMDNS_CLOSE_TIMEOUT_MILLIS,
            threadName = "test-bounded-close-hung",
            onLateFinish = {},
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.timedOut)
        assertTrue(
            "bounded close took ${elapsedMs}ms for a ${JMDNS_CLOSE_TIMEOUT_MILLIS}ms cap",
            elapsedMs < JMDNS_CLOSE_TIMEOUT_MILLIS + 1_000,
        )
    }
}
