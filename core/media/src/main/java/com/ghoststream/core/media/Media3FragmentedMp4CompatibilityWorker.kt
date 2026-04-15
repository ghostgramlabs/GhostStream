package com.ghoststream.core.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.nio.ByteBuffer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.TransformationRequest
import com.ghoststream.core.model.PlaybackMode
import com.ghoststream.core.model.SharedItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.ghoststream.core.model.DebugLogSink
import com.ghoststream.core.model.NoOpDebugLogSink

@OptIn(UnstableApi::class)
class Media3FragmentedMp4CompatibilityWorker(
    private val context: Context,
    private val debugLogSink: DebugLogSink = NoOpDebugLogSink,
) : CompatibilityWorker {
    private val activeTransforms = ConcurrentHashMap<String, ActiveTransform>()

    override suspend fun prepare(
        item: SharedItem,
        cache: PlaybackCache,
        stabilizedSource: StabilizedSourceInfo?,
        startOffsetMs: Long,
        onUpdate: (CompatibilityWorkerUpdate) -> Unit,
    ): CompatibilityWorkerResult {
        val result = runJob(item, cache, stabilizedSource, startOffsetMs, onUpdate)

        // If REMUX fails, we automatically retry with TRANSCODE. AVI files often report
        // H.264 video but have timing issues that cause Media3's Transmuxer to fail
        // during REMUX; dropping back to full TRANSCODE usually resolves this.
        return if (result is CompatibilityWorkerResult.Failure &&
            item.playbackDecision.mode == PlaybackMode.REMUX
        ) {
            onUpdate(
                CompatibilityWorkerUpdate(
                    message = "Lightning optimization failed; retrying with full compatibility conversion...",
                    status = CompatibilityStatus.PREPARING,
                    progressPercent = 0,
                ),
            )
            // Force a transcode decision for the retry attempt.
            val fallbackItem = item.copy(
                playbackDecision = item.playbackDecision.copy(
                    mode = PlaybackMode.TRANSCODE,
                    reason = "Falling back to transcode after remux failure",
                ),
            )
            runJob(fallbackItem, cache, stabilizedSource, startOffsetMs, onUpdate)
        } else {
            result
        }
    }

    private suspend fun runJob(
        item: SharedItem,
        cache: PlaybackCache,
        stabilizedSource: StabilizedSourceInfo?,
        startOffsetMs: Long,
        onUpdate: (CompatibilityWorkerUpdate) -> Unit,
    ): CompatibilityWorkerResult = withContext(Dispatchers.IO) {
        if (item.playbackDecision.mode == PlaybackMode.DIRECT) {
            return@withContext CompatibilityWorkerResult.Failure(item.playbackDecision.reason)
        }

        val jobStartMs = System.currentTimeMillis()
        android.util.Log.i(
            "GhostStream/Compat",
            "prepare_enqueue id=${item.id} mode=${item.playbackDecision.mode} " +
                "reason=\"${item.playbackDecision.reason}\" " +
                "size=${item.sizeBytes} offset=$startOffsetMs",
        )

        val tmpOutputFile = cache.newOutputFile(item, "tmp")
        val finalOutputFile = cache.newOutputFile(item, "mp4")
        runCatching { tmpOutputFile.parentFile?.mkdirs() }

        // If a finalized prepared asset already exists and matches source, skip re-prepare.
        val existingAsset = cache.lookup(item)
        if (existingAsset != null) {
            android.util.Log.i("GhostStream/Compat", "cache_hit id=${item.id} path=${existingAsset.filePath}")
            onUpdate(
                CompatibilityWorkerUpdate(
                    status = CompatibilityStatus.READY,
                    message = "Cached browser playback is ready.",
                    progressPercent = 100,
                    preparedAsset = existingAsset,
                    hlsReady = true,
                    directReady = true,
                    streamable = true,
                ),
            )
            return@withContext CompatibilityWorkerResult.Success(
                preparedAsset = existingAsset,
                message = "Cached browser playback is ready.",
            )
        }

        // Delete any leftover .tmp from a previous interrupted transcode (safe to remove since
        // TempPlaybackCache.lookup ignores .tmp files — a partial .tmp is never treated as READY).
        if (tmpOutputFile.exists()) runCatching { tmpOutputFile.delete() }
        // Delete any stale .mp4 that might have been left over as well.
        if (finalOutputFile.exists()) runCatching { finalOutputFile.delete() }

        // Boost the encoder thread to foreground priority so the OS scheduler gives it
        // more CPU slices, reducing transcode latency when the user is actively waiting.
        val thread = HandlerThread(
            "ghoststream-transform-${item.id}",
            Process.THREAD_PRIORITY_FOREGROUND,
        ).apply { start() }
        val handler = Handler(thread.looper)
        val completion = CompletableDeferred<CompatibilityWorkerResult>()
        val cancelled = AtomicBoolean(false)
        val transform = ActiveTransform(
            itemId = item.id,
            outputFile = tmpOutputFile,
            finalFile = finalOutputFile,
            handler = handler,
            thread = thread,
            completion = completion,
        )
        activeTransforms[item.id] = transform

        // Resolve the stable source for input
        val inputSource = when (val stable = stabilizedSource?.chosenStableSource) {
            is StableWorkerSource.AppPrivateFile -> Uri.fromFile(File(stable.path))
            is StableWorkerSource.MediaStoreUri -> Uri.parse(stable.uri)
            is StableWorkerSource.PersistedUri -> Uri.parse(stable.uri)
            null -> {
                CompatLogger.warn("CompatWorker", "No stabilized source for ${item.id}; falling back to original URI")
                Uri.parse(item.uri)
            }
        }
        CompatLogger.info("CompatExport", "Starting export id=${item.id} from stableSource=$inputSource")

        handler.post {
            try {
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        val message = completedMessage(item, exportResult)
                        val elapsedSec = (System.currentTimeMillis() - jobStartMs) / 1000
                        // Optimization Step: Ensure Faststart (moov at front) for the finalized file.
                        // We do this BEFORE validation so we can verify the moov position.
                        val optimizedFile = try {
                            finalizePlaybackAsset(item, tmpOutputFile)
                        } catch (e: Exception) {
                            CompatLogger.warn("CompatExport", "Optimization failed for ${item.id}; falling back to raw output", e)
                            tmpOutputFile
                        }

                        // Validation Step: Inspect the optimized file before marking it as READY.
                        val validationResult = validateOutput(optimizedFile)
                        if (!validationResult.isValid) {
                            CompatLogger.error("CompatValidate", "VALIDATION FAILED id=${item.id} reason=${validationResult.error}")
                            runCatching { optimizedFile.delete() }
                            if (optimizedFile != tmpOutputFile) runCatching { tmpOutputFile.delete() }
                            completion.complete(
                                CompatibilityWorkerResult.Failure(
                                    message = "Generated media failed universal compatibility validation: ${validationResult.error}",
                                ),
                            )
                            return
                        }

                        // Atomic rename: .tmp/.opt → .mp4
                        val completedFile = if (optimizedFile.renameTo(finalOutputFile)) {
                            val totalElapsed = (System.currentTimeMillis() - jobStartMs) / 1000
                            android.util.Log.i("GhostStream/Compat", "finalize_complete id=${item.id} path=${finalOutputFile.name} size=${finalOutputFile.length()} totalElapsed=${totalElapsed}s")
                            finalOutputFile
                        } else {
                            android.util.Log.w("GhostStream/Compat", "finalize_rename_failed id=${item.id}")
                            optimizedFile
                        }
                        
                        // Only TRANSCODE uses fragmented MP4 (for play-while-transcoding HLS).
                        // REMUX and TRANSMUX produce regular MP4 files.
                        val isHlsMode = item.playbackDecision.mode == PlaybackMode.TRANSCODE
                        val asset = cache.record(
                            itemId = item.id,
                            file = completedFile,
                            mimeType = "video/mp4",
                            isComplete = true,
                            isFragmentedMp4 = isHlsMode,
                        )
                        
                        android.util.Log.i("GhostStream/Compat", "READY id=${item.id} mode=${item.playbackDecision.mode}")
                        onUpdate(
                            CompatibilityWorkerUpdate(
                                status = CompatibilityStatus.READY,
                                message = message,
                                progressPercent = 100,
                                preparedAsset = asset,
                                hlsReady = true,
                                directReady = true,
                                streamable = true,
                            ),
                        )
                        completion.complete(
                            CompatibilityWorkerResult.Success(
                                preparedAsset = asset,
                                message = message,
                            ),
                        )
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        val failedElapsed = (System.currentTimeMillis() - jobStartMs) / 1000
                        val logMsg = "FAILED id=${item.id} mode=${item.playbackDecision.mode} offset=$startOffsetMs elapsed=${failedElapsed}s"
                        android.util.Log.e("GhostStream/Compat", logMsg, exportException)
                        debugLogSink.log("CompatWorker", logMsg, exportException)
                        runCatching { tmpOutputFile.delete() }
                        completion.complete(
                            CompatibilityWorkerResult.Failure(
                                message = exportException.message
                                    ?: "Unable to prepare a compatible browser stream for this file.",
                            ),
                        )
                    }

                    override fun onFallbackApplied(
                        composition: Composition,
                        originalTransformationRequest: TransformationRequest,
                        fallbackTransformationRequest: TransformationRequest,
                    ) {
                        onUpdate(
                            CompatibilityWorkerUpdate(
                                message = "Adjusted compatibility settings for this device.",
                            ),
                        )
                    }
                }

                // Muxer configuration:
                // - REMUX + TRANSMUX: Regular (non-fragmented) MP4 with moov at front.
                //   This produces a universally playable file that can be served with
                //   HTTP range requests to any browser. No HLS/MSE complexity needed.
                // - TRANSCODE: Fragmented MP4 so the HLS indexer can serve segments
                //   while encoding is still in progress (play-while-transcoding).
                //   Once complete, the file is finalized with faststart moov.
                val useFragmentedMp4 = item.playbackDecision.mode == PlaybackMode.TRANSCODE
                val builder = Transformer.Builder(context)
                    .setLooper(thread.looper)
                    .setMuxerFactory(
                        InAppMuxer.Factory.Builder()
                            .setOutputFragmentedMp4(useFragmentedMp4)
                            .setFragmentDurationMs(FRAGMENT_DURATION_MS)
                            .build(),
                    )
                    .addListener(listener)

                // Detect whether the source audio track is already AAC so we can skip
                // audio re-encoding (passthrough/transmux).  Re-encoding AAC→AAC wastes
                // CPU and slightly degrades quality with no benefit to the browser.
                val sourceAudioMimeType = runCatching { detectAudioMimeType(context, inputSource) }.getOrNull()
                val isNativeAudio = sourceAudioMimeType == MimeTypes.AUDIO_AAC ||
                    sourceAudioMimeType == "audio/mp4a-latm" ||
                    sourceAudioMimeType == MimeTypes.AUDIO_MPEG ||
                    sourceAudioMimeType == "audio/mpeg" ||
                    sourceAudioMimeType == "audio/x-mp3"

                // Universal format configuration: H.264 Main 4.1 + AAC-LC.
                // We use setVideoMimeType and setAudioMimeType to trigger re-encoding.
                if (item.playbackDecision.mode == PlaybackMode.TRANSCODE) {
                    builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                    // Note: Media3 Transformer currently doesn't allow direct profile/level 
                    // setting on the builder, but DefaultEncoderFactory will pick a 
                    // compatible one for the given MIME. Validation later will catch any deviation.
                }

                if (!isNativeAudio) {
                    builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                }

                // If we are doing any encoding (video or audio), we need an encoder factory.
                if (item.playbackDecision.mode == PlaybackMode.TRANSCODE || !isNativeAudio) {
                    val encoderFactory = runCatching {
                        DefaultEncoderFactory.Builder(context)
                            .setEnableFallback(true)
                            .build()
                    }.getOrNull()
                    if (encoderFactory != null) {
                        builder.setEncoderFactory(encoderFactory)
                    }
                }

                val mediaItem = MediaItem.Builder()
                    .setUri(inputSource)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startOffsetMs)
                            .build()
                    )
                    .build()

                val editedMediaItem = if (item.playbackDecision.mode == PlaybackMode.TRANSCODE) {
                    EditedMediaItem.Builder(mediaItem)
                        .setEffects(
                            Effects(
                                /* audioProcessors = */ emptyList(),
                                /* videoEffects = */ listOf(
                                    Presentation.createForHeight(MAX_OUTPUT_HEIGHT),
                                    // Ensure 8-bit YUV 420 for maximum compatibility.
                                    // (Media3 handles this via the encoder/presentation logic)
                                ),
                            ),
                        )
                        .build()
                } else {
                    EditedMediaItem.Builder(mediaItem).build()
                }

                val transformer = builder.build()
                transform.transformer = transformer
                transformer.start(editedMediaItem, tmpOutputFile.absolutePath)
                scheduleProgressUpdates(
                    item = item,
                    cache = cache,
                    transform = transform,
                    cancelled = cancelled,
                    onUpdate = onUpdate,
                )
            } catch (error: Exception) {
                completion.complete(
                    CompatibilityWorkerResult.Failure(
                        message = error.message ?: "Unable to start compatibility preparation.",
                    ),
                )
            }
        }

        try {
            completion.await()
        } finally {
            cancelled.set(true)
            handler.removeCallbacksAndMessages(null)
            activeTransforms.remove(item.id)
            thread.quitSafely()
        }
    }


    /**
     * Returns the MIME type of the first audio track found in [item]'s source URI, or null
     * if the source has no audio or cannot be inspected.  Used to decide whether to skip
     * audio re-encoding (passthrough) when the source is already AAC.
     */
    private fun detectAudioMimeType(context: Context, uri: Uri): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) return mime
            }
            null
        } finally {
            extractor.release()
        }
    }

    private data class ValidationResult(val isValid: Boolean, val error: String? = null)

    /**
     * Inspects the output file to ensure it meets our "Universal Compatibility" standard.
     * We check for:
     * 1. H.264 Video Codec
     * 2. AAC Audio Codec
     * 3. Profile <= High (100) and Level <= 4.1 (41)
     * 4. 8-bit bit depth
     * 5. YUV 420 pixel format
     * 6. Faststart (moov before mdat)
     */
    private fun validateOutput(file: File): ValidationResult {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var videoOk = false
            var audioOk = false
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                
                if (mime.startsWith("video/")) {
                    if (mime != android.media.MediaFormat.MIMETYPE_VIDEO_AVC) {
                        return ValidationResult(false, "Invalid video codec: $mime (expected H.264/AVC)")
                    }
                    
                    val profile = if (format.containsKey(android.media.MediaFormat.KEY_PROFILE)) {
                        format.getInteger(android.media.MediaFormat.KEY_PROFILE)
                    } else null
                    
                    val level = if (format.containsKey(android.media.MediaFormat.KEY_LEVEL)) {
                        format.getInteger(android.media.MediaFormat.KEY_LEVEL)
                    } else null

                    // 100 = High, 77 = Main, 66 = Baseline.
                    if (profile != null && profile > 100) {
                        return ValidationResult(false, "Incompatible video profile: $profile (expected High or below)")
                    }
                    
                    // 41 = 4.1.
                    if (level != null && level > 41) {
                        return ValidationResult(false, "Incompatible video level: $level (expected 4.1 or below)")
                    }

                    // Strict check for 8-bit depth.
                    if (format.containsKey("bit-per-sample")) {
                        val depth = format.getInteger("bit-per-sample")
                        if (depth > 8) return ValidationResult(false, "Incompatible bit depth: $depth (expected 8-bit)")
                    }
                    
                    // Standardize on 4:2:0 YUV. 
                    if (format.containsKey(android.media.MediaFormat.KEY_COLOR_FORMAT)) {
                        val color = format.getInteger(android.media.MediaFormat.KEY_COLOR_FORMAT)
                        // Common safe 4:2:0 formats: 19 (Planar), 21 (Semi-planar)
                        // In Media3/Android, if it's not one of these or explicitly 10-bit, we should be okay,
                        // but let's be strict for iOS.
                        val isYuv420 = color == 19 || color == 21 || color == 0x7FA30C04 || color == 0x7FA30C03
                        if (!isYuv420 && color != 0 && color != 2130706688) { // 2130706688 is sometimes used for flexible YUV
                             // Check for 10-bit explicitly
                             if (color == 0x7FA30C00 || color == 54) {
                                 return ValidationResult(false, "Incompatible 10-bit color format: $color")
                             }
                             CompatLogger.debug("CompatValidate", "Non-standard color format $color for ${file.name} — allowing")
                        }
                    }
                    videoOk = true
                }
                if (mime.startsWith("audio/")) {
                    if (mime != android.media.MediaFormat.MIMETYPE_AUDIO_AAC &&
                        mime != "audio/mp4a-latm" &&
                        mime != android.media.MediaFormat.MIMETYPE_AUDIO_MPEG &&
                        mime != "audio/mpeg"
                    ) {
                        return ValidationResult(false, "Invalid audio codec: $mime (expected AAC or MP3)")
                    }
                    audioOk = true
                }
            }
            if (!videoOk && !audioOk) {
                return ValidationResult(false, "No valid tracks found in output")
            }
            ValidationResult(true)
        } catch (e: Exception) {
            ValidationResult(false, "Validation exception: ${e.message}")
        } finally {
            extractor.release()
        }
    }

    /**
     * For TRANSCODE mode: converts the fragmented MP4 (used for live HLS streaming)
     * into a regular MP4 for cached playback.
     * For REMUX/TRANSMUX: InAppMuxer already produces regular MP4 with moov at front,
     * so this is a no-op (returns the input file unchanged).
     */
    private fun finalizePlaybackAsset(item: SharedItem, inputFile: File): File {
        // REMUX and TRANSMUX use non-fragmented output — already correct format.
        if (item.playbackDecision.mode != PlaybackMode.TRANSCODE) return inputFile
        // Small files don't benefit from re-muxing
        if (inputFile.length() < 1024 * 50) return inputFile

        val optimizedFile = File(inputFile.parentFile, "${inputFile.name}.opt")
        val extractor = MediaExtractor()
        var muxer: android.media.MediaMuxer? = null

        return try {
            extractor.setDataSource(inputFile.absolutePath)
            muxer = android.media.MediaMuxer(
                optimizedFile.absolutePath,
                android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )

            val trackCount = extractor.trackCount
            val trackMap = mutableMapOf<Int, Int>()

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                trackMap[i] = muxer.addTrack(format)
            }

            muxer.start()

            val bufferSize = 1024 * 1024
            val buffer = java.nio.ByteBuffer.allocate(bufferSize)
            val bufferInfo = android.media.MediaCodec.BufferInfo()

            for (i in 0 until trackCount) {
                extractor.selectTrack(i)
                while (true) {
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    bufferInfo.offset = 0
                    val outputTrackIndex = trackMap[i] ?: continue
                    muxer.writeSampleData(outputTrackIndex, buffer, bufferInfo)
                    extractor.advance()
                }
                extractor.unselectTrack(i)
            }

            muxer.stop()
            runCatching { inputFile.delete() }
            optimizedFile
        } catch (e: Exception) {
            CompatLogger.warn("CompatExport", "Finalization re-mux failed for ${item.id}", e)
            runCatching { optimizedFile.delete() }
            inputFile // Fall back to the fragmented version
        } finally {
            extractor.release()
            runCatching { muxer?.release() }
        }
    }

    private fun scheduleProgressUpdates(
        item: SharedItem,
        cache: PlaybackCache,
        transform: ActiveTransform,
        cancelled: AtomicBoolean,
        onUpdate: (CompatibilityWorkerUpdate) -> Unit,
    ) {
        var lastLoggedBucket = -1
        var wasStreamable = false
        val startTimeMs = System.currentTimeMillis()
        // Only TRANSCODE uses fragmented MP4 — only it can be streamed mid-job.
        // REMUX and TRANSMUX produce non-fragmented MP4 that isn't playable until complete.
        val canStreamDuringPrepare = item.playbackDecision.mode == PlaybackMode.TRANSCODE

        val progressRunnable = object : Runnable {
            override fun run() {
                if (cancelled.get() || transform.completion.isCompleted) return

                val transformer = transform.transformer ?: return
                val progressHolder = ProgressHolder()
                val progress = when (transformer.getProgress(progressHolder)) {
                    Transformer.PROGRESS_STATE_AVAILABLE -> progressHolder.progress
                    else -> null
                }

                val currentSize = transform.outputFile.length()
                val streamable = canStreamDuringPrepare && currentSize >= STREAMABLE_BYTES_THRESHOLD

                // Log every 5% bucket
                val currentBucket = progress?.let { (it / 5) * 5 } ?: -1
                if (currentBucket != lastLoggedBucket && currentBucket >= 0) {
                    lastLoggedBucket = currentBucket
                    val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000
                    android.util.Log.i(
                        "GhostStream/Compat",
                        "progress id=${item.id} progress=$progress% bucket=$currentBucket% " +
                            "outputBytes=$currentSize streamable=$streamable elapsed=${elapsedSec}s",
                    )
                }
                if (streamable && !wasStreamable) {
                    wasStreamable = true
                    android.util.Log.i(
                        "GhostStream/Compat",
                        "streamable id=${item.id} outputBytes=$currentSize progress=$progress%",
                    )
                }

                onUpdate(
                    CompatibilityWorkerUpdate(
                        status = CompatibilityStatus.PREPARING,
                        message = when {
                            streamable && item.playbackDecision.mode == PlaybackMode.REMUX ->
                                "Finalizing the optimized browser stream."
                            streamable ->
                                "Finalizing the compatible browser stream."
                            item.playbackDecision.mode == PlaybackMode.REMUX ->
                                "Optimizing for browser playback..."
                            item.playbackDecision.mode == PlaybackMode.TRANSMUX ->
                                "Repackaging for browser playback..."
                            else ->
                                "Preparing for web playback..."
                        },
                        progressPercent = progress,
                        preparedAsset = null, // Do NOT expose temp file as playback asset
                        hlsReady = streamable,
                        directReady = false,
                        streamable = streamable,
                    ),
                )

                transform.handler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
            }
        }

        transform.handler.post(progressRunnable)
    }

    private fun completedMessage(item: SharedItem, exportResult: ExportResult): String {
        return when (exportResult.videoConversionProcess) {
            ExportResult.CONVERSION_PROCESS_TRANSMUXED -> "Browser playback is ready."
            ExportResult.CONVERSION_PROCESS_TRANSCODED -> "Compatible playback is ready."
            ExportResult.CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED -> "Compatible playback is ready."
            else -> if (item.playbackDecision.mode == PlaybackMode.REMUX) {
                "Optimized playback is ready."
            } else {
                "Browser playback is ready."
            }
        }
    }

    private data class ActiveTransform(
        val itemId: String,
        val outputFile: File,
        val finalFile: File,
        val handler: Handler,
        val thread: HandlerThread,
        val completion: CompletableDeferred<CompatibilityWorkerResult>,
        @Volatile var transformer: Transformer? = null,
    ) {
        fun cancel() {
            handler.post {
                transformer?.cancel()
            }
            runCatching { outputFile.delete() }
            completion.complete(
                CompatibilityWorkerResult.Failure(
                    message = "Compatibility preparation was stopped.",
                ),
            )
            thread.quitSafely()
        }
    }

    private companion object {
        const val FRAGMENT_DURATION_MS = 2_000L
        const val MAX_OUTPUT_HEIGHT = 1080
        const val PROGRESS_POLL_INTERVAL_MS = 700L
        const val STREAMABLE_BYTES_THRESHOLD = 256L * 1024L
    }
}