package com.ghoststream.core.storage

import com.ghoststream.core.model.MediaCategory
import com.ghoststream.core.model.PlaybackMode
import com.ghoststream.core.model.SharedFolder
import com.ghoststream.core.model.SharedItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against silent library data loss on app updates.
 *
 * If anyone edits SharedItem, SharedFolder, PlaybackDecision, MediaCategory, PlaybackMode,
 * or SubtitleMatch in a way that breaks decoding of older user data, these tests fail.
 *
 * The JSON snapshots below are frozen — do not "fix" them by adding new fields. If a new
 * field is added to a model, give it a default value so old data still decodes.
 */
class LibrarySchemaCompatTest {

    // Mirrors the private PersistedLibrary in AndroidStorageRepository.
    @Serializable
    private data class PersistedLibrary(
        val items: List<SharedItem>,
        val folders: List<SharedFolder>,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `v1 minimal library JSON still decodes`() {
        // Frozen v1 schema — only the fields present in the very first release.
        val legacy = """
        {
          "items": [
            {
              "id": "item-1",
              "uri": "content://com.android.providers.media.documents/document/video%3A42",
              "displayName": "vacation.mp4",
              "mimeType": "video/mp4",
              "category": "VIDEO",
              "sizeBytes": 1048576,
              "dateAddedEpochMs": 1700000000000
            }
          ],
          "folders": [
            {
              "id": "folder-1",
              "treeUri": "content://com.android.externalstorage.documents/tree/primary%3ADCIM",
              "displayName": "Camera",
              "fileCount": 12,
              "totalSizeBytes": 52428800,
              "addedAtEpochMs": 1700000000000,
              "permissionPersisted": true
            }
          ]
        }
        """.trimIndent()

        val decoded = json.decodeFromString(PersistedLibrary.serializer(), legacy)

        assertEquals(1, decoded.items.size)
        assertEquals("item-1", decoded.items[0].id)
        assertEquals(MediaCategory.VIDEO, decoded.items[0].category)
        assertEquals(PlaybackMode.DIRECT, decoded.items[0].playbackDecision.mode)
        assertEquals(true, decoded.items[0].isAvailable)
        assertEquals(1, decoded.folders.size)
        assertEquals("Camera", decoded.folders[0].displayName)
    }

    @Test
    fun `v1 JSON containing a now-removed field still decodes`() {
        // Simulates a future where we remove a field — old data with the field must still load.
        val withExtra = """
        {
          "items": [
            {
              "id": "item-1",
              "uri": "content://x/1",
              "displayName": "a.mp4",
              "mimeType": "video/mp4",
              "category": "VIDEO",
              "sizeBytes": 1,
              "dateAddedEpochMs": 1,
              "removedFutureField": "ignored"
            }
          ],
          "folders": []
        }
        """.trimIndent()

        val decoded = json.decodeFromString(PersistedLibrary.serializer(), withExtra)
        assertEquals(1, decoded.items.size)
    }

    @Test
    fun `unknown enum value falls back without crashing`() {
        // If an old build wrote an enum value that the new build no longer knows about,
        // coerceInputValues must keep the decode alive (item gets default category).
        val withUnknownEnum = """
        {
          "items": [
            {
              "id": "item-1",
              "uri": "content://x/1",
              "displayName": "weird.bin",
              "mimeType": null,
              "category": "FILE",
              "sizeBytes": 1,
              "dateAddedEpochMs": 1,
              "playbackDecision": { "mode": "TOTALLY_NEW_MODE" }
            }
          ],
          "folders": []
        }
        """.trimIndent()

        val decoded = json.decodeFromString(PersistedLibrary.serializer(), withUnknownEnum)
        assertEquals(1, decoded.items.size)
        assertNotNull(decoded.items[0].playbackDecision)
        // Mode coerces to default (DIRECT) instead of throwing.
        assertEquals(PlaybackMode.DIRECT, decoded.items[0].playbackDecision.mode)
    }

    @Test
    fun `nullable mimeType decodes as null`() {
        val withNullMime = """
        {
          "items": [
            {
              "id": "item-1",
              "uri": "content://x/1",
              "displayName": "noext",
              "mimeType": null,
              "category": "FILE",
              "sizeBytes": 1,
              "dateAddedEpochMs": 1
            }
          ],
          "folders": []
        }
        """.trimIndent()

        val decoded = json.decodeFromString(PersistedLibrary.serializer(), withNullMime)
        assertTrue(decoded.items[0].mimeType == null)
    }
}
