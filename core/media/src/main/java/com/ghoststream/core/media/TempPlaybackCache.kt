package com.ghoststream.core.media

import android.content.Context
import com.ghoststream.core.model.SharedItem
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface PlaybackCache {
    /**
     * Looks up a previously completed (non-partial) cache entry for [item].
     * Validates the cache key against the item's current size and last-modified time so
     * that a stale entry from a replaced/updated source file is never returned as READY.
     * Returns null if no valid entry exists.
     */
    fun lookup(item: SharedItem): CachedPlaybackAsset?

    /**
     * Returns the output [File] path for a new transcode job for [item].
     * The filename encodes a fingerprint of the source file (size + mtime) so that
     * cache entries are automatically invalidated when the source changes.
     */
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

    /**
     * Computes a short fingerprint for [item]'s source file using its size and
     * last-modified timestamp.  If the file changes on disk, the fingerprint changes,
     * making the old cache entry unreachable — it will be swept on the next clearAll().
     */
    private fun sourceFingerprint(item: SharedItem): String {
        val size = item.sizeBytes
        val mtime = item.lastModifiedEpochMs ?: 0L
        // Simple, human-readable fingerprint: size_mtime.
        // Collisions are astronomically unlikely for files with the same itemId.
        return "${size}_${mtime}"
    }

    /** The base filename (without extension) used for both the .tmp and .mp4 cache files. */
    private fun cacheBaseName(item: SharedItem): String {
        return "${item.id}_${sourceFingerprint(item)}_prepared"
    }

    override fun lookup(item: SharedItem): CachedPlaybackAsset? {
        val expectedBase = cacheBaseName(item)
        // Look for a completed file (.mp4, .m4a, etc.) with the exact fingerprinted name.
        // Ignore .tmp files (in-progress or interrupted transcodes) and any file whose
        // name doesn't match the current fingerprint (stale cache from a changed source).
        val file = rootDir.listFiles()
            ?.firstOrNull { candidate ->
                candidate.isFile &&
                !candidate.name.endsWith(".tmp") &&
                candidate.nameWithoutExtension == expectedBase
            }
            ?: run {
                // Clean up stale entries for this item ID (different fingerprint = old source).
                purgeStaleCacheFor(item.id, expectedBase)
                return null
            }

        return CachedPlaybackAsset(
            itemId = item.id,
            filePath = file.absolutePath,
            mimeType = inferMimeType(file),
            sizeBytes = file.length(),
            createdAtEpochMs = file.lastModified(),
        )
    }

    override fun newOutputFile(item: SharedItem, suffix: String): File {
        val extension = suffix.trimStart('.').lowercase(Locale.US)
        return File(rootDir, "${cacheBaseName(item)}.$extension")
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

    /**
     * Deletes any cache files for [itemId] whose fingerprinted base name does NOT match
     * [currentBase].  Called lazily on every lookup miss so the cache directory stays tidy
     * without requiring a scheduled sweep.
     */
    private fun purgeStaleCacheFor(itemId: String, currentBase: String) {
        rootDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(itemId) && it.nameWithoutExtension != currentBase }
            ?.forEach { stale -> runCatching { stale.delete() } }
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
