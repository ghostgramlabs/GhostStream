package com.ghoststream.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDecisionTest {
    @Test
    fun `default playback mode is direct`() {
        val decision = PlaybackDecision()

        assertEquals(PlaybackMode.DIRECT, decision.mode)
    }
}
