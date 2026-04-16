package com.ghoststream.core.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.ghoststream.core.model.PlaybackDecision
import com.ghoststream.core.model.PlaybackMode
import com.ghoststream.core.model.SharedItem

/**
 * Android implementation of MediaAnalyzer leveraging system Extractors and Retrievers.
 */
class AndroidMediaAnalyzer(
    private val context: Context,
    private val decisionEngine: SmartPlaybackDecisionEngine,
    private val stabilizer: MediaSourceStabilizer,
) : MediaAnalyzer {

    override fun inspect(uri: Uri, mimeType: String?, displayName: String): MediaInspection {
        val tracks = inspectTracks(uri)
        
        return MediaInspection(
            originalMimeType = mimeType,
            normalizedMimeType = mimeType,
            displayName = displayName,
            extension = uri.toString().substringAfterLast('.', ""),
            container = inferContainer(mimeType, displayName),
            videoTrackMimeType = tracks.videoTrackMimeType,
            audioTrackMimeType = tracks.audioTrackMimeType,
            browserSafe = false, // Engine decides this
            likelyContainerOnlyIssue = true,
            likelyNeedsTranscode = false,
            durationMs = tracks.durationMs,
            videoProfile = tracks.videoProfile,
            videoLevel = tracks.videoLevel,
            bitDepth = tracks.bitDepth,
            hasFaststart = null, // Requires deeper probe
            width = tracks.width,
            height = tracks.height,
            frameRate = tracks.frameRate,
            bitrate = tracks.bitrate,
            audioChannels = tracks.audioChannels,
            hdrFormat = tracks.hdrFormat,
        )
    }

    override fun decidePlayback(inspection: MediaInspection, capabilities: ClientCapabilities): PlaybackDecision {
        return decisionEngine.decide(inspection, capabilities)
    }

    override fun readDurationMs(uri: Uri, mimeType: String?): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun inspectTracks(uri: Uri): TrackInspection {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, emptyMap())
            var videoTrackMimeType: String? = null
            var audioTrackMimeType: String? = null
            var durationMs: Long? = null
            var videoProfile: Int? = null
            var videoLevel: Int? = null
            var bitDepth: Int? = null
            var width: Int? = null
            var height: Int? = null
            var frameRate: Float? = null
            var bitrate: Long? = null
            var audioChannels: Int? = null
            var hdrFormat: String? = null

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    val d = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                    durationMs = maxOf(durationMs ?: 0L, d)
                }

                if (mime.startsWith("video/")) {
                    videoTrackMimeType = mime
                    videoProfile = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else null
                    videoLevel = if (format.containsKey(MediaFormat.KEY_LEVEL)) format.getInteger(MediaFormat.KEY_LEVEL) else null
                    width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else null
                    height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else null
                    frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getFloat(MediaFormat.KEY_FRAME_RATE) else null
                    bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE).toLong() else null
                    
                    if (format.containsKey("bit-per-sample")) bitDepth = format.getInteger("bit-per-sample")
                } else if (mime.startsWith("audio/")) {
                    audioTrackMimeType = mime
                    audioChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else null
                }
            }
            
            TrackInspection(
                videoTrackMimeType = videoTrackMimeType,
                audioTrackMimeType = audioTrackMimeType,
                durationMs = durationMs,
                videoProfile = videoProfile,
                videoLevel = videoLevel,
                bitDepth = bitDepth,
                width = width,
                height = height,
                frameRate = frameRate,
                bitrate = bitrate,
                audioChannels = audioChannels,
                hdrFormat = hdrFormat,
            )
        } catch (e: Exception) {
            TrackInspection()
        } finally {
            extractor.release()
        }
    }

    override suspend fun loadThumbnailBytes(item: SharedItem, maxSizePx: Int): ByteArray? = null
    override suspend fun extractFrameAtMs(item: SharedItem, timeMs: Long, maxSizePx: Int): ByteArray? = null
    override suspend fun clearTemporaryCache() {}

    private fun inferContainer(mimeType: String?, displayName: String): MediaContainer {
        val normalizedMime = mimeType?.lowercase().orEmpty()
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return when {
            normalizedMime.contains("mp4") || extension == "mp4" || extension == "m4v" -> MediaContainer.MP4
            normalizedMime.contains("quicktime") || extension == "mov" -> MediaContainer.QUICKTIME
            normalizedMime.contains("matroska") || extension == "mkv" -> MediaContainer.MATROSKA
            normalizedMime.contains("webm") || extension == "webm" -> MediaContainer.WEBM
            normalizedMime.contains("mp2t") || extension == "ts" -> MediaContainer.TS
            normalizedMime.contains("x-msvideo") || extension == "avi" -> MediaContainer.AVI
            normalizedMime.contains("mpeg") && normalizedMime.startsWith("video/") -> MediaContainer.MPEG
            normalizedMime.startsWith("audio/") && extension == "mp3" -> MediaContainer.MPEG_AUDIO
            normalizedMime.startsWith("audio/") && (extension == "m4a" || extension == "aac") -> MediaContainer.AAC_AUDIO
            normalizedMime.startsWith("image/") -> MediaContainer.IMAGE
            normalizedMime == "application/pdf" || extension == "pdf" -> MediaContainer.PDF
            else -> MediaContainer.OTHER
        }
    }

    private data class TrackInspection(
        val videoTrackMimeType: String? = null,
        val audioTrackMimeType: String? = null,
        val durationMs: Long? = null,
        val videoProfile: Int? = null,
        val videoLevel: Int? = null,
        val bitDepth: Int? = null,
        val width: Int? = null,
        val height: Int? = null,
        val frameRate: Float? = null,
        val bitrate: Long? = null,
        val audioChannels: Int? = null,
        val hdrFormat: String? = null,
    )
}
