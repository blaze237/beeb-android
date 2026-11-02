package org.beebplayer.android

import org.junit.Assert.*
import org.junit.Test

class CenterKeyActionTest {
    @Test fun plainFocusTogglesPlayPause() {
        assertEquals(CenterKeyAction.TOGGLE_PLAY_PAUSE, centerKeyAction(focusedIsAction = false))
    }

    @Test fun focusedActionActivatesInsteadOfToggling() {
        assertEquals(CenterKeyAction.ACTIVATE_FOCUSED, centerKeyAction(focusedIsAction = true))
    }
}
