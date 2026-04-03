package com.ghoststream.core.media

import com.ghoststream.core.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultSmartPlaybackDecisionEngineTest {
    private val engine = DefaultSmartPlaybackDecisionEngine()

    @Test
    fun `browser safe inspection resolves to direct playback`() {
        val decision = engine.decide(
            MediaInspection(
                originalMimeType = "video/mp4",
                normalizedMimeType = "video/mp4",
                displayName = "movie.mp4",
                extension = "mp4",
                container = MediaContainer.MP4,
                videoTrackMimeType = "video/avc",
                audioTrackMimeType = "audio/mp4a-latm",
                browserSafe = true,
                likelyContainerOnlyIssue = false,
                likelyNeedsTranscode = false,
            ),
        )

        assertEquals(PlaybackMode.DIRECT, decision.mode)
        assertEquals("video/mp4", decision.browserMimeType)
    }

    @Test
    fun `container only issue resolves to remux`() {
        val decision = engine.decide(
            MediaInspection(
                originalMimeType = "video/x-matroska",
                normalizedMimeType = "video/x-matroska",
                displayName = "movie.mkv",
                extension = "mkv",
                container = MediaContainer.MATROSKA,
                videoTrackMimeType = "video/avc",
                audioTrackMimeType = "audio/mp4a-latm",
                browserSafe = false,
                likelyContainerOnlyIssue = true,
                likelyNeedsTranscode = false,
            ),
        )

        assertEquals(PlaybackMode.REMUX, decision.mode)
        assertEquals("video/mp4", decision.browserMimeType)
    }

    @Test
    fun `unsupported video resolves to transcode`() {
        val decision = engine.decide(
            MediaInspection(
                originalMimeType = "video/x-msvideo",
                normalizedMimeType = "video/x-msvideo",
                displayName = "movie.avi",
                extension = "avi",
                container = MediaContainer.OTHER,
                videoTrackMimeType = "video/hevc",
                audioTrackMimeType = "audio/ac3",
                browserSafe = false,
                likelyContainerOnlyIssue = false,
                likelyNeedsTranscode = true,
            ),
        )

        assertEquals(PlaybackMode.TRANSCODE, decision.mode)
        assertEquals("video/mp4", decision.browserMimeType)
    }
}
