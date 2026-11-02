package org.beebplayer.android

import org.junit.Assert.*
import org.junit.Test

class FriendlyErrorTest {
    @Test fun offlineCauseMapsToPlainLanguage() {
        assertEquals(
            "You're offline. Check your connection, then try again.",
            friendlyError(RuntimeException("fetch failed", java.net.UnknownHostException("example.com")))
        )
        assertEquals(
            "You're offline. Check your connection, then try again.",
            friendlyError(java.net.ConnectException("Failed to connect"))
        )
    }

    @Test fun timeoutMessageMapsToPlainLanguage() {
        assertEquals(
            "The connection timed out. Try again.",
            friendlyError(RuntimeException("connection timed out"))
        )
    }

    @Test fun restrictedNetworkMapsToPlainLanguage() {
        assertEquals(
            "This content is blocked on your network. Try again later.",
            friendlyError(RuntimeException("Request failed: HTTP 403"))
        )
        assertEquals(
            "The programme service is having trouble. Try again shortly.",
            friendlyError(RuntimeException("Request failed: HTTP 503"))
        )
    }

    @Test fun technicalMessagesNeverLeakRaw() {
        assertEquals("Something went wrong. Try again.", friendlyError(RuntimeException()))
        assertEquals(
            "Something went wrong. Try again.",
            friendlyError(RuntimeException("androidx.media3.exoplayer.ExoPlaybackException: Source error"))
        )
        assertEquals("Something went wrong. Try again.", friendlyError(RuntimeException("x".repeat(200))))
    }

    @Test fun shortPlainMessagesPassThrough() {
        assertEquals("Nothing here yet.", friendlyError(RuntimeException("Nothing here yet.")))
    }
}
