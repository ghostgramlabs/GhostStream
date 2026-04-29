package com.ghoststream.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
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
        val container = inferContainer(mimeType, displayName)

        return MediaInspection(
            originalMimeType = mimeType,
            normalizedMimeType = mimeType,
            displayName = displayName,
            extension = extension,
            container = container,
            videoTrackMimeType = tracks.videoTrackMimeType,
            audioTrackMimeType = tracks.audioTrackMimeType,
            browserSafe = false, // Engine decides this
            likelyContainerOnlyIssue = true,
            likelyNeedsTranscode = false,
            durationMs = tracks.durationMs,
            videoProfile = tracks.videoProfile,
            videoLevel = tracks.videoLevel,
            bitDepth = tracks.bitDepth,
            hasFaststart = probeFaststart(uri, container),
            width = tracks.width,
            height = tracks.height,
            frameRate = tracks.frameRate,
            bitrate = tracks.bitrate,
            audioChannels = tracks.audioChannels,
            hdrFormat = tracks.hdrFormat,
            audioInitDataOk = tracks.audioInitDataOk,
        )
    }

    // Top-level box walk to determine moov-before-mdat (faststart) vs moov-at-end.
    // Returns null when the container isn't MP4/MOV, when probing fails, or when
    // the probe budget is exhausted before both boxes are seen — callers must treat
    // null as "unknown" and not as a remux trigger.
    private fun probeFaststart(uri: Uri, container: MediaContainer): Boolean? {
        if (container != MediaContainer.MP4 && container != MediaContainer.QUICKTIME) return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(8)
                val largeBuf = ByteArray(8)
                var pos = 0L
                var moovOffset = -1L
                var mdatOffset = -1L
                val maxScanBytes = 256L * 1024L * 1024L
                while (pos < maxScanBytes) {
                    if (!stream.readFullyOrReturnFalse(header)) break
                    val size32 = ((header[0].toLong() and 0xff) shl 24) or
                        ((header[1].toLong() and 0xff) shl 16) or
                        ((header[2].toLong() and 0xff) shl 8) or
                        (header[3].toLong() and 0xff)
                    val type = String(header, 4, 4, Charsets.US_ASCII)
                    val boxSize: Long
                    val headerSize: Long
                    when {
                        size32 == 1L -> {
                            if (!stream.readFullyOrReturnFalse(largeBuf)) return@use null
                            boxSize = java.nio.ByteBuffer.wrap(largeBuf).long
                            headerSize = 16L
                        }
                        size32 == 0L -> {
                            // Box extends to EOF — last box. Decide based on what we've already seen.
                            if (type == "moov" && moovOffset < 0) moovOffset = pos
                            if (type == "mdat" && mdatOffset < 0) mdatOffset = pos
                            break
                        }
                        else -> {
                            boxSize = size32
                            headerSize = 8L
                        }
                    }
                    if (type == "moov" && moovOffset < 0) moovOffset = pos
                    if (type == "mdat" && mdatOffset < 0) mdatOffset = pos
                    if (moovOffset >= 0 && mdatOffset >= 0) break
                    if (boxSize <= 0) break
                    val bodySize = boxSize - headerSize
                    if (bodySize < 0) break
                    if (!stream.skipFullyExact(bodySize)) break
                    pos += boxSize
                }
                when {
                    moovOffset < 0 -> null
                    mdatOffset < 0 -> true
                    else -> moovOffset < mdatOffset
                }
            }
        }.getOrNull()
    }

    private fun java.io.InputStream.readFullyOrReturnFalse(buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun java.io.InputStream.skipFullyExact(n: Long): Boolean {
        var remaining = n
        val scratch = ByteArray(8 * 1024)
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val toRead = remaining.coerceAtMost(scratch.size.toLong()).toInt()
            val r = read(scratch, 0, toRead)
            if (r < 0) return false
            remaining -= r
        }
        return true
    }

    override fun decidePlayback(
        inspection: MediaInspection,
        capabilities: ClientCapabilities,
        directPlaybackFailed: Boolean,
    ): PlaybackDecision {
        return (decisionEngine as? DefaultSmartPlaybackDecisionEngine)?.decide(inspection, capabilities, directPlaybackFailed)
            ?: decisionEngine.decide(inspection, capabilities)
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
            var audioInitDataOk: Boolean? = null

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

                    // "bit-per-sample" is a non-standard MediaFormat key populated by some
                    // 10-bit HEVC sources but missing on others. Prefer the explicit value
                    // when present, otherwise infer from the HEVC Main10 profile (== 2),
                    // which always implies 10-bit luma.
                    bitDepth = when {
                        format.containsKey("bit-per-sample") -> format.getInteger("bit-per-sample")
                        inferredMime == "video/hevc" && videoProfile == 2 -> 10
                        else -> bitDepth
                    }

                    hdrFormat = inferHdrFormat(format) ?: hdrFormat
                } else if (inferredMime.startsWith("audio/")) {
                    audioTrackMimeType = inferredMime
                    audioChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else null
                    if (inferredMime == "audio/mp4a-latm") {
                        // AAC requires a non-empty AudioSpecificConfig (csd-0) for browser MSE init.
                        // Missing/empty csd-0 is the most common cause of "audio decode error" in
                        // hls.js after the init segment. Surface as audioInitDataOk = false so the
                        // decision engine forces an audio re-encode.
                        val csd0 = runCatching { format.getByteBuffer("csd-0") }.getOrNull()
                        audioInitDataOk = csd0 != null && csd0.remaining() >= 2
                    } else if (audioInitDataOk == null) {
                        audioInitDataOk = true
                    }
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
                audioInitDataOk = audioInitDataOk,
            )
        } catch (e: Exception) {
            TrackInspection()
        } finally {
            extractor.release()
        }
    }

    override suspend fun loadThumbnailBytes(item: SharedItem, maxSizePx: Int): ByteArray? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(item.uri)
        val isPdf = item.mimeType == "application/pdf" ||
            item.displayName.endsWith(".pdf", ignoreCase = true)
        when {
            item.mimeType?.startsWith("image/") == true -> loadImageThumbnail(uri, maxSizePx)
            item.mimeType?.startsWith("video/") == true -> extractVideoFrame(uri, null, maxSizePx)
            isPdf -> loadPdfThumbnail(uri, maxSizePx)
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
        val audioInitDataOk: Boolean? = null,
    )

    // Inspect MediaFormat color metadata for an HDR transfer function.
    // Returns null when the format does not advertise HDR (SDR or unknown).
    private fun inferHdrFormat(format: MediaFormat): String? {
        val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        } else return null
        return when (transfer) {
            MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR10"
            MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
    }

    private fun loadImageThumbnail(uri: Uri, maxSizePx: Int): ByteArray? {
        return runCatching {
            val orientation = readExifOrientation(uri)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }?.let { bitmap ->
                val oriented = bitmap.applyExifOrientation(orientation)
                if (oriented !== bitmap) bitmap.recycle()
                oriented.useScaledJpeg(maxSizePx)
            }
        }.getOrNull()
    }

    private fun loadPdfThumbnail(uri: Uri, maxSizePx: Int): ByteArray? {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount == 0) return@use null
                    renderer.openPage(0).use { page ->
                        val aspect = page.width.toFloat() / page.height.toFloat()
                        val targetWidth: Int
                        val targetHeight: Int
                        if (aspect >= 1f) {
                            targetWidth = maxSizePx
                            targetHeight = (maxSizePx / aspect).toInt().coerceAtLeast(1)
                        } else {
                            targetHeight = maxSizePx
                            targetWidth = (maxSizePx * aspect).toInt().coerceAtLeast(1)
                        }
                        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap.useScaledJpeg(maxSizePx)
                    }
                }
            }
        }.getOrNull()
    }

    private fun readExifOrientation(uri: Uri): Int {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }

    private fun Bitmap.applyExifOrientation(orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.preScale(-1f, 1f)
            }
            else -> return this
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
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
