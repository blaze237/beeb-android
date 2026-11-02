// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Low-memory buffering for 1st-gen Fire TV Stick 4K (Fire OS 6 / 1.5 GB RAM).
 *
 * Stock Media3 defaults buffer ~50s of media, which exhausts the small
 * per-app Java heap on mantis and surfaces as
 * ERROR_CODE_IO_UNSPECIFIED caused by OutOfMemoryError mid-playback.
 * Keep the same behaviour with a smaller ring: ~15-30s forward, no back
 * buffer retained, and an explicit byte ceiling so high-bitrate variants
 * cannot pile up byte arrays even inside the time window.
 */
@OptIn(UnstableApi::class)
object PlaybackMemory {
    const val MIN_BUFFER_MS = 15_000
    const val MAX_BUFFER_MS = 30_000
    const val BUFFER_FOR_PLAYBACK_MS = 2_500
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000
    const val BACK_BUFFER_MS = 0
    const val TARGET_BUFFER_BYTES = 12 * 1024 * 1024

    fun createLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setBackBuffer(BACK_BUFFER_MS, false)
        .setTargetBufferBytes(TARGET_BUFFER_BYTES)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
}
