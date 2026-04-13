package com.ghoststream.core.media

import com.ghoststream.core.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultSmartPlaybackDecisionEngineTest {
    private val engine = DefaultSmartPlaybackDecisionEngine()

    private fun inspect(
        container: MediaContainer,
        video: String? = "video/avc",
        audio: String? = "audio/mp4a-latm",
        mime: String = "video/mp4",
        name: String = "movie.mp4",
    ) = MediaInspection(
        originalMimeType = mime,
        normalizedMimeType = mime,
        displayName = name,
        extension = name.substringAfterLast('.'),
        container = container,
        videoTrackMimeType = video,
        audioTrackMimeType = audio,
        browserSafe = container == MediaContainer.MP4,
        likelyContainerOnlyIssue = false,
        likelyNeedsTranscode = false,
    )

    // ── Tier 0: DIRECT ───────────────────────────────────────────────────────

    @Test
    fun `MP4 + H264 + AAC resolves to DIRECT`() {
        val d = engine.decide(inspect(MediaContainer.MP4))
        assertEquals(PlaybackMode.DIRECT, d.mode)
        assertEquals("video/mp4", d.browserMimeType)
    }

    @Test
    fun `MP4 + H264 + MP3 resolves to DIRECT`() {
        val d = engine.decide(inspect(MediaContainer.MP4, audio = "audio/mpeg"))
        assertEquals(PlaybackMode.DIRECT, d.mode)
    }

    @Test
    fun `MP4 + H264 + no audio resolves to DIRECT`() {
        val d = engine.decide(inspect(MediaContainer.MP4, audio = null))
        assertEquals(PlaybackMode.DIRECT, d.mode)
    }

    @Test
    fun `MOV + H264 + AAC resolves to DIRECT (QuickTime is browser-safe)`() {
        val d = engine.decide(inspect(MediaContainer.QUICKTIME, mime = "video/quicktime", name = "movie.mov"))
        assertEquals(PlaybackMode.DIRECT, d.mode)
        assertEquals("video/mp4", d.browserMimeType)
    }

    @Test
    fun `MOV + H264 + MP3 resolves to DIRECT`() {
        val d = engine.decide(inspect(MediaContainer.QUICKTIME, audio = "audio/mpeg", name = "movie.mov"))
        assertEquals(PlaybackMode.DIRECT, d.mode)
    }

    // ── Tier 0 exclusions: things that must NOT be DIRECT ────────────────────

    @Test
    fun `MP4 + HEVC + AAC resolves to TRANSCODE (HEVC not universally supported)`() {
        val d = engine.decide(inspect(MediaContainer.MP4, video = "video/hevc"))
        assertEquals(PlaybackMode.TRANSCODE, d.mode)
    }

    @Test
    fun `MP4 + HEVC + AAC (hvc1 tag) resolves to TRANSCODE`() {
        val d = engine.decide(inspect(MediaContainer.MP4, video = "video/hvc1"))
        assertEquals(PlaybackMode.TRANSCODE, d.mode)
    }

    @Test
    fun `MP4 + H264 + Opus resolves to REMUX (Opus excluded from DIRECT)`() {
        // Opus fails in Safari and some Android WebViews — remux to transcode audio to AAC.
        val d = engine.decide(inspect(MediaContainer.MP4, audio = "audio/opus"))
        assertEquals(PlaybackMode.REMUX, d.mode)
    }

    @Test
    fun `MP4 + H264 + AC3 resolves to REMUX (AC3 not browser-safe)`() {
        val d = engine.decide(inspect(MediaContainer.MP4, audio = "audio/ac3"))
        assertEquals(PlaybackMode.REMUX, d.mode)
    }

    @Test
    fun `MP4 + H264 + DTS resolves to REMUX`() {
        val d = engine.decide(inspect(MediaContainer.MP4, audio = "audio/dtshd-ma"))
        assertEquals(PlaybackMode.REMUX, d.mode)
    }

    // ── Tier 1 & 2: REMUX ────────────────────────────────────────────────────

    @Test
    fun `MKV + H264 + AAC resolves to REMUX (container-only issue)`() {
        val d = engine.decide(inspect(MediaContainer.MATROSKA, mime = "video/x-matroska", name = "movie.mkv"))
        assertEquals(PlaybackMode.REMUX, d.mode)
        assertEquals("video/mp4", d.browserMimeType)
    }

    @Test
    fun `TS + H264 + AAC resolves to REMUX`() {
        val d = engine.decide(inspect(MediaContainer.TS, mime = "video/mp2t", name = "movie.ts"))
        assertEquals(PlaybackMode.REMUX, d.mode)
    }

    @Test
    fun `AVI + H264 + AAC resolves to REMUX`() {
        val d = engine.decide(inspect(MediaContainer.AVI, mime = "video/x-msvideo", name = "movie.avi"))
        assertEquals(PlaybackMode.REMUX, d.mode)
    }

    @Test
    fun `MKV + H264 + DTS resolves to REMUX (video copy + audio transcode)`() {
        val d = engine.decide(inspect(MediaContainer.MATROSKA, audio = "audio/dtshd-ma", name = "movie.mkv"))
        assertEquals(PlaybackMode.REMUX, d.mode)
    }

    @Test
    fun `MKV + H264 + EAC3 resolves to REMUX`() {
        val d = engine.decide(inspect(MediaContainer.MATROSKA, audio = "audio/eac3", name = "movie.mkv"))
        assertEquals(PlaybackMode.REMUX, d.mode)
    }

    @Test
    fun `MOV + H264 + AC3 resolves to REMUX (audio needs transcoding)`() {
        val d = engine.decide(inspect(MediaContainer.QUICKTIME, audio = "audio/ac3", name = "movie.mov"))
        assertEquals(PlaybackMode.REMUX, d.mode)
    }

    // ── Tier 3 & 4: TRANSCODE ────────────────────────────────────────────────

    @Test
    fun `MKV + HEVC + AAC resolves to TRANSCODE`() {
        val d = engine.decide(inspect(MediaContainer.MATROSKA, video = "video/hevc", name = "movie.mkv"))
        assertEquals(PlaybackMode.TRANSCODE, d.mode)
    }

    @Test
    fun `MKV + HEVC + DTS resolves to TRANSCODE (both streams incompatible)`() {
        val d = engine.decide(inspect(MediaContainer.MATROSKA, video = "video/hevc", audio = "audio/dtshd-ma", name = "movie.mkv"))
        assertEquals(PlaybackMode.TRANSCODE, d.mode)
    }

    @Test
    fun `MP4 + VP9 + AAC resolves to TRANSCODE`() {
        val d = engine.decide(inspect(MediaContainer.MP4, video = "video/x-vnd.on2.vp9"))
        assertEquals(PlaybackMode.TRANSCODE, d.mode)
    }

    @Test
    fun `AVI + HEVC + AC3 resolves to TRANSCODE`() {
        val d = engine.decide(inspect(MediaContainer.AVI, video = "video/hevc", audio = "audio/ac3", name = "movie.avi"))
        assertEquals(PlaybackMode.TRANSCODE, d.mode)
    }

    @Test
    fun `MPEG2 video resolves to TRANSCODE`() {
        val d = engine.decide(inspect(MediaContainer.MPEG, video = "video/mpeg2", name = "movie.mpg"))
        assertEquals(PlaybackMode.TRANSCODE, d.mode)
    }

    @Test
    fun `WMV resolves to TRANSCODE`() {
        val d = engine.decide(inspect(MediaContainer.WMV, video = "video/x-ms-wmv", audio = "audio/wma", name = "movie.wmv"))
        assertEquals(PlaybackMode.TRANSCODE, d.mode)
    }

    // ── Non-video files ────────────────────────────────────────────────────────

    @Test
    fun `MP3 audio file resolves to DIRECT`() {
        val d = engine.decide(inspect(MediaContainer.MPEG_AUDIO, video = null, audio = "audio/mpeg", mime = "audio/mpeg", name = "song.mp3"))
        assertEquals(PlaybackMode.DIRECT, d.mode)
    }

    @Test
    fun `M4A audio file resolves to DIRECT`() {
        val d = engine.decide(inspect(MediaContainer.AAC_AUDIO, video = null, audio = "audio/mp4a-latm", mime = "audio/mp4", name = "song.m4a"))
        assertEquals(PlaybackMode.DIRECT, d.mode)
    }

    @Test
    fun `PDF resolves to DIRECT`() {
        val d = engine.decide(inspect(MediaContainer.PDF, video = null, audio = null, mime = "application/pdf", name = "doc.pdf"))
        assertEquals(PlaybackMode.DIRECT, d.mode)
        assertEquals("application/pdf", d.browserMimeType)
    }
}
