package com.ghostgramlabs.directserve.live

import com.ghoststream.core.model.LiveIceCandidatePayload
import com.ghoststream.core.model.LiveMuxedStreamInfo
import com.ghoststream.core.model.LiveScreenSessionState
import com.ghoststream.core.model.LiveScreenSessionStore
import com.ghoststream.core.model.LiveScreenStatus
import com.ghoststream.core.model.LiveScreenStreamPacket
import com.ghoststream.core.model.LiveViewerSessionRegistration
import com.ghoststream.core.model.LiveViewerStatus
import com.ghoststream.core.model.LiveWebRtcAndroidOffer
import com.ghoststream.core.model.LiveWebRtcAnswer
import com.ghoststream.core.model.LiveWebRtcBrowserAnswer
import com.ghoststream.core.model.LiveWebRtcOffer
import com.ghoststream.core.model.LiveWebRtcOfferRequest
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LiveScreenManager : LiveScreenSessionStore {
    private val _state = MutableStateFlow(LiveScreenSessionState())
    private val _streamPackets = MutableSharedFlow<LiveScreenStreamPacket>(
        replay = 1,
        extraBufferCapacity = 32,
    )
    private val signalingMutex = Mutex()
    private var viewerSession: ViewerSession? = null
    private var muxedStream: LiveMuxedStreamInfo? = null

    override val state: StateFlow<LiveScreenSessionState> = _state.asStateFlow()
    override val streamPackets: SharedFlow<LiveScreenStreamPacket> = _streamPackets.asSharedFlow()
    override var latestFormat: LiveScreenStreamPacket.Format? = null
        private set

    override fun updateState(transform: (LiveScreenSessionState) -> LiveScreenSessionState) {
        _state.value = transform(_state.value)
    }

    override fun updateMuxedStream(info: LiveMuxedStreamInfo?) {
        muxedStream = info
    }

    override fun currentMuxedStream(): LiveMuxedStreamInfo? = muxedStream

    override suspend fun emitPacket(packet: LiveScreenStreamPacket) {
        if (packet is LiveScreenStreamPacket.Format) {
            latestFormat = packet
        }
        _streamPackets.emit(packet)
    }

    override fun clearStream() {
        latestFormat = null
        viewerSession = null
        muxedStream = null
        _state.value = LiveScreenSessionState(status = LiveScreenStatus.STOPPED)
    }

    override suspend fun registerViewer(
        clientIp: String,
        viewerName: String,
        pin: String?,
    ): LiveViewerSessionRegistration = signalingMutex.withLock {
        val sessionState = _state.value
        if (!sessionState.isActive) {
            return LiveViewerSessionRegistration(
                accepted = false,
                status = LiveViewerStatus.ENDED,
            )
        }
        val requiredPin = sessionState.pin
        if (!requiredPin.isNullOrBlank() && pin != requiredPin) {
            return LiveViewerSessionRegistration(
                accepted = false,
                status = LiveViewerStatus.PIN_REQUIRED,
                requiresPin = true,
            )
        }

        val existing = viewerSession
        if (existing != null && existing.clientIp != clientIp) {
            return LiveViewerSessionRegistration(
                accepted = false,
                status = LiveViewerStatus.BUSY,
            )
        }

        val newViewer = ViewerSession(
            viewerId = UUID.randomUUID().toString(),
            clientIp = clientIp,
            viewerName = viewerName,
        )
        viewerSession = newViewer
        _state.value = _state.value.copy(
            viewerCount = 0,
            viewerName = viewerName,
        )
        LiveViewerSessionRegistration(
            accepted = true,
            viewerId = newViewer.viewerId,
            status = LiveViewerStatus.WAITING,
        )
    }

    override suspend fun consumeViewerOfferRequest(): LiveWebRtcOfferRequest? = signalingMutex.withLock {
        val existing = viewerSession ?: return null
        if (existing.androidOfferRequested || existing.pendingAndroidOffer != null) return null
        viewerSession = existing.copy(
            androidOfferRequested = true,
            status = LiveViewerStatus.CONNECTING,
        )
        _state.value = _state.value.copy(
            viewerCount = 0,
            viewerName = existing.viewerName,
        )
        LiveWebRtcOfferRequest(
            viewerId = existing.viewerId,
            viewerName = existing.viewerName,
            clientIp = existing.clientIp,
        )
    }

    override suspend fun submitAndroidOffer(
        viewerId: String,
        sdp: String,
        audioEnabled: Boolean,
    ): Boolean = signalingMutex.withLock {
        val existing = viewerSession ?: return false
        if (existing.viewerId != viewerId) return false
        viewerSession = existing.copy(
            pendingAndroidOffer = LiveWebRtcAndroidOffer(
                viewerId = viewerId,
                sdp = sdp,
                audioEnabled = audioEnabled,
            ),
            status = LiveViewerStatus.CONNECTING,
        )
        true
    }

    override suspend fun consumeAndroidOffer(viewerId: String): LiveWebRtcAndroidOffer? = signalingMutex.withLock {
        val existing = viewerSession ?: return null
        if (existing.viewerId != viewerId) return null
        val offer = existing.pendingAndroidOffer ?: return null
        viewerSession = existing.copy(pendingAndroidOffer = null)
        offer
    }

    override suspend fun submitBrowserAnswer(viewerId: String, sdp: String): Boolean = signalingMutex.withLock {
        val existing = viewerSession ?: return false
        if (existing.viewerId != viewerId) return false
        viewerSession = existing.copy(
            pendingBrowserAnswer = LiveWebRtcBrowserAnswer(
                viewerId = viewerId,
                sdp = sdp,
            ),
            status = LiveViewerStatus.CONNECTING,
        )
        true
    }

    override suspend fun consumeBrowserAnswer(viewerId: String): LiveWebRtcBrowserAnswer? = signalingMutex.withLock {
        val existing = viewerSession ?: return null
        if (existing.viewerId != viewerId) return null
        val answer = existing.pendingBrowserAnswer ?: return null
        viewerSession = existing.copy(pendingBrowserAnswer = null)
        answer
    }

    override suspend fun submitViewerOffer(viewerId: String, sdp: String): Boolean = signalingMutex.withLock {
        val existing = viewerSession ?: return false
        if (existing.viewerId != viewerId) return false
        viewerSession = existing.copy(
            pendingOfferSdp = sdp,
            pendingAnswer = null,
            browserIce = mutableListOf(),
            androidIce = mutableListOf(),
            status = LiveViewerStatus.CONNECTING,
        )
        _state.value = _state.value.copy(
            viewerCount = 0,
            viewerName = existing.viewerName,
        )
        true
    }

    override suspend fun consumeViewerOffer(): LiveWebRtcOffer? = signalingMutex.withLock {
        val existing = viewerSession ?: return null
        val sdp = existing.pendingOfferSdp ?: return null
        viewerSession = existing.copy(pendingOfferSdp = null)
        LiveWebRtcOffer(
            viewerId = existing.viewerId,
            viewerName = existing.viewerName,
            clientIp = existing.clientIp,
            sdp = sdp,
        )
    }

    override suspend fun submitAndroidAnswer(
        viewerId: String,
        sdp: String,
        audioEnabled: Boolean,
    ): Boolean = signalingMutex.withLock {
        val existing = viewerSession ?: return false
        if (existing.viewerId != viewerId) return false
        viewerSession = existing.copy(
            pendingAnswer = LiveWebRtcAnswer(
                viewerId = viewerId,
                sdp = sdp,
                audioEnabled = audioEnabled,
            ),
        )
        true
    }

    override suspend fun consumeViewerAnswer(viewerId: String): LiveWebRtcAnswer? = signalingMutex.withLock {
        val existing = viewerSession ?: return null
        if (existing.viewerId != viewerId) return null
        val answer = existing.pendingAnswer ?: return null
        viewerSession = existing.copy(pendingAnswer = null)
        answer
    }

    override suspend fun addViewerIceCandidate(
        viewerId: String,
        candidate: LiveIceCandidatePayload,
    ): Boolean = signalingMutex.withLock {
        val existing = viewerSession ?: return false
        if (existing.viewerId != viewerId) return false
        existing.browserIce += candidate
        true
    }

    override suspend fun drainViewerIceCandidates(viewerId: String): List<LiveIceCandidatePayload> = signalingMutex.withLock {
        val existing = viewerSession ?: return emptyList()
        if (existing.viewerId != viewerId) return emptyList()
        val candidates = existing.browserIce.toList()
        existing.browserIce.clear()
        candidates
    }

    override suspend fun addAndroidIceCandidate(
        viewerId: String,
        candidate: LiveIceCandidatePayload,
    ): Boolean = signalingMutex.withLock {
        val existing = viewerSession ?: return false
        if (existing.viewerId != viewerId) return false
        existing.androidIce += candidate
        true
    }

    override suspend fun drainAndroidIceCandidates(viewerId: String): List<LiveIceCandidatePayload> = signalingMutex.withLock {
        val existing = viewerSession ?: return emptyList()
        if (existing.viewerId != viewerId) return emptyList()
        val candidates = existing.androidIce.toList()
        existing.androidIce.clear()
        candidates
    }

    override suspend fun updateViewerStatus(viewerId: String, status: LiveViewerStatus) {
        signalingMutex.withLock {
            val existing = viewerSession ?: return
            if (existing.viewerId != viewerId) return
            viewerSession = existing.copy(status = status)
            _state.value = _state.value.copy(
                viewerCount = if (status == LiveViewerStatus.LIVE) 1 else 0,
                viewerName = existing.viewerName,
            )
        }
    }

    override suspend fun disconnectViewer(viewerId: String) {
        signalingMutex.withLock {
            val existing = viewerSession ?: return
            if (existing.viewerId != viewerId) return
            viewerSession = null
            _state.value = _state.value.copy(
                viewerCount = 0,
                viewerName = null,
            )
        }
    }

    private data class ViewerSession(
        val viewerId: String,
        val clientIp: String,
        val viewerName: String,
        val androidOfferRequested: Boolean = false,
        val pendingAndroidOffer: LiveWebRtcAndroidOffer? = null,
        val pendingBrowserAnswer: LiveWebRtcBrowserAnswer? = null,
        val pendingOfferSdp: String? = null,
        val pendingAnswer: LiveWebRtcAnswer? = null,
        val browserIce: MutableList<LiveIceCandidatePayload> = mutableListOf(),
        val androidIce: MutableList<LiveIceCandidatePayload> = mutableListOf(),
        val status: LiveViewerStatus = LiveViewerStatus.WAITING,
    )
}
