package com.ghostgramlabs.directserve.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import com.ghoststream.core.model.LiveAudioStatus
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min

/**
 * Captures system playback audio via MediaProjection and buffers PCM for
 * injection into WebRTC's audio pipeline through [JavaAudioDeviceModule]'s
 * AudioBufferCallback. Opens a separate AudioRecord on the USAGE_MEDIA /
 * USAGE_GAME playback streams rather than the microphone.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackAudioPump(
    private val projection: MediaProjection,
    val sampleRate: Int,
    val channelCount: Int,
    private val onStatus: (LiveAudioStatus, String) -> Unit,
    private val onActivity: () -> Unit,
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val ring: ByteArray
    private val ringCapacity: Int
    private val ringLock = Any()
    private var ringHead: Int = 0
    private var ringSize: Int = 0
    private var activityReported: Boolean = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var keepAlive: Boolean = false

    private var readerThread: Thread? = null

    init {
        require(sampleRate > 0)
        require(channelCount == 1 || channelCount == 2)
        // Hold ~2 seconds of PCM16 so we can absorb jitter without blocking WebRTC.
        ringCapacity = sampleRate * channelCount * BYTES_PER_SAMPLE * 2
        ring = ByteArray(ringCapacity)
    }

    fun start(): Boolean {
        val channelMask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            onStatus(LiveAudioStatus.AUDIO_FAILED, "min_buffer_size_invalid_$minBuf")
            return false
        }
        val bufferBytes = maxOf(minBuf, bytesPerFrame() * (sampleRate / 10)) // ~100ms

        val config = runCatching {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        }.getOrElse {
            logger("playback capture config build failed", it)
            onStatus(LiveAudioStatus.AUDIO_FAILED, "playback_config_build_failed")
            return false
        }

        val record = runCatching {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }.getOrElse {
            logger("playback AudioRecord build failed", it)
            onStatus(LiveAudioStatus.AUDIO_FAILED, "playback_audiorecord_build_failed")
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            onStatus(LiveAudioStatus.AUDIO_FAILED, "playback_audiorecord_uninitialized")
            return false
        }

        val actualSampleRate = record.sampleRate
        val actualChannelCount = record.channelCount
        if (actualSampleRate != sampleRate || actualChannelCount != channelCount) {
            logger(
                "playback AudioRecord format mismatch requested=${sampleRate}Hz/${channelCount}ch actual=${actualSampleRate}Hz/${actualChannelCount}ch",
                null,
            )
        } else {
            logger(
                "playback AudioRecord format confirmed sampleRate=${actualSampleRate}Hz channels=$actualChannelCount bufferBytes=$bufferBytes",
                null,
            )
        }

        audioRecord = record
        keepAlive = true
        runCatching { record.startRecording() }.onFailure {
            logger("playback AudioRecord startRecording failed", it)
            runCatching { record.release() }
            audioRecord = null
            onStatus(LiveAudioStatus.AUDIO_FAILED, "playback_audiorecord_start_failed")
            return false
        }

        val thread = Thread({ readLoop(bufferBytes) }, "DirectServeAudioPump")
        thread.priority = Thread.NORM_PRIORITY + 2
        thread.isDaemon = true
        readerThread = thread
        thread.start()

        onStatus(LiveAudioStatus.AUDIO_INITIALIZING, "playback_pump_started")
        return true
    }

    fun stop() {
        keepAlive = false
        val record = audioRecord
        audioRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        readerThread?.let { thread ->
            runCatching { thread.join(THREAD_JOIN_TIMEOUT_MS) }
        }
        readerThread = null
        synchronized(ringLock) {
            ringHead = 0
            ringSize = 0
        }
    }

    /**
     * Fills [dst] with PCM from the ring buffer, padding with silence if we
     * are short on samples. WebRTC hands the buffer to us after its own
     * read(), so we rewind to capacity-length writes from offset zero — that
     * is what the native side will submit via the cached direct-buffer
     * pointer. Returns the peak absolute PCM sample copied into WebRTC.
     */
    fun pull(dst: ByteBuffer): Int {
        val capacity = dst.capacity()
        if (capacity == 0) return 0
        dst.clear()
        synchronized(ringLock) {
            val available = min(ringSize, capacity)
            var peak = 0
            if (available > 0) {
                peak = copyOutLocked(dst, available)
                ringSize -= available
                ringHead = (ringHead + available) % ringCapacity
            }
            val padding = capacity - available
            if (padding > 0) {
                val zeros = ByteArray(padding)
                dst.put(zeros)
            }
            return peak
        }
    }

    private fun copyOutLocked(dst: ByteBuffer, count: Int): Int {
        val first = min(count, ringCapacity - ringHead)
        dst.put(ring, ringHead, first)
        var peak = peakPcm16(ring, ringHead, first)
        val remainder = count - first
        if (remainder > 0) {
            dst.put(ring, 0, remainder)
            peak = maxOf(peak, peakPcm16(ring, 0, remainder))
        }
        return peak
    }

    private fun readLoop(bufferBytes: Int) {
        val buffer = ByteArray(bufferBytes)
        while (keepAlive) {
            val record = audioRecord ?: break
            val bytesRead = runCatching { record.read(buffer, 0, buffer.size) }.getOrElse { err ->
                logger("playback AudioRecord read threw", err)
                onStatus(LiveAudioStatus.AUDIO_FAILED, "playback_read_exception")
                return
            }
            if (bytesRead <= 0) {
                if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION || bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                    logger("playback AudioRecord read error code=$bytesRead", null)
                    onStatus(LiveAudioStatus.AUDIO_FAILED, "playback_read_error_$bytesRead")
                    return
                }
                continue
            }
            appendToRing(buffer, bytesRead)
            if (!activityReported && hasNonSilentSample(buffer, bytesRead)) {
                activityReported = true
                onActivity()
            }
        }
    }

    private fun appendToRing(data: ByteArray, length: Int) {
        synchronized(ringLock) {
            val copyLength = min(length, ringCapacity)
            val srcOffset = length - copyLength
            val writeStart = (ringHead + ringSize) % ringCapacity
            val first = min(copyLength, ringCapacity - writeStart)
            System.arraycopy(data, srcOffset, ring, writeStart, first)
            val remainder = copyLength - first
            if (remainder > 0) {
                System.arraycopy(data, srcOffset + first, ring, 0, remainder)
            }
            val newSize = ringSize + copyLength
            if (newSize > ringCapacity) {
                val overflow = newSize - ringCapacity
                ringHead = (ringHead + overflow) % ringCapacity
                ringSize = ringCapacity
            } else {
                ringSize = newSize
            }
        }
    }

    private fun hasNonSilentSample(buffer: ByteArray, length: Int): Boolean {
        return peakPcm16(buffer, 0, length) > SILENCE_THRESHOLD
    }

    private fun peakPcm16(buffer: ByteArray, offset: Int, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val index = offset + i
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xff)).toShort().toInt()
            peak = maxOf(peak, abs(sample))
            i += 2
        }
        return peak
    }

    private fun bytesPerFrame(): Int = BYTES_PER_SAMPLE * channelCount

    companion object {
        private const val BYTES_PER_SAMPLE = 2
        private const val SILENCE_THRESHOLD = 8
        private const val THREAD_JOIN_TIMEOUT_MS = 500L
    }
}
