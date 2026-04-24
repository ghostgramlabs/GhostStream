package com.ghostgramlabs.directserve.live

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.muxer.Muxer
import com.ghoststream.core.model.LiveAudioStatus
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

@RequiresApi(Build.VERSION_CODES.Q)
class LiveScreenMuxedStreamer(
    private val context: Context,
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val fps: Int,
    private val videoBitrateKbps: Int,
    private val captureAudio: Boolean,
    private val onAudioStatus: (LiveAudioStatus, String) -> Unit,
    private val onReady: (File) -> Unit,
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
) {
    val fragmentDurationSeconds: Double = FRAGMENT_DURATION_MS / 1000.0
    val outputFile: File = File(context.cacheDir, "live-screen/live-${System.currentTimeMillis()}.mp4")

    private val running = AtomicBoolean(false)
    private val muxerLock = Any()
    private val pendingSamples = ArrayDeque<EncodedSample>()

    private var fileOutputStream: FileOutputStream? = null
    private var muxer: FragmentedMp4Muxer? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var videoTrack: Muxer.TrackToken? = null
    private var audioTrack: Muxer.TrackToken? = null
    private var expectAudioTrack: Boolean = false
    private var readyReported: Boolean = false
    private var audioObserved: Boolean = false
    private var videoBasePtsUs: Long? = null
    private var audioBasePtsUs: Long? = null
    private var lastVideoPtsUs: Long = -1L
    private var lastAudioPtsUs: Long = -1L

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        return runCatching {
            fileOutputStream = FileOutputStream(outputFile)
            muxer = FragmentedMp4Muxer.Builder(fileOutputStream!!)
                .setFragmentDurationMs(FRAGMENT_DURATION_MS)
                .setSampleCopyEnabled(true)
                .build()

            expectAudioTrack = captureAudio
            startVideoEncoder()
            if (captureAudio && !startAudioEncoder()) {
                synchronized(muxerLock) {
                    expectAudioTrack = false
                    flushPendingSamplesLocked()
                }
                onAudioStatus(LiveAudioStatus.AUDIO_UNAVAILABLE, "muxed_audio_unavailable")
            }
            logger(
                "muxed live stream started file=${outputFile.name} width=$width height=$height fps=$fps audio=$expectAudioTrack",
                null,
            )
            true
        }.getOrElse { error ->
            logger("muxed live stream failed to start", error)
            stop()
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { videoCodec?.signalEndOfInputStream() }
        joinThread(videoThread)
        joinThread(audioThread)
        videoThread = null
        audioThread = null
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        videoCodec = null
        audioCodec = null
        synchronized(muxerLock) {
            pendingSamples.clear()
            runCatching { muxer?.close() }.onFailure { logger("muxed live stream close failed", it) }
            runCatching { fileOutputStream?.close() }
            muxer = null
            fileOutputStream = null
            videoTrack = null
            audioTrack = null
            readyReported = false
            videoBasePtsUs = null
            audioBasePtsUs = null
            lastVideoPtsUs = -1L
            lastAudioPtsUs = -1L
        }
    }

    private fun startVideoEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_KEYFRAME_INTERVAL_SECONDS)
            setInteger(MediaFormat.KEY_LATENCY, 0)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        videoCodec = codec
        inputSurface = surface
        virtualDisplay = projection.createVirtualDisplay(
            "DirectServeLiveMux",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )
        videoThread = Thread({ drainEncoder(codec, TrackKind.Video) }, "DirectServeLiveVideoMux").also {
            it.priority = Thread.NORM_PRIORITY + 2
            it.start()
        }
    }

    private fun startAudioEncoder(): Boolean {
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val minBufferSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            onAudioStatus(LiveAudioStatus.AUDIO_FAILED, "muxed_audio_min_buffer_$minBufferSize")
            return false
        }
        val bufferSize = maxOf(minBufferSize, AUDIO_SAMPLE_RATE * AUDIO_CHANNEL_COUNT * BYTES_PER_SAMPLE / 5)
        val config = runCatching {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        }.getOrElse {
            logger("muxed playback capture config failed", it)
            onAudioStatus(LiveAudioStatus.AUDIO_FAILED, "muxed_audio_config_failed")
            return false
        }
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        }.getOrElse {
            logger("muxed playback AudioRecord build failed", it)
            onAudioStatus(LiveAudioStatus.AUDIO_FAILED, "muxed_audio_record_build_failed")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            onAudioStatus(LiveAudioStatus.AUDIO_FAILED, "muxed_audio_record_uninitialized")
            return false
        }

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_COUNT,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        runCatching { record.startRecording() }.onFailure {
            logger("muxed playback AudioRecord start failed", it)
            runCatching { record.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            onAudioStatus(LiveAudioStatus.AUDIO_FAILED, "muxed_audio_record_start_failed")
            return false
        }

        audioRecord = record
        audioCodec = codec
        audioThread = Thread({ audioEncodeLoop(codec, record) }, "DirectServeLiveAudioMux").also {
            it.priority = Thread.NORM_PRIORITY + 1
            it.start()
        }
        onAudioStatus(LiveAudioStatus.AUDIO_INITIALIZING, "muxed_audio_started")
        return true
    }

    private fun audioEncodeLoop(codec: MediaCodec, record: AudioRecord) {
        var submittedFrames = 0L
        val bufferInfo = MediaCodec.BufferInfo()
        while (running.get()) {
            val inputIndex = codec.dequeueInputBuffer(ENCODER_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    val bytesRead = record.read(inputBuffer, inputBuffer.capacity(), AudioRecord.READ_BLOCKING)
                    if (bytesRead > 0) {
                        if (!audioObserved && inputBufferHasAudio(inputBuffer, bytesRead)) {
                            audioObserved = true
                            onAudioStatus(LiveAudioStatus.AUDIO_LIVE, "muxed_audio_activity")
                        }
                        val ptsUs = submittedFrames * 1_000_000L / AUDIO_SAMPLE_RATE
                        submittedFrames += bytesRead / (AUDIO_CHANNEL_COUNT * BYTES_PER_SAMPLE)
                        codec.queueInputBuffer(inputIndex, 0, bytesRead, ptsUs, 0)
                    } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION || bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                        logger("muxed playback AudioRecord read error code=$bytesRead", null)
                        onAudioStatus(LiveAudioStatus.AUDIO_FAILED, "muxed_audio_read_$bytesRead")
                        running.set(false)
                    } else {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            submittedFrames * 1_000_000L / AUDIO_SAMPLE_RATE,
                            0,
                        )
                    }
                }
            }
            drainAvailable(codec, bufferInfo, TrackKind.Audio)
        }
        runCatching {
            val inputIndex = codec.dequeueInputBuffer(ENCODER_TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    submittedFrames * 1_000_000L / AUDIO_SAMPLE_RATE,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
        }
        drainUntilEnd(codec, bufferInfo, TrackKind.Audio)
    }

    private fun drainEncoder(codec: MediaCodec, kind: TrackKind) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (running.get()) {
            drainAvailable(codec, bufferInfo, kind)
        }
        drainUntilEnd(codec, bufferInfo, kind)
    }

    private fun drainAvailable(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo, kind: TrackKind) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, ENCODER_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> registerTrack(kind, codec.outputFormat)
                outputIndex >= 0 -> {
                    writeOutputSample(codec, outputIndex, bufferInfo, kind)
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    private fun drainUntilEnd(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo, kind: TrackKind) {
        var idlePolls = 0
        while (idlePolls < FINAL_DRAIN_POLLS) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, ENCODER_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> idlePolls += 1
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> registerTrack(kind, codec.outputFormat)
                outputIndex >= 0 -> {
                    idlePolls = 0
                    writeOutputSample(codec, outputIndex, bufferInfo, kind)
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    private fun registerTrack(kind: TrackKind, mediaFormat: MediaFormat) {
        synchronized(muxerLock) {
            val activeMuxer = muxer ?: return
            when (kind) {
                TrackKind.Video -> {
                    if (videoTrack != null) return
                    videoTrack = activeMuxer.addTrack(mediaFormat.toMedia3VideoFormat(width, height, fps))
                    logger("muxed video track added format=$mediaFormat", null)
                }

                TrackKind.Audio -> {
                    if (audioTrack != null) return
                    audioTrack = activeMuxer.addTrack(mediaFormat.toMedia3AudioFormat())
                    logger("muxed audio track added format=$mediaFormat", null)
                }
            }
            flushPendingSamplesLocked()
        }
    }

    private fun writeOutputSample(
        codec: MediaCodec,
        outputIndex: Int,
        sourceInfo: MediaCodec.BufferInfo,
        kind: TrackKind,
    ) {
        if (sourceInfo.size <= 0 || (sourceInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
        val outputBuffer = codec.getOutputBuffer(outputIndex) ?: return
        val data = ByteArray(sourceInfo.size)
        outputBuffer.position(sourceInfo.offset)
        outputBuffer.limit(sourceInfo.offset + sourceInfo.size)
        outputBuffer.get(data)
        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, normalizedPresentationTimeUs(kind, sourceInfo.presentationTimeUs), sourceInfo.flags)
        }
        synchronized(muxerLock) {
            if (!tracksReadyLocked()) {
                pendingSamples += EncodedSample(kind, data, info)
                return
            }
            writeSampleLocked(EncodedSample(kind, data, info))
        }
    }

    private fun flushPendingSamplesLocked() {
        if (!tracksReadyLocked()) return
        while (pendingSamples.isNotEmpty()) {
            writeSampleLocked(pendingSamples.removeFirst())
        }
        reportReadyLocked()
    }

    private fun writeSampleLocked(sample: EncodedSample) {
        val activeMuxer = muxer ?: return
        val token = when (sample.kind) {
            TrackKind.Video -> videoTrack
            TrackKind.Audio -> audioTrack
        } ?: return
        runCatching {
            activeMuxer.writeSampleData(token, ByteBuffer.wrap(sample.data), sample.info)
        }.onFailure {
            logger("muxed sample write failed kind=${sample.kind}", it)
            running.set(false)
        }
        reportReadyLocked()
    }

    private fun normalizedPresentationTimeUs(kind: TrackKind, rawPtsUs: Long): Long {
        val basePtsUs = when (kind) {
            TrackKind.Video -> videoBasePtsUs
            TrackKind.Audio -> audioBasePtsUs
        } ?: run {
            when (kind) {
                TrackKind.Video -> videoBasePtsUs = rawPtsUs
                TrackKind.Audio -> audioBasePtsUs = rawPtsUs
            }
            logger("muxed ${kind.name.lowercase()} timestamp base=$rawPtsUs", null)
            rawPtsUs
        }

        val normalized = (rawPtsUs - basePtsUs).coerceAtLeast(0L)
        return when (kind) {
            TrackKind.Video -> {
                val adjusted = if (normalized <= lastVideoPtsUs) {
                    lastVideoPtsUs + (1_000_000L / fps.coerceAtLeast(1))
                } else {
                    normalized
                }
                lastVideoPtsUs = adjusted
                adjusted
            }

            TrackKind.Audio -> {
                val adjusted = if (normalized <= lastAudioPtsUs) lastAudioPtsUs + 1L else normalized
                lastAudioPtsUs = adjusted
                adjusted
            }
        }
    }

    private fun reportReadyLocked() {
        if (readyReported || !tracksReadyLocked()) return
        readyReported = true
        onReady(outputFile)
    }

    private fun tracksReadyLocked(): Boolean =
        videoTrack != null && (!expectAudioTrack || audioTrack != null)

    private fun inputBufferHasAudio(buffer: ByteBuffer, bytesRead: Int): Boolean {
        val oldPosition = buffer.position()
        val oldLimit = buffer.limit()
        buffer.flip()
        var index = 0
        while (index + 1 < bytesRead && buffer.remaining() >= 2) {
            val low = buffer.get().toInt() and 0xff
            val high = buffer.get().toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            if (abs(sample) > SILENCE_THRESHOLD) {
                buffer.position(oldPosition)
                buffer.limit(oldLimit)
                return true
            }
            index += 2
        }
        buffer.position(oldPosition)
        buffer.limit(oldLimit)
        return false
    }

    private fun joinThread(thread: Thread?) {
        if (thread == null || thread == Thread.currentThread()) return
        runCatching { thread.join(THREAD_JOIN_TIMEOUT_MS) }
    }

    private enum class TrackKind {
        Video,
        Audio,
    }

    private data class EncodedSample(
        val kind: TrackKind,
        val data: ByteArray,
        val info: MediaCodec.BufferInfo,
    )

    private companion object {
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNEL_COUNT = 2
        private const val AUDIO_BITRATE = 128_000
        private const val BYTES_PER_SAMPLE = 2
        private const val ENCODER_TIMEOUT_US = 10_000L
        private const val FINAL_DRAIN_POLLS = 20
        private const val FRAGMENT_DURATION_MS = 1_000L
        private const val VIDEO_KEYFRAME_INTERVAL_SECONDS = 1
        private const val THREAD_JOIN_TIMEOUT_MS = 1_000L
        private const val SILENCE_THRESHOLD = 8
    }
}

private fun MediaFormat.toMedia3VideoFormat(width: Int, height: Int, fps: Int): Format =
    Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(width)
        .setHeight(height)
        .setFrameRate(fps.toFloat())
        .setInitializationData(copyCsdBuffers())
        .build()

private fun MediaFormat.toMedia3AudioFormat(): Format =
    Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC)
        .setChannelCount(getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        .setSampleRate(getInteger(MediaFormat.KEY_SAMPLE_RATE))
        .setInitializationData(copyCsdBuffers())
        .build()

private fun MediaFormat.copyCsdBuffers(): List<ByteArray> {
    val data = mutableListOf<ByteArray>()
    var index = 0
    while (containsKey("csd-$index")) {
        val buffer = getByteBuffer("csd-$index") ?: break
        val duplicate = buffer.duplicate()
        duplicate.position(0)
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        data += bytes
        index += 1
    }
    return data
}
