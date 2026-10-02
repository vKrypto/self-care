package com.forma.datasync.sync

import org.junit.Assert.*
import org.junit.Test

class SyncPlannerTest {
    @Test fun backfillStopsAtAnotherSourcesAcknowledgedCursor() {
        val window = SyncPlanner.nextWindow(listOf(100L, 200L, 500L), 900L, 400L)!!
        assertEquals(100L, window.start)
        assertEquals(200L, window.end)
    }

    @Test fun newlyGrantedSourceCanCatchUpWithoutReexportingCurrentSources() {
        var newSourceCursor = 0L
        val acknowledgedSource = 350L
        val windows = mutableListOf<SyncPlanner.Window>()
        while (newSourceCursor < acknowledgedSource) {
            val window = SyncPlanner.nextWindow(listOf(newSourceCursor, acknowledgedSource), 600L, 100L)!!
            windows.add(window)
            // Simulate a server acknowledgement, the only trigger that advances this source.
            newSourceCursor = window.end
        }
        assertEquals(listOf(100L, 200L, 300L, 350L), windows.map { it.end })
        assertTrue(windows.all { it.end <= acknowledgedSource })
    }

    @Test fun alignedSourcesUseOneBoundedWindow() {
        assertEquals(SyncPlanner.Window(100L, 300L), SyncPlanner.nextWindow(listOf(100L, 100L), 500L, 200L))
    }

    @Test fun latestWindowStopsAtNow() {
        assertEquals(SyncPlanner.Window(450L, 500L), SyncPlanner.nextWindow(listOf(450L), 500L, 200L))
    }

    @Test fun noWorkWhenEverySourceIsAcknowledgedOrUnavailable() {
        assertNull(SyncPlanner.nextWindow(emptyList(), 500L, 100L))
        assertNull(SyncPlanner.nextWindow(listOf(500L, 600L), 500L, 100L))
    }

    @Test fun failedUploadKeepsTheExactSameWindowForRetry() {
        val storedCursors = listOf(100L, 200L)
        val original = SyncPlanner.nextWindow(storedCursors, 500L, 100L)
        // A failed request leaves storedCursors unchanged.
        assertEquals(original, SyncPlanner.nextWindow(storedCursors, 500L, 100L))
    }

    @Test fun sizeReductionTerminatesAtTheMinimum() {
        var chunk = SyncPlanner.DAY_MS
        var reductions = 0
        while (true) {
            val reduced = SyncPlanner.smallerChunk(chunk) ?: break
            assertTrue(reduced < chunk)
            chunk = reduced
            reductions++
            assertTrue("Size retries must be bounded", reductions < 32)
        }
        assertEquals(SyncPlanner.MIN_CHUNK_MS, chunk)
        assertNull(SyncPlanner.smallerChunk(chunk))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroLengthChunkCannotCreateAnInfiniteRetryWindow() {
        SyncPlanner.nextWindow(listOf(100L), 500L, 0L)
    }
}
