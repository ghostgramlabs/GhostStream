package com.ghoststream.core.media

import com.ghoststream.core.model.MediaCategory
import com.ghoststream.core.model.PlaybackDecision
import com.ghoststream.core.model.PlaybackMode
import com.ghoststream.core.model.SharedItem
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedCompatibilityPipelineTest {
    @Test
    fun `preparation success resolves to cached playback source`() = runTest {
        val cache = FakePlaybackCache()
        val pipeline = QueuedCompatibilityPipeline(
            cache = cache,
            worker = SuccessfulWorker(),
            scope = backgroundScope,
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
        val pipeline = QueuedCompatibilityPipeline(
            cache = cache,
            worker = object : CompatibilityWorker {
                override suspend fun prepare(
                    item: SharedItem,
                    cache: PlaybackCache,
                    onUpdate: (CompatibilityWorkerUpdate) -> Unit,
                ): CompatibilityWorkerResult {
                    return CompatibilityWorkerResult.Failure("Compatibility conversion failed.")
                }
            },
            scope = backgroundScope,
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
        val releaseCompletion = CompletableDeferred<Unit>()
        val pipeline = QueuedCompatibilityPipeline(
            cache = cache,
            worker = object : CompatibilityWorker {
                override suspend fun prepare(
                    item: SharedItem,
                    cache: PlaybackCache,
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
            scope = backgroundScope,
        )
        val item = transcodeItem(id = "video-3")

        pipeline.requestPreparation(item)
        runCurrent()

        val inFlightJob = pipeline.currentJob(item.id) ?: error("Expected compatibility job")
        assertEquals(CompatibilityStatus.PREPARING, inFlightJob.status)
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

        override fun lookup(itemId: String): CachedPlaybackAsset? {
            val asset = assets[itemId] ?: return null
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
    }
}
