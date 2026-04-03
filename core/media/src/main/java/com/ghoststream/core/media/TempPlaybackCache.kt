package com.ghoststream.core.media

import android.content.Context
import com.ghoststream.core.model.SharedItem
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface PlaybackCache {
    fun lookup(itemId: String): CachedPlaybackAsset?
    fun newOutputFile(item: SharedItem, suffix: String): File
    fun record(
        itemId: String,
        file: File,
        mimeType: String?,
        isComplete: Boolean = true,
        isFragmentedMp4: Boolean = false,
    ): CachedPlaybackAsset
    suspend fun clearAll()
}

class TempPlaybackCache(
    context: Context,
) : PlaybackCache {
    private val rootDir = File(context.cacheDir, "ghoststream_compat").apply { mkdirs() }

    override fun lookup(itemId: String): CachedPlaybackAsset? {
        val file = rootDir.listFiles()
            ?.firstOrNull { candidate -> candidate.name.startsWith(itemId) && candidate.isFile }
            ?: return null
        return CachedPlaybackAsset(
            itemId = itemId,
            filePath = file.absolutePath,
            mimeType = inferMimeType(file),
            sizeBytes = file.length(),
            createdAtEpochMs = file.lastModified(),
        )
    }

    override fun newOutputFile(item: SharedItem, suffix: String): File {
        val extension = suffix.trimStart('.').lowercase(Locale.US)
        return File(rootDir, "${item.id}_prepared.$extension")
    }

    override fun record(
        itemId: String,
        file: File,
        mimeType: String?,
        isComplete: Boolean,
        isFragmentedMp4: Boolean,
    ): CachedPlaybackAsset {
        return CachedPlaybackAsset(
            itemId = itemId,
            filePath = file.absolutePath,
            mimeType = mimeType,
            sizeBytes = file.length(),
            createdAtEpochMs = System.currentTimeMillis(),
            isComplete = isComplete,
            isFragmentedMp4 = isFragmentedMp4,
        )
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            rootDir.listFiles()?.forEach { file ->
                runCatching { file.delete() }
            }
        }
    }

    private fun inferMimeType(file: File): String? {
        return when (file.extension.lowercase(Locale.US)) {
            "mp4", "m4v" -> "video/mp4"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            else -> null
        }
    }
}
