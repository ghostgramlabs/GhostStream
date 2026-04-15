package com.ghostgramlabs.directserve.debug

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.ghostgramlabs.directserve.BuildConfig
import com.ghoststream.core.model.DebugLogSink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DebugLogRepository(
    context: Context,
    private val enabled: Boolean = BuildConfig.DEBUG,
) : DebugLogSink {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()
    private var cachedModernUri: Uri? = null

    // Generate a unique filename per session using the session start timestamp.
    // This prevents MediaStore "file already exists" failures on repeated installs
    // or log sharing, where a file with the same name may still exist in Downloads.
    private val fileName: String = run {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        "directserve-debug-$ts.log"
    }

    fun isEnabled(): Boolean = enabled

    override fun log(tag: String, message: String, throwable: Throwable?) {
        if (!enabled) return
        val rendered = buildString {
            append(timestamp())
            append(" [")
            append(tag)
            append("] ")
            append(message)
            if (throwable != null) {
                appendLine()
                append(Log.getStackTraceString(throwable))
            }
        }
        Log.d(LOG_TAG, rendered)
        scope.launch {
            append(rendered)
        }
    }

    suspend fun clear(): Result<Unit> {
        if (!enabled) return Result.success(Unit)
        return runCatching {
            withContext(Dispatchers.IO) {
                fileMutex.withLock {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val uri = try {
                            ensureModernLogUri()
                        } catch (t: Throwable) {
                            Log.e(LOG_TAG, "Failed to ensure URI for clear", t)
                            null
                        } ?: error("Unable to create debug log file.")
                        appContext.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                            writer.appendLine("${timestamp()} [DebugLog] Log cleared")
                        } ?: error("Unable to clear debug log file.")
                    } else {
                        val file = ensureLegacyLogFile()
                        file.parentFile?.mkdirs()
                        file.writeText("${timestamp()} [DebugLog] Log cleared\n")
                    }
                }
            }
        }
    }

    suspend fun shareableUri(): Result<Uri> {
        if (!enabled) return Result.failure(IllegalStateException("Debug logging is only available in debug builds."))
        return runCatching {
            withContext(Dispatchers.IO) {
                fileMutex.withLock {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            ensureModernLogUri() ?: error("Unable to create debug log file.")
                        } catch (t: Throwable) {
                            Log.e(LOG_TAG, "Failed to obtain shareable URI", t)
                            throw t
                        }
                    } else {
                        FileProvider.getUriForFile(
                            appContext,
                            "${BuildConfig.APPLICATION_ID}.fileprovider",
                            ensureLegacyLogFile(),
                        )
                    }
                }
            }
        }
    }

    fun locationDescription(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${RELATIVE_PATH}$fileName"
        } else {
            "DirectServe app downloads/$fileName"
        }
    }

    private suspend fun append(rendered: String) {
        if (!enabled) return
        runCatching {
            withContext(Dispatchers.IO) {
                fileMutex.withLock {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val uri = ensureModernLogUri() ?: return@withLock
                        appContext.contentResolver.openOutputStream(uri, "wa")?.bufferedWriter()?.use { writer ->
                            writer.appendLine(rendered)
                        }
                    } else {
                        val file = ensureLegacyLogFile()
                        file.parentFile?.mkdirs()
                        file.appendText("$rendered\n")
                    }
                }
            }
        }.onFailure { t ->
            Log.e(LOG_TAG, "Failed to append log entry", t)
        }
    }

    private fun ensureLegacyLogFile(): File {
        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "debug-downloads")
        return File(File(directory, "DirectServe"), fileName)
    }

    private fun ensureModernLogUri(): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        cachedModernUri?.let { return it }

        return try {
            val resolver = appContext.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

            // ── 1. Try exact query by this session's filename ──
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            val selectionArgs = arrayOf(fileName, RELATIVE_PATH)

            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    return ContentUris.withAppendedId(collection, id).also { cachedModernUri = it }
                }
            }

            // ── 2. Fallback: name-only query ──
            val nameSelection = "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
            val nameArgs = arrayOf(fileName)
            resolver.query(collection, projection, nameSelection, nameArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    return ContentUris.withAppendedId(collection, id).also { cachedModernUri = it }
                }
            }

            // ── 3. Insert new file ──
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            }
            resolver.insert(collection, values)?.also { cachedModernUri = it }
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Critical failure in ensureModernLogUri; falling back to logcat only", t)
            null
        }
    }

    private fun timestamp(): String {
        return dateFormatter.format(Date())
    }

    private companion object {
        const val LOG_TAG = "DirectServeDebug"
        val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/DirectServe/"
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
