package com.ghoststream.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import com.ghoststream.core.model.MediaCategory
import com.ghoststream.core.model.PlaybackDecision
import com.ghoststream.core.model.SharedItem
import com.ghoststream.core.model.DebugLogSink
import com.ghoststream.core.model.NoOpDebugLogSink
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidMediaAnalyzer(
    private val context: Context,
    private val decisionEngine: SmartPlaybackDecisionEngine = DefaultSmartPlaybackDecisionEngine(),
    private val debugLogSink: DebugLogSink = NoOpDebugLogSink,
) : MediaAnalyzer {

    private val thumbCacheDir: File = File(context.cacheDir, "ghoststream_thumbs").apply { mkdirs() }
    private val previewCacheDir: File = File(context.cacheDir, "ghoststream_previews").apply { mkdirs() }

    override fun inspect(uri: Uri, mimeType: String?, displayName: String): MediaInspection {
        return try {
            val lower = displayName.lowercase()
            val normalizedMimeType = normalizeMimeType(mimeType = mimeType, lowerCaseName = lower)
            val trackInspection = try {
                inspectTracks(uri)
            } catch (e: Exception) {
                debugLogSink.log("MediaAnalyzer", "Track inspection failed for $displayName ($uri)", e)
                TrackInspection(null, null, null)
            }
        val container = detectContainer(normalizedMimeType = normalizedMimeType, lowerCaseName = lower)
        val browserSafe = isBrowserSafe(normalizedMimeType = normalizedMimeType, lowerCaseName = lower)

        // Detect moov atom position for MP4/MOV files.
        // Browser playback with HTTP range requests requires moov before mdat (faststart).
        val hasFaststart = if (container == MediaContainer.MP4 || container == MediaContainer.QUICKTIME) {
            detectFaststart(uri)
        } else {
            null
        }
        // Only H.264 / AVC is universally browser-safe for all target platforms (Chrome,
        // Firefox, Safari, Android WebView). HEVC, VP9, AV1, VP8 are excluded because
        // they are not supported on all devices — HEVC is Safari-only in Chrome context,
        // VP9/AV1 fail on older Android WebViews, and none guarantee universal reach.
        val browserVideoCompatible = trackInspection.videoTrackMimeType == null ||
            trackInspection.videoTrackMimeType == "video/avc"

        // AAC-LC and MP3 are universally browser-safe. Opus is excluded because it
        // fails in Safari and some Android WebViews even when in an MP4 container.
        val browserAudioCompatible = trackInspection.audioTrackMimeType == null ||
            trackInspection.audioTrackMimeType == "audio/mp4a-latm" ||
            trackInspection.audioTrackMimeType == "audio/mpeg" ||
            trackInspection.audioTrackMimeType == "audio/x-mp3"
        // True when only the container needs rewrapping — codecs are already H.264 + AAC/MP3.
        // TS is included because H.264+AAC in .ts is a very common "container-only" case.
        val likelyContainerOnlyIssue = (
            container == MediaContainer.MATROSKA ||
                container == MediaContainer.QUICKTIME ||
                container == MediaContainer.MP4 ||
                container == MediaContainer.TS
            ) &&
            browserVideoCompatible &&
            browserAudioCompatible

        val likelyNeedsTranscode = trackInspection.videoTrackMimeType != null &&
            (
                !browserVideoCompatible ||
                    !browserAudioCompatible ||
                    // If it's not browser-safe (e.g. not a fragmented MP4) AND
                    // we can't just remux it (not indexed yet), then we need transcode.
                    (!browserSafe && !likelyContainerOnlyIssue)
                )
        return MediaInspection(
            originalMimeType = mimeType,
            normalizedMimeType = normalizedMimeType,
            displayName = displayName,
            extension = lower.substringAfterLast('.', ""),
            container = container,
            videoTrackMimeType = trackInspection.videoTrackMimeType,
            audioTrackMimeType = trackInspection.audioTrackMimeType,
            browserSafe = browserSafe,
            likelyContainerOnlyIssue = likelyContainerOnlyIssue,
            likelyNeedsTranscode = likelyNeedsTranscode,
            durationMs = trackInspection.durationMs,
            videoProfile = trackInspection.videoProfile,
            videoLevel = trackInspection.videoLevel,
            bitDepth = trackInspection.bitDepth,
            hasFaststart = hasFaststart,
            width = trackInspection.width,
            height = trackInspection.height,
        )
        } catch (e: Exception) {
            debugLogSink.log("MediaAnalyzer", "CRITICAL: Inspection failure for $displayName", e)
            MediaInspection(
                originalMimeType = mimeType,
                normalizedMimeType = if (mimeType != null) normalizeMimeType(mimeType, displayName.lowercase()) else null,
                displayName = displayName,
                extension = displayName.substringAfterLast('.', ""),
                container = MediaContainer.OTHER,
                videoTrackMimeType = null,
                audioTrackMimeType = null,
                browserSafe = false,
                likelyContainerOnlyIssue = false,
                likelyNeedsTranscode = true, // Err on the side of caution
                durationMs = null,
                videoProfile = null,
                videoLevel = null,
                bitDepth = null,
                hasFaststart = null,
                width = null,
                height = null,
            )
        }
    }

    override fun decidePlayback(inspection: MediaInspection): PlaybackDecision {
        return decisionEngine.decide(inspection)
    }

    override fun readDurationMs(uri: Uri, mimeType: String?): Long? {
        if (mimeType?.startsWith("video/") != true && mimeType?.startsWith("audio/") != true) {
            return null
        }
        val metadataDuration = try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            debugLogSink.log("MediaAnalyzer", "Duration metadata extraction failed for $uri", e)
            null
        }

        // Fallback: MediaExtractor is often more reliable for containers like MKV/WEBM on some Android versions.
        val extractorDuration = inspectTracks(uri).durationMs

        return when {
            metadataDuration == null -> extractorDuration
            extractorDuration == null -> metadataDuration
            // If they both exist, pick the longer one. Large discrepancies usually mean
            // metadata is truncated or one tool failed to index the whole file.
            else -> maxOf(metadataDuration, extractorDuration)
        }
    }

    override suspend fun loadThumbnailBytes(item: SharedItem, maxSizePx: Int): ByteArray? {
        val cacheFile = File(thumbCacheDir, "${item.id}.jpg")
        if (cacheFile.exists()) {
            return withContext(Dispatchers.IO) { cacheFile.readBytes() }
        }

        return withContext(Dispatchers.IO) {
            val uri = Uri.parse(item.uri)
            val bitmap = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    (item.category == MediaCategory.VIDEO || item.category == MediaCategory.PHOTO) -> {
                    try {
                        context.contentResolver.loadThumbnail(
                            uri,
                            Size(maxSizePx, maxSizePx),
                            null,
                        )
                    } catch (e: Exception) {
                        debugLogSink.log("MediaAnalyzer", "ContentResolver.loadThumbnail failed for ${item.displayName}", e)
                        null
                    }
                }

                else -> null
            } ?: fallbackThumbnailBitmap(
                item = item,
                uri = uri,
                maxSizePx = maxSizePx,
            ) ?: return@withContext null

            bitmap.toJpegBytes()?.also { bytes ->
                runCatching { cacheFile.writeBytes(bytes) }
            }
        }
    }

    override suspend fun extractFrameAtMs(item: SharedItem, timeMs: Long, maxSizePx: Int): ByteArray? {
        val cacheFile = File(previewCacheDir, "${item.id}_${timeMs}.jpg")
        if (cacheFile.exists()) {
            return withContext(Dispatchers.IO) { cacheFile.readBytes() }
        }

        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(item.uri))
                // OPTION_CLOSEST_SYNC is much faster than OPTION_CLOSEST because it only
                // decodes the nearest I-frame (keyframe). This is ideal for scrubbing previews
                // where responsiveness is more important than frame-perfect accuracy.
                val bitmap = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                
                bitmap?.toJpegBytes()?.also { bytes ->
                    runCatching { cacheFile.writeBytes(bytes) }
                }
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
    }

    override suspend fun clearTemporaryCache() {
        withContext(Dispatchers.IO) {
            thumbCacheDir.listFiles()?.forEach { file ->
                runCatching { file.delete() }
            }
            previewCacheDir.listFiles()?.forEach { file ->
                runCatching { file.delete() }
            }
        }
    }

    private fun Bitmap.toJpegBytes(): ByteArray? {
        return ByteArrayOutputStream().use { output ->
            if (compress(Bitmap.CompressFormat.JPEG, 82, output)) output.toByteArray() else null
        }
    }

    private fun fallbackThumbnailBitmap(
        item: SharedItem,
        uri: Uri,
        maxSizePx: Int,
    ): Bitmap? {
        return when (item.category) {
            com.ghoststream.core.model.MediaCategory.VIDEO -> runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.frameAtTime
                } finally {
                    retriever.release()
                }
            }.getOrNull()

            com.ghoststream.core.model.MediaCategory.PHOTO -> decodePhotoThumbnail(uri, maxSizePx)
            else -> null
        }
    }

    private fun decodePhotoThumbnail(uri: Uri, maxSizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
        val sampleSize = generateSequence(1) { it * 2 }
            .takeWhile { largestSide / it > maxSizePx }
            .lastOrNull()
            ?: 1

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun normalizeMimeType(mimeType: String?, lowerCaseName: String): String? {
        return when {
            mimeType == "video/mp4" || lowerCaseName.endsWith(".mp4") || lowerCaseName.endsWith(".m4v") -> "video/mp4"
            mimeType == "audio/mpeg" || lowerCaseName.endsWith(".mp3") -> "audio/mpeg"
            mimeType == "audio/mp4" || lowerCaseName.endsWith(".m4a") -> "audio/mp4"
            mimeType == "image/jpeg" || lowerCaseName.endsWith(".jpg") || lowerCaseName.endsWith(".jpeg") -> "image/jpeg"
            mimeType == "image/png" || lowerCaseName.endsWith(".png") -> "image/png"
            mimeType == "image/webp" || lowerCaseName.endsWith(".webp") -> "image/webp"
            mimeType == "application/pdf" || lowerCaseName.endsWith(".pdf") -> "application/pdf"
            mimeType == "video/x-msvideo" || lowerCaseName.endsWith(".avi") -> "video/x-msvideo"
            mimeType == "video/webm" || lowerCaseName.endsWith(".webm") -> "video/webm"
            mimeType == "video/x-ms-wmv" || lowerCaseName.endsWith(".wmv") -> "video/x-ms-wmv"
            mimeType == "video/mp2t" || lowerCaseName.endsWith(".ts") -> "video/mp2t"
            mimeType == "video/mpeg" || lowerCaseName.endsWith(".mpeg") || lowerCaseName.endsWith(".mpg") -> "video/mpeg"
            mimeType == "video/3gpp" || lowerCaseName.endsWith(".3gp") || lowerCaseName.endsWith(".3gpp") -> "video/3gpp"
            mimeType == "video/dvd" || lowerCaseName.endsWith(".vob") -> "video/mpeg"
            mimeType == "video/ogg" || lowerCaseName.endsWith(".ogv") -> "video/ogg"
            else -> mimeType
        }
    }

    private fun detectContainer(normalizedMimeType: String?, lowerCaseName: String): MediaContainer {
        return when {
            normalizedMimeType == "video/mp4" || lowerCaseName.endsWith(".mp4") || lowerCaseName.endsWith(".m4v") -> MediaContainer.MP4
            lowerCaseName.endsWith(".mkv") -> MediaContainer.MATROSKA
            lowerCaseName.endsWith(".mov") -> MediaContainer.QUICKTIME
            normalizedMimeType == "audio/mpeg" || lowerCaseName.endsWith(".mp3") -> MediaContainer.MPEG_AUDIO
            normalizedMimeType == "audio/mp4" || lowerCaseName.endsWith(".m4a") -> MediaContainer.AAC_AUDIO
            normalizedMimeType?.startsWith("image/") == true -> MediaContainer.IMAGE
            normalizedMimeType == "application/pdf" || lowerCaseName.endsWith(".pdf") -> MediaContainer.PDF
            normalizedMimeType == "video/x-msvideo" || lowerCaseName.endsWith(".avi") -> MediaContainer.AVI
            normalizedMimeType == "video/webm" || lowerCaseName.endsWith(".webm") -> MediaContainer.WEBM
            normalizedMimeType == "video/x-ms-wmv" || lowerCaseName.endsWith(".wmv") -> MediaContainer.WMV
            normalizedMimeType == "video/x-flv" || lowerCaseName.endsWith(".flv") -> MediaContainer.FLV
            normalizedMimeType == "video/mp2t" || lowerCaseName.endsWith(".ts") -> MediaContainer.TS
            normalizedMimeType == "video/mpeg" || lowerCaseName.endsWith(".mpeg") || lowerCaseName.endsWith(".mpg") -> MediaContainer.MPEG
            normalizedMimeType == "video/3gpp" || lowerCaseName.endsWith(".3gp") -> MediaContainer.THREE_GP
            normalizedMimeType == "video/ogg" || lowerCaseName.endsWith(".ogv") -> MediaContainer.OGG_VIDEO
            lowerCaseName.endsWith(".vob") -> MediaContainer.VOB
            else -> MediaContainer.OTHER
        }
    }

    private fun isBrowserSafe(normalizedMimeType: String?, lowerCaseName: String): Boolean {
        return normalizedMimeType == "video/mp4" ||
            lowerCaseName.endsWith(".mp4") ||
            lowerCaseName.endsWith(".m4v") ||
            // MOV + H.264 + AAC is browser-playable in Chrome, Firefox, and Safari directly.
            lowerCaseName.endsWith(".mov") ||
            normalizedMimeType == "audio/mpeg" ||
            lowerCaseName.endsWith(".mp3") ||
            normalizedMimeType == "audio/mp4" ||
            lowerCaseName.endsWith(".m4a") ||
            normalizedMimeType == "image/jpeg" ||
            normalizedMimeType == "image/png" ||
            normalizedMimeType == "image/webp" ||
            normalizedMimeType == "video/webm" ||
            lowerCaseName.endsWith(".webm") ||
            normalizedMimeType == "application/pdf" ||
            lowerCaseName.endsWith(".pdf")
    }

    /**
     * Detects whether an MP4/MOV file has the moov atom before the mdat atom (faststart).
     *
     * MP4 files consist of top-level "boxes" (atoms). For browser streaming with HTTP range
     * requests, the moov atom (which contains the sample table / index) must appear before
     * the mdat atom (which contains the actual media data). If moov is at the end, the
     * browser must download the entire file before it can begin playback or seek.
     *
     * This method reads only the first few KB of the file to scan for the atom headers,
     * so it is fast even for very large files.
     *
     * Returns true if moov comes before mdat, false if mdat comes first, or null if
     * detection failed (e.g. the file is not a valid MP4/MOV).
     */
    private fun detectFaststart(uri: Uri): Boolean? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(8)
                var offset = 0L
                var foundMoov = false
                var foundMdat = false
                // Scan top-level boxes. We only need to find moov and mdat.
                // Limit scan to first 256 MB to avoid reading entire huge files.
                val maxScan = 256L * 1024L * 1024L
                while (offset < maxScan) {
                    val bytesRead = readFully(input, buf, 8)
                    if (bytesRead < 8) break
                    val size = ((buf[0].toLong() and 0xFF) shl 24) or
                        ((buf[1].toLong() and 0xFF) shl 16) or
                        ((buf[2].toLong() and 0xFF) shl 8) or
                        (buf[3].toLong() and 0xFF)
                    val type = String(buf, 4, 4, Charsets.US_ASCII)

                    when (type) {
                        "moov" -> {
                            foundMoov = true
                            if (!foundMdat) return@use true // moov before mdat = faststart
                            return@use false // moov after mdat = not faststart
                        }
                        "mdat" -> {
                            foundMdat = true
                            if (!foundMoov) return@use false // mdat before moov = not faststart
                            return@use true // mdat after moov = faststart
                        }
                    }

                    // Skip to the next box
                    val boxSize = when {
                        size == 1L -> {
                            // Extended size: next 8 bytes are the real 64-bit size
                            val extBuf = ByteArray(8)
                            if (readFully(input, extBuf, 8) < 8) return@use null
                            val extSize = ((extBuf[0].toLong() and 0xFF) shl 56) or
                                ((extBuf[1].toLong() and 0xFF) shl 48) or
                                ((extBuf[2].toLong() and 0xFF) shl 40) or
                                ((extBuf[3].toLong() and 0xFF) shl 32) or
                                ((extBuf[4].toLong() and 0xFF) shl 24) or
                                ((extBuf[5].toLong() and 0xFF) shl 16) or
                                ((extBuf[6].toLong() and 0xFF) shl 8) or
                                (extBuf[7].toLong() and 0xFF)
                            extSize - 16 // Already read 8+8 bytes
                        }
                        size == 0L -> return@use null // Box extends to end of file, can't continue
                        else -> size - 8 // Already read 8 bytes of header
                    }

                    if (boxSize <= 0) break
                    val skipped = input.skip(boxSize)
                    if (skipped < boxSize) break
                    offset += 8 + boxSize
                }
                // If we found moov but no mdat in our scan window, assume faststart
                if (foundMoov && !foundMdat) true else null
            }
        }.getOrNull()
    }

    /** Read exactly [count] bytes into [buf], returning the number of bytes actually read. */
    private fun readFully(input: java.io.InputStream, buf: ByteArray, count: Int): Int {
        var total = 0
        while (total < count) {
            val n = input.read(buf, total, count - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun inspectTracks(uri: Uri): TrackInspection {
        return runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, emptyMap())
                var videoTrackMimeType: String? = null
                var audioTrackMimeType: String? = null
                var durationMs: Long? = null
                var videoProfile: Int? = null
                var videoLevel: Int? = null
                var bitDepth: Int? = null
                var width: Int? = null
                var height: Int? = null

                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mimeType = format.getString(MediaFormat.KEY_MIME)
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        val trackDuration = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                        durationMs = maxOf(durationMs ?: 0L, trackDuration)
                    }
                    when {
                        videoTrackMimeType == null && mimeType?.startsWith("video/") == true -> {
                            videoTrackMimeType = normalizeVideoCodec(mimeType)
                            if (format.containsKey(MediaFormat.KEY_PROFILE)) {
                                videoProfile = format.getInteger(MediaFormat.KEY_PROFILE)
                            }
                            if (format.containsKey(MediaFormat.KEY_LEVEL)) {
                                videoLevel = format.getInteger(MediaFormat.KEY_LEVEL)
                            }
                            if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                                width = format.getInteger(MediaFormat.KEY_WIDTH)
                            }
                            if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                                height = format.getInteger(MediaFormat.KEY_HEIGHT)
                            }
                            // Bit depth is often not explicitly in KEY_BIT_PER_SAMPLE for video
                            // but sometimes in KEY_COLOR_FORMAT (e.g. 10-bit YUV formats)
                            // or KEY_COLOR_TRANSFER (HDR10). 
                            // For simplicity, we detect 10-bit if bit-per-sample is present and > 8
                            if (format.containsKey("bit-per-sample")) {
                                bitDepth = format.getInteger("bit-per-sample")
                            } else if (format.containsKey("color-format")) {
                                // Some common 10-bit formats on Android
                                val colorFormat = format.getInteger("color-format")
                                // 0x7FA30C00 is sometimes used for 10-bit
                                if (colorFormat == 0x7FA30C00 || colorFormat == 54) { // 54 = YCBCR_P010
                                    bitDepth = 10
                                }
                            }
                        }
                        audioTrackMimeType == null && mimeType?.startsWith("audio/") == true ->
                            audioTrackMimeType = normalizeAudioCodec(mimeType)
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
                )
            } finally {
                extractor.release()
            }
        }.getOrDefault(TrackInspection())
    }


    /**
     * Normalises video codec MIME strings returned by [android.media.MediaExtractor] into
     * the canonical set expected by [SmartPlaybackDecisionEngine].
     *
     * Different Android versions and OEM ROMs report the same codec with slightly different
     * strings.  For example H.264 can appear as "video/avc", "video/h264", "video/x-264",
     * or "video/avc-baseline".  Without normalisation these fall through every explicit
     * check and end up routed incorrectly.
     */
    private fun normalizeVideoCodec(mime: String): String = when {
        // H.264 / AVC — canonical: "video/avc"
        mime == "video/avc" ||
        mime.contains("h264", ignoreCase = true) ||
        mime.contains("x-264", ignoreCase = true) ||
        mime.contains("avc", ignoreCase = true) -> "video/avc"

        // HEVC / H.265 — canonical: "video/hevc"
        mime == "video/hevc" || mime == "video/hvc1" || mime == "video/hev1" ||
        mime.contains("h265", ignoreCase = true) ||
        mime.contains("hevc", ignoreCase = true) ||
        mime.contains("hvc", ignoreCase = true) -> "video/hevc"

        // VP9 — canonical: "video/x-vnd.on2.vp9"
        mime == "video/x-vnd.on2.vp9" || mime == "video/vp9" ||
        mime.contains("vp9", ignoreCase = true) -> "video/x-vnd.on2.vp9"

        // VP8 — canonical: "video/x-vnd.on2.vp8"
        mime == "video/x-vnd.on2.vp8" || mime == "video/vp8" ||
        mime.contains("vp8", ignoreCase = true) -> "video/x-vnd.on2.vp8"

        // AV1
        mime == "video/av01" || mime == "video/av1" ||
        mime.contains("av1", ignoreCase = true) ||
        mime.contains("av01", ignoreCase = true) -> "video/av01"

        // MPEG-4 Part 2 (DivX / Xvid)
        mime == "video/mp4v-es" || mime == "video/mpeg4" ||
        mime.contains("mp4v", ignoreCase = true) ||
        mime.contains("divx", ignoreCase = true) ||
        mime.contains("xvid", ignoreCase = true) -> "video/mp4v-es"

        else -> mime
    }

    /**
     * Normalises audio codec MIME strings from [android.media.MediaExtractor].
     *
     * AAC in particular is reported as "audio/mp4a-latm", "audio/mp4a",
     * "audio/mpeg4-generic", "audio/aac", or "audio/aac-latm" depending on the
     * Android version, ROM, and container.  All of these mean the same AAC-LC track
     * and must normalise to "audio/mp4a-latm" so the browser-compatibility checks work.
     */
    private fun normalizeAudioCodec(mime: String): String = when {
        // AAC-LC variants — canonical: "audio/mp4a-latm"
        mime == "audio/mp4a-latm" || mime == "audio/mp4a" ||
        mime == "audio/aac" || mime == "audio/aac-latm" ||
        mime == "audio/mpeg4-generic" ||
        mime.startsWith("audio/mp4a") -> "audio/mp4a-latm"

        // MP3 variants — canonical: "audio/mpeg"
        mime == "audio/mpeg" || mime == "audio/mp3" || mime == "audio/x-mp3" ||
        mime == "audio/mpeg3" || mime == "audio/x-mpeg" -> "audio/mpeg"

        // AC-3 / Dolby Digital
        mime == "audio/ac3" || mime == "audio/a52" ||
        mime.contains("dolby", ignoreCase = true) && !mime.contains("e-ac") -> "audio/ac3"

        // E-AC-3 / Dolby Digital Plus
        mime == "audio/eac3" || mime == "audio/ec3" ||
        mime == "audio/e-ac3" -> "audio/eac3"

        // DTS variants
        mime == "audio/vnd.dts" || mime == "audio/vnd.dts.hd" ||
        mime == "audio/dtshd-ma" || mime == "audio/dts" ||
        mime.contains("dts", ignoreCase = true) -> "audio/vnd.dts"

        // TrueHD / Dolby Atmos lossless
        mime == "audio/truehd" || mime.contains("truehd", ignoreCase = true) -> "audio/truehd"

        // Vorbis (WebM)
        mime == "audio/vorbis" -> "audio/vorbis"

        // Opus
        mime == "audio/opus" -> "audio/opus"

        else -> mime
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
    )
}
