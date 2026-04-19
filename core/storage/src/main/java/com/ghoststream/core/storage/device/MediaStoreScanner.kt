package com.ghoststream.core.storage.device

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.ghoststream.core.media.MediaAnalyzer
import com.ghoststream.core.model.MediaCategory
import com.ghoststream.core.model.SharedFolder
import com.ghoststream.core.model.SharedItem
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreScanner(
    private val context: Context,
    private val mediaAnalyzer: MediaAnalyzer,
) {
    private val TAG = "MediaStoreScanner"

    suspend fun scanAllDeviceMedia(): Pair<List<SharedItem>, List<SharedFolder>> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SharedItem>()
        val foldersMap = mutableMapOf<String, SharedFolderBuilder>()

        Log.d(TAG, "Starting full device media scan...")

        scanCollection(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            category = MediaCategory.VIDEO,
            items = items,
            foldersMap = foldersMap
        )

        scanCollection(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            category = MediaCategory.PHOTO,
            items = items,
            foldersMap = foldersMap
        )

        scanCollection(
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            category = MediaCategory.MUSIC,
            items = items,
            foldersMap = foldersMap,
            isAudio = true
        )

        scanCollection(
            collection = MediaStore.Files.getContentUri("external"),
            category = MediaCategory.FILE,
            items = items,
            foldersMap = foldersMap,
            selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE}"
        )

        Log.d(TAG, "Scan complete. Found ${items.size} items across ${foldersMap.size} folders.")

        val folders = foldersMap.values.map {
            SharedFolder(
                id = it.id,
                treeUri = "mediastore://${it.id}",
                displayName = it.displayName,
                fileCount = it.fileCount,
                totalSizeBytes = it.totalSizeBytes,
                addedAtEpochMs = System.currentTimeMillis(),
                permissionPersisted = true
            )
        }.sortedBy { it.displayName.lowercase() }

        Pair(items, folders)
    }

    private fun scanCollection(
        collection: Uri,
        category: MediaCategory,
        items: MutableList<SharedItem>,
        foldersMap: MutableMap<String, SharedFolderBuilder>,
        isAudio: Boolean = false,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ) {
        Log.d(TAG, "Scanning collection: $collection (Category: $category)")

        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED
        ).apply {
            if (isAudio) {
                add(MediaStore.Audio.Media.ALBUM_ID)
                add(MediaStore.Audio.Media.ALBUM)
                add(MediaStore.Audio.Media.DURATION)
            } else {
                // Documents in MediaStore.Files might not always support BUCKET columns gracefully on all providers
                try {
                    add(MediaStore.MediaColumns.BUCKET_ID)
                    add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                } catch (e: Exception) {
                    Log.w(TAG, "Bucket columns not available for this collection")
                }
                
                if (category == MediaCategory.VIDEO) {
                    add(MediaStore.Video.Media.DURATION)
                }
            }
        }.toTypedArray()

        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val addedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val modifiedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                
                val folderIdIdx = cursor.getColumnIndex(if (isAudio) MediaStore.Audio.Media.ALBUM_ID else MediaStore.MediaColumns.BUCKET_ID)
                val folderNameIdx = cursor.getColumnIndex(if (isAudio) MediaStore.Audio.Media.ALBUM else MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val durationIdx = cursor.getColumnIndex(if (isAudio) MediaStore.Audio.Media.DURATION else MediaStore.Video.Media.DURATION)

                var count = 0
                while (cursor.moveToNext()) {
                    if (idIdx < 0) continue
                    
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getStringOrNull(nameIdx) ?: "Unknown"
                    val mime = cursor.getStringOrNull(mimeIdx) ?: "application/octet-stream"
                    val size = cursor.getLong(sizeIdx)
                    val added = if (addedIdx >= 0) cursor.getLong(addedIdx) * 1000L else System.currentTimeMillis()
                    val modified = if (modifiedIdx >= 0) cursor.getLong(modifiedIdx) * 1000L else System.currentTimeMillis()
                    val contentUri = ContentUris.withAppendedId(collection, id)

                    val folderIdRaw = if (folderIdIdx >= 0) cursor.getStringOrNull(folderIdIdx) else null
                    val folderNameRaw = if (folderNameIdx >= 0) cursor.getStringOrNull(folderNameIdx) else null
                    
                    val durationMs = if (durationIdx >= 0) cursor.getLongOrNull(durationIdx) else null

                    val sourceFolderId = if (folderIdRaw != null) stableId("folder_$folderIdRaw") else null
                    val folderName = folderNameRaw ?: "Unknown Folder"

                    if (sourceFolderId != null) {
                        val builder = foldersMap.getOrPut(sourceFolderId) {
                            SharedFolderBuilder(sourceFolderId, folderName)
                        }
                        builder.fileCount++
                        builder.totalSizeBytes += size
                    }

                    // Basic inference, will be updated lazily by mediaAnalyzer if needed
                    val browserSafe = category != MediaCategory.VIDEO || mime == "video/mp4" || mime == "video/webm"
                    
                    items.add(
                        SharedItem(
                            id = stableId(contentUri.toString()),
                            uri = contentUri.toString(),
                            displayName = name,
                            mimeType = mime,
                            category = category,
                            sizeBytes = size,
                            durationMs = durationMs,
                            dateAddedEpochMs = added,
                            lastModifiedEpochMs = modified,
                            sourceFolderId = sourceFolderId,
                            thumbnailKey = stableId("thumb:${contentUri}"),
                            metadata = buildMap {
                                put("source", "mediastore")
                                put("browser_safe", browserSafe.toString())
                            }
                        )
                    )
                    count++
                }
                Log.d(TAG, "Processed $count items from $collection")
            } ?: Log.w(TAG, "Cursor was null for $collection")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan collection $collection", e)
        }
    }

    private class SharedFolderBuilder(val id: String, val displayName: String) {
        var fileCount = 0
        var totalSizeBytes = 0L
    }

    private fun Cursor.getStringOrNull(idx: Int): String? = if (!isNull(idx)) getString(idx) else null
    private fun Cursor.getLongOrNull(idx: Int): Long? = if (!isNull(idx)) getLong(idx) else null

    private fun stableId(value: String): String {
        return UUID.nameUUIDFromBytes(value.toByteArray()).toString()
    }
}
