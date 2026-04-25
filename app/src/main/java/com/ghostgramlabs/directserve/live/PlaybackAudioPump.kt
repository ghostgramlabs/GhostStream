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
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Synchronous wrapper around an [AudioPlaybackCaptureConfiguration]-backed
 * [AudioRecord]. Designed to be driven from WebRTC's audio buffer callback
 * thread: the callback already runs at the microphone's 10 ms cadence, so we
 * simply [overwriteWithDeviceAudio] the mic PCM in place with freshly-read
 * playback PCM. WebRTC then stamps the resulting audio with the mic's own
 * capture clock — which is what keeps audio in sync with the screen-capture
 * video track, because both tracks then share the same underlying timebase.
 *
 * This mirrors the ScreenStream / Stream.io pattern: do not fight WebRTC's
 * internal audio pacing, ride on top of it.
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
    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var reusableBuffer: ByteBuffer? = null

    @Volatile
    private var activityReported: Boolean = false

    init {
        require(sampleRate > 0)
        require(channelCount == 1 || channelCount == 2)
    }

    fun start(): Boolean {
        val channelMask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            onStatus(LiveAudioStatus.AUDIO_FAILED, "min_buffer_size_invalid_$minBuf")
            return false
        }
        // Keep the AudioRecord buffer snug so we never drag behind WebRTC's
        // 10 ms read cadence by more than a handful of milliseconds.
        val bufferBytes = maxOf(minBuf, bytesPerFrame() * sampleRate * AUDIO_RECORD_BUFFER_MS / 1000)

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

        runCatching { record.startRecording() }.onFailure {
            logger("playback AudioRecord startRecording failed", it)
            runCatching { record.release() }
            onStatus(LiveAudioStatus.AUDIO_FAILED, "playback_audiorecord_start_failed")
            return false
        }

        audioRecord = record
        logger(
            "playback AudioRecord ready sampleRate=$sampleRate channels=$channelCount bufferBytes=$bufferBytes",
            null,
        )
        onStatus(LiveAudioStatus.AUDIO_INITIALIZING, "playback_pump_started")
        return true
    }

    fun stop() {
        val record = audioRecord
        audioRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        reusableBuffer = null
        activityReported = false
    }

    /**
     * Reads [bytesToFill] bytes of device playback PCM and writes them into
     * [dst] starting at absolute index 0, leaving [dst]'s position and limit
     * untouched (WebRTC is responsible for those). Returns the peak PCM-16
     * sample magnitude observed, or -1 if the pump is not yet producing audio
     * (caller should treat this as "keep mic PCM silent" — do NOT let the
     * untouched mic audio leak out).
     */
    fun overwriteWithDeviceAudio(dst: ByteBuffer, bytesToFill: Int): Int {
        val record = audioRecord ?: return -1
        val capacityNeeded = bytesToFill.coerceAtMost(dst.capacity())
        if (capacityNeeded <= 0) return -1
        val temp = obtainReusableBuffer(capacityNeeded)
        temp.clear()
        val readBytes = runCatching {
            record.read(temp, capacityNeeded, AudioRecord.READ_BLOCKING)
        }.getOrElse { err ->
            logger("playback AudioRecord read threw", err)
            onStatus(LiveAudioStatus.AUDIO_FAILED, "playback_read_exception")
            return -1
        }
        if (readBytes <= 0) {
            if (readBytes == AudioRecord.ERROR_INVALID_OPERATION || readBytes == AudioRecord.ERROR_BAD_VALUE) {
                logger("playback AudioRecord read error code=$readBytes", null)
                onStatus(LiveAudioStatus.AUDIO_FAILED, "playback_read_error_$readBytes")
            }
            return -1
        }

        temp.order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0
        var i = 0
        while (i + 1 < readBytes) {
            val sample = temp.getShort(i).toInt()
            peak = maxOf(peak, abs(sample))
            i += 2
        }
        // Overwrite dst[0..readBytes] using absolute positioning so WebRTC's
        // position/limit state is preserved exactly as it handed us the buffer.
        for (idx in 0 until readBytes) {
            dst.put(idx, temp.get(idx))
        }
        // If the mic read was shorter than what WebRTC is expecting, pad the
        // remainder with silence so no stale mic PCM leaks out.
        if (readBytes < bytesToFill) {
            for (idx in readBytes until bytesToFill.coerceAtMost(dst.capacity())) {
                dst.put(idx, 0)
            }
        }

        if (!activityReported && peak > SILENCE_THRESHOLD) {
            activityReported = true
            onActivity()
        }
        return peak
    }

    private fun obtainReusableBuffer(capacity: Int): ByteBuffer {
        val existing = reusableBuffer
        if (existing != null && existing.capacity() >= capacity) return existing
        val fresh = ByteBuffer.allocateDirect(capacity)
        reusableBuffer = fresh
        return fresh
    }

    private fun bytesPerFrame(): Int = BYTES_PER_SAMPLE * channelCount

    companion object {
        private const val BYTES_PER_SAMPLE = 2
        private const val SILENCE_THRESHOLD = 8
        private const val AUDIO_RECORD_BUFFER_MS = 20
    }
}
