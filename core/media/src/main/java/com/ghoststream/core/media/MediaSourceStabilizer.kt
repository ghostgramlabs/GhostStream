package com.ghoststream.core.media

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaSourceStabilizer(
    private val context: Context,
    private val cache: TempPlaybackCache,
) {
    private val resolver: ContentResolver = context.contentResolver

    suspend fun stabilize(
        uriString: String,
        mimeType: String?,
        sizeBytes: Long,
        itemId: String
    ): StabilizedSourceInfo = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val sourceType = classify(uri)
        
        CompatLogger.debug("Stabilizer", "stabilizing itemId=$itemId type=$sourceType uri=$uriString")

        var permissionAttempted = false
        var permissionSucceeded = false
        
        // 1. Persistable Permission
        if (sourceType == SourceType.SAF_DOCUMENT_URI) {
            permissionAttempted = true
            permissionSucceeded = try {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                true
            } catch (e: Exception) {
                CompatLogger.warn("Stabilizer", "Failed to take persistable permission for $uri", e)
                false
            }
            CompatLogger.debug("Stabilizer", "persistable_grant itemId=$itemId success=$permissionSucceeded")
        }

        // 2. Readability Probe
        val readProbeSuccess = probeReadability(uri)
        CompatLogger.debug("Stabilizer", "read_probe itemId=$itemId success=$readProbeSuccess")

        // 3. Normalization
        var normalizedUri: String? = null
        var normalizationAttempted = false
        var normalizationVerified = false
        var normalizationConfidence = 0f
        var normalizationFailureReason: String? = null

        if (sourceType == SourceType.SAF_DOCUMENT_URI) {
            normalizationAttempted = true
            val resolved = tryNormalizeSafToMediaStore(uri)
            if (resolved != null) {
                normalizedUri = resolved.toString()
                normalizationConfidence = 1.0f
                normalizationVerified = probeReadability(resolved)
                if (!normalizationVerified) {
                    normalizationFailureReason = "NORMALIZED_URI_UNREADABLE"
                }
            } else {
                normalizationFailureReason = "NO_MEDIASTORE_MATCH"
            }
            CompatLogger.debug("Stabilizer", "normalization itemId=$itemId success=$normalizationVerified result=$normalizedUri")
        }

        // 4. Decision & Materialization
        val (stableSource, finalSourceType) = when {
            // Priority 1: App private file (already stable)
            sourceType == SourceType.APP_FILE -> {
                StableWorkerSource.AppPrivateFile(uri.path ?: uriString) to SourceType.APP_FILE
            }
            
            // Priority 2: Verified Normalization (Fast path)
            normalizationVerified && normalizedUri != null -> {
                StableWorkerSource.MediaStoreUri(normalizedUri) to SourceType.MEDIASTORE_URI
            }

            // Priority 3: Persisted SAF (if verified readable and direct read is preferred)
            // However, the policy says for fragile URIs (SAF), materialization is preferred for reliability.
            // sourceType == SourceType.SAF_DOCUMENT_URI && permissionSucceeded && readProbeSuccess -> { ... }

            // Priority 4: Materialization (Reliability path)
            else -> {
                val materializedFile = materialize(uri, itemId)
                if (materializedFile != null) {
                    StableWorkerSource.AppPrivateFile(materializedFile.absolutePath) to SourceType.APP_FILE
                } else {
                    null to sourceType // Failed to materialize
                }
            }
        }

        StabilizedSourceInfo(
            originalUri = uriString,
            sourceType = sourceType,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            persistablePermissionAttempted = permissionAttempted,
            persistablePermissionSucceeded = permissionSucceeded,
            normalizationAttempted = normalizationAttempted,
            normalizationConfidence = normalizationConfidence,
            normalizationVerified = normalizationVerified,
            normalizationFailureReason = normalizationFailureReason,
            readProbeSuccess = readProbeSuccess,
            chosenStableSource = stableSource
        )
    }

    private fun classify(uri: Uri): SourceType {
        val scheme = uri.scheme
        val authority = uri.authority ?: ""
        
        return when {
            scheme == "file" -> SourceType.APP_FILE
            scheme == "content" && authority == MediaStore.AUTHORITY -> SourceType.MEDIASTORE_URI
            scheme == "content" && authority.contains("documents") -> SourceType.SAF_DOCUMENT_URI
            scheme == "content" -> SourceType.PROVIDER_URI
            else -> SourceType.OTHER_URI
        }
    }

    private fun probeReadability(uri: Uri): Boolean {
        return try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun tryNormalizeSafToMediaStore(safUri: Uri): Uri? {
        if (!DocumentsContract.isDocumentUri(context, safUri)) return null
        
        return try {
            val documentId = DocumentsContract.getDocumentId(safUri)
            if (documentId.startsWith("video:") || documentId.startsWith("audio:") || documentId.startsWith("image:")) {
                val id = documentId.split(":")[1]
                val baseUri = when {
                    documentId.startsWith("video:") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    documentId.startsWith("audio:") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                Uri.withAppendedPath(baseUri, id)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun materialize(uri: Uri, itemId: String): File? = withContext(Dispatchers.IO) {
        val targetFile = cache.getStabilizedSourceFile(itemId)
        try {
            CompatLogger.info("Stabilizer", "materializing itemId=$itemId to ${targetFile.absolutePath}")
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (targetFile.exists() && targetFile.length() > 0) {
                targetFile
            } else {
                null
            }
        } catch (e: Exception) {
            CompatLogger.error("Stabilizer", "materialization_failed itemId=$itemId", e)
            runCatching { targetFile.delete() }
            null
        }
    }
}
