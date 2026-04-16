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

/**
 * Authoritative pipeline for media compatibility.
 * 
 * STRICT ARCHITECTURAL RULES (Request-Driven Architecture):
 * 1. STRICTLY REQUEST-DRIVEN: Compatibility work must NEVER start automatically. No auto-warmup.
 * 2. NO LIFECYCLE TRIGGERS: Discovery, bootstrap, and library browsing must NEVER enqueue jobs.
 * 3. EXPLICIT TRIGGER ONLY: Jobs are only created via Play (HIGH), Seek (CRITICAL), or Manual Prepare (LOW).
 * 4. PRIORITY LOCKDOWN: LOW priority is reserved EXCLUSIVELY for explicit manual batch prepare.
 * 5. DO NOT REINTRODUCE prefetch/warmup logic without thorough architectural review.
 */
interface CompatibilityPipeline {
    val jobs: StateFlow<Map<String, CompatibilityJob>>

    /** Investigates item metadata; does NOT trigger preparation workers. */
    suspend fun inspect(item: SharedItem, capabilities: ClientCapabilities? = null): CompatibilityJob
    
    /** 
     * Explicitly requests media preparation. 
     * Spawns a worker if the file is not already compatible or cached.
     * 
     * GUARDRAIL: Must only be called from explicit user actions (Play/Manual Prepare).
     */
    suspend fun requestPreparation(
        item: SharedItem, 
        priority: JobPriority = JobPriority.LOW,
        capabilities: ClientCapabilities? = null
    ): CompatibilityJob
    
    /** Escalates preparation priority and optionally restarts worker from a specific offset. */
    suspend fun requestSeek(item: SharedItem, offsetMs: Long): CompatibilityJob
    
    /** Determines the best source (Original, Prepared, or LIVE_HLS) for immediate playback. */
    suspend fun resolvePlayback(item: SharedItem, capabilities: ClientCapabilities? = null): PlaybackResolution
    
    fun currentJob(itemId: String): CompatibilityJob?
    suspend fun markMediaServed(itemId: String)
    suspend fun clearFailure(itemId: String)
    suspend fun invalidate(itemId: String)
    suspend fun cancelPendingPreparations()
    suspend fun clearTemporaryOutputs()
}

class QueuedCompatibilityPipeline(
    private val cache: TempPlaybackCache,
    private val worker: CompatibilityWorker,
    private val stabilizer: MediaSourceStabilizer,
    private val decisionEngine: SmartPlaybackDecisionEngine = DefaultSmartPlaybackDecisionEngine(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : CompatibilityPipeline {
    
    init {
        scope.launch {
            // Startup cleanup only; no job spawning here.
            cache.cleanupOrphans(_jobs.value.keys)
            cache.cleanupStabilizedSources(_jobs.value.keys)
            cache.enforceBudget(TempPlaybackCache.DEFAULT_CACHE_BUDGET_BYTES, protectedIds = _jobs.value.keys)
        }
    }

    private val _jobs = MutableStateFlow<Map<String, CompatibilityJob>>(emptyMap())
    private val queueMutex = Mutex()
    private val pendingRequests = mutableListOf<PreparationRequest>()
    private val reprioritizedItems = mutableSetOf<String>()
    private val canceledItems = mutableSetOf<String>()
    private var queueProcessorRunning = false
    private var activeRequest: PreparationRequest? = null

    override val jobs: StateFlow<Map<String, CompatibilityJob>> = _jobs.asStateFlow()

    override suspend fun inspect(item: SharedItem, capabilities: ClientCapabilities?): CompatibilityJob {
        val cachedAsset = cache.lookup(item)
        val isCacheHealthy = cachedAsset != null && cachedAsset.isComplete
        
        val caps = capabilities ?: ClientCapabilities.DEFAULT
        val customDecision = decisionEngine.decide(
            inspection = MediaInspection(
                originalMimeType = item.mimeType,
                normalizedMimeType = item.playbackDecision.browserMimeType ?: item.mimeType,
                displayName = item.displayName,
                extension = item.uri.substringAfterLast('.', ""),
                container = when {
                    item.mimeType?.contains("mp4") == true -> MediaContainer.MP4
                    item.mimeType?.contains("matroska") == true -> MediaContainer.MATROSKA
                    item.mimeType?.contains("webm") == true -> MediaContainer.WEBM
                    else -> MediaContainer.OTHER
                },
                videoTrackMimeType = item.metadata["video_codec"],
                audioTrackMimeType = item.metadata["audio_codec"],
                browserSafe = false,
                likelyContainerOnlyIssue = true,
                likelyNeedsTranscode = false,
                durationMs = item.durationMs,
                itemId = item.id,
                width = item.metadata["width"]?.toIntOrNull(),
                height = item.metadata["height"]?.toIntOrNull(),
                hasFaststart = item.metadata["faststart"]?.toBoolean(),
            ),
            capabilities = caps
        )

        val job = when (customDecision.mode) {
            PlaybackMode.DIRECT -> CompatibilityJob(
                itemId = item.id,
                decision = customDecision,
                status = CompatibilityStatus.READY,
                message = "DIRECT: Playback supported natively",
                streamable = true,
            )

            else -> CompatibilityJob(
                itemId = item.id,
                decision = customDecision,
                status = if (isCacheHealthy) CompatibilityStatus.READY else CompatibilityStatus.IDLE,
                message = if (isCacheHealthy) "Playback Ready" else "Playback Ready (Pending Request)",
                preparedAsset = if (isCacheHealthy) cachedAsset else null,
                streamable = isCacheHealthy,
            )
        }

        return upsert(job.copy(
            width = cachedAsset?.width ?: item.metadata["width"]?.toIntOrNull(),
            height = cachedAsset?.height ?: item.metadata["height"]?.toIntOrNull(),
            totalDurationMs = cachedAsset?.totalDurationMs ?: item.durationMs
        ))
    }

    override suspend fun requestPreparation(
        item: SharedItem,
        priority: JobPriority,
        capabilities: ClientCapabilities?
    ): CompatibilityJob {
        // HARD RULE: Only explicit user actions trigger this path.
        val current = currentJob(item.id) ?: inspect(item, capabilities)
        
        if (current.isTerminalFailure || current.status == CompatibilityStatus.READY ||
            (current.status != CompatibilityStatus.IDLE && current.status != CompatibilityStatus.QUEUED && priority == JobPriority.LOW)
        ) {
             return current
        }

        // Priority Promotion: If a user clicks play (HIGH) on an existing prepare job, elevate it.
        if (priority != JobPriority.LOW && current.priority == JobPriority.LOW) {
             upsert(current.copy(priority = priority))
        }

        if (item.playbackDecision.mode == PlaybackMode.DIRECT) return current

        // Stabilization
        val stabilizedCurrent = if (current.stabilizedSource == null) {
            val stabilized = stabilizer.stabilize(
                uriString = item.uri,
                mimeType = item.mimeType,
                sizeBytes = item.sizeBytes,
                itemId = item.id
            )
            upsert(current.copy(stabilizedSource = stabilized))
        } else current

        val queued = upsert(
            stabilizedCurrent.copy(
                status = CompatibilityStatus.QUEUED,
                message = queuedMessage(item.playbackDecision.mode, priority),
                priority = priority,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        )

        enqueueAndProcess(PreparationRequest(item, priority))
        return queued
    }

    override suspend fun requestSeek(item: SharedItem, offsetMs: Long): CompatibilityJob {
        val current = currentJob(item.id) ?: inspect(item)
        
        // Seek Optimization: If we are already READY/PREPARED, seeking is handled by the player via range requests.
        // We only need a CRITICAL priority re-spawn if we are in LIVE_HLS mode and need a new segment generation.
        if (current.status == CompatibilityStatus.READY || current.directReady) {
            CompatLogger.info("CompatPipeline", "seek_noop id=${item.id} reason=hardware_capable")
            return current
        }

        val queued = upsert(
            current.copy(
                status = CompatibilityStatus.QUEUED,
                message = "Seeking to new position...",
                streamable = false,
                startOffsetMs = offsetMs,
                priority = JobPriority.CRITICAL,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        )

        enqueueAndProcess(PreparationRequest(item, JobPriority.CRITICAL, startOffsetMs = offsetMs))
        return queued
    }

    override suspend fun resolvePlayback(item: SharedItem, capabilities: ClientCapabilities?): PlaybackResolution {
        val job = inspect(item, capabilities)
        return when {
            item.playbackDecision.mode == PlaybackMode.DIRECT -> PlaybackResolution.Ready(
                source = PlaybackSource.OriginalUri(item.uri, item.mimeType, item.sizeBytes),
                job = job
            )
            job.status == CompatibilityStatus.READY || job.directReady -> PlaybackResolution.Ready(
                source = PlaybackSource.CachedFile(job.preparedAsset!!.filePath, job.preparedAsset.mimeType, job.preparedAsset.sizeBytes, isComplete = true),
                job = job
            )
            job.streamable -> PlaybackResolution.Ready(
                source = PlaybackSource.CachedFile(job.preparedAsset!!.filePath, job.preparedAsset.mimeType, job.preparedAsset.sizeBytes, allowGrowing = true, isComplete = false),
                job = job
            )
            job.status == CompatibilityStatus.FAILED -> PlaybackResolution.Failed(job)
            else -> PlaybackResolution.Pending(job)
        }
    }

    override fun currentJob(itemId: String): CompatibilityJob? = _jobs.value[itemId]

    override suspend fun markMediaServed(itemId: String) {
        _jobs.update { current ->
            val job = current[itemId] ?: return@update current
            current + (itemId to job.copy(lastMediaServedAt = System.currentTimeMillis()))
        }
    }

    override suspend fun clearFailure(itemId: String) {
        _jobs.update { current ->
            val job = current[itemId] ?: return@update current
            current + (itemId to job.copy(isTerminalFailure = false, failureCount = 0, status = CompatibilityStatus.IDLE))
        }
    }

    override suspend fun invalidate(itemId: String) {
        cache.evict(itemId)
        _jobs.update { it - itemId }
    }

    override suspend fun cancelPendingPreparations() {
        queueMutex.withLock { pendingRequests.clear() }
    }

    override suspend fun clearTemporaryOutputs() {
        cache.clearAll()
        _jobs.update { emptyMap() }
    }

    private suspend fun enqueueAndProcess(request: PreparationRequest) {
        var startProcessor = false
        var preemptedId: String? = null
        
        queueMutex.withLock {
            if (request.priority != JobPriority.LOW) {
                preemptedId = preemptActiveLocked(request.item.id)
            }
            pendingRequests.removeAll { it.item.id == request.item.id }
            pendingRequests.add(request)
            pendingRequests.sortBy { it.priority.ordinal }
            
            if (!queueProcessorRunning) {
                queueProcessorRunning = true
                startProcessor = true
            }
        }

        preemptedId?.let(worker::cancel)
        if (startProcessor) scope.launch { drainQueue() }
    }

    private suspend fun drainQueue() {
        while (true) {
            val next = queueMutex.withLock {
                if (pendingRequests.isEmpty()) {
                    queueProcessorRunning = false
                    activeRequest = null
                    return@drainQueue
                }
                pendingRequests.removeAt(pendingRequests.size - 1).also { activeRequest = it }
            }
            process(next)
        }
    }

    private suspend fun process(request: PreparationRequest) {
        val item = request.item
        val currentJob = currentJob(item.id) ?: return
        
        val result = try {
            worker.prepare(
                item = item,
                cache = cache,
                stabilizedSource = currentJob.stabilizedSource,
                startOffsetMs = request.startOffsetMs,
                onUpdate = { update ->
                    upsert(currentJob(item.id)?.copy(
                        status = update.status ?: CompatibilityStatus.PREPARING,
                        progressPercent = update.progressPercent,
                        preparedAsset = update.preparedAsset,
                        hlsReady = update.hlsReady ?: false,
                        directReady = update.directReady ?: false,
                        streamable = update.streamable ?: false,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ) ?: return@prepare)
                }
            )
        } catch (e: Exception) {
            CompatibilityWorkerResult.Failure(e.message ?: "Unknown worker error", CompatibilityFailureType.SOURCE)
        }

        val completed = when (result) {
            is CompatibilityWorkerResult.Success -> currentJob(item.id)!!.copy(
                status = CompatibilityStatus.READY,
                progressPercent = 100,
                preparedAsset = result.preparedAsset,
                streamable = true,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            is CompatibilityWorkerResult.Failure -> {
                cache.evict(item.id)
                currentJob(item.id)!!.copy(
                    status = CompatibilityStatus.FAILED,
                    isTerminalFailure = true,
                    lastFailureReason = result.message,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            }
        }
        upsert(completed)
    }

    private fun upsert(job: CompatibilityJob): CompatibilityJob {
        _jobs.update { it + (job.itemId to job) }
        return job
    }

    private fun queuedMessage(mode: PlaybackMode, priority: JobPriority): String {
        return when {
            priority == JobPriority.CRITICAL -> "Escalating preparation..."
            priority == JobPriority.HIGH -> "Opening browser stream..."
            else -> "Preparing compatible version..."
        }
    }

    private fun preemptActiveLocked(priorityItemId: String): String? {
        val active = activeRequest ?: return null
        if (active.item.id == priorityItemId) return null
        return if (active.priority == JobPriority.LOW) active.item.id else null
    }

    private data class PreparationRequest(
        val item: SharedItem,
        val priority: JobPriority,
        val startOffsetMs: Long = 0L,
        val requestedAt: Long = System.currentTimeMillis()
    )
}
