package com.ghoststream.core.media

import com.ghoststream.core.model.PlaybackMode
import com.ghoststream.core.model.SharedItem
import java.io.File
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
    suspend fun requestPreparation(item: SharedItem, prioritize: Boolean = false): CompatibilityJob
    suspend fun requestSeek(item: SharedItem, offsetMs: Long): CompatibilityJob
    suspend fun resolvePlayback(item: SharedItem): PlaybackResolution
    fun currentJob(itemId: String): CompatibilityJob?
    suspend fun invalidate(itemId: String)
    suspend fun cancelPendingPreparations()
    suspend fun clearTemporaryOutputs()
}

class QueuedCompatibilityPipeline(
    private val cache: TempPlaybackCache,
    private val worker: CompatibilityWorker,
    private val stabilizer: MediaSourceStabilizer,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : CompatibilityPipeline {
    init {
        scope.launch {
            // Startup cleanup: remove orphaned .tmp files and enforce cache budget
            cache.cleanupOrphans(_jobs.value.keys)
            cache.cleanupStabilizedSources(_jobs.value.keys)
            cache.enforceBudget(TempPlaybackCache.DEFAULT_CACHE_BUDGET_BYTES, protectedIds = _jobs.value.keys)
            CompatLogger.info("CacheInit", "startup cleanup complete totalCache=${cache.totalCacheSizeBytes() / 1024 / 1024}MB")
        }
    }
    private val _jobs = MutableStateFlow<Map<String, CompatibilityJob>>(emptyMap())
    private val queueMutex = Mutex()
    private val pendingRequests = mutableListOf<PreparationRequest>()
    private val reprioritizedItems = mutableSetOf<String>()
    private val canceledItems = mutableSetOf<String>()
    private var queueProcessorRunning = false
    private var activeRequest: PreparationRequest? = null

    // Throttling: track last emitted coarse progress per item to avoid excessive updates
    private val lastEmittedBucket = mutableMapOf<String, Int>()
    
    // Retry protection: track how many times an item has been reported as broken by the client
    private val failureCounts = mutableMapOf<String, Int>()

    override val jobs: StateFlow<Map<String, CompatibilityJob>> = _jobs.asStateFlow()

    companion object {
        /** Only emit UI-visible updates when progress moves by at least this many percent */
        private const val PROGRESS_BUCKET_SIZE = 5
        /** Max time (ms) with no progress movement before marking STALLED */
        private const val STALL_TIMEOUT_MS = 120_000L // 2 minutes
        /** Max stall checks before giving up */
        private const val MAX_STALL_RETRIES = 1
        /** Max times we allow the client to report a prepared asset as broken before giving up */
        private const val MAX_RETRY_ATTEMPTS = 2
    }

    override suspend fun inspect(item: SharedItem): CompatibilityJob {
        val existing = currentJob(item.id)

        // If there's an actively running job (QUEUED/ANALYZING/PREPARING/FINALIZING), trust it.
        // These states mean work is in progress and we shouldn't interfere.
        if (existing != null && existing.status in activeJobStates) {
            return existing
        }

        // If existing job is READY with a valid preparedAsset, verify the file still exists on disk.
        // This catches the case where the file was deleted externally or by clearTemporaryOutputs().
        if (existing != null && existing.status == CompatibilityStatus.READY && existing.preparedAsset != null) {
            val assetFile = File(existing.preparedAsset.filePath)
            if (assetFile.exists() && assetFile.length() > 0) {
                return existing // Reuse: file is valid on disk
            }
            // File is missing/empty â€” fall through to disk cache lookup below
        }

        // ALWAYS check the disk cache for a valid prepared asset.
        // This is the critical reuse path: even if the in-memory _jobs map was cleared
        // (e.g. by cancelPendingPreparations), the .mp4 may still exist on disk from a
        // previous successful prepare. cache.lookup() validates the file against the
        // source's size + mtime fingerprint, so stale files are never returned.
        val cachedAsset = cache.lookup(item)
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

            PlaybackMode.TRANSMUX -> CompatibilityJob(
                itemId = item.id,
                decision = item.playbackDecision,
                status = if (cachedAsset != null) CompatibilityStatus.READY else CompatibilityStatus.IDLE,
                message = if (cachedAsset != null) {
                    "Lightning-fast stream is ready."
                } else {
                    "Will prepare a lightning-fast stream when requested."
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

    private val activeJobStates = setOf(
        CompatibilityStatus.QUEUED,
        CompatibilityStatus.ANALYZING,
        CompatibilityStatus.PREPARING,
        CompatibilityStatus.FINALIZING,
    )
    override suspend fun requestPreparation(item: SharedItem, prioritize: Boolean): CompatibilityJob {
        val current = inspect(item)
        if (current.status == CompatibilityStatus.READY ||
            current.status == CompatibilityStatus.PREPARING ||
            current.status == CompatibilityStatus.ANALYZING ||
            current.status == CompatibilityStatus.FINALIZING
        ) {
            return current
        }
        if (item.playbackDecision.mode == PlaybackMode.DIRECT) {
            return current
        }
        // 1. Ensure the source is stabilized BEFORE it ever enters the queue.
        // This avoids SecurityException in background worker later.
        val stabilizedCurrent = if (current.stabilizedSource == null && current.status != CompatibilityStatus.READY) {
            val stabilized = stabilizer.stabilize(
                uriString = item.uri,
                mimeType = item.mimeType,
                sizeBytes = item.sizeBytes,
                itemId = item.id
            )
            upsert(current.copy(stabilizedSource = stabilized))
        } else {
            current
        }

        if (stabilizedCurrent.stabilizedSource != null && !stabilizedCurrent.stabilizedSource!!.readProbeSuccess) {
            // If stabilization already failed, don't even try to queue.
            return upsert(stabilizedCurrent.copy(
                status = CompatibilityStatus.FAILED,
                message = "Unable to access the media source. Please ensure the file still exists and the app has permission to read it."
            ))
        }

        val queued = upsert(
            stabilizedCurrent.copy(
                status = CompatibilityStatus.QUEUED,
                message = queuedMessage(item.playbackDecision.mode, prioritize),
                progressPercent = 0,
                preparedAsset = if (stabilizedCurrent.status == CompatibilityStatus.READY) stabilizedCurrent.preparedAsset else null,
                streamable = stabilizedCurrent.status == CompatibilityStatus.READY,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )

        var startProcessor = false
        var preemptedItemId: String? = null
        queueMutex.withLock {
            if (prioritize) {
                preemptedItemId = preemptActiveLocked(item.id)
            }
            enqueueLocked(PreparationRequest(item = item, prioritizePlayback = prioritize))
            if (!queueProcessorRunning) {
                queueProcessorRunning = true
                startProcessor = true
            }
        }

        preemptedItemId?.let(worker::cancel)

        if (startProcessor) {
            scope.launch {
                drainQueue()
            }
        }
        return queued
    }

    override suspend fun requestSeek(item: SharedItem, offsetMs: Long): CompatibilityJob {
        val current = inspect(item)
        if (item.playbackDecision.mode == PlaybackMode.DIRECT) return current

        // Update UI immediately and reset job state for the new offset.
        // We MUST clear preparedAsset so that the HLS Indexer and server stop
        // trying to serve segments from the previous offset/file.
        val queued = upsert(
            current.copy(
                status = CompatibilityStatus.QUEUED,
                message = "Seeking to requested position...",
                progressPercent = 0,
                preparedAsset = null,
                streamable = false,
                startOffsetMs = offsetMs,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )

        var startProcessor = false
        var preemptedItemId: String? = null
        queueMutex.withLock {
            // Seek requests ALWAYS prioritize and preempt.
            preemptedItemId = preemptActiveLocked(item.id)
            enqueueLocked(PreparationRequest(item = item, prioritizePlayback = true, startOffsetMs = offsetMs))
            if (!queueProcessorRunning) {
                queueProcessorRunning = true
                startProcessor = true
            }
        }

        preemptedItemId?.let(worker::cancel)

        if (startProcessor) {
            scope.launch {
                drainQueue()
            }
        }
        return queued
    }

    override suspend fun resolvePlayback(item: SharedItem): PlaybackResolution {
        val job = inspect(item)
        if (item.playbackDecision.mode != PlaybackMode.DIRECT &&
            job.preparedAsset == null &&
            job.status == CompatibilityStatus.IDLE
        ) {
            return PlaybackResolution.Pending(requestPreparation(item, prioritize = true))
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

            job.status == CompatibilityStatus.FAILED || job.status == CompatibilityStatus.STALLED -> PlaybackResolution.Failed(job)
            else -> PlaybackResolution.Pending(job)
        }
    }

    override fun currentJob(itemId: String): CompatibilityJob? = jobs.value[itemId]

    override suspend fun invalidate(itemId: String) {
        // 1. Cancel any active or pending work for this item.
        worker.cancel(itemId)
        queueMutex.withLock {
            pendingRequests.removeAll { it.item.id == itemId }
            reprioritizedItems.remove(itemId)
            canceledItems.remove(itemId)
            if (activeRequest?.item?.id == itemId) {
                activeRequest = null
            }
        }

        // 2. Clear the physical cache entries.
        cache.evict(itemId)

        // 3. Track failure to prevent infinite retry loops.
        failureCounts[itemId] = (failureCounts[itemId] ?: 0) + 1

        // 4. Reset the job state in memory.
        _jobs.update { current ->
            current - itemId
        }
    }

    override suspend fun cancelPendingPreparations() {
        var activeItemId: String? = null
        queueMutex.withLock {
            pendingRequests.clear()
            reprioritizedItems.clear()
            activeItemId = activeRequest?.item?.id
            activeItemId?.let(canceledItems::add)
        }
        activeItemId?.let(worker::cancel)
        _jobs.update { current ->
            current.mapValues { (_, job) ->
                if (job.status == CompatibilityStatus.QUEUED || job.status == CompatibilityStatus.PREPARING ||
                    job.status == CompatibilityStatus.ANALYZING || job.status == CompatibilityStatus.FINALIZING
                ) {
                    job.copy(
                        status = CompatibilityStatus.IDLE,
                        message = job.decision.reason,
                        progressPercent = null,
                        preparedAsset = null,
                        streamable = false,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                } else {
                    job
                }
            }
        }
    }

    override suspend fun clearTemporaryOutputs() {
        worker.cancelAll()
        cache.clearAll()
        queueMutex.withLock {
            pendingRequests.clear()
            reprioritizedItems.clear()
            canceledItems.clear()
            activeRequest = null
            queueProcessorRunning = false
        }
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

    private suspend fun drainQueue() {
        while (true) {
            val nextRequest = queueMutex.withLock {
                if (pendingRequests.isEmpty()) {
                    queueProcessorRunning = false
                    activeRequest = null
                    return
                }
                pendingRequests.removeAt(0).also { activeRequest = it }
            }

            try {
                val current = currentJob(nextRequest.item.id)
                if (current?.status == CompatibilityStatus.READY ||
                    current?.status == CompatibilityStatus.PREPARING ||
                    current?.status == CompatibilityStatus.ANALYZING ||
                    current?.status == CompatibilityStatus.FINALIZING
                ) {
                    continue
                }
                process(nextRequest)
            } finally {
                queueMutex.withLock {
                    if (activeRequest?.item?.id == nextRequest.item.id) {
                        activeRequest = null
                    }
                }
            }
        }
    }

    /**
     * Returns true if the progress update represents a meaningful change worth emitting.
     * Filters out tiny 1% increments that create UI/notification churn.
     */
    private fun isMeaningfulUpdate(itemId: String, update: CompatibilityWorkerUpdate, current: CompatibilityJob): Boolean {
        // State changes are always meaningful
        if (update.status != null && update.status != current.status) return true
        // Streamable becoming true is meaningful
        if (update.streamable == true && !current.streamable) return true
        // Asset becoming available is meaningful
        if (update.preparedAsset != null && current.preparedAsset == null) return true
        // HLS/direct ready transitions
        if (update.hlsReady == true && !current.hlsReady) return true
        if (update.directReady == true && !current.directReady) return true

        // Progress: only meaningful if it crosses a 5% bucket boundary
        if (update.progressPercent != null) {
            val newBucket = (update.progressPercent!! / PROGRESS_BUCKET_SIZE) * PROGRESS_BUCKET_SIZE
            val lastBucket = lastEmittedBucket[itemId] ?: -1
            if (newBucket > lastBucket) {
                lastEmittedBucket[itemId] = newBucket
                return true
            }
        }

        return false
    }

    /**
     * Check if a job appears stuck and handle stall detection.
     */
    private fun checkForStall(current: CompatibilityJob): CompatibilityJob {
        if (current.status != CompatibilityStatus.PREPARING) return current
        val now = System.currentTimeMillis()
        val timeSinceProgress = now - current.lastProgressAt
        if (timeSinceProgress < STALL_TIMEOUT_MS) return current

        return if (current.stallCheckCount >= MAX_STALL_RETRIES) {
            current.copy(
                status = CompatibilityStatus.STALLED,
                message = "Preparation appears stuck. The file may be too complex for this device.",
                updatedAtEpochMs = now,
            )
        } else {
            current.copy(
                stallCheckCount = current.stallCheckCount + 1,
                updatedAtEpochMs = now,
            )
        }
    }

    private suspend fun process(request: PreparationRequest) {
        val item = request.item
        val now = System.currentTimeMillis()

        // Persistent failure protection: if this item has failed too many times, 
        // don't start it again. 
        val failures = failureCounts[item.id] ?: 0
        if (failures >= MAX_RETRY_ATTEMPTS) {
            upsert(
                (currentJob(item.id) ?: inspect(item)).copy(
                    status = CompatibilityStatus.FAILED,
                    message = "This file is repeatedly failing playback even after optimization. It might be corrupt or rely on a codec your device cannot handle.",
                    updatedAtEpochMs = now,
                )
            )
            return
        }

        // Start with ANALYZING phase
        val analyzing = upsert(
            (currentJob(item.id) ?: inspect(item)).copy(
                status = CompatibilityStatus.ANALYZING,
                message = "Analyzing media file...",
                progressPercent = 5,
                streamable = false,
                startOffsetMs = request.startOffsetMs,
                updatedAtEpochMs = now,
                lastProgressAt = now,
                lastProgressValue = 0,
                lastOutputBytesAt = now,
                stallCheckCount = 0,
            ),
        )
        lastEmittedBucket[item.id] = 0

        // Transition to PREPARING
        val preparing = upsert(
            analyzing.copy(
                status = CompatibilityStatus.PREPARING,
                message = when (item.playbackDecision.mode) {
                    PlaybackMode.REMUX -> "Optimizing container for browser playback..."
                    PlaybackMode.TRANSMUX -> "Preparing lightning-fast stream..."
                    PlaybackMode.TRANSCODE -> "Preparing for web playback..."
                    PlaybackMode.DIRECT -> item.playbackDecision.reason
                },
                progressPercent = 10,
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
            PlaybackMode.TRANSMUX,
            PlaybackMode.TRANSCODE,
            -> when (
                val result = worker.prepare(
                    item = item,
                    cache = cache,
                    stabilizedSource = (currentJob(item.id) ?: preparing).stabilizedSource,
                    startOffsetMs = request.startOffsetMs,
                    onUpdate = onUpdate@{ update ->
                        val current = currentJob(item.id) ?: preparing

                        // Check for stall condition
                        val stallChecked = checkForStall(current)
                        if (stallChecked.status == CompatibilityStatus.STALLED) {
                            upsert(stallChecked)
                            return@onUpdate
                        }

                        // Only emit meaningful updates (throttled)
                        if (!isMeaningfulUpdate(item.id, update, current)) return@onUpdate

                        val newProgress = if (update.progressPercent != null && current.progressPercent != null) {
                            maxOf(current.progressPercent!!, update.progressPercent!!)
                        } else {
                            update.progressPercent ?: current.progressPercent
                        }

                        val progressNow = System.currentTimeMillis()
                        val progressMoved = newProgress != null && newProgress > current.lastProgressValue

                        // Detect FINALIZING state when streamable becomes true
                        val effectiveStatus = when {
                            update.status == CompatibilityStatus.READY -> CompatibilityStatus.READY
                            update.streamable == true && !current.streamable -> CompatibilityStatus.FINALIZING
                            update.status != null -> update.status
                            else -> current.status
                        }

                        upsert(
                            current.copy(
                                status = effectiveStatus,
                                message = if (effectiveStatus == CompatibilityStatus.FINALIZING) {
                                    "Finalizing browser stream..."
                                } else {
                                    update.message ?: current.message
                                },
                                progressPercent = newProgress,
                                preparedAsset = update.preparedAsset ?: current.preparedAsset,
                                hlsReady = update.hlsReady ?: current.hlsReady,
                                directReady = update.directReady ?: current.directReady,
                                streamable = update.streamable ?: current.streamable,
                                updatedAtEpochMs = progressNow,
                                lastProgressAt = if (progressMoved) progressNow else current.lastProgressAt,
                                lastProgressValue = newProgress ?: current.lastProgressValue,
                                lastOutputBytesAt = if (update.streamable == true || update.preparedAsset != null) progressNow else current.lastOutputBytesAt,
                            ),
                        )
                    },
                )
            ) {
                is CompatibilityWorkerResult.Success -> {
                    queueMutex.withLock {
                        reprioritizedItems.remove(item.id)
                        canceledItems.remove(item.id)
                    }
                    // Use currentJob() not the stale 'preparing' snapshot so that any
                    // in-flight onUpdate progress/state fields are preserved.
                    (currentJob(item.id) ?: preparing).copy(
                        status = CompatibilityStatus.READY,
                        progressPercent = 100,
                        preparedAsset = result.preparedAsset,
                        message = result.message,
                        hlsReady = true,
                        directReady = true,
                        streamable = true,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                }

                is CompatibilityWorkerResult.Failure -> {
                    val (reprioritized, canceled) = queueMutex.withLock {
                        (reprioritizedItems.remove(item.id) to canceledItems.remove(item.id))
                    }
                    when {
                        canceled -> {
                            // Explicitly canceled (e.g. stopSharing or preempt). 
                            // Use IDLE so the item can be re-queued by the warmup on next session.
                            // Log so it shows up in the debug log.
                            CompatLogger.info("CompatPipeline", "job_canceled id=${item.id} — reset to IDLE for re-queue")
                            preparing.copy(
                                status = CompatibilityStatus.IDLE,
                                progressPercent = null,
                                preparedAsset = null,
                                message = preparing.decision.reason,
                                streamable = false,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            )
                        }

                        reprioritized -> preparing.copy(
                            status = CompatibilityStatus.QUEUED,
                            progressPercent = 0,
                            preparedAsset = null,
                            message = queuedMessage(item.playbackDecision.mode, prioritize = false),
                            streamable = false,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )

                        else -> {
                            CompatLogger.error("CompatPipeline", "job_failed id=${item.id} reason=${result.message}")
                            (currentJob(item.id) ?: preparing).copy(
                                status = CompatibilityStatus.FAILED,
                                progressPercent = null,
                                message = result.message,
                                streamable = false,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            )
                        }
                    }
                }
            }
        }

        upsert(completed)
    }

    private fun upsert(job: CompatibilityJob): CompatibilityJob {
        _jobs.update { current ->
            current + (job.itemId to job)
        }
        return job
    }

    private fun queuedMessage(mode: PlaybackMode, prioritize: Boolean): String {
        return when {
            prioritize && mode == PlaybackMode.REMUX -> "Opening this video next for browser playback."
            prioritize && mode == PlaybackMode.TRANSCODE -> "Opening this video next for browser playback."
            mode == PlaybackMode.REMUX -> "Queued for lightweight playback optimization."
            mode == PlaybackMode.TRANSCODE -> "Queued for compatibility conversion."
            else -> "Ready."
        }
    }

    private fun enqueueLocked(request: PreparationRequest) {
        pendingRequests.removeAll { it.item.id == request.item.id }
        if (request.prioritizePlayback) {
            pendingRequests.add(0, request)
        } else {
            pendingRequests.add(request)
        }
    }

    private fun preemptActiveLocked(priorityItemId: String): String? {
        val currentActive = activeRequest ?: return null
        if (currentActive.item.id == priorityItemId || currentActive.prioritizePlayback) {
            return null
        }
        reprioritizedItems += currentActive.item.id
        pendingRequests.removeAll { it.item.id == currentActive.item.id }
        pendingRequests.add(0, currentActive.copy(prioritizePlayback = false))
        return currentActive.item.id
    }

    private data class PreparationRequest(
        val item: SharedItem,
        val prioritizePlayback: Boolean,
        val startOffsetMs: Long = 0L,
    )
}
