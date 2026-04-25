package com.ghoststream.core.model

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable

@Serializable
enum class LiveScreenStatus {
    STOPPED,
    STARTING,
    LIVE,
    ERROR,
}

@Serializable
enum class LiveAudioStatus {
    AUDIO_SUPPORTED,
    AUDIO_INITIALIZING,
    AUDIO_LIVE,
    AUDIO_SILENT,
    AUDIO_UNAVAILABLE,
    AUDIO_FAILED,
}

@Serializable
data class LiveScreenSessionState(
    val status: LiveScreenStatus = LiveScreenStatus.STOPPED,
    val sessionUrl: String? = null,
    val displayUrl: String? = null,
    val pin: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int = 20,
    val bitrateKbps: Int = 2_000,
    val audioStatus: LiveAudioStatus = LiveAudioStatus.AUDIO_UNAVAILABLE,
    val lastError: String? = null,
    val startedAtEpochMs: Long? = null,
    val viewerCount: Int = 0,
    val viewerName: String? = null,
) {
    val isActive: Boolean
        get() = status == LiveScreenStatus.STARTING || status == LiveScreenStatus.LIVE
}

@Serializable
enum class LiveViewerStatus {
    WAITING,
    CONNECTING,
    LIVE,
    RECONNECTING,
    ENDED,
    BUSY,
    PIN_REQUIRED,
}

@Serializable
data class LiveViewerSessionRegistration(
    val accepted: Boolean,
    val viewerId: String? = null,
    val status: LiveViewerStatus,
    val requiresPin: Boolean = false,
    val message: String? = null,
)

@Serializable
data class LiveWebRtcOffer(
    val viewerId: String,
    val viewerName: String,
    val clientIp: String,
    val sdp: String,
)

@Serializable
data class LiveWebRtcOfferRequest(
    val viewerId: String,
    val viewerName: String,
    val clientIp: String,
)

@Serializable
data class LiveWebRtcAndroidOffer(
    val viewerId: String,
    val sdp: String,
    val audioEnabled: Boolean,
)

@Serializable
data class LiveWebRtcAnswer(
    val viewerId: String,
    val sdp: String,
    val audioEnabled: Boolean,
)

@Serializable
data class LiveWebRtcBrowserAnswer(
    val viewerId: String,
    val sdp: String,
)

@Serializable
data class LiveIceCandidatePayload(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String,
)

data class LiveMuxedStreamInfo(
    val filePath: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val fragmentDurationSeconds: Double,
    val startedAtEpochMs: Long,
)

sealed interface LiveScreenStreamPacket {
    data class Format(
        val width: Int,
        val height: Int,
        val codec: String,
        val sps: ByteArray,
        val pps: ByteArray,
    ) : LiveScreenStreamPacket

    data class VideoFrame(
        val data: ByteArray,
        val timestampUs: Long,
        val isKeyFrame: Boolean,
    ) : LiveScreenStreamPacket
}

interface LiveScreenSessionStore {
    val state: StateFlow<LiveScreenSessionState>
    val streamPackets: SharedFlow<LiveScreenStreamPacket>
    val latestFormat: LiveScreenStreamPacket.Format?

    fun updateState(transform: (LiveScreenSessionState) -> LiveScreenSessionState)
    fun updateMuxedStream(info: LiveMuxedStreamInfo?)
    fun currentMuxedStream(): LiveMuxedStreamInfo?
    suspend fun emitPacket(packet: LiveScreenStreamPacket)
    fun clearStream()
    suspend fun registerViewer(clientIp: String, viewerName: String, pin: String?): LiveViewerSessionRegistration
    suspend fun consumeViewerOfferRequest(): LiveWebRtcOfferRequest?
    suspend fun submitAndroidOffer(viewerId: String, sdp: String, audioEnabled: Boolean): Boolean
    suspend fun consumeAndroidOffer(viewerId: String): LiveWebRtcAndroidOffer?
    suspend fun submitBrowserAnswer(viewerId: String, sdp: String): Boolean
    suspend fun consumeBrowserAnswer(viewerId: String): LiveWebRtcBrowserAnswer?
    suspend fun submitViewerOffer(viewerId: String, sdp: String): Boolean
    suspend fun consumeViewerOffer(): LiveWebRtcOffer?
    suspend fun submitAndroidAnswer(viewerId: String, sdp: String, audioEnabled: Boolean): Boolean
    suspend fun consumeViewerAnswer(viewerId: String): LiveWebRtcAnswer?
    suspend fun addViewerIceCandidate(viewerId: String, candidate: LiveIceCandidatePayload): Boolean
    suspend fun drainViewerIceCandidates(viewerId: String): List<LiveIceCandidatePayload>
    suspend fun addAndroidIceCandidate(viewerId: String, candidate: LiveIceCandidatePayload): Boolean
    suspend fun drainAndroidIceCandidates(viewerId: String): List<LiveIceCandidatePayload>
    suspend fun updateViewerStatus(viewerId: String, status: LiveViewerStatus)
    suspend fun disconnectViewer(viewerId: String)
}

@Serializable
enum class QuickTextTargetType {
    DEVICE,
    BROADCAST,
}

@Serializable
data class QuickTextMessage(
    val id: String,
    val text: String,
    val senderId: String,
    val senderName: String,
    val targetType: QuickTextTargetType,
    val targetId: String? = null,
    val targetName: String? = null,
    val timestampMs: Long,
) {
    val isLink: Boolean
        get() = text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
}

@Serializable
data class QuickTextDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val isHostPhone: Boolean = false,
    val isCurrentDevice: Boolean = false,
)

fun browserDeviceName(
    userAgent: String?,
    fallbackIp: String,
    isHostPhone: Boolean = false,
): String {
    if (isHostPhone) return "This Phone"
    val ua = userAgent.orEmpty()
    val browser = when {
        ua.contains("Edg/", ignoreCase = true) -> "Edge"
        ua.contains("Firefox", ignoreCase = true) -> "Firefox"
        ua.contains("Chrome", ignoreCase = true) || ua.contains("CriOS", ignoreCase = true) -> "Chrome"
        ua.contains("Safari", ignoreCase = true) -> "Safari"
        else -> "Browser"
    }
    val device = when {
        ua.contains("Android", ignoreCase = true) && ua.contains("Mobile", ignoreCase = true) -> "Phone"
        ua.contains("Android", ignoreCase = true) -> "Tablet"
        ua.contains("iPad", ignoreCase = true) -> "Tablet"
        ua.contains("iPhone", ignoreCase = true) -> "Phone"
        ua.contains("Windows", ignoreCase = true) -> "Windows"
        ua.contains("Mac OS", ignoreCase = true) || ua.contains("Macintosh", ignoreCase = true) -> "Mac"
        ua.contains("Linux", ignoreCase = true) -> "Linux"
        else -> fallbackIp
    }
    return "$browser on $device"
}
