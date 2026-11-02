package org.beebplayer.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LiveScheduleTest {
    private fun broadcast(start: String, end: String, title: String?, blanked: Boolean = false): JSONObject {
        val episode = JSONObject()
        if (title != null) episode.put("title", title)
        return JSONObject().put("scheduled_start", start).put("scheduled_end", end)
            .put("blanked", blanked).put("episode", episode)
    }

    @Test fun currentNextAndProgressComeFromScheduleOrder() {
        val now = Instant.parse("2026-05-01T12:30:00Z")
        val elements = listOf(
            broadcast("2026-05-01T13:00:00Z", "2026-05-01T14:00:00Z", "Next Show"),
            broadcast("2026-05-01T12:00:00Z", "2026-05-01T13:00:00Z", "Now Show"),
            broadcast("2026-05-01T12:00:00Z", "2026-05-01T13:00:00Z", "Blanked", blanked = true),
            broadcast("2026-05-01T11:00:00Z", "2026-05-01T12:00:00Z", "Earlier")
        )
        val slot = Parsing.broadcast(elements, now)
        assertEquals("Now Show", slot.current?.optJSONObject("episode")?.optString("title"))
        assertEquals("Next Show", slot.nextTitle)
        assertNotNull(slot.progress)
        assertEquals(0.5f, slot.progress!!, 0.001f)
    }

    @Test fun missingOrMalformedScheduleYieldsEmptyNextAndNullProgress() {
        val now = Instant.parse("2026-05-01T12:30:00Z")
        val empty = Parsing.broadcast(emptyList(), now)
        assertNull(empty.current)
        assertEquals("", empty.nextTitle)
        assertNull(empty.progress)
        val malformed = Parsing.broadcast(listOf(broadcast("not-a-time", "also-bad", "Broken")), now)
        assertNull(malformed.current)
        assertEquals("", malformed.nextTitle)
        assertNull(malformed.progress)
        val zeroLength = Parsing.broadcast(listOf(broadcast("2026-05-01T12:00:00Z", "2026-05-01T12:00:00Z", "Zero")), Instant.parse("2026-05-01T12:00:00Z"))
        assertNull(zeroLength.progress)
    }

    @Test fun channelKeepsBackwardsCompatibleDefaults() {
        val channel = Channel("bbc_one_london", "BBC One")
        assertEquals("BBC One", channel.title)
        assertEquals("", channel.nextTitle)
        assertNull(channel.progress)
    }
}
