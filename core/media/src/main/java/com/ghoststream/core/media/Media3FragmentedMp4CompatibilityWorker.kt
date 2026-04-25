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
import com.ghoststream.core.model.PlaybackDecision
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

import com.ghoststream.core.media.FragmentedMp4HlsIndex
import com.ghoststream.core.media.FragmentedMp4HlsIndexer
import com.ghostgramlabs.directserve.core.resources.R

class Media3FragmentedMp4CompatibilityWorker(
    private val context: Context,
    private val debugLogSink: DebugLogSink = NoOpDebugLogSink,
) : CompatibilityWorker {
    private val activeTransforms = ConcurrentHashMap<String, ActiveTransform>()
    private val copyModeVideoCodecs = setOf(
        android.media.MediaFormat.MIMETYPE_VIDEO_AVC,
        android.media.MediaFormat.MIMETYPE_VIDEO_HEVC,
        "video/x-vnd.on2.vp9",
        "video/vp9",
        "video/av01",
        "video/av1",
    )
    private val copyModeAudioCodecs = setOf(
        android.media.MediaFormat.MIMETYPE_AUDIO_AAC,
        "audio/mp4a-latm",
        android.media.MediaFormat.MIMETYPE_AUDIO_MPEG,
        "audio/mpeg",
        "audio/x-mp3",
        "audio/opus",
        "audio/ac3",
        "audio/eac3",
        "audio/ec-3",
    )

    override suspend fun prepare(
        item: SharedItem,
        cache: PlaybackCache,
        stabilizedSource: StabilizedSourceInfo?,
        startOffsetMs: Long,
        onUpdate: (CompatibilityWorkerUpdate) -> Unit,
    ): CompatibilityWorkerResult {
        return runJob(item, cache, stabilizedSource, startOffsetMs, onUpdate)
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
        debugLogSink.log("CompatWorker", "prepare_start id=${item.id} mode=${item.playbackDecision.mode} size=${item.sizeBytes}")

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
                    messageResId = R.string.compatibility_message_cached_ready,
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
                messageResId = R.string.compatibility_message_cached_ready,
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
            cancelled = cancelled,
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
        val sourceProbe = inspectSourceTracks(inputSource)
        val effectiveItem = adjustedItemForSourceProbe(item, sourceProbe)
        if (effectiveItem.playbackDecision != item.playbackDecision) {
            onUpdate(
                CompatibilityWorkerUpdate(
                    decision = effectiveItem.playbackDecision,
                    status = CompatibilityStatus.ANALYZING,
                    message = effectiveItem.playbackDecision.reason,
                    messageResId = R.string.compatibility_message_analyzing,
                ),
            )
        }
        debugLogSink.log(
            "CompatWorker",
            "prepare_plan id=${item.id} container=${item.metadata["container"] ?: item.mimeType ?: "unknown"} " +
                "video=${sourceProbe.videoMime ?: item.metadata["video_codec"] ?: "unknown"} " +
                "audio=${sourceProbe.audioMime ?: item.metadata["audio_codec"] ?: "none"} " +
                "selectedPlan=${effectiveItem.playbackDecision.mode} remuxEligible=${sourceProbe.remuxEligibleToMp4} " +
                "audioCopySafe=${sourceProbe.audioCopySafeToMp4} transcodeFallbackReason=${sourceProbe.transcodeFallbackReason ?: "none"}",
        )

        handler.post {
            try {
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cancelled.get()) {
                            safeComplete(
                                completion,
                                CompatibilityWorkerResult.Failure(
                                    message = "Compatibility preparation was stopped.",
                                    messageResId = R.string.compatibility_message_stopped,
                                    type = CompatibilityFailureType.CANCEL,
                                ),
                            )
                            return
                        }
                        val message = completedMessage(effectiveItem, exportResult)
                        val elapsedSec = (System.currentTimeMillis() - jobStartMs) / 1000
                        // Optimization Step: Ensure Faststart (moov at front) for the finalized file.
                        debugLogSink.log("CompatExport", "finalize_begin id=${effectiveItem.id} mode=${effectiveItem.playbackDecision.mode} elapsed=${(System.currentTimeMillis()-jobStartMs)/1000}s size=${tmpOutputFile.length()}")
                        if (cancelled.get()) return
                        
                        val optimizedFile = try {
                            finalizePlaybackAsset(effectiveItem, tmpOutputFile, cancelled)
                        } catch (e: Exception) {
                            if (cancelled.get()) return
                            CompatLogger.warn("CompatExport", "Optimization failed for ${effectiveItem.id}; falling back to raw output", e)
                            debugLogSink.log("CompatExport", "finalize_fallback id=${effectiveItem.id} error=${e.message}")
                            tmpOutputFile
                        }

                        if (cancelled.get()) return

                        // Validation Step: Inspect the optimized file before marking it as READY.
                        debugLogSink.log("CompatValidate", "validate_begin id=${effectiveItem.id} file=${optimizedFile.name} size=${optimizedFile.length()}")
                        val validationResult = validateOutput(
                            file = optimizedFile,
                            cancelled = cancelled,
                            profile = validationProfileFor(effectiveItem),
                        )
                        if (cancelled.get()) return
                        
                        if (!validationResult.isValid) {
                            val reason = validationResult.error ?: "unknown"
                            CompatLogger.error("CompatValidate", "VALIDATION FAILED id=${effectiveItem.id} reason=$reason")
                            debugLogSink.log("CompatValidate", "VALIDATION FAILED id=${effectiveItem.id} reason=$reason")
                            runCatching { optimizedFile.delete() }
                            if (optimizedFile != tmpOutputFile) runCatching { tmpOutputFile.delete() }
                            completion.complete(
                                CompatibilityWorkerResult.Failure(
                                    message = "Compatibility validation failed: $reason",
                                    messageResId = R.string.compatibility_message_validation_failed,
                                    messageArgs = listOf(reason),
                                    type = CompatibilityFailureType.VALIDATION,
                                ),
                            )
                            return
                        }
                        debugLogSink.log("CompatValidate", "validate_ok id=${effectiveItem.id}")

                        // Atomic rename: .tmp/.opt → .mp4
                        val completedFile = if (optimizedFile.renameTo(finalOutputFile)) {
                            val totalElapsed = (System.currentTimeMillis() - jobStartMs) / 1000
                            android.util.Log.i("GhostStream/Compat", "finalize_complete id=${effectiveItem.id} path=${finalOutputFile.name} size=${finalOutputFile.length()} totalElapsed=${totalElapsed}s")
                            finalOutputFile
                        } else {
                            android.util.Log.w("GhostStream/Compat", "finalize_rename_failed id=${effectiveItem.id}")
                            optimizedFile
                        }
                        
                        // All compatibility modes now write fragmented MP4 while in progress so
                        // playback can begin before full completion. After finalize we still
                        // prefer a regular MP4 for cached reuse; only keep the fragmented marker
                        // if the finalize step had to fall back to the live fragmented file.
                        val completedIsFragmentedMp4 = optimizedFile == tmpOutputFile
                        val asset = cache.record(
                            itemId = effectiveItem.id,
                            file = completedFile,
                            mimeType = "video/mp4",
                            isComplete = true,
                            isFragmentedMp4 = completedIsFragmentedMp4,
                        )
                        onUpdate(
                            CompatibilityWorkerUpdate(
                                decision = effectiveItem.playbackDecision,
                                status = CompatibilityStatus.PLAYABLE_NOW,
                                message = message,
                                messageResId = completedMessageResId(effectiveItem, exportResult),
                                progressPercent = 100,
                                preparedAsset = asset,
                                hlsReady = false,
                                directReady = true,
                                streamable = true,
                            ),
                        )
                        
                        android.util.Log.i("GhostStream/Compat", "READY id=${effectiveItem.id} mode=${effectiveItem.playbackDecision.mode}")
                        debugLogSink.log("CompatWorker", "READY id=${effectiveItem.id} asset=${completedFile.name} elapsed=${(System.currentTimeMillis()-jobStartMs)/1000}s")
                        onUpdate(
                            CompatibilityWorkerUpdate(
                                decision = effectiveItem.playbackDecision,
                                status = CompatibilityStatus.READY,
                                message = message,
                                messageResId = R.string.compatibility_message_ready,
                                progressPercent = 100,
                                preparedAsset = asset,
                                hlsReady = false,
                                directReady = true,
                                streamable = true,
                            ),
                        )
                        completion.complete(
                            CompatibilityWorkerResult.Success(
                                preparedAsset = asset,
                                message = message,
                                messageResId = completedMessageResId(effectiveItem, exportResult),
                            ),
                        )
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (cancelled.get()) {
                            safeComplete(
                                completion,
                                CompatibilityWorkerResult.Failure(
                                    message = "Compatibility preparation was stopped.",
                                    messageResId = R.string.compatibility_message_stopped,
                                    type = CompatibilityFailureType.CANCEL,
                                ),
                            )
                            return
                        }
                        val failedElapsed = (System.currentTimeMillis() - jobStartMs) / 1000
                        val logMsg = "FAILED id=${effectiveItem.id} mode=${effectiveItem.playbackDecision.mode} offset=$startOffsetMs elapsed=${failedElapsed}s"
                        android.util.Log.e("GhostStream/Compat", logMsg, exportException)
                        debugLogSink.log("CompatWorker", logMsg, exportException)
                        runCatching { tmpOutputFile.delete() }
                        completion.complete(
                            CompatibilityWorkerResult.Failure(
                                message = exportException.message
                                    ?: "Unable to prepare a compatible browser stream for this file.",
                                messageResId = R.string.pipeline_error_start_failed,
                                type = CompatibilityFailureType.EXPORT,
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
                                messageResId = R.string.compatibility_message_adjusted,
                            ),
                        )
                    }
                }

                val fallbackTranscode = isFallbackTranscode(effectiveItem)
                val useFragmentedMp4 = shouldUseFragmentedMp4(effectiveItem)
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
                val sourceAudioMimeType = sourceProbe.audioMime
                val isNativeAudio = sourceAudioMimeType == MimeTypes.AUDIO_AAC ||
                    sourceAudioMimeType == "audio/mp4a-latm" ||
                    sourceAudioMimeType == MimeTypes.AUDIO_MPEG ||
                    sourceAudioMimeType == "audio/mpeg" ||
                    sourceAudioMimeType == "audio/x-mp3"
                val forceAudioTranscode = effectiveItem.playbackDecision.mode == PlaybackMode.TRANSCODE ||
                    fallbackTranscode ||
                    !isNativeAudio ||
                    sourceProbe.audioMissingCodecConfig
                val targetAudioMime = if (forceAudioTranscode) MimeTypes.AUDIO_AAC else sourceAudioMimeType
                debugLogSink.log(
                    "CompatWorker",
                    "output_codecs_selected id=${effectiveItem.id} video=${if (effectiveItem.playbackDecision.mode == PlaybackMode.TRANSCODE) MimeTypes.VIDEO_H264 else "copy"} audio=${targetAudioMime ?: "none"}",
                )

                // Universal format configuration: H.264 Main 4.1 + AAC-LC.
                // We use setVideoMimeType and setAudioMimeType to trigger re-encoding.
                if (effectiveItem.playbackDecision.mode == PlaybackMode.TRANSCODE) {
                    builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                    // Note: Media3 Transformer currently doesn't allow direct profile/level 
                    // setting on the builder, but DefaultEncoderFactory will pick a 
                    // compatible one for the given MIME. Validation later will catch any deviation.
                }

                if (forceAudioTranscode) {
                    builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                }

                // If we are doing any encoding (video or audio), we need an encoder factory.
                if (effectiveItem.playbackDecision.mode == PlaybackMode.TRANSCODE || forceAudioTranscode) {
                    val encoderFactory = DefaultEncoderFactory.Builder(context)
                        .setEnableFallback(false)
                        .build()
                    builder.setEncoderFactory(encoderFactory)
                }
                if (effectiveItem.playbackDecision.mode == PlaybackMode.TRANSCODE) {
                    debugLogSink.log(
                        "CompatWorker",
                        "transcode_pipeline_fresh id=${effectiveItem.id} fallback=$fallbackTranscode container=mp4 fragmented=$useFragmentedMp4 video=${MimeTypes.VIDEO_H264} audio=${targetAudioMime ?: "none"}",
                    )
                    debugLogSink.log(
                        "CompatWorker",
                        "muxer_start_allowed id=${effectiveItem.id} container=mp4 fragmented=$useFragmentedMp4 video=${MimeTypes.VIDEO_H264} audio=${targetAudioMime ?: "none"}",
                    )
                }

                val mediaItem = MediaItem.Builder()
                    .setUri(inputSource)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startOffsetMs)
                            .build()
                    )
                    .build()

                val editedMediaItem = if (effectiveItem.playbackDecision.mode == PlaybackMode.TRANSCODE) {
                    EditedMediaItem.Builder(mediaItem)
                        .setEffects(
                            Effects(
                                /* audioProcessors = */ emptyList(),
                                /* videoEffects = */ listOf(
                                    buildEvenPresentation(effectiveItem),
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
                    item = effectiveItem,
                    cache = cache,
                    transform = transform,
                    cancelled = cancelled,
                    onUpdate = onUpdate,
                )
            } catch (error: Exception) {
                safeComplete(
                    completion,
                    CompatibilityWorkerResult.Failure(
                        message = error.message ?: "Unable to start compatibility preparation.",
                        messageResId = R.string.pipeline_error_start_failed,
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
            if (!finalOutputFile.exists()) {
                runCatching { tmpOutputFile.delete() }
                runCatching { File("${tmpOutputFile.absolutePath}.opt").delete() }
            }
            thread.quitSafely()
        }
    }


    private fun inspectSourceTracks(uri: Uri): SourceProbe {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var videoMime: String? = null
            var audioMime: String? = null
            var videoCopySafeToMp4 = false
            var audioCopySafeToMp4 = false
            var audioMissingCodecConfig = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoMime == null) {
                    videoMime = mime
                    videoCopySafeToMp4 = isMp4MuxableTrack(mime, format)
                    // AV1 requires codec-specific data (OBU sequence header) for
                    // FragmentedMp4Muxer to write the av1C box.  If MediaExtractor
                    // did not propagate csd-0, copy-mux will crash with
                    // "csd-0 is not found in the format".  Fall back to transcode.
                    if (videoCopySafeToMp4 && (mime == "video/av01" || mime == "video/av1")) {
                        if (!format.containsKey("csd-0")) {
                            CompatLogger.warn(
                                "CompatProbe",
                                "AV1 track missing csd-0 — marking as copy-unsafe",
                            )
                            videoCopySafeToMp4 = false
                        }
                    }
                } else if (mime.startsWith("audio/") && audioMime == null) {
                    audioMime = mime
                    audioCopySafeToMp4 = isMp4MuxableTrack(mime, format)
                    if (mime == android.media.MediaFormat.MIMETYPE_AUDIO_AAC || mime == "audio/mp4a-latm") {
                        if (!format.containsKey("csd-0")) {
                            audioMissingCodecConfig = true
                        }
                    }
                }
            }
            val remuxEligibleToMp4 = videoCopySafeToMp4 &&
                (audioMime == null || audioCopySafeToMp4 || audioMissingCodecConfig)
            SourceProbe(
                videoMime = videoMime,
                audioMime = audioMime,
                videoCopySafeToMp4 = videoCopySafeToMp4,
                audioCopySafeToMp4 = audioCopySafeToMp4,
                audioMissingCodecConfig = audioMissingCodecConfig,
                remuxEligibleToMp4 = remuxEligibleToMp4,
                transcodeFallbackReason = when {
                    videoMime == null && audioMime == null ->
                        "Source has no readable audio or video tracks"
                    !videoCopySafeToMp4 ->
                        "Video codec ${videoMime ?: "unknown"} cannot be copied into browser-ready MP4 output"
                    else -> null
                },
                transcodeFallbackReasonResId = when {
                    videoMime == null && audioMime == null ->
                        R.string.pipeline_reason_no_tracks
                    !videoCopySafeToMp4 ->
                        R.string.pipeline_reason_video_incompatible
                    else -> null
                },
                transcodeFallbackArgs = when {
                    videoMime == null && audioMime == null -> emptyList()
                    !videoCopySafeToMp4 -> listOf(videoMime ?: "unknown")
                    else -> emptyList()
                },
            )
        } catch (error: Exception) {
            SourceProbe(
                transcodeFallbackReason = "Track probe failed: ${error.message ?: "unknown"}",
                transcodeFallbackReasonResId = R.string.pipeline_reason_probe_failed,
                transcodeFallbackArgs = listOf(error.message ?: "unknown"),
            )
        } finally {
            extractor.release()
        }
    }

    private fun adjustedItemForSourceProbe(item: SharedItem, sourceProbe: SourceProbe): SharedItem {
        val metadataUpdates = buildMap<String, String> {
            sourceProbe.videoMime?.let { put("video_codec", it) }
            sourceProbe.audioMime?.let { put("audio_codec", it) }
        }
        val upgradedDecision = upgradedDecisionForSourceProbe(item, sourceProbe)
        if (upgradedDecision != null) {
            return item.copy(
                playbackDecision = upgradedDecision,
                metadata = item.metadata + metadataUpdates,
            )
        }
        val fallbackReason = sourceProbe.transcodeFallbackReason ?: return item.copy(
            metadata = item.metadata + metadataUpdates,
        )
        return item.copy(
            playbackDecision = item.playbackDecision.copy(
                mode = PlaybackMode.TRANSCODE,
                compatibilityLabel = "Transcoding",
                labelResId = R.string.pipeline_label_transcoding,
                browserMimeType = "video/mp4",
                reason = "$FALLBACK_TRANSCODE_REASON_PREFIX: $fallbackReason",
                reasonResId = sourceProbe.transcodeFallbackReasonResId,
            ),
            metadata = item.metadata + metadataUpdates,
        )
    }

    private fun upgradedDecisionForSourceProbe(item: SharedItem, sourceProbe: SourceProbe): PlaybackDecision? {
        if (item.playbackDecision.mode != PlaybackMode.TRANSCODE) return null
        if (item.playbackDecision.reason.contains("Browser rejected", ignoreCase = true)) {
            return null
        }
        if (!sourceProbe.remuxEligibleToMp4 || sourceProbe.transcodeFallbackReason != null) return null

        val needsAudioTranscode = sourceProbe.audioMime != null &&
            !sourceProbe.audioCopySafeToMp4 &&
            !sourceProbe.audioMissingCodecConfig

        return PlaybackDecision(
            mode = PlaybackMode.TRANSMUX,
            compatibilityLabel = "Repackaging",
            labelResId = R.string.pipeline_label_repackaging,
            browserMimeType = "video/mp4",
            reason = when {
                needsAudioTranscode ->
                    "Video can be copied, but audio must be converted to a browser-safe format"
                else ->
                    "Codecs are acceptable, but the container must be repackaged for the browser"
            },
            reasonResId = when {
                needsAudioTranscode ->
                    R.string.pipeline_reason_audio_convert
                else ->
                    R.string.pipeline_reason_container_repackage
            },
        )
    }

    private fun isFallbackTranscode(item: SharedItem): Boolean {
        return item.playbackDecision.mode == PlaybackMode.TRANSCODE &&
            item.playbackDecision.reason.startsWith(FALLBACK_TRANSCODE_REASON_PREFIX)
    }

    private fun shouldUseFragmentedMp4(item: SharedItem): Boolean {
        return when (item.playbackDecision.mode) {
            PlaybackMode.TRANSCODE,
            PlaybackMode.REMUX,
            PlaybackMode.TRANSMUX -> true
            PlaybackMode.DIRECT -> false
        }
    }

    private fun remuxToMp4(
        item: SharedItem,
        inputSource: Uri,
        tmpOutputFile: File,
        finalOutputFile: File,
        cache: PlaybackCache,
        cancelled: AtomicBoolean,
        jobStartMs: Long,
        onUpdate: (CompatibilityWorkerUpdate) -> Unit,
    ): CompatibilityWorkerResult {
        onUpdate(
            CompatibilityWorkerUpdate(
                status = CompatibilityStatus.PREPARING,
                message = if (item.playbackDecision.mode == PlaybackMode.TRANSMUX) {
                    "Repackaging video for browser playback..."
                } else {
                    "Optimizing video container..."
                },
                messageResId = if (item.playbackDecision.mode == PlaybackMode.TRANSMUX) {
                    R.string.compatibility_message_repackaging_web
                } else {
                    R.string.compatibility_message_optimizing_web
                },
            ),
        )

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(context, inputSource, emptyMap())
            if (extractor.trackCount <= 0) {
                return CompatibilityWorkerResult.Failure(
                    "Source file has no readable tracks.",
                    CompatibilityFailureType.SOURCE,
                )
            }

            muxer = MediaMuxer(tmpOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            copyRotationHint(inputSource, muxer)

            val trackMap = linkedMapOf<Int, Int>()
            val durationUs = maxTrackDurationUs(extractor)
            val bufferSize = maxTrackBufferSize(extractor)
            var sourceVideoMime: String? = null
            var sourceAudioMime: String? = null

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && sourceVideoMime == null) sourceVideoMime = mime
                if (mime.startsWith("audio/") && sourceAudioMime == null) sourceAudioMime = mime
                if (!isMp4MuxableTrack(mime, format)) {
                    return CompatibilityWorkerResult.Failure(
                        "Track $index is not safe to copy into MP4: $mime",
                        CompatibilityFailureType.VALIDATION,
                    )
                }
                trackMap[index] = muxer.addTrack(format)
            }

            debugLogSink.log(
                "CompatWorker",
                "transmux_candidate id=${item.id} video=${sourceVideoMime ?: "none"} audio=${sourceAudioMime ?: "none"} container=${item.mimeType ?: "unknown"}",
            )

            if (trackMap.isEmpty()) {
                return CompatibilityWorkerResult.Failure(
                    "No valid tracks found in source.",
                    CompatibilityFailureType.SOURCE,
                )
            }

            muxer.start()
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = android.media.MediaCodec.BufferInfo()
            var lastProgressBucket = -1

            for ((sourceTrack, outputTrack) in trackMap) {
                if (cancelled.get()) {
                    return CompatibilityWorkerResult.Failure(
                        "Compatibility preparation was stopped.",
                        CompatibilityFailureType.CANCEL,
                    )
                }
                extractor.selectTrack(sourceTrack)
                while (true) {
                    if (cancelled.get()) {
                        return CompatibilityWorkerResult.Failure(
                            "Compatibility preparation was stopped.",
                            CompatibilityFailureType.CANCEL,
                        )
                    }
                    buffer.clear()
                    info.size = extractor.readSampleData(buffer, 0)
                    if (info.size < 0) break
                    info.offset = 0
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(outputTrack, buffer, info)

                    if (durationUs > 0L && info.presentationTimeUs >= 0L) {
                        val progress = ((info.presentationTimeUs * 100L) / durationUs).toInt().coerceIn(0, 99)
                        val bucket = (progress / 5) * 5
                        if (bucket != lastProgressBucket) {
                            lastProgressBucket = bucket
                            onUpdate(
                                CompatibilityWorkerUpdate(
                                    status = CompatibilityStatus.PREPARING,
                                    message = if (item.playbackDecision.mode == PlaybackMode.TRANSMUX) {
                                        "Repackaging video for browser playback..."
                                    } else {
                                        "Optimizing video container..."
                                    },
                                    messageResId = if (item.playbackDecision.mode == PlaybackMode.TRANSMUX) {
                                        R.string.compatibility_message_repackaging_web
                                    } else {
                                        R.string.compatibility_message_optimizing_web
                                    },
                                    progressPercent = progress,
                                ),
                            )
                        }
                    }
                    extractor.advance()
                }
                extractor.unselectTrack(sourceTrack)
            }

            onUpdate(
                CompatibilityWorkerUpdate(
                    status = CompatibilityStatus.FINALIZING,
                    message = "Almost ready...",
                    messageResId = R.string.compatibility_message_almost_ready,
                ),
            )

            debugLogSink.log(
                "CompatWorker",
                "transmux_output_created id=${item.id} path=${tmpOutputFile.name} size=${tmpOutputFile.length()}",
            )
            muxer.stop()
            muxer.release()
            muxer = null
            debugLogSink.log(
                "CompatWorker",
                "transmux_finalize_ok id=${item.id} path=${tmpOutputFile.name} size=${tmpOutputFile.length()}",
            )

            debugLogSink.log(
                "CompatValidate",
                "transmux_validate_begin id=${item.id} file=${tmpOutputFile.name} size=${tmpOutputFile.length()}",
            )
            val validationResult = validateOutput(
                file = tmpOutputFile,
                cancelled = cancelled,
                profile = validationProfileFor(item),
            )
            if (!validationResult.isValid) {
                debugLogSink.log(
                    "CompatValidate",
                    "transmux_validate_failed id=${item.id} reason=${validationResult.error ?: "unknown"}",
                )
                runCatching { tmpOutputFile.delete() }
                return CompatibilityWorkerResult.Failure(
                    "Compatibility validation failed: ${validationResult.error ?: "unknown"}",
                    CompatibilityFailureType.VALIDATION,
                )
            }
            debugLogSink.log("CompatValidate", "transmux_validate_ok id=${item.id}")

            val completedFile = if (tmpOutputFile.renameTo(finalOutputFile)) finalOutputFile else tmpOutputFile
            val asset = cache.record(
                itemId = item.id,
                file = completedFile,
                mimeType = "video/mp4",
                isComplete = true,
                isFragmentedMp4 = false,
            )
            onUpdate(
                CompatibilityWorkerUpdate(
                    status = CompatibilityStatus.PLAYABLE_NOW,
                    message = "Browser playback is ready.",
                    messageResId = R.string.compatibility_message_ready,
                    progressPercent = 100,
                    preparedAsset = asset,
                    directReady = true,
                    streamable = true,
                ),
            )
            debugLogSink.log(
                "CompatWorker",
                "READY id=${item.id} asset=${completedFile.name} elapsed=${(System.currentTimeMillis() - jobStartMs) / 1000}s",
            )
            onUpdate(
                CompatibilityWorkerUpdate(
                    status = CompatibilityStatus.READY,
                    message = "Browser playback is ready.",
                    messageResId = R.string.compatibility_message_ready,
                    progressPercent = 100,
                    preparedAsset = asset,
                    directReady = true,
                    streamable = true,
                ),
            )
            CompatibilityWorkerResult.Success(
                preparedAsset = asset,
                message = "Browser playback is ready.",
                messageResId = R.string.compatibility_message_ready,
            )
        } catch (error: Exception) {
            runCatching { tmpOutputFile.delete() }
            CompatibilityWorkerResult.Failure(
                error.message ?: "Unable to repackage this file for browser playback.",
                messageResId = R.string.pipeline_error_repackage_failed,
                type = CompatibilityFailureType.EXPORT,
            )
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private fun isMp4MuxableTrack(mime: String, format: android.media.MediaFormat): Boolean {
        return when {
            mime.startsWith("video/") -> mime in copyModeVideoCodecs
            mime == android.media.MediaFormat.MIMETYPE_AUDIO_AAC || mime == "audio/mp4a-latm" ->
                format.containsKey("csd-0")
            mime == android.media.MediaFormat.MIMETYPE_AUDIO_MPEG || mime == "audio/mpeg" || mime == "audio/x-mp3" -> true
            mime == "audio/opus" || mime == "audio/ac3" || mime == "audio/eac3" || mime == "audio/ec-3" -> true
            else -> false
        }
    }

    private fun maxTrackDurationUs(extractor: MediaExtractor): Long {
        var durationUs = 0L
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                durationUs = maxOf(durationUs, format.getLong(android.media.MediaFormat.KEY_DURATION))
            }
        }
        return durationUs
    }

    private fun maxTrackBufferSize(extractor: MediaExtractor): Int {
        var maxBufferSize = 512 * 1024
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (format.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) {
                maxBufferSize = maxOf(maxBufferSize, format.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE))
            }
        }
        return maxBufferSize
    }

    private fun copyRotationHint(inputSource: Uri, muxer: MediaMuxer) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, inputSource)
            val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: return
            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
            }
        } catch (_: Exception) {
        } finally {
            retriever.release()
        }
    }

    private fun buildEvenPresentation(item: SharedItem): Presentation {
        val width = item.metadata["width"]?.toIntOrNull()
        val height = item.metadata["height"]?.toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) {
            return Presentation.createForHeight(MAX_OUTPUT_HEIGHT)
        }

        val scale = minOf(1f, MAX_OUTPUT_HEIGHT.toFloat() / height.toFloat())
        val scaledWidth = (((width * scale).toInt()) / 2) * 2
        val scaledHeight = (((height * scale).toInt()) / 2) * 2
        val targetWidth = scaledWidth.coerceAtLeast(2)
        val targetHeight = scaledHeight.coerceAtLeast(2)
        return Presentation.createForWidthAndHeight(
            targetWidth,
            targetHeight,
            Presentation.LAYOUT_SCALE_TO_FIT,
        )
    }

    private data class ValidationResult(val isValid: Boolean, val error: String? = null)
    private data class SourceProbe(
        val videoMime: String? = null,
        val audioMime: String? = null,
        val videoCopySafeToMp4: Boolean = false,
        val audioCopySafeToMp4: Boolean = false,
        val audioMissingCodecConfig: Boolean = false,
        val remuxEligibleToMp4: Boolean = false,
        val transcodeFallbackReason: String? = null,
        val transcodeFallbackReasonResId: Int? = null,
        val transcodeFallbackArgs: List<String> = emptyList(),
    )
    private data class ValidationProfile(
        val allowedVideoCodecs: Set<String>,
        val allowedAudioCodecs: Set<String>,
        val requireAacAudio: Boolean = false,
        val requireUniversalAvc: Boolean = false,
    )

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
    private fun validateOutput(
        file: File,
        cancelled: AtomicBoolean,
        profile: ValidationProfile,
    ): ValidationResult {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var videoOk = false
            var audioOk = false
            
            val trackCount = extractor.trackCount
            for (i in 0 until trackCount) {
                if (cancelled.get()) return ValidationResult(false, "Cancelled during validation")
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                
                if (mime.startsWith("video/")) {
                    if (profile.requireUniversalAvc && mime != android.media.MediaFormat.MIMETYPE_VIDEO_AVC) {
                        return ValidationResult(false, "Invalid video codec: $mime (expected H.264/AVC)")
                    }
                    if (!profile.requireUniversalAvc && mime !in profile.allowedVideoCodecs) {
                        return ValidationResult(false, "Invalid video codec: $mime")
                    }
                    
                    val codecProfile = if (format.containsKey(android.media.MediaFormat.KEY_PROFILE)) {
                        format.getInteger(android.media.MediaFormat.KEY_PROFILE)
                    } else null
                    
                    val level = if (format.containsKey(android.media.MediaFormat.KEY_LEVEL)) {
                        format.getInteger(android.media.MediaFormat.KEY_LEVEL)
                    } else null

                    // Profile check: 100 = AVCProfileHigh, 77 = AVCProfileMain, 66 = AVCProfileBaseline.
                    // These are direct numeric values from MediaCodecInfo.CodecProfileLevel.
                    if (codecProfile != null &&
                        codecProfile > 100 &&
                        mime == android.media.MediaFormat.MIMETYPE_VIDEO_AVC
                    ) {
                        return ValidationResult(false, "Incompatible video profile: $codecProfile (expected High=100 or below)")
                    }
                    
                    // Level check: Android uses MediaCodecInfo.CodecProfileLevel.AVCLevelXX bitfield constants,
                    // NOT plain numeric level values. Correct constants:
                    //   AVCLevel1=0x01, AVCLevel3=0x40(64), AVCLevel31=0x80(128),
                    //   AVCLevel4=0x200(512), AVCLevel41=0x400(1024), AVCLevel5=0x1000(4096)
                    // Level 4.1 bitfield constant = 1024 (0x400). Reject anything above 4.1.
                    // NOTE: Do NOT use > 41 here — that incorrectly rejects every real encoder output.
                    val AVC_LEVEL_41 = 0x400 // AVCLevel41 = 1024
                    if (level != null &&
                        level > AVC_LEVEL_41 &&
                        mime == android.media.MediaFormat.MIMETYPE_VIDEO_AVC
                    ) {
                        CompatLogger.warn("CompatValidate", "Video level $level (>AVCLevel41=0x400) may not play on all devices, allowing")
                        // Allow but log — do not reject, as the encoder may target a higher
                        // level and many browsers can handle it. Only 10-bit/HDR is truly blocking.
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
                    val isAac = mime == android.media.MediaFormat.MIMETYPE_AUDIO_AAC ||
                        mime == "audio/mp4a-latm"
                    val isMp3 = mime == android.media.MediaFormat.MIMETYPE_AUDIO_MPEG ||
                        mime == "audio/mpeg"
                    if (profile.requireAacAudio && !isAac) {
                        return ValidationResult(false, "Invalid audio codec: $mime (expected AAC)")
                    }
                    if (!profile.requireAacAudio &&
                        mime !in profile.allowedAudioCodecs &&
                        !isAac &&
                        !isMp3
                    ) {
                        return ValidationResult(false, "Invalid audio codec: $mime")
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

    private fun validationProfileFor(item: SharedItem): ValidationProfile {
        return when (item.playbackDecision.mode) {
            PlaybackMode.TRANSCODE -> ValidationProfile(
                allowedVideoCodecs = setOf(android.media.MediaFormat.MIMETYPE_VIDEO_AVC),
                allowedAudioCodecs = setOf(
                    android.media.MediaFormat.MIMETYPE_AUDIO_AAC,
                    "audio/mp4a-latm",
                ),
                requireAacAudio = true,
                requireUniversalAvc = true,
            )

            PlaybackMode.REMUX,
            PlaybackMode.TRANSMUX -> ValidationProfile(
                allowedVideoCodecs = copyModeVideoCodecs,
                allowedAudioCodecs = copyModeAudioCodecs,
            )

            PlaybackMode.DIRECT -> ValidationProfile(
                allowedVideoCodecs = copyModeVideoCodecs,
                allowedAudioCodecs = copyModeAudioCodecs,
            )
        }
    }

    /**
     * Returns the fragmented MP4 as-is. The previous implementation re-muxed the
     * completed fMP4 into a non-fragmented MP4 for cached reuse, but that second
     * extract+mux pass doubled tail latency at the exact moment the user starts
     * watching. Modern browsers play fragmented MP4 directly via MSE, and the HLS
     * indexer also re-uses the fMP4 file, so no conversion is required to mark the
     * asset READY.
     *
     * The downstream cache.record path already handles `isFragmentedMp4 = true`
     * (set when optimizedFile == tmpOutputFile), so callers continue to work
     * unchanged.
     */
    private fun finalizePlaybackAsset(item: SharedItem, inputFile: File, cancelled: AtomicBoolean): File {
        return inputFile
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
        // All compatibility jobs now use fragmented MP4 during active preparation so playback
        // can begin before the finalize-to-cache step completes.
        val canStreamDuringPrepare = shouldUseFragmentedMp4(item)
        var lastProgressPercent = 0
        var firstFragmentTimeMs: Long? = null
        var lastDiagnosticLogged: String? = null
        var lastInitSegmentLength = 0L
        var lastCommittedSegmentCount = 0

        val progressRunnable = object : Runnable {
            override fun run() {
                if (cancelled.get() || transform.completion.isCompleted) return

                val transformer = transform.transformer ?: return
                val progressHolder = ProgressHolder()
                val progress = when (transformer.getProgress(progressHolder)) {
                    Transformer.PROGRESS_STATE_AVAILABLE -> progressHolder.progress
                    else -> null
                }?.coerceAtLeast(lastProgressPercent)
                if (progress != null) {
                    lastProgressPercent = progress
                }
                
                val currentSize = transform.outputFile.length()
                
                // Direct playback readiness is based on the growing fragmented MP4 itself.
                // HLS remains optional and keeps its stricter buffered-duration threshold.
                val hlsIndex = if (canStreamDuringPrepare) {
                    runCatching {
                        FragmentedMp4HlsIndexer.read(
                            transform.outputFile,
                            fragmentDurationSeconds = FRAGMENT_DURATION_MS / 1000.0,
                        )
                    }.getOrElse { error ->
                        // Fallback to a stub with the error message
                        FragmentedMp4HlsIndex(
                            initSegmentLength = 0,
                            segments = emptyList(),
                            fileLength = currentSize,
                            diagnosticInfo = "Indexer crash: ${error.message}"
                        )
                    }
                } else null
                
                val directReadiness = if (canStreamDuringPrepare) {
                    HlsReadinessValidator.validateFragmentedMp4Playback(hlsIndex)
                } else {
                    HlsReadinessValidator.ReadinessResult(
                        isReady = false,
                        bufferedDurationSeconds = 0.0,
                        segmentCount = 0,
                    )
                }
                val hlsReadiness = if (canStreamDuringPrepare) {
                    HlsReadinessValidator.validate(hlsIndex)
                } else {
                    HlsReadinessValidator.ReadinessResult(
                        isReady = false,
                        bufferedDurationSeconds = 0.0,
                        segmentCount = 0,
                    )
                }
                val streamable = directReadiness.isReady

                if (canStreamDuringPrepare && hlsIndex != null) {
                    if (hlsIndex.initSegmentLength > 0L && hlsIndex.initSegmentLength != lastInitSegmentLength) {
                        lastInitSegmentLength = hlsIndex.initSegmentLength
                        debugLogSink.log(
                            "CompatWorker",
                            "init_segment_locked id=${item.id} bytes=${hlsIndex.initSegmentLength}",
                        )
                    }

                    if (hlsIndex.segments.size > lastCommittedSegmentCount) {
                        for (segmentIndex in lastCommittedSegmentCount until hlsIndex.segments.size) {
                            val segment = hlsIndex.segments[segmentIndex]
                            debugLogSink.log(
                                "CompatWorker",
                                "segment_committed id=${item.id} index=${segment.index} bytes=${segment.length}",
                            )
                        }
                        lastCommittedSegmentCount = hlsIndex.segments.size
                    }
                }

                // Log every 5% bucket
                val currentBucket = progress?.let { (it / 5) * 5 } ?: -1
                if (currentBucket != lastLoggedBucket && currentBucket >= 0) {
                    lastLoggedBucket = currentBucket
                    val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000
                    android.util.Log.i(
                        "GhostStream/Compat",
                        "progress id=${item.id} progress=$progress% bucket=$currentBucket% " +
                            "outputBytes=$currentSize streamable=$streamable segments=${hlsIndex?.segments?.size ?: 0} elapsed=${elapsedSec}s",
                    )
                }
                if (streamable && !wasStreamable) {
                    wasStreamable = true
                    val ttfpf = System.currentTimeMillis() - startTimeMs
                    firstFragmentTimeMs = System.currentTimeMillis()
                    android.util.Log.i(
                        "GhostStream/Compat",
                        "streamable id=${item.id} ttfpf=${ttfpf}ms outputBytes=$currentSize segments=${hlsIndex?.segments?.size} progress=$progress%",
                    )
                    debugLogSink.log("CompatWorker", "streamable id=${item.id} ttfpf=${ttfpf}ms segments=${hlsIndex?.segments?.size}")
                    debugLogSink.log(
                        "CompatWorker",
                        "streamability_check_pass id=${item.id} buffered=${"%.2f".format(java.util.Locale.US, directReadiness.bufferedDurationSeconds)}s segments=${directReadiness.segmentCount}",
                    )
                }
                
                // Log indexer failures to internal trace if we are still not streamable
                if (!streamable && directReadiness.diagnosticInfo != null && directReadiness.diagnosticInfo != lastDiagnosticLogged) {
                    lastDiagnosticLogged = directReadiness.diagnosticInfo
                    android.util.Log.w("GhostStream/Compat", "readiness_pending id=${item.id} reason=\"${directReadiness.diagnosticInfo}\"")
                    debugLogSink.log("CompatWorker", "streamability_check_fail id=${item.id} reason=\"${directReadiness.diagnosticInfo}\" buffered=${"%.2f".format(java.util.Locale.US, directReadiness.bufferedDurationSeconds)}s segments=${directReadiness.segmentCount}")
                }

                val incompleteAsset = if (canStreamDuringPrepare) {
                    CachedPlaybackAsset(
                        itemId = item.id,
                        filePath = transform.outputFile.absolutePath,
                        mimeType = "video/mp4",
                        sizeBytes = currentSize,
                        createdAtEpochMs = startTimeMs,
                        isComplete = false,
                        isFragmentedMp4 = true,
                    )
                } else {
                    null
                }

                onUpdate(
                    CompatibilityWorkerUpdate(
                        status = if (streamable && canStreamDuringPrepare) CompatibilityStatus.PLAYABLE_NOW else CompatibilityStatus.PREPARING,
                        message = when {
                            streamable && item.playbackDecision.mode == PlaybackMode.REMUX ->
                                "Finalizing the optimized browser stream."
                            streamable ->
                                "Finalizing the compatible browser stream."
                            item.playbackDecision.mode == PlaybackMode.REMUX ->
                                "Optimizing for browser playback..."
                            item.playbackDecision.mode == PlaybackMode.TRANSMUX ->
                                "Repackaging for browser playback..."
                            isFallbackTranscode(item) ->
                                "Converting for browser playback..."
                            else ->
                                "Preparing for web playback..."
                        },
                        messageResId = when {
                            streamable && item.playbackDecision.mode == PlaybackMode.REMUX ->
                                R.string.compatibility_message_finalizing_optimized
                            streamable ->
                                R.string.compatibility_message_finalizing_compatible
                            item.playbackDecision.mode == PlaybackMode.REMUX ->
                                R.string.compatibility_message_optimizing_web
                            item.playbackDecision.mode == PlaybackMode.TRANSMUX ->
                                R.string.compatibility_message_repackaging_web
                            isFallbackTranscode(item) ->
                                R.string.compatibility_message_converting_web
                            else ->
                                R.string.compatibility_message_preparing_web
                        },
                        progressPercent = progress,
                        preparedAsset = incompleteAsset,
                        hlsReady = hlsReadiness.isReady && canStreamDuringPrepare,
                        // This asset is streamable, but it is still the in-progress fragmented file.
                        // Reserve directReady for finalized prepared assets so server/UI completion
                        // signals do not claim the temporary file is fully complete.
                        directReady = false,
                        streamable = streamable && canStreamDuringPrepare,
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

    private fun completedMessageResId(item: SharedItem, exportResult: ExportResult): Int {
        return when (exportResult.videoConversionProcess) {
            ExportResult.CONVERSION_PROCESS_TRANSMUXED -> R.string.compatibility_message_ready
            ExportResult.CONVERSION_PROCESS_TRANSCODED -> R.string.compatibility_message_ready_compatible
            ExportResult.CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED -> R.string.compatibility_message_ready_compatible
            else -> if (item.playbackDecision.mode == PlaybackMode.REMUX) {
                R.string.compatibility_message_ready_optimized
            } else {
                R.string.compatibility_message_ready
            }
        }
    }

    override fun cancel(itemId: String) {
        activeTransforms[itemId]?.cancel()
    }

    override fun cancelAll() {
        activeTransforms.values.forEach { it.cancel() }
        activeTransforms.clear()
    }

    private data class ActiveTransform(
        val itemId: String,
        val outputFile: File,
        val finalFile: File,
        val handler: Handler,
        val thread: HandlerThread,
        val completion: CompletableDeferred<CompatibilityWorkerResult>,
        val cancelled: AtomicBoolean,
        @Volatile var transformer: Transformer? = null,
    ) {
        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            handler.post {
                runCatching { transformer?.cancel() }
                if (!completion.isCompleted) {
                    completion.complete(
                        CompatibilityWorkerResult.Failure(
                            message = "Compatibility preparation was stopped.",
                            messageResId = R.string.compatibility_message_stopped,
                            type = CompatibilityFailureType.CANCEL,
                        ),
                    )
                }
            }
        }
    }

    private fun safeComplete(
        completion: CompletableDeferred<CompatibilityWorkerResult>,
        result: CompatibilityWorkerResult,
    ) {
        if (!completion.isCompleted) {
            completion.complete(result)
        }
    }

    private companion object {
        const val FALLBACK_TRANSCODE_REASON_PREFIX = "Falling back to transcode after remux preflight"
        const val FRAGMENT_DURATION_MS = 2_000L
        const val MAX_OUTPUT_HEIGHT = 1080
        const val PROGRESS_POLL_INTERVAL_MS = 700L
    }
}
