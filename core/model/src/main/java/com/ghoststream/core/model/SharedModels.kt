package com.ghoststream.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SubtitleMatch(
    val subtitleItemId: String,
    val label: String = "Subtitle",
    val mimeType: String = "text/vtt",
)

@Serializable
data class SharedItem(
    val id: String,
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val category: MediaCategory,
    val sizeBytes: Long,
    val durationMs: Long? = null,
    val dateAddedEpochMs: Long,
    val lastModifiedEpochMs: Long? = null,
    val sourceFolderId: String? = null,
    val thumbnailKey: String? = null,
    val playbackDecision: PlaybackDecision = PlaybackDecision(),
    val subtitleMatch: SubtitleMatch? = null,
    val isAvailable: Boolean = true,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class SharedFolder(
    val id: String,
    val treeUri: String,
    val displayName: String,
    val fileCount: Int,
    val totalSizeBytes: Long,
    val addedAtEpochMs: Long,
    val permissionPersisted: Boolean,
)

@Serializable
data class LibrarySummary(
    val videos: Int = 0,
    val photos: Int = 0,
    val music: Int = 0,
    val files: Int = 0,
    val totalItems: Int = 0,
    val totalBytes: Long = 0,
)

@Serializable
data class LibraryState(
    val items: List<SharedItem> = emptyList(),
    val folders: List<SharedFolder> = emptyList(),
    val summary: LibrarySummary = LibrarySummary(),
)

@Serializable
data class SmartSelectionGroup(
    val id: String,
    val title: String,
    val description: String,
    val itemCount: Int,
    val totalSizeBytes: Long,
    val uris: List<String>,
)
