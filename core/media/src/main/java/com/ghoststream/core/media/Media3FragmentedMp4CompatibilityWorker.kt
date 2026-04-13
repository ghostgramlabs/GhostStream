package com.ghoststream.core.media

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
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

@OptIn(UnstableApi::class)
class Media3FragmentedMp4CompatibilityWorker(
    private val context: Context,
) : CompatibilityWorker {
    private val activeTransforms = ConcurrentHashMap<String, ActiveTransform>()

    override suspend fun prepare(
        item: SharedItem,
        cache: PlaybackCache,
        onUpdate: (CompatibilityWorkerUpdate) -> Unit,
    ): CompatibilityWorkerResult = withContext(Dispatchers.IO) {
        if (item.playbackDecision.mode == PlaybackMode.DIRECT) {
            return@withContext CompatibilityWorkerResult.Failure(item.playbackDecision.reason)
        }

        val outputFile = cache.newOutputFile(item, "mp4")
        runCatching { outputFile.parentFile?.mkdirs() }
        if (outputFile.exists()) {
            runCatching { outputFile.delete() }
        }

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
            outputFile = outputFile,
            handler = handler,
            thread = thread,
            completion = completion,
        )
        activeTransforms[item.id] = transform

        handler.post {
            try {
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        val message = completedMessage(item, exportResult)
                        val asset = cache.record(
                            itemId = item.id,
                            file = outputFile,
                            mimeType = "video/mp4",
                            isComplete = true,
                            isFragmentedMp4 = true,
                        )
                        onUpdate(
                            CompatibilityWorkerUpdate(
                                status = CompatibilityStatus.READY,
                                message = message,
                                progressPercent = 100,
                                preparedAsset = asset,
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
                        runCatching { outputFile.delete() }
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

                val builder = Transformer.Builder(context)
                    .setLooper(thread.looper)
                    .setMuxerFactory(
                        InAppMuxer.Factory.Builder()
                            .setOutputFragmentedMp4(true)
                            .setFragmentDurationMs(FRAGMENT_DURATION_MS)
                            .build(),
                    )
                    .addListener(listener)

                // Detect whether the source audio track is already AAC so we can skip
                // audio re-encoding (passthrough/transmux).  Re-encoding AAC→AAC wastes
                // CPU and slightly degrades quality with no benefit to the browser.
                val sourceAudioMimeType = runCatching { detectAudioMimeType(item) }.getOrNull()
                val sourceAudioIsAac = sourceAudioMimeType == MimeTypes.AUDIO_AAC ||
                    sourceAudioMimeType == "audio/mp4a-latm"

                if (item.playbackDecision.mode == PlaybackMode.TRANSCODE) {
                    // Always encode video to H.264 for browser MSE compatibility.
                    builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                    // Only re-encode audio if the source isn't already AAC-LC.
                    // Transmuxing existing AAC is free; re-encoding adds ~10-20% overhead.
                    if (!sourceAudioIsAac) {
                        builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                    }
                    val encoderFactory = runCatching {
                        DefaultEncoderFactory.Builder(context)
                            .setEnableFallback(true)
                            .build()
                    }.getOrNull()
                    if (encoderFactory != null) {
                        builder.setEncoderFactory(encoderFactory)
                    }
                }

                // Build the EditedMediaItem. For TRANSCODE we add a Presentation effect to
                // cap output resolution at 1080p — transcoding 4K is ~4× slower than 1080p
                // with no benefit since browsers render at display resolution anyway.
                // For REMUX we add no effects so Media3 can fast-path transmux the video.
                val mediaItem = MediaItem.fromUri(Uri.parse(item.uri))
                val editedMediaItem = if (item.playbackDecision.mode == PlaybackMode.TRANSCODE) {
                    EditedMediaItem.Builder(mediaItem)
                        .setEffects(
                            Effects(
                                /* audioProcessors = */ emptyList(),
                                /* videoEffects = */ listOf(
                                    Presentation.createForHeight(MAX_OUTPUT_HEIGHT),
                                ),
                            ),
                        )
                        .build()
                } else {
                    EditedMediaItem.Builder(mediaItem).build()
                }

                val transformer = builder.build()
                transform.transformer = transformer
                transformer.start(editedMediaItem, outputFile.absolutePath)
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
    private fun detectAudioMimeType(item: SharedItem): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, Uri.parse(item.uri), null)
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

    override fun cancel(itemId: String) {
        activeTransforms.remove(itemId)?.cancel()
    }

    override fun cancelAll() {
        activeTransforms.values.toList().forEach { it.cancel() }
        activeTransforms.clear()
    }

    private fun scheduleProgressUpdates(
        item: SharedItem,
        cache: PlaybackCache,
        transform: ActiveTransform,
        cancelled: AtomicBoolean,
        onUpdate: (CompatibilityWorkerUpdate) -> Unit,
    ) {
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
                val streamable = currentSize >= STREAMABLE_BYTES_THRESHOLD
                val asset = if (currentSize > 0L) {
                    cache.record(
                        itemId = item.id,
                        file = transform.outputFile,
                        mimeType = "video/mp4",
                        isComplete = false,
                        isFragmentedMp4 = true,
                    )
                } else {
                    null
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
                                "Preparing fragmented playback for fast browser start..."

                            else ->
                                "Creating a fragmented compatibility stream..."
                        },
                        progressPercent = progress,
                        preparedAsset = asset,
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
            ExportResult.CONVERSION_PROCESS_TRANSMUXED -> "Fragmented playback is ready."
            ExportResult.CONVERSION_PROCESS_TRANSCODED -> "Compatibility playback is ready."
            ExportResult.CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED -> "Compatibility playback is ready."
            else -> if (item.playbackDecision.mode == PlaybackMode.REMUX) {
                "Fragmented playback is ready."
            } else {
                "Compatibility playback is ready."
            }
        }
    }

    private data class ActiveTransform(
        val itemId: String,
        val outputFile: File,
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
        // Cap output resolution at 1080p for TRANSCODE mode.  Higher resolutions have no
        // benefit in a browser and multiply encode time (4K → ~4× longer than 1080p).
        // Media3's Presentation effect is a no-op when the source is already ≤ 1080p.
        const val MAX_OUTPUT_HEIGHT = 1080
        const val PROGRESS_POLL_INTERVAL_MS = 700L
        // 256 KB is enough to have the init segment plus at least one moof+mdat fragment
        // for typical video bitrates. The previous 4 MB threshold caused a 16+ second
        // startup delay because the worker wouldn't mark the job as streamable until 4 MB
        // had been written — by which point most short/medium videos were nearly done.
        // The server's compatibilityStreamReady() now relies on HLS segment counting
        // rather than this byte threshold for video, so this constant is primarily used
        // by audio/non-fMP4 paths via CompatibilityJob.canServePlayback.
        const val STREAMABLE_BYTES_THRESHOLD = 256L * 1024L
    }
}
