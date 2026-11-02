package org.beebplayer.android

import org.junit.Assert.*
import org.junit.Test

class PlaybackMemoryTest {
    @Test fun buffersStayWithinLowMemoryBudget() {
        assertTrue(PlaybackMemory.MIN_BUFFER_MS <= 20_000)
        assertTrue(PlaybackMemory.MAX_BUFFER_MS <= 30_000)
        assertTrue(PlaybackMemory.MIN_BUFFER_MS <= PlaybackMemory.MAX_BUFFER_MS)
        assertEquals(0, PlaybackMemory.BACK_BUFFER_MS)
        assertTrue(PlaybackMemory.TARGET_BUFFER_BYTES <= 16 * 1024 * 1024)
    }

    @Test fun loadControlFactoryBuilds() {
        assertNotNull(PlaybackMemory.createLoadControl())
    }
}
