package com.ghoststream.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class HlsReadinessValidatorTest {

    @Test
    fun `validateFragmentedMp4Playback accepts first committed fragment`() {
        val ready = HlsReadinessValidator.validateFragmentedMp4Playback(
            FragmentedMp4HlsIndex(
                initSegmentLength = 1024,
                segments = listOf(
                    HlsMediaSegment(index = 0, offset = 1024, length = 4096, durationSeconds = 2.0),
                ),
                fileLength = 5120,
                videoCodecString = null,
            ),
        )

        assertTrue(ready.isReady)
        assertEquals(1, ready.segmentCount)
    }

    @Test
    fun `validate rejects incomplete fragmented output`() {
        val notReady = HlsReadinessValidator.validate(
            FragmentedMp4HlsIndex(
                initSegmentLength = 0,
                segments = listOf(
                    HlsMediaSegment(index = 0, offset = 1024, length = 2048, durationSeconds = 2.0),
                ),
                fileLength = 4096,
                videoCodecString = null,
            ),
        )

        assertFalse(notReady.isReady)
    }

    @Test
    fun `validate accepts committed streamable output`() {
        val ready = HlsReadinessValidator.validate(
            FragmentedMp4HlsIndex(
                initSegmentLength = 1024,
                segments = listOf(
                    HlsMediaSegment(index = 0, offset = 1024, length = 4096, durationSeconds = 2.0),
                    HlsMediaSegment(index = 1, offset = 5120, length = 4096, durationSeconds = 2.0),
                ),
                fileLength = 9216,
                videoCodecString = "avc1.640028",
            ),
        )

        assertTrue(ready.isReady)
    }
}
