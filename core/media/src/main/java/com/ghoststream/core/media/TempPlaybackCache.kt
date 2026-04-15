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

    /** Deletes all cache entries (completed and temporary) for [itemId]. */
    suspend fun evict(itemId: String)

    suspend fun clearAll()

    fun getStabilizedSourceFile(itemId: String): File
    suspend fun evictStabilizedSource(itemId: String)
    suspend fun cleanupStabilizedSources(activeItemIds: Set<String>)

    /**
     * Enforce cache budget: evict oldest prepared assets (by last-modified time)
     * until total cache size is under [maxBytes]. Never evicts items in [protectedIds].
     */
    suspend fun enforceBudget(maxBytes: Long, protectedIds: Set<String> = emptySet())

    /** Returns the total size in bytes of all prepared cache files. */
    fun totalCacheSizeBytes(): Long

    /** Deletes orphaned .tmp files and stale entries not matching any active library item. */
    suspend fun cleanupOrphans(activeItemIds: Set<String>)
}

class TempPlaybackCache(
    context: Context,
) : PlaybackCache {
    private val rootDir = File(context.cacheDir, "ghoststream_compat").apply { mkdirs() }
    private val stabilizerDir = File(context.cacheDir, "stabilized_sources").apply { mkdirs() }

    companion object {
        private const val MAX_STABILIZER_CACHE_BYTES = 500L * 1024L * 1024L // 500 MB
        /** Default prepared-asset cache budget: 2 GB */
        const val DEFAULT_CACHE_BUDGET_BYTES = 2L * 1024L * 1024L * 1024L
    }

    /**
     * Computes a short fingerprint for [item]'s source file using its size and
     * last-modified timestamp.  If the file changes on disk, the fingerprint changes,
     * making the old cache entry unreachable — it will be swept on the next clearAll().
     */
    private fun sourceFingerprint(item: SharedItem): String {
        val size = item.sizeBytes
        val mtime = item.lastModifiedEpochMs ?: 0L
        return "${size}_${mtime}"
    }

    /** The base filename (without extension) used for both the .tmp and .mp4 cache files. */
    private fun cacheBaseName(item: SharedItem): String {
        return "${item.id}_${sourceFingerprint(item)}_prepared"
    }

    override fun lookup(item: SharedItem): CachedPlaybackAsset? {
        val expectedBase = cacheBaseName(item)
        val file = rootDir.listFiles()
            ?.firstOrNull { candidate ->
                candidate.isFile &&
                !candidate.name.endsWith(".tmp") &&
                !candidate.name.endsWith(".opt") &&
                candidate.nameWithoutExtension == expectedBase
            }
            ?: run {
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

    override suspend fun evict(itemId: String) {
        withContext(Dispatchers.IO) {
            rootDir.listFiles()
                ?.filter { it.name.startsWith(itemId) }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            rootDir.listFiles()?.forEach { file ->
                runCatching { file.delete() }
            }
        }
    }

    override fun getStabilizedSourceFile(itemId: String): File {
        return File(stabilizerDir, "${itemId}.source")
    }

    override suspend fun evictStabilizedSource(itemId: String) {
        withContext(Dispatchers.IO) {
            val file = getStabilizedSourceFile(itemId)
            runCatching { file.delete() }
        }
    }

    override suspend fun cleanupStabilizedSources(activeItemIds: Set<String>) {
        withContext(Dispatchers.IO) {
            stabilizerDir.listFiles()?.forEach { file ->
                val itemId = file.nameWithoutExtension
                if (itemId !in activeItemIds) {
                    runCatching { file.delete() }
                }
            }
            // Enforce size budget for stabilized sources
            val files = stabilizerDir.listFiles()?.toList() ?: return@withContext
            val totalSize = files.sumOf { it.length() }
            if (totalSize > MAX_STABILIZER_CACHE_BYTES) {
                val sorted = files.sortedBy { it.lastModified() }
                var freed = 0L
                val target = totalSize - MAX_STABILIZER_CACHE_BYTES
                for (file in sorted) {
                    if (freed >= target) break
                    if (file.nameWithoutExtension !in activeItemIds) {
                        freed += file.length()
                        runCatching { file.delete() }
                    }
                }
            }
        }
    }

    override suspend fun enforceBudget(maxBytes: Long, protectedIds: Set<String>) {
        withContext(Dispatchers.IO) {
            val files = rootDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".opt") }
                ?: return@withContext
            val totalSize = files.sumOf { it.length() }
            if (totalSize <= maxBytes) return@withContext

            // Sort by last modified (oldest first) for LRU eviction
            val evictionCandidates = files
                .filter { file -> protectedIds.none { id -> file.name.startsWith(id) } }
                .sortedBy { it.lastModified() }

            var currentSize = totalSize
            for (file in evictionCandidates) {
                if (currentSize <= maxBytes) break
                val fileSize = file.length()
                if (runCatching { file.delete() }.isSuccess) {
                    currentSize -= fileSize
                    CompatLogger.debug("CacheEvict", "evicted ${file.name} (${fileSize / 1024}KB) budget=${currentSize / 1024 / 1024}MB")
                }
            }
        }
    }

    override fun totalCacheSizeBytes(): Long {
        return rootDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".opt") }
            ?.sumOf { it.length() }
            ?: 0L
    }

    override suspend fun cleanupOrphans(activeItemIds: Set<String>) {
        withContext(Dispatchers.IO) {
            rootDir.listFiles()?.forEach { file ->
                // Delete .tmp files (interrupted transcodes)
                if (file.name.endsWith(".tmp") || file.name.endsWith(".opt")) {
                    runCatching { file.delete() }
                    return@forEach
                }
                // Delete files whose item ID is not in the active library
                val fileItemId = file.name.substringBefore('_')
                if (fileItemId.isNotEmpty() && fileItemId !in activeItemIds) {
                    runCatching { file.delete() }
                    CompatLogger.debug("CacheCleanup", "orphan removed: ${file.name}")
                }
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
