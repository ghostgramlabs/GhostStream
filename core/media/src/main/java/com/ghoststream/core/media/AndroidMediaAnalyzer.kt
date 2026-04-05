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
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidMediaAnalyzer(
    private val context: Context,
    private val decisionEngine: SmartPlaybackDecisionEngine = DefaultSmartPlaybackDecisionEngine(),
) : MediaAnalyzer {

    private val thumbCacheDir: File = File(context.cacheDir, "ghoststream_thumbs").apply { mkdirs() }

    override fun inspect(uri: Uri, mimeType: String?, displayName: String): MediaInspection {
        val lower = displayName.lowercase()
        val normalizedMimeType = normalizeMimeType(mimeType = mimeType, lowerCaseName = lower)
        val trackInspection = inspectTracks(uri)
        val container = detectContainer(normalizedMimeType = normalizedMimeType, lowerCaseName = lower)
        val browserSafe = isBrowserSafe(normalizedMimeType = normalizedMimeType, lowerCaseName = lower)
        val browserVideoCompatible = trackInspection.videoTrackMimeType == null || trackInspection.videoTrackMimeType == "video/avc"
        val browserAudioCompatible = trackInspection.audioTrackMimeType == null ||
            trackInspection.audioTrackMimeType == "audio/mp4a-latm" ||
            trackInspection.audioTrackMimeType == "audio/mpeg"
        val likelyContainerOnlyIssue = (container == MediaContainer.MATROSKA || container == MediaContainer.QUICKTIME) &&
            browserVideoCompatible &&
            browserAudioCompatible
        val likelyNeedsTranscode = trackInspection.videoTrackMimeType != null &&
            (
                !browserVideoCompatible ||
                    !browserAudioCompatible ||
                    (
                        !browserSafe &&
                            container != MediaContainer.MATROSKA &&
                            container != MediaContainer.QUICKTIME
                        )
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
        )
    }

    override fun decidePlayback(inspection: MediaInspection): PlaybackDecision {
        return decisionEngine.decide(inspection)
    }

    override fun readDurationMs(uri: Uri, mimeType: String?): Long? {
        if (mimeType?.startsWith("video/") != true && mimeType?.startsWith("audio/") != true) {
            return null
        }
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
        }.getOrNull()
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
                    runCatching {
                        context.contentResolver.loadThumbnail(
                            uri,
                            Size(maxSizePx, maxSizePx),
                            null,
                        )
                    }.getOrNull()
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

    override suspend fun clearTemporaryCache() {
        withContext(Dispatchers.IO) {
            thumbCacheDir.listFiles()?.forEach { file ->
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
            MediaCategory.VIDEO -> runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.frameAtTime
                } finally {
                    retriever.release()
                }
            }.getOrNull()

            MediaCategory.PHOTO -> decodePhotoThumbnail(uri, maxSizePx)
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
            else -> MediaContainer.OTHER
        }
    }

    private fun isBrowserSafe(normalizedMimeType: String?, lowerCaseName: String): Boolean {
        return normalizedMimeType == "video/mp4" ||
            lowerCaseName.endsWith(".mp4") ||
            lowerCaseName.endsWith(".m4v") ||
            normalizedMimeType == "audio/mpeg" ||
            lowerCaseName.endsWith(".mp3") ||
            normalizedMimeType == "audio/mp4" ||
            lowerCaseName.endsWith(".m4a") ||
            normalizedMimeType == "image/jpeg" ||
            normalizedMimeType == "image/png" ||
            normalizedMimeType == "image/webp" ||
            normalizedMimeType == "application/pdf" ||
            lowerCaseName.endsWith(".pdf")
    }

    private fun inspectTracks(uri: Uri): TrackInspection {
        return runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, emptyMap())
                var videoTrackMimeType: String? = null
                var audioTrackMimeType: String? = null
                for (index in 0 until extractor.trackCount) {
                    val mimeType = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    when {
                        videoTrackMimeType == null && mimeType?.startsWith("video/") == true -> videoTrackMimeType = mimeType
                        audioTrackMimeType == null && mimeType?.startsWith("audio/") == true -> audioTrackMimeType = mimeType
                    }
                }
                TrackInspection(
                    videoTrackMimeType = videoTrackMimeType,
                    audioTrackMimeType = audioTrackMimeType,
                )
            } finally {
                extractor.release()
            }
        }.getOrDefault(TrackInspection())
    }

    private data class TrackInspection(
        val videoTrackMimeType: String? = null,
        val audioTrackMimeType: String? = null,
    )
}
