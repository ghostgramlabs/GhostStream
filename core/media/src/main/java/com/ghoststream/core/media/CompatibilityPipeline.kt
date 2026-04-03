package com.ghoststream.core.media

import com.ghoststream.core.model.PlaybackMode
import com.ghoststream.core.model.SharedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface CompatibilityPipeline {
    val jobs: StateFlow<Map<String, CompatibilityJob>>

    suspend fun inspect(item: SharedItem): CompatibilityJob
    suspend fun requestPreparation(item: SharedItem): CompatibilityJob
    suspend fun resolvePlayback(item: SharedItem): PlaybackResolution
    fun currentJob(itemId: String): CompatibilityJob?
    suspend fun clearTemporaryOutputs()
}

class QueuedCompatibilityPipeline(
    private val cache: PlaybackCache,
    private val worker: CompatibilityWorker,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : CompatibilityPipeline {
    private val processingMutex = Mutex()
    private val _jobs = MutableStateFlow<Map<String, CompatibilityJob>>(emptyMap())

    override val jobs: StateFlow<Map<String, CompatibilityJob>> = _jobs.asStateFlow()

    override suspend fun inspect(item: SharedItem): CompatibilityJob {
        val existing = currentJob(item.id)
        if (existing != null) return existing

        val cachedAsset = cache.lookup(item.id)
        val job = when (item.playbackDecision.mode) {
            PlaybackMode.DIRECT -> CompatibilityJob(
                itemId = item.id,
                decision = item.playbackDecision,
                status = CompatibilityStatus.READY,
                message = item.playbackDecision.reason,
                streamable = true,
            )

            PlaybackMode.REMUX -> CompatibilityJob(
                itemId = item.id,
                decision = item.playbackDecision,
                status = if (cachedAsset != null) CompatibilityStatus.READY else CompatibilityStatus.IDLE,
                message = if (cachedAsset != null) {
                    "Optimized playback is ready."
                } else {
                    "Will optimize this container for browser playback when requested."
                },
                preparedAsset = cachedAsset,
                streamable = cachedAsset != null,
            )

            PlaybackMode.TRANSCODE -> CompatibilityJob(
                itemId = item.id,
                decision = item.playbackDecision,
                status = if (cachedAsset != null) CompatibilityStatus.READY else CompatibilityStatus.IDLE,
                message = if (cachedAsset != null) {
                    "Compatible playback is ready."
                } else {
                    "Will prepare a compatible browser stream when requested."
                },
                preparedAsset = cachedAsset,
                streamable = cachedAsset != null,
            )
        }
        return upsert(job)
    }

    override suspend fun requestPreparation(item: SharedItem): CompatibilityJob {
        val current = inspect(item)
        if (current.status == CompatibilityStatus.READY ||
            current.status == CompatibilityStatus.QUEUED ||
            current.status == CompatibilityStatus.PREPARING
        ) {
            return current
        }
        if (item.playbackDecision.mode == PlaybackMode.DIRECT) {
            return current
        }

        val queued = upsert(
            current.copy(
                status = CompatibilityStatus.QUEUED,
                message = when (item.playbackDecision.mode) {
                    PlaybackMode.REMUX -> "Queued for lightweight playback optimization."
                    PlaybackMode.TRANSCODE -> "Queued for compatibility conversion."
                    PlaybackMode.DIRECT -> current.message
                },
                progressPercent = 0,
                streamable = current.streamable,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )

        scope.launch {
            process(item)
        }
        return queued
    }

    override suspend fun resolvePlayback(item: SharedItem): PlaybackResolution {
        val job = inspect(item)
        if (item.playbackDecision.mode != PlaybackMode.DIRECT &&
            job.preparedAsset == null &&
            job.status == CompatibilityStatus.IDLE
        ) {
            return PlaybackResolution.Pending(requestPreparation(item))
        }
        return when {
            item.playbackDecision.mode == PlaybackMode.DIRECT -> PlaybackResolution.Ready(
                source = PlaybackSource.OriginalUri(
                    uriString = item.uri,
                    mimeType = item.playbackDecision.browserMimeType ?: item.mimeType,
                    sizeBytes = item.sizeBytes,
                ),
                job = job,
            )

            job.canServePlayback && job.preparedAsset != null -> PlaybackResolution.Ready(
                source = PlaybackSource.CachedFile(
                    filePath = job.preparedAsset.filePath,
                    mimeType = job.preparedAsset.mimeType ?: item.playbackDecision.browserMimeType ?: item.mimeType,
                    sizeBytes = job.preparedAsset.sizeBytes,
                    allowGrowing = !job.preparedAsset.isComplete,
                    isComplete = job.preparedAsset.isComplete,
                ),
                job = job,
            )

            job.status == CompatibilityStatus.FAILED -> PlaybackResolution.Failed(job)
            else -> PlaybackResolution.Pending(job)
        }
    }

    override fun currentJob(itemId: String): CompatibilityJob? = jobs.value[itemId]

    override suspend fun clearTemporaryOutputs() {
        worker.cancelAll()
        cache.clearAll()
        _jobs.value = _jobs.value
            .mapValues { (_, job) ->
                if (job.decision.mode == PlaybackMode.DIRECT) {
                    job.copy(
                        status = CompatibilityStatus.READY,
                        preparedAsset = null,
                        streamable = true,
                        progressPercent = null,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                } else {
                    job.copy(
                        status = CompatibilityStatus.IDLE,
                        message = job.decision.reason,
                        preparedAsset = null,
                        streamable = false,
                        progressPercent = null,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                }
            }
    }

    private suspend fun process(item: SharedItem) {
        processingMutex.withLock {
            val preparing = upsert(
                (currentJob(item.id) ?: inspect(item)).copy(
                    status = CompatibilityStatus.PREPARING,
                    message = when (item.playbackDecision.mode) {
                        PlaybackMode.REMUX -> "Optimizing container for browser playback..."
                        PlaybackMode.TRANSCODE -> "Optimizing for browser playback..."
                        PlaybackMode.DIRECT -> item.playbackDecision.reason
                    },
                    progressPercent = 12,
                    streamable = false,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )

            val completed = when (item.playbackDecision.mode) {
                PlaybackMode.DIRECT -> preparing.copy(
                    status = CompatibilityStatus.READY,
                    progressPercent = 100,
                    streamable = true,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )

                PlaybackMode.REMUX,
                PlaybackMode.TRANSCODE,
                -> when (
                    val result = worker.prepare(
                        item = item,
                        cache = cache,
                        onUpdate = { update ->
                            val current = currentJob(item.id) ?: preparing
                            upsert(
                                current.copy(
                                    status = update.status ?: current.status,
                                    message = update.message ?: current.message,
                                    progressPercent = update.progressPercent ?: current.progressPercent,
                                    preparedAsset = update.preparedAsset ?: current.preparedAsset,
                                    streamable = update.streamable ?: current.streamable,
                                    updatedAtEpochMs = System.currentTimeMillis(),
                                ),
                            )
                        },
                    )
                ) {
                    is CompatibilityWorkerResult.Success -> preparing.copy(
                        status = CompatibilityStatus.READY,
                        progressPercent = 100,
                        preparedAsset = result.preparedAsset,
                        message = result.message,
                        streamable = true,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )

                    is CompatibilityWorkerResult.Failure -> preparing.copy(
                        status = CompatibilityStatus.FAILED,
                        progressPercent = null,
                        message = result.message,
                        streamable = false,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                }
            }

            upsert(completed)
        }
    }

    private fun upsert(job: CompatibilityJob): CompatibilityJob {
        _jobs.update { current ->
            current + (job.itemId to job)
        }
        return job
    }
}
