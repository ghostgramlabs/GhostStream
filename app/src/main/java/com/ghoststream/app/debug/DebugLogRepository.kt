package com.ghoststream.app.debug

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.ghoststream.app.BuildConfig
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
                        val uri = ensureModernLogUri() ?: error("Unable to create debug log file.")
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
                        ensureModernLogUri() ?: error("Unable to create debug log file.")
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
            "Downloads/GhostStream/$FILE_NAME"
        } else {
            "GhostStream app downloads/$FILE_NAME"
        }
    }

    private suspend fun append(rendered: String) {
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
    }

    private fun ensureLegacyLogFile(): File {
        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "debug-downloads")
        return File(File(directory, "GhostStream"), FILE_NAME)
    }

    private fun ensureModernLogUri(): Uri? {
        cachedModernUri?.let { return it }
        val resolver = appContext.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(FILE_NAME, RELATIVE_PATH)
        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return ContentUris.withAppendedId(collection, id).also { cachedModernUri = it }
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        }
        return resolver.insert(collection, values)?.also { cachedModernUri = it }
    }

    private fun timestamp(): String {
        return dateFormatter.format(Date())
    }

    private companion object {
        const val LOG_TAG = "GhostStreamDebug"
        const val FILE_NAME = "ghoststream-debug.log"
        val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/GhostStream/"
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
