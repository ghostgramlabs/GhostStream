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
import com.ghostgramlabs.directserve.core.resources.R

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
    private val cache: PlaybackCache,
    private val worker: CompatibilityWorker,
    private val stabilizer: MediaSourceStabilizer? = null,
    private val decisionEngine: SmartPlaybackDecisionEngine = DefaultSmartPlaybackDecisionEngine(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : CompatibilityPipeline {
    private val terminalFailureTypes = setOf(
        CompatibilityFailureType.SOURCE,
        CompatibilityFailureType.VALIDATION,
    )

    private val _jobs = MutableStateFlow<Map<String, CompatibilityJob>>(emptyMap())
    private val queueMutex = Mutex()
    private val pendingRequests = mutableListOf<PreparationRequest>()
    private val reprioritizedItems = mutableSetOf<String>()
    private val canceledItems = mutableSetOf<String>()
    private var queueProcessorRunning = false
    private var activeRequest: PreparationRequest? = null

    override val jobs: StateFlow<Map<String, CompatibilityJob>> = _jobs.asStateFlow()

    init {
        scope.launch {
            // Startup cleanup only; no job spawning here.
            cache.cleanupOrphans(_jobs.value.keys)
            cache.cleanupStabilizedSources(_jobs.value.keys)
            cache.enforceBudget(TempPlaybackCache.DEFAULT_CACHE_BUDGET_BYTES, protectedIds = _jobs.value.keys)
        }
    }

    override suspend fun inspect(item: SharedItem, capabilities: ClientCapabilities?): CompatibilityJob {
        val cachedAsset = cache.lookup(item)
        val isCacheHealthy = cachedAsset != null && cachedAsset.isComplete
        val existing = currentJob(item.id)
        val effectiveDecision = when {
            existing != null && (
                existing.status != CompatibilityStatus.IDLE ||
                    existing.preparedAsset != null ||
                    existing.isTerminalFailure
                ) -> existing.decision
            else -> item.playbackDecision
        }

        if (existing != null) {
            val refreshed = when {
                isCacheHealthy -> existing.copy(
                    decision = effectiveDecision,
                    status = CompatibilityStatus.READY,
                    message = "Playback Ready",
                    messageResId = R.string.compatibility_message_ready,
                    preparedAsset = cachedAsset,
                    progressPercent = 100,
                    hlsReady = cachedAsset.isFragmentedMp4,
                    directReady = true,
                    streamable = true,
                    isTerminalFailure = false,
                    lastFailureType = null,
                    lastFailureReason = null,
                    lastFailureAt = null,
                    updatedAtEpochMs = System.currentTimeMillis(),
                    width = cachedAsset.width ?: existing.width ?: item.metadata["width"]?.toIntOrNull(),
                    height = cachedAsset.height ?: existing.height ?: item.metadata["height"]?.toIntOrNull(),
                    totalDurationMs = cachedAsset.totalDurationMs ?: existing.totalDurationMs ?: item.durationMs,
                )

                else -> existing.copy(
                    decision = effectiveDecision,
                    width = existing.width ?: item.metadata["width"]?.toIntOrNull(),
                    height = existing.height ?: item.metadata["height"]?.toIntOrNull(),
                    totalDurationMs = existing.totalDurationMs ?: item.durationMs,
                )
            }
            return upsert(refreshed)
        }

        val job = when (effectiveDecision.mode) {
            PlaybackMode.DIRECT -> CompatibilityJob(
                itemId = item.id,
                decision = effectiveDecision,
                status = CompatibilityStatus.READY,
                message = "DIRECT: Playback supported natively",
                messageResId = R.string.playback_reason_direct_safe,
                streamable = true,
            )

            else -> CompatibilityJob(
                itemId = item.id,
                decision = effectiveDecision,
                status = if (isCacheHealthy) CompatibilityStatus.READY else CompatibilityStatus.IDLE,
                message = if (isCacheHealthy) "Playback Ready" else "Playback Ready (Pending Request)",
                messageResId = if (isCacheHealthy) R.string.compatibility_message_ready else R.string.session_browser_prep_message_manual,
                preparedAsset = if (isCacheHealthy) cachedAsset else null,
                hlsReady = isCacheHealthy && cachedAsset?.isFragmentedMp4 == true,
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
        fun modeRank(mode: PlaybackMode): Int = when (mode) {
            PlaybackMode.DIRECT -> 0
            PlaybackMode.REMUX -> 1
            PlaybackMode.TRANSMUX -> 2
            PlaybackMode.TRANSCODE -> 3
        }
        val forcedCompatibilityOverride = priority != JobPriority.LOW &&
            item.playbackDecision.mode != PlaybackMode.DIRECT &&
            modeRank(item.playbackDecision.mode) > modeRank(current.decision.mode)
        val reconciledCurrent = if (forcedCompatibilityOverride) {
            upsert(
                current.copy(
                    decision = item.playbackDecision,
                    status = CompatibilityStatus.IDLE,
                    message = queuedMessage(item.playbackDecision.mode, priority),
                    messageResId = queuedMessageResId(priority),
                    progressPercent = null,
                    preparedAsset = null,
                    hlsReady = false,
                    directReady = false,
                    streamable = false,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        } else {
            current
        }

        if (reconciledCurrent.isTerminalFailure) {
            return reconciledCurrent
        }
        if (reconciledCurrent.status == CompatibilityStatus.READY ||
            reconciledCurrent.status == CompatibilityStatus.PLAYABLE_NOW
        ) {
            return reconciledCurrent
        }
        if (reconciledCurrent.status != CompatibilityStatus.IDLE &&
            reconciledCurrent.status != CompatibilityStatus.QUEUED &&
            priority == JobPriority.LOW
        ) {
            return reconciledCurrent
        }

        // Priority Promotion: If a user clicks play (HIGH) on an existing prepare job, elevate it.
        val effectiveCurrent = if (priority != JobPriority.LOW &&
            (reconciledCurrent.priority == JobPriority.LOW || forcedCompatibilityOverride)
        ) {
            upsert(
                reconciledCurrent.copy(
                    priority = priority,
                    decision = if (forcedCompatibilityOverride) item.playbackDecision else reconciledCurrent.decision,
                    message = queuedMessage(
                        if (forcedCompatibilityOverride) item.playbackDecision.mode else reconciledCurrent.decision.mode,
                        priority,
                    ),
                    messageResId = queuedMessageResId(priority),
                ),
            )
        } else {
            reconciledCurrent
        }

        if (effectiveCurrent.decision.mode == PlaybackMode.DIRECT) return effectiveCurrent

        if (effectiveCurrent.status == CompatibilityStatus.QUEUED ||
            effectiveCurrent.status == CompatibilityStatus.ANALYZING ||
            effectiveCurrent.status == CompatibilityStatus.PREPARING ||
            effectiveCurrent.status == CompatibilityStatus.FINALIZING ||
            effectiveCurrent.status == CompatibilityStatus.PLAYABLE_NOW
        ) {
            return effectiveCurrent
        }

        val effectiveItem = item.copy(playbackDecision = effectiveCurrent.decision)

        // Stabilization
        val stabilizedCurrent = if (effectiveCurrent.stabilizedSource == null) {
            val stabilized = stabilizer?.stabilize(
                uriString = effectiveItem.uri,
                mimeType = effectiveItem.mimeType,
                sizeBytes = effectiveItem.sizeBytes,
                itemId = effectiveItem.id
            ) ?: StabilizedSourceInfo(
                originalUri = effectiveItem.uri,
                sourceType = SourceType.PROVIDER_URI,
                mimeType = effectiveItem.mimeType,
                sizeBytes = effectiveItem.sizeBytes,
                chosenStableSource = StableWorkerSource.PersistedUri(effectiveItem.uri),
            )
            upsert(effectiveCurrent.copy(stabilizedSource = stabilized))
        } else effectiveCurrent

        val queued = upsert(
            stabilizedCurrent.copy(
                status = CompatibilityStatus.QUEUED,
                message = queuedMessage(current.decision.mode, priority),
                messageResId = queuedMessageResId(priority),
                priority = priority,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        )

        enqueueAndProcess(PreparationRequest(effectiveItem, priority))
        return queued
    }

    override suspend fun requestSeek(item: SharedItem, offsetMs: Long): CompatibilityJob {
        val current = currentJob(item.id) ?: inspect(item)
        
        // Fully finalized prepared assets can satisfy seeks through normal range requests.
        // PLAYABLE_NOW is still an in-progress state, so explicit seeks must remain request-driven
        // and may need a CRITICAL restart from the new offset.
        if (current.status == CompatibilityStatus.READY || current.directReady) {
            CompatLogger.info("CompatPipeline", "seek_noop id=${item.id} reason=hardware_capable")
            return current
        }

        val queued = upsert(
            current.copy(
                status = CompatibilityStatus.QUEUED,
                message = "Seeking to new position...",
                messageResId = R.string.pipeline_message_seeking,
                streamable = false,
                startOffsetMs = offsetMs,
                priority = JobPriority.CRITICAL,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        )

        enqueueAndProcess(PreparationRequest(item.copy(playbackDecision = current.decision), JobPriority.CRITICAL, startOffsetMs = offsetMs))
        return queued
    }

    override suspend fun resolvePlayback(item: SharedItem, capabilities: ClientCapabilities?): PlaybackResolution {
        val job = inspect(item, capabilities)
        return when {
            job.decision.mode == PlaybackMode.DIRECT -> PlaybackResolution.Ready(
                source = PlaybackSource.OriginalUri(item.uri, item.mimeType, item.sizeBytes),
                job = job
            )
            (job.status == CompatibilityStatus.READY || job.status == CompatibilityStatus.PLAYABLE_NOW || job.directReady) &&
                job.preparedAsset != null -> PlaybackResolution.Ready(
                source = PlaybackSource.CachedFile(
                    job.preparedAsset.filePath,
                    job.preparedAsset.mimeType,
                    job.preparedAsset.sizeBytes,
                    allowGrowing = !job.preparedAsset.isComplete,
                    isComplete = job.preparedAsset.isComplete,
                    isFragmentedMp4 = job.preparedAsset.isFragmentedMp4,
                ),
                job = job
            )
            job.streamable && job.preparedAsset != null -> PlaybackResolution.Ready(
                source = PlaybackSource.CachedFile(
                    job.preparedAsset.filePath,
                    job.preparedAsset.mimeType,
                    job.preparedAsset.sizeBytes,
                    allowGrowing = true,
                    isComplete = false,
                    isFragmentedMp4 = job.preparedAsset.isFragmentedMp4,
                ),
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
            current + (
                itemId to job.copy(
                    isTerminalFailure = false,
                    failureCount = 0,
                    lastFailureType = null,
                    lastFailureReason = null,
                    lastFailureAt = null,
                    status = CompatibilityStatus.IDLE,
                    progressPercent = null,
                    preparedAsset = null,
                    hlsReady = false,
                    directReady = false,
                    streamable = false,
                )
            )
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
                pendingRequests.removeAt(0).also { activeRequest = it }
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
                        decision = update.decision ?: currentJob(item.id)?.decision ?: item.playbackDecision,
                        status = update.status ?: CompatibilityStatus.PREPARING,
                        message = update.message ?: currentJob(item.id)?.message ?: item.playbackDecision.reason,
                        messageResId = update.messageResId ?: currentJob(item.id)?.messageResId ?: item.playbackDecision.reasonResId,
                        messageArgs = update.messageArgs,
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
                message = result.message,
                messageResId = result.messageResId,
                messageArgs = result.messageArgs,
                progressPercent = 100,
                preparedAsset = result.preparedAsset,
                hlsReady = result.preparedAsset.isFragmentedMp4,
                directReady = true,
                streamable = true,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            is CompatibilityWorkerResult.Failure -> {
                cache.evict(item.id)
                currentJob(item.id)!!.copy(
                    status = CompatibilityStatus.FAILED,
                    message = result.message,
                    messageResId = result.messageResId,
                    messageArgs = result.messageArgs,
                    progressPercent = null,
                    preparedAsset = null,
                    hlsReady = false,
                    directReady = false,
                    streamable = false,
                    lastFailureType = result.type,
                    failureCount = currentJob(item.id)?.failureCount?.plus(1) ?: 1,
                    isTerminalFailure = result.type in terminalFailureTypes,
                    lastFailureReason = result.message,
                    lastFailureAt = System.currentTimeMillis(),
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

    private fun queuedMessageResId(priority: JobPriority): Int {
        return when {
            priority == JobPriority.CRITICAL -> R.string.pipeline_message_escalating
            priority == JobPriority.HIGH -> R.string.pipeline_message_opening_stream
            else -> R.string.pipeline_message_preparing_generic
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
