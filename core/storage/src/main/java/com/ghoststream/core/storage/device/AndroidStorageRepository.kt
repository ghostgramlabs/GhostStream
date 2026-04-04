package com.ghoststream.core.storage.device

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.documentfile.provider.DocumentFile
import com.ghoststream.core.media.MediaAnalyzer
import com.ghoststream.core.model.LibraryState
import com.ghoststream.core.model.LibrarySummary
import com.ghoststream.core.model.MediaCategory
import com.ghoststream.core.model.SharedFolder
import com.ghoststream.core.model.SharedItem
import com.ghoststream.core.model.SmartSelectionGroup
import com.ghoststream.core.storage.StorageRepository
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AndroidStorageRepository(
    private val context: Context,
    private val mediaAnalyzer: MediaAnalyzer,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : StorageRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistence: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { context.preferencesDataStoreFile("ghoststream_library.preferences_pb") },
    )
    private val stateMutex = Mutex()
    private val _libraryState = MutableStateFlow(LibraryState())

    override val libraryState: StateFlow<LibraryState> = _libraryState.asStateFlow()

    init {
        scope.launchStateRestore()
    }

    override suspend fun addFiles(uris: List<Uri>): LibraryState = stateMutex.withLock {
        val current = currentPersistedState()
        val newItems = uris.mapNotNull { uri ->
            withContext(Dispatchers.IO) {
                buildSingleItemSync(uri = uri, sourceFolderId = null)
            }
        }
        val merged = mergeState(
            items = current.items + newItems,
            folders = current.folders,
        )
        persistAndPublish(merged)
        merged
    }

    override suspend fun addFolder(treeUri: Uri): Result<SharedFolder> = runCatching {
        takeTreePermission(treeUri)
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Unable to access the selected folder.")
        val folderId = stableId(treeUri.toString())
        val addedAt = System.currentTimeMillis()
        val scanned = scanTree(root = root, folderId = folderId)
        val folder = SharedFolder(
            id = folderId,
            treeUri = treeUri.toString(),
            displayName = root.name ?: "Shared folder",
            fileCount = scanned.size,
            totalSizeBytes = scanned.sumOf { it.sizeBytes },
            addedAtEpochMs = addedAt,
            permissionPersisted = true,
        )
        stateMutex.withLock {
            val current = currentPersistedState()
            val merged = mergeState(
                items = current.items.filterNot { it.sourceFolderId == folderId } + scanned,
                folders = current.folders.filterNot { it.id == folderId } + folder,
            )
            persistAndPublish(merged)
        }
        folder
    }

    override suspend fun addSmartSelection(uris: List<Uri>): LibraryState = addFiles(uris)

    override suspend fun removeItem(itemId: String) {
        stateMutex.withLock {
            val current = currentPersistedState()
            val merged = current.copy(items = current.items.filterNot { it.id == itemId })
                .withSummary()
            persistAndPublish(merged)
        }
    }

    override suspend fun removeFolder(folderId: String) {
        stateMutex.withLock {
            val current = currentPersistedState()
            val merged = current.copy(
                items = current.items.filterNot { it.sourceFolderId == folderId },
                folders = current.folders.filterNot { it.id == folderId },
            ).withSummary()
            persistAndPublish(merged)
        }
    }

    override suspend fun refreshAvailability() {
        stateMutex.withLock {
            val current = currentPersistedState()
            val merged = refreshExistingState(current)
            persistAndPublish(merged)
        }
    }

    override suspend fun clearSelection() {
        stateMutex.withLock {
            persistAndPublish(LibraryState())
        }
    }

    override suspend fun loadSmartSelectionGroups(): List<SmartSelectionGroup> = withContext(Dispatchers.IO) {
        val nowSeconds = System.currentTimeMillis() / 1_000L
        val daySeconds = 24L * 60L * 60L
        buildList {
            queryMediaStoreGroup(
                id = "photos_today",
                title = "Photos from today",
                description = "Share today's moments in one tap",
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                mimePrefix = "image/",
                selection = "${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                args = arrayOf((nowSeconds - daySeconds).toString()),
                sortColumn = MediaStore.MediaColumns.DATE_ADDED,
            )?.let(::add)

            queryMediaStoreGroup(
                id = "photos_last_7",
                title = "Photos from last 7 days",
                description = "Fresh photos ready to send",
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                mimePrefix = "image/",
                selection = "${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                args = arrayOf((nowSeconds - 7 * daySeconds).toString()),
                sortColumn = MediaStore.MediaColumns.DATE_ADDED,
            )?.let(::add)

            queryMediaStoreGroup(
                id = "videos_last_week",
                title = "Videos from last week",
                description = "Recent clips optimized for quick sharing",
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                mimePrefix = "video/",
                selection = "${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                args = arrayOf((nowSeconds - 7 * daySeconds).toString()),
                sortColumn = MediaStore.MediaColumns.DATE_ADDED,
            )?.let(::add)

            queryMediaStoreGroup(
                id = "large_files",
                title = "Large files",
                description = "Big files you may want to transfer directly",
                collection = MediaStore.Files.getContentUri("external"),
                mimePrefix = null,
                selection = "${MediaStore.MediaColumns.SIZE} >= ?",
                args = arrayOf((150L * 1024L * 1024L).toString()),
                sortColumn = MediaStore.MediaColumns.SIZE,
            )?.let(::add)

            queryMediaStoreGroup(
                id = "recent_media",
                title = "Recently added media",
                description = "A quick mix of new photos, videos, and audio",
                collection = MediaStore.Files.getContentUri("external"),
                mimePrefix = null,
                selection = "${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                args = arrayOf((nowSeconds - 3 * daySeconds).toString()),
                sortColumn = MediaStore.MediaColumns.DATE_ADDED,
            )?.let(::add)
        }.filter { it.itemCount > 0 }
    }

    override fun findItemById(itemId: String): SharedItem? {
        return _libraryState.value.items.firstOrNull { it.id == itemId }
    }

    private fun buildSingleItemSync(uri: Uri, sourceFolderId: String?): SharedItem? {
        val resolver = context.contentResolver
        return runCatching {
            val meta = resolver.queryOpenableMeta(uri)
            val now = System.currentTimeMillis()
            val inspection = mediaAnalyzer.inspect(uri, meta.mimeType, meta.displayName)
            val playbackDecision = mediaAnalyzer.decidePlayback(inspection)
            SharedItem(
                id = stableId(uri.toString()),
                uri = uri.toString(),
                displayName = meta.displayName,
                mimeType = meta.mimeType,
                category = determineCategory(meta.mimeType, meta.displayName),
                sizeBytes = meta.sizeBytes,
                durationMs = mediaAnalyzer.readDurationMs(uri, meta.mimeType),
                dateAddedEpochMs = now,
                lastModifiedEpochMs = meta.lastModifiedEpochMs,
                sourceFolderId = sourceFolderId,
                thumbnailKey = stableId("thumb:${uri}"),
                playbackDecision = playbackDecision,
                metadata = buildMap {
                    put("source", uri.authority ?: "local")
                    inspection.videoTrackMimeType?.let { put("video_codec", it) }
                    inspection.audioTrackMimeType?.let { put("audio_codec", it) }
                    put("browser_safe", inspection.browserSafe.toString())
                },
            )
        }.getOrNull()
    }

    private suspend fun scanTree(root: DocumentFile, folderId: String): List<SharedItem> {
        return withContext(Dispatchers.IO) {
            val accumulator = mutableListOf<SharedItem>()

            fun walk(node: DocumentFile) {
                val children = runCatching { node.listFiles() }.getOrDefault(emptyArray())
                children.forEach { child ->
                    when {
                        child.isDirectory -> walk(child)
                        child.isFile -> {
                            buildSingleItemSync(
                                uri = child.uri,
                                sourceFolderId = folderId,
                            )?.let(accumulator::add)
                        }
                    }
                }
            }

            walk(root)
            pairSubtitles(accumulator)
        }
    }

    private fun pairSubtitles(items: List<SharedItem>): List<SharedItem> {
        val subtitleLookup = items
            .filter { item -> item.mimeType == "application/x-subrip" || item.displayName.endsWith(".srt", ignoreCase = true) || item.displayName.endsWith(".vtt", ignoreCase = true) }
            .associateBy { item -> item.displayName.substringBeforeLast('.') }

        return items.map { item ->
            if (item.category != MediaCategory.VIDEO) return@map item
            val subtitle = subtitleLookup[item.displayName.substringBeforeLast('.')]
            if (subtitle == null) item else item.copy(
                subtitleMatch = com.ghoststream.core.model.SubtitleMatch(
                    subtitleItemId = subtitle.id,
                    label = subtitle.displayName.substringAfterLast('.', "Subtitle").uppercase(),
                    mimeType = subtitle.mimeType ?: "text/vtt",
                ),
            )
        }
    }

    private suspend fun currentPersistedState(): LibraryState {
        return persistence.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .map { preferences ->
                preferences[LIBRARY_KEY]
                    ?.let { encoded ->
                        runCatching {
                            json.decodeFromString(PersistedLibrary.serializer(), encoded)
                        }.getOrNull()
                    }
                    ?.toState()
                    ?.withSummary()
                    ?: LibraryState()
            }
            .first()
    }

    private suspend fun persistAndPublish(state: LibraryState) {
        persistence.edit { preferences ->
            preferences[LIBRARY_KEY] = json.encodeToString(
                PersistedLibrary.serializer(),
                PersistedLibrary.from(state),
            )
        }
        _libraryState.value = state
    }

    private fun mergeState(items: List<SharedItem>, folders: List<SharedFolder>): LibraryState {
        val mergedItems = pairSubtitles(items)
            .distinctBy { it.uri }
            .sortedByDescending { it.dateAddedEpochMs }
        return LibraryState(
            items = mergedItems,
            folders = folders.distinctBy { it.id }.sortedByDescending { it.addedAtEpochMs },
        ).withSummary()
    }

    private fun LibraryState.withSummary(): LibraryState {
        val availableItems = items.filter { it.isAvailable }
        return copy(
            summary = LibrarySummary(
                videos = availableItems.count { it.category == MediaCategory.VIDEO },
                photos = availableItems.count { it.category == MediaCategory.PHOTO },
                music = availableItems.count { it.category == MediaCategory.MUSIC },
                files = availableItems.count { it.category == MediaCategory.FILE },
                totalItems = availableItems.size,
                totalBytes = availableItems.sumOf { it.sizeBytes },
            ),
        )
    }

    private fun queryMediaStoreGroup(
        id: String,
        title: String,
        description: String,
        collection: Uri,
        mimePrefix: String?,
        selection: String,
        args: Array<String>,
        sortColumn: String,
    ): SmartSelectionGroup? {
        return runCatching {
            val uris = mutableListOf<String>()
            var totalBytes = 0L
            querySmartGroupCursor(
                collection = collection,
                selection = selection,
                args = args,
                sortColumn = sortColumn,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val itemMime = cursor.getString(mimeIndex)
                    if (mimePrefix != null && itemMime?.startsWith(mimePrefix) != true) continue
                    val contentUri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                    uris += contentUri.toString()
                    totalBytes += cursor.getLong(sizeIndex)
                }
            }
            if (uris.isEmpty()) {
                null
            } else {
                SmartSelectionGroup(
                    id = id,
                    title = title,
                    description = description,
                    itemCount = uris.size,
                    totalSizeBytes = totalBytes,
                    uris = uris,
                )
            }
        }.getOrNull()
    }

    private fun querySmartGroupCursor(
        collection: Uri,
        selection: String,
        args: Array<String>,
        sortColumn: String,
    ): Cursor? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(sortColumn))
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, 60)
            }
            context.contentResolver.query(collection, projection, queryArgs, null)
        } else {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "$sortColumn DESC",
            )
        }
    }

    private fun isUriAvailable(uri: Uri): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: FileNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun takeTreePermission(treeUri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
    }

    private fun determineCategory(mimeType: String?, displayName: String): MediaCategory {
        val mime = mimeType.orEmpty()
        return when {
            mime.startsWith("video/") -> MediaCategory.VIDEO
            mime.startsWith("image/") -> MediaCategory.PHOTO
            mime.startsWith("audio/") -> MediaCategory.MUSIC
            displayName.endsWith(".mp4", ignoreCase = true) -> MediaCategory.VIDEO
            displayName.endsWith(".jpg", ignoreCase = true) || displayName.endsWith(".png", ignoreCase = true) -> MediaCategory.PHOTO
            displayName.endsWith(".mp3", ignoreCase = true) || displayName.endsWith(".wav", ignoreCase = true) -> MediaCategory.MUSIC
            else -> MediaCategory.FILE
        }
    }

    private fun stableId(value: String): String {
        return UUID.nameUUIDFromBytes(value.toByteArray()).toString()
    }

    private fun CoroutineScope.launchStateRestore() {
        launch {
            val restored = currentPersistedState().withSummary()
            val refreshed = refreshExistingState(restored)
            if (refreshed != restored) {
                persistAndPublish(refreshed)
            } else {
                _libraryState.value = refreshed
            }
        }
    }

    private suspend fun refreshExistingState(state: LibraryState): LibraryState = withContext(Dispatchers.IO) {
        val refreshedItems = state.items.map { item ->
            refreshExistingItemSync(item)
        }
        mergeState(
            items = refreshedItems,
            folders = state.folders,
        )
    }

    private fun refreshExistingItemSync(item: SharedItem): SharedItem {
        val uri = item.uri.toUri()
        val available = isUriAvailable(uri)
        if (!available) {
            return item.copy(isAvailable = false)
        }

        return runCatching {
            val meta = context.contentResolver.queryOpenableMeta(uri)
            val resolvedMimeType = meta.mimeType ?: item.mimeType
            val inspection = mediaAnalyzer.inspect(uri, resolvedMimeType, meta.displayName)
            val playbackDecision = mediaAnalyzer.decidePlayback(inspection)
            item.copy(
                displayName = meta.displayName,
                mimeType = resolvedMimeType,
                category = determineCategory(resolvedMimeType, meta.displayName),
                sizeBytes = meta.sizeBytes.takeIf { it > 0L } ?: item.sizeBytes,
                durationMs = mediaAnalyzer.readDurationMs(uri, resolvedMimeType) ?: item.durationMs,
                lastModifiedEpochMs = meta.lastModifiedEpochMs ?: item.lastModifiedEpochMs,
                playbackDecision = playbackDecision,
                isAvailable = true,
                metadata = buildMap {
                    put("source", uri.authority ?: "local")
                    inspection.videoTrackMimeType?.let { put("video_codec", it) }
                    inspection.audioTrackMimeType?.let { put("audio_codec", it) }
                    put("browser_safe", inspection.browserSafe.toString())
                },
            )
        }.getOrElse {
            item.copy(isAvailable = false)
        }
    }

    private data class OpenableMeta(
        val displayName: String,
        val mimeType: String?,
        val sizeBytes: Long,
        val lastModifiedEpochMs: Long?,
    )

    private fun ContentResolver.queryOpenableMeta(uri: Uri): OpenableMeta {
        val fallbackName = uri.lastPathSegment ?: "Shared item"
        val mimeType = getType(uri)
        var displayName = fallbackName
        var sizeBytes = 0L
        var lastModified: Long? = null

        query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                displayName = cursor.getStringOrNull(OpenableColumns.DISPLAY_NAME) ?: fallbackName
                sizeBytes = cursor.getLongOrNull(OpenableColumns.SIZE) ?: 0L
                lastModified = cursor.getLongOrNull(MediaStore.MediaColumns.DATE_MODIFIED)?.times(1_000L)
            }
        }

        return OpenableMeta(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            lastModifiedEpochMs = lastModified,
        )
    }

    private fun Cursor.getStringOrNull(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.getLongOrNull(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    @Serializable
    private data class PersistedLibrary(
        val items: List<SharedItem>,
        val folders: List<SharedFolder>,
    ) {
        fun toState(): LibraryState = LibraryState(items = items, folders = folders)

        companion object {
            fun from(state: LibraryState): PersistedLibrary = PersistedLibrary(
                items = state.items,
                folders = state.folders,
            )
        }
    }

    private companion object {
        val LIBRARY_KEY = stringPreferencesKey("shared_library_json")
    }
}
