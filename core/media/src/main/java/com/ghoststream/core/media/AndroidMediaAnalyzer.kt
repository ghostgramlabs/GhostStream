package com.ghoststream.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.ghoststream.core.model.PlaybackDecision
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val extension = displayName.substringAfterLast('.', "").lowercase()
        
        return MediaInspection(
            originalMimeType = mimeType,
            normalizedMimeType = mimeType,
            displayName = displayName,
            extension = extension,
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
                val inferredMime = inferTrackMime(format) ?: continue
                
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    val d = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                    durationMs = maxOf(durationMs ?: 0L, d)
                }

                if (inferredMime.startsWith("video/")) {
                    videoTrackMimeType = inferredMime
                    videoProfile = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else null
                    videoLevel = if (format.containsKey(MediaFormat.KEY_LEVEL)) format.getInteger(MediaFormat.KEY_LEVEL) else null
                    width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else null
                    height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else null
                    frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getFloat(MediaFormat.KEY_FRAME_RATE) else null
                    bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE).toLong() else null
                    
                    if (format.containsKey("bit-per-sample")) bitDepth = format.getInteger("bit-per-sample")
                } else if (inferredMime.startsWith("audio/")) {
                    audioTrackMimeType = inferredMime
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

    override suspend fun loadThumbnailBytes(item: SharedItem, maxSizePx: Int): ByteArray? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(item.uri)
        when {
            item.mimeType?.startsWith("image/") == true -> loadImageThumbnail(uri, maxSizePx)
            item.mimeType?.startsWith("video/") == true -> extractVideoFrame(uri, null, maxSizePx)
            else -> null
        }
    }

    override suspend fun extractFrameAtMs(item: SharedItem, timeMs: Long, maxSizePx: Int): ByteArray? = withContext(Dispatchers.IO) {
        extractVideoFrame(Uri.parse(item.uri), timeMs, maxSizePx)
    }
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
            normalizedMime.contains("x-flv") || extension == "flv" -> MediaContainer.FLV
            normalizedMime.contains("mpeg") && normalizedMime.startsWith("video/") -> MediaContainer.MPEG
            normalizedMime.contains("3gpp") || extension == "3gp" -> MediaContainer.THREE_GP
            normalizedMime.startsWith("audio/") && extension == "mp3" -> MediaContainer.MPEG_AUDIO
            normalizedMime.startsWith("audio/") && (extension == "m4a" || extension == "aac") -> MediaContainer.AAC_AUDIO
            normalizedMime.startsWith("image/") -> MediaContainer.IMAGE
            normalizedMime == "application/pdf" || extension == "pdf" -> MediaContainer.PDF
            else -> MediaContainer.OTHER
        }
    }

    private fun inferTrackMime(format: MediaFormat): String? {
        val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase()
        if (!mime.isNullOrBlank()) return mime

        val codecString = format.getString("codecs-string")?.lowercase().orEmpty()
        if (codecString.isBlank()) return null

        return when {
            codecString.contains("avc1") || codecString.contains("h264") -> "video/avc"
            codecString.contains("hvc1") || codecString.contains("hev1") || codecString.contains("h265") -> "video/hevc"
            codecString.contains("vp09") -> "video/x-vnd.on2.vp9"
            codecString.contains("av01") -> "video/av01"
            codecString.contains("mp4a") -> "audio/mp4a-latm"
            codecString.contains("opus") -> "audio/opus"
            codecString.contains("ac-3") -> "audio/ac3"
            codecString.contains("ec-3") -> "audio/eac3"
            codecString.contains("mp3") -> "audio/mpeg"
            else -> null
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

    private fun loadImageThumbnail(uri: Uri, maxSizePx: Int): ByteArray? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }?.let { bitmap ->
                bitmap.useScaledJpeg(maxSizePx)
            }
        }.getOrNull()
    }

    private fun extractVideoFrame(uri: Uri, timeMs: Long?, maxSizePx: Int): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = if (timeMs != null) {
                retriever.getFrameAtTime(timeMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: return null
            frame.useScaledJpeg(maxSizePx)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun Bitmap.useScaledJpeg(maxSizePx: Int): ByteArray {
        val scaled = scaleDown(maxSizePx)
        val bytes = ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
            output.toByteArray()
        }
        if (scaled !== this) {
            scaled.recycle()
        }
        recycle()
        return bytes
    }

    private fun Bitmap.scaleDown(maxSizePx: Int): Bitmap {
        val longestSide = maxOf(width, height)
        if (longestSide <= maxSizePx || maxSizePx <= 0) return this
        val scale = maxSizePx.toFloat() / longestSide.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }
}
