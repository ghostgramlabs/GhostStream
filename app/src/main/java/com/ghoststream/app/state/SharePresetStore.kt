package com.ghoststream.app.state

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.ghoststream.core.model.LibraryState
import com.ghoststream.core.model.SharePreset
import com.ghoststream.core.storage.StorageRepository
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SharePresetStore(
    context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { context.preferencesDataStoreFile("ghoststream_share_presets.preferences_pb") },
    )
    private val _presets = MutableStateFlow<List<SharePreset>>(emptyList())

    val presets: StateFlow<List<SharePreset>> = _presets.asStateFlow()

    init {
        scope.launch {
            _presets.value = currentPresets()
        }
    }

    suspend fun saveCurrentSelection(name: String, libraryState: LibraryState): Result<SharePreset> = runCatching {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Enter a collection name first." }
        require(libraryState.summary.totalItems > 0 || libraryState.folders.isNotEmpty()) {
            "Add some content first before saving a collection."
        }

        val existing = currentPresets()
        val now = System.currentTimeMillis()
        val matched = existing.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        val preset = SharePreset(
            id = matched?.id ?: stableId("preset:$trimmed"),
            name = trimmed,
            createdAtEpochMs = matched?.createdAtEpochMs ?: now,
            lastUsedAtEpochMs = matched?.lastUsedAtEpochMs,
            itemUris = libraryState.items.map { it.uri },
            folderUris = libraryState.folders.map { it.treeUri },
            itemCount = libraryState.summary.totalItems,
            totalBytes = libraryState.summary.totalBytes,
        )
        val merged = (existing.filterNot { it.id == preset.id } + preset)
            .sortedByDescending { it.lastUsedAtEpochMs ?: it.createdAtEpochMs }
        persistPresets(merged)
        preset
    }

    suspend fun saveSelectedItems(
        name: String,
        selectedItemIds: Collection<String>,
        libraryState: LibraryState,
    ): Result<SharePreset> = runCatching {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Enter a collection name first." }
        require(selectedItemIds.isNotEmpty()) { "Select at least one file first." }

        val selectedIdSet = selectedItemIds.toSet()
        val selectedItems = libraryState.items
            .filter { it.id in selectedIdSet }
            .ifEmpty { error("Those files are no longer available in Shared Library.") }

        val selectedWithSubtitles = selectedItems + selectedItems.mapNotNull { item ->
            item.subtitleMatch?.let { match ->
                libraryState.items.firstOrNull { it.id == match.subtitleItemId }
            }
        }

        val existing = currentPresets()
        val now = System.currentTimeMillis()
        val matched = existing.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        val preset = SharePreset(
            id = matched?.id ?: stableId("preset:$trimmed"),
            name = trimmed,
            createdAtEpochMs = matched?.createdAtEpochMs ?: now,
            lastUsedAtEpochMs = matched?.lastUsedAtEpochMs,
            itemUris = selectedWithSubtitles.map { it.uri }.distinct(),
            folderUris = emptyList(),
            itemCount = selectedWithSubtitles.size,
            totalBytes = selectedWithSubtitles.sumOf { it.sizeBytes },
        )
        val merged = (existing.filterNot { it.id == preset.id } + preset)
            .sortedByDescending { it.lastUsedAtEpochMs ?: it.createdAtEpochMs }
        persistPresets(merged)
        preset
    }

    suspend fun applyPreset(
        presetId: String,
        storageRepository: StorageRepository,
    ): Result<LibraryState> = runCatching {
        val presets = currentPresets()
        val preset = presets.firstOrNull { it.id == presetId }
            ?: error("This collection is no longer available.")

        withContext(Dispatchers.IO) {
            storageRepository.clearSelection()
            preset.folderUris
                .distinct()
                .map(Uri::parse)
                .forEach { treeUri ->
                    storageRepository.addFolder(treeUri)
                }

            val fileUris = preset.itemUris
                .distinct()
                .map(Uri::parse)
            if (fileUris.isNotEmpty()) {
                storageRepository.addFiles(fileUris)
            }
        }

        val refreshedState = storageRepository.libraryState.value
        if (refreshedState.summary.totalItems == 0 && preset.itemCount > 0) {
            error("That collection couldn't be restored. Some files or permissions are no longer available.")
        }

        val now = System.currentTimeMillis()
        persistPresets(
            presets.map { existing ->
                if (existing.id == preset.id) {
                    existing.copy(
                        lastUsedAtEpochMs = now,
                        itemCount = refreshedState.summary.totalItems,
                        totalBytes = refreshedState.summary.totalBytes,
                    )
                } else {
                    existing
                }
            }.sortedByDescending { it.lastUsedAtEpochMs ?: it.createdAtEpochMs },
        )
        refreshedState
    }

    suspend fun deletePreset(presetId: String) {
        persistPresets(currentPresets().filterNot { it.id == presetId })
    }

    private suspend fun currentPresets(): List<SharePreset> {
        return dataStore.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .map { preferences ->
                preferences[PRESETS_KEY]
                    ?.let(::decodePresets)
                    .orEmpty()
                    .sortedByDescending { it.lastUsedAtEpochMs ?: it.createdAtEpochMs }
            }
            .first()
    }

    private suspend fun persistPresets(presets: List<SharePreset>) {
        dataStore.edit { preferences ->
            preferences[PRESETS_KEY] = encodePresets(presets)
        }
        _presets.value = presets
    }

    private fun decodePresets(encoded: String): List<SharePreset> {
        return runCatching {
            val root = JSONArray(encoded)
            buildList {
                for (index in 0 until root.length()) {
                    val entry = root.optJSONObject(index) ?: continue
                    add(
                        SharePreset(
                            id = entry.optString("id"),
                            name = entry.optString("name"),
                            createdAtEpochMs = entry.optLong("createdAtEpochMs"),
                            lastUsedAtEpochMs = entry.takeIf { !it.isNull("lastUsedAtEpochMs") }?.optLong("lastUsedAtEpochMs"),
                            itemUris = entry.optJSONArray("itemUris").toStringList(),
                            folderUris = entry.optJSONArray("folderUris").toStringList(),
                            itemCount = entry.optInt("itemCount"),
                            totalBytes = entry.optLong("totalBytes"),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun encodePresets(presets: List<SharePreset>): String {
        return JSONArray().apply {
            presets.forEach { preset ->
                put(
                    JSONObject().apply {
                        put("id", preset.id)
                        put("name", preset.name)
                        put("createdAtEpochMs", preset.createdAtEpochMs)
                        put("lastUsedAtEpochMs", preset.lastUsedAtEpochMs)
                        put("itemUris", JSONArray().apply { preset.itemUris.forEach(::put) })
                        put("folderUris", JSONArray().apply { preset.folderUris.forEach(::put) })
                        put("itemCount", preset.itemCount)
                        put("totalBytes", preset.totalBytes)
                    },
                )
            }
        }.toString()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val value = optString(index)
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun stableId(value: String): String {
        return UUID.nameUUIDFromBytes(value.toByteArray()).toString()
    }

    private companion object {
        val PRESETS_KEY = stringPreferencesKey("share_presets_json")
    }
}
