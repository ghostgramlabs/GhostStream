package com.ghoststream.core.storage

import android.net.Uri
import com.ghoststream.core.model.LibraryState
import com.ghoststream.core.model.SharedFolder
import com.ghoststream.core.model.SharedItem
import com.ghoststream.core.model.SmartSelectionGroup
import kotlinx.coroutines.flow.StateFlow

interface StorageRepository {
    val libraryState: StateFlow<LibraryState>

    suspend fun addFiles(uris: List<Uri>): LibraryState
    suspend fun addFolder(treeUri: Uri): Result<SharedFolder>
    suspend fun addSmartSelection(uris: List<Uri>): LibraryState
    suspend fun removeItem(itemId: String)
    suspend fun removeFolder(folderId: String)
    suspend fun refreshAvailability()
    suspend fun clearSelection()
    suspend fun loadSmartSelectionGroups(): List<SmartSelectionGroup>
    fun findItemById(itemId: String): SharedItem?
}

