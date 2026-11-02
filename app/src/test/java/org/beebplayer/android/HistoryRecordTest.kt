package org.beebplayer.android

import org.junit.Assert.*
import org.junit.Test

class HistoryRecordTest {
    private fun record(id: String, completed: Boolean = false) =
        Progress(Programme(id, "Show", "Episode"), null, 60_000, 120_000, completed, 0)

    @Test fun findsUnfinishedEpisode() {
        assertTrue(hasHistoryRecord(listOf(record("abcdefgh")), "abcdefgh"))
    }

    @Test fun findsCompletedUpNextEpisode() {
        assertTrue(hasHistoryRecord(listOf(record("abcdefgh", completed = true)), "abcdefgh"))
    }

    @Test fun missesUnknownEpisode() {
        assertFalse(hasHistoryRecord(listOf(record("abcdefgh")), "ijklmnop"))
    }

    @Test fun emptyHistoryHasNothing() {
        assertFalse(hasHistoryRecord(emptyList(), "abcdefgh"))
    }
}
