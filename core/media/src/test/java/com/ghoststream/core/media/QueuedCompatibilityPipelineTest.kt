package com.ghoststream.core.media

import com.ghoststream.core.model.MediaCategory
import com.ghoststream.core.model.PlaybackDecision
import com.ghoststream.core.model.PlaybackMode
import com.ghoststream.core.model.SharedItem
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueuedCompatibilityPipelineTest {
    @Test
    fun `preparation success resolves to cached playback source`() = runTest {
        val cache = FakePlaybackCache()
        val testScope = CoroutineScope(coroutineContext)
        val pipeline = QueuedCompatibilityPipeline(
            cache = cache,
            worker = SuccessfulWorker(),
            scope = testScope,
        )
        val item = transcodeItem()

        val queued = pipeline.requestPreparation(item)
        assertEquals(CompatibilityStatus.QUEUED, queued.status)

        advanceUntilIdle()

        val job = pipeline.currentJob(item.id) ?: error("Expected compatibility job")
        assertEquals(CompatibilityStatus.READY, job.status)
        val resolution = pipeline.resolvePlayback(item)
        assertTrue(resolution is PlaybackResolution.Ready)
        assertTrue((resolution as PlaybackResolution.Ready).source is PlaybackSource.CachedFile)
    }

    @Test
    fun `preparation failure surfaces failed status`() = runTest {
        val cache = FakePlaybackCache()
        val testScope = CoroutineScope(coroutineContext)
        val pipeline = QueuedCompatibilityPipeline(
            cache = cache,
            worker = object : CompatibilityWorker {
                override suspend fun prepare(
                    item: SharedItem,
                    cache: PlaybackCache,
                    stabilizedSource: StabilizedSourceInfo?,
                    startOffsetMs: Long,
                    onUpdate: (CompatibilityWorkerUpdate) -> Unit,
                ): CompatibilityWorkerResult {
                    return CompatibilityWorkerResult.Failure("Compatibility conversion failed.")
                }
            },
            scope = testScope,
        )
        val item = transcodeItem(id = "video-2")

        pipeline.requestPreparation(item)
        advanceUntilIdle()

        val job = pipeline.currentJob(item.id) ?: error("Expected compatibility job")
        assertEquals(CompatibilityStatus.FAILED, job.status)
    }

    @Test
    fun `fragmented output can become streamable before completion`() = runTest {
        val cache = FakePlaybackCache()
        val testScope = CoroutineScope(coroutineContext)
        val releaseCompletion = CompletableDeferred<Unit>()
        val pipeline = QueuedCompatibilityPipeline(
            cache = cache,
            worker = object : CompatibilityWorker {
                override suspend fun prepare(
                    item: SharedItem,
                    cache: PlaybackCache,
                    stabilizedSource: StabilizedSourceInfo?,
                    startOffsetMs: Long,
                    onUpdate: (CompatibilityWorkerUpdate) -> Unit,
                ): CompatibilityWorkerResult {
                    val outputFile = cache.newOutputFile(item, "mp4").apply {
                        parentFile?.mkdirs()
                        writeText("fragmented-partial")
                    }
                    onUpdate(
                        CompatibilityWorkerUpdate(
                            status = CompatibilityStatus.PREPARING,
                            message = "Fragmented playback is live.",
                            progressPercent = 24,
                            preparedAsset = cache.record(
                                itemId = item.id,
                                file = outputFile,
                                mimeType = "video/mp4",
                                isComplete = false,
                                isFragmentedMp4 = true,
                            ),
                            streamable = true,
                        ),
                    )
                    releaseCompletion.await()
                    return CompatibilityWorkerResult.Success(
                        preparedAsset = cache.record(
                            itemId = item.id,
                            file = outputFile,
                            mimeType = "video/mp4",
                            isComplete = true,
                            isFragmentedMp4 = true,
                        ),
                        message = "Compatibility playback is ready.",
                    )
                }
            },
            scope = testScope,
        )
        val item = transcodeItem(id = "video-3")

        pipeline.requestPreparation(item)
        advanceUntilIdle()

        val inFlightJob = pipeline.currentJob(item.id) ?: error("Expected compatibility job")
        // When streamable becomes true, the pipeline promotes to FINALIZING
        assertTrue(
            "Expected PREPARING or FINALIZING but was ${inFlightJob.status}",
            inFlightJob.status == CompatibilityStatus.PREPARING || inFlightJob.status == CompatibilityStatus.FINALIZING,
        )
        assertTrue(inFlightJob.streamable)

        val resolution = pipeline.resolvePlayback(item)
        assertTrue(resolution is PlaybackResolution.Ready)
        val source = (resolution as PlaybackResolution.Ready).source as PlaybackSource.CachedFile
        assertTrue(source.allowGrowing)

        releaseCompletion.complete(Unit)
        advanceUntilIdle()

        val completedJob = pipeline.currentJob(item.id) ?: error("Expected compatibility job")
        assertEquals(CompatibilityStatus.READY, completedJob.status)
    }

    @Test
    fun `inspect does not downgrade active job back to idle`() = runTest {
        val cache = FakePlaybackCache()
        val testScope = CoroutineScope(coroutineContext)
        val releaseCompletion = CompletableDeferred<Unit>()
        val pipeline = QueuedCompatibilityPipeline(
            cache = cache,
            worker = object : CompatibilityWorker {
                override suspend fun prepare(
                    item: SharedItem,
                    cache: PlaybackCache,
                    stabilizedSource: StabilizedSourceInfo?,
                    startOffsetMs: Long,
                    onUpdate: (CompatibilityWorkerUpdate) -> Unit,
                ): CompatibilityWorkerResult {
                    onUpdate(
                        CompatibilityWorkerUpdate(
                            status = CompatibilityStatus.PREPARING,
                            message = "Preparing...",
                        ),
                    )
                    releaseCompletion.await()
                    return CompatibilityWorkerResult.Failure("stopped")
                }
            },
            scope = testScope,
        )
        val item = transcodeItem(id = "video-4")

        pipeline.requestPreparation(item)
        advanceUntilIdle()

        val beforeInspect = pipeline.currentJob(item.id) ?: error("Expected compatibility job")
        assertEquals(CompatibilityStatus.PREPARING, beforeInspect.status)

        val inspected = pipeline.inspect(item)
        assertEquals(CompatibilityStatus.PREPARING, inspected.status)
        assertEquals(CompatibilityStatus.PREPARING, pipeline.currentJob(item.id)?.status)

        releaseCompletion.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `worker decision updates replace stale queued decision`() = runTest {
        val cache = FakePlaybackCache()
        val testScope = CoroutineScope(coroutineContext)
        val pipeline = QueuedCompatibilityPipeline(
            cache = cache,
            worker = object : CompatibilityWorker {
                override suspend fun prepare(
                    item: SharedItem,
                    cache: PlaybackCache,
                    stabilizedSource: StabilizedSourceInfo?,
                    startOffsetMs: Long,
                    onUpdate: (CompatibilityWorkerUpdate) -> Unit,
                ): CompatibilityWorkerResult {
                    onUpdate(
                        CompatibilityWorkerUpdate(
                            decision = item.playbackDecision.copy(
                                mode = PlaybackMode.TRANSCODE,
                                reason = "Falling back to transcode after remux failure",
                            ),
                            status = CompatibilityStatus.PREPARING,
                            message = "Retrying with full compatibility conversion...",
                        ),
                    )
                    return CompatibilityWorkerResult.Failure("Compatibility conversion failed.")
                }
            },
            scope = testScope,
        )
        val item = transcodeItem(id = "video-5").copy(
            playbackDecision = PlaybackDecision(
                mode = PlaybackMode.TRANSMUX,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Repackage for browser playback",
                reason = "Container can be repackaged for this browser",
            ),
        )

        pipeline.requestPreparation(item)
        advanceUntilIdle()

        val job = pipeline.currentJob(item.id) ?: error("Expected compatibility job")
        assertEquals(PlaybackMode.TRANSCODE, job.decision.mode)
        assertEquals(CompatibilityStatus.FAILED, job.status)
    }

    @Test
    fun `inspect preserves active fallback decision instead of restoring original mode`() = runTest {
        val cache = FakePlaybackCache()
        val testScope = CoroutineScope(coroutineContext)
        val pipeline = QueuedCompatibilityPipeline(
            cache = cache,
            worker = object : CompatibilityWorker {
                override suspend fun prepare(
                    item: SharedItem,
                    cache: PlaybackCache,
                    stabilizedSource: StabilizedSourceInfo?,
                    startOffsetMs: Long,
                    onUpdate: (CompatibilityWorkerUpdate) -> Unit,
                ): CompatibilityWorkerResult {
                    onUpdate(
                        CompatibilityWorkerUpdate(
                            decision = item.playbackDecision.copy(
                                mode = PlaybackMode.TRANSCODE,
                                reason = "Falling back to transcode after remux failure",
                            ),
                            status = CompatibilityStatus.PREPARING,
                            message = "Retrying with full compatibility conversion...",
                        ),
                    )
                    return CompatibilityWorkerResult.Failure("Compatibility conversion failed.")
                }
            },
            scope = testScope,
        )
        val item = transcodeItem(id = "video-6").copy(
            playbackDecision = PlaybackDecision(
                mode = PlaybackMode.TRANSMUX,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Repackage for browser playback",
                reason = "Container can be repackaged for this browser",
            ),
        )

        pipeline.requestPreparation(item)
        advanceUntilIdle()

        val inspected = pipeline.inspect(item)
        assertEquals(PlaybackMode.TRANSCODE, inspected.decision.mode)
        assertEquals(PlaybackMode.TRANSCODE, pipeline.currentJob(item.id)?.decision?.mode)
    }

    @Test
    fun `high priority playback request runs ahead of low priority manual prepare`() = runTest {
        val cache = FakePlaybackCache()
        val executionOrder = CopyOnWriteArrayList<String>()
        val testScope = CoroutineScope(coroutineContext)
        val pipeline = QueuedCompatibilityPipeline(
            cache = cache,
            worker = object : CompatibilityWorker {
                override suspend fun prepare(
                    item: SharedItem,
                    cache: PlaybackCache,
                    stabilizedSource: StabilizedSourceInfo?,
                    startOffsetMs: Long,
                    onUpdate: (CompatibilityWorkerUpdate) -> Unit,
                ): CompatibilityWorkerResult {
                    executionOrder += item.id
                    val outputFile = cache.newOutputFile(item, "mp4").apply {
                        parentFile?.mkdirs()
                        writeText(item.id)
                    }
                    return CompatibilityWorkerResult.Success(
                        preparedAsset = cache.record(
                            itemId = item.id,
                            file = outputFile,
                            mimeType = "video/mp4",
                        ),
                        message = "ready",
                    )
                }
            },
            scope = testScope,
        )
        val low = transcodeItem(id = "low-priority")
        val high = transcodeItem(id = "high-priority")

        pipeline.requestPreparation(low, priority = JobPriority.LOW)
        pipeline.requestPreparation(high, priority = JobPriority.HIGH)
        advanceUntilIdle()

        assertEquals(listOf("high-priority", "low-priority"), executionOrder)
    }

    @Test
    fun `forced fallback overrides ready direct job before early return`() = runTest {
        val cache = FakePlaybackCache()
        val testScope = CoroutineScope(coroutineContext)
        val pipeline = QueuedCompatibilityPipeline(
            cache = cache,
            worker = SuccessfulWorker(),
            scope = testScope,
        )
        val directItem = transcodeItem(id = "video-direct").copy(
            playbackDecision = PlaybackDecision(
                mode = PlaybackMode.DIRECT,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Direct Play",
                reason = "Container and codecs are browser-safe for this client",
            ),
        )
        val forcedCompatItem = directItem.copy(
            playbackDecision = PlaybackDecision(
                mode = PlaybackMode.REMUX,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Preparing compatible version",
                reason = "Browser rejected direct playback; preparing compatible version",
            ),
        )

        val initial = pipeline.inspect(directItem)
        assertEquals(PlaybackMode.DIRECT, initial.decision.mode)
        assertEquals(CompatibilityStatus.READY, initial.status)

        val queued = pipeline.requestPreparation(forcedCompatItem, priority = JobPriority.HIGH)
        assertEquals(PlaybackMode.REMUX, queued.decision.mode)
        assertEquals(JobPriority.HIGH, queued.priority)

        advanceUntilIdle()

        val completed = pipeline.currentJob(forcedCompatItem.id) ?: error("Expected compatibility job")
        assertEquals(PlaybackMode.REMUX, completed.decision.mode)
        assertEquals(CompatibilityStatus.READY, completed.status)
    }

    private fun transcodeItem(id: String = "video-1"): SharedItem {
        return SharedItem(
            id = id,
            uri = "content://ghoststream/$id",
            displayName = "Movie.avi",
            mimeType = "video/x-msvideo",
            category = MediaCategory.VIDEO,
            sizeBytes = 8_192L,
            dateAddedEpochMs = 1_000L,
            playbackDecision = PlaybackDecision(
                mode = PlaybackMode.TRANSCODE,
                browserMimeType = "video/mp4",
                compatibilityLabel = "Optimization may be needed",
                reason = "This format may need compatibility conversion for browser playback",
            ),
        )
    }

    private class SuccessfulWorker : CompatibilityWorker {
        override suspend fun prepare(
            item: SharedItem,
            cache: PlaybackCache,
            stabilizedSource: StabilizedSourceInfo?,
            startOffsetMs: Long,
            onUpdate: (CompatibilityWorkerUpdate) -> Unit,
        ): CompatibilityWorkerResult {
            val outputFile = cache.newOutputFile(item, "mp4").apply {
                parentFile?.mkdirs()
                writeText("prepared")
            }
            return CompatibilityWorkerResult.Success(
                preparedAsset = cache.record(
                    itemId = item.id,
                    file = outputFile,
                    mimeType = "video/mp4",
                    isComplete = true,
                    isFragmentedMp4 = true,
                ),
                message = "Compatible playback is ready.",
            )
        }
    }

    private class FakePlaybackCache : PlaybackCache {
        private val rootDir = Files.createTempDirectory("ghoststream-compat-test").toFile()
        private val assets = linkedMapOf<String, CachedPlaybackAsset>()

        override fun lookup(item: SharedItem): CachedPlaybackAsset? {
            val asset = assets[item.id] ?: return null
            return asset.takeIf { File(it.filePath).exists() }
        }

        override fun newOutputFile(item: SharedItem, suffix: String): File {
            return File(rootDir, "${item.id}_prepared.${suffix.trimStart('.')}")
        }

        override fun record(
            itemId: String,
            file: File,
            mimeType: String?,
            isComplete: Boolean,
            isFragmentedMp4: Boolean,
        ): CachedPlaybackAsset {
            val asset = CachedPlaybackAsset(
                itemId = itemId,
                filePath = file.absolutePath,
                mimeType = mimeType,
                sizeBytes = file.length(),
                createdAtEpochMs = System.currentTimeMillis(),
                isComplete = isComplete,
                isFragmentedMp4 = isFragmentedMp4,
            )
            assets[itemId] = asset
            return asset
        }

        override suspend fun clearAll() {
            rootDir.listFiles()?.forEach { it.delete() }
            rootDir.delete()
            assets.clear()
        }

        override suspend fun evict(itemId: String) {}
        override fun getStabilizedSourceFile(itemId: String): java.io.File = java.io.File(rootDir, "$itemId.source")
        override suspend fun evictStabilizedSource(itemId: String) {}
        override suspend fun cleanupStabilizedSources(activeItemIds: Set<String>) {}
        override suspend fun enforceBudget(maxBytes: Long, protectedIds: Set<String>) {}
        override fun totalCacheSizeBytes(): Long = 0L
        override suspend fun cleanupOrphans(activeItemIds: Set<String>) {}
    }
}
