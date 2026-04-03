package com.ghoststream.core.media

import android.net.Uri
import com.ghoststream.core.model.PlaybackDecision
import com.ghoststream.core.model.SharedItem

interface MediaAnalyzer {
    fun inspect(uri: Uri, mimeType: String?, displayName: String): MediaInspection
    fun decidePlayback(inspection: MediaInspection): PlaybackDecision
    fun decidePlayback(uri: Uri, mimeType: String?, displayName: String): PlaybackDecision {
        return decidePlayback(inspect(uri, mimeType, displayName))
    }
    fun readDurationMs(uri: Uri, mimeType: String?): Long?
    suspend fun loadThumbnailBytes(item: SharedItem, maxSizePx: Int = 640): ByteArray?
    suspend fun clearTemporaryCache()
}
