package com.ghostgramlabs.directserve.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ghostgramlabs.directserve.GhostStreamApplication
import com.ghostgramlabs.directserve.MainActivity
import com.ghostgramlabs.directserve.R
import com.ghostgramlabs.directserve.core.resources.R as SharedR
import com.ghostgramlabs.directserve.live.PlaybackAudioPump
import com.ghostgramlabs.directserve.live.LiveScreenMuxedStreamer
import com.ghoststream.core.model.LiveAudioStatus
import com.ghoststream.core.model.LiveIceCandidatePayload
import com.ghoststream.core.model.LiveMuxedStreamInfo
import com.ghoststream.core.model.LiveScreenStatus
import com.ghoststream.core.model.LiveViewerStatus
import com.ghoststream.core.model.LiveWebRtcOfferRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStreamTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpCapabilities
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

class LiveScreenCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val container by lazy { (application as GhostStreamApplication).container }

    private var captureJob: Job? = null
    private var signalingJob: Job? = null
    private var displayManager: DisplayManager? = null

    private var eglBase: EglBase? = null
    private var peerFactory: PeerConnectionFactory? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var audioTrack: org.webrtc.AudioTrack? = null
    private var peerConnection: PeerConnection? = null
    private var audioSenderAttached: Boolean = false
    private var remoteAnswerSet: Boolean = false
    private var submittedAndroidOfferViewerId: String? = null
    private var submittedAndroidAnswerViewerId: String? = null
    private val pendingViewerIce = mutableListOf<LiveIceCandidatePayload>()
    private var activeViewerId: String? = null
    private var activeProjection: MediaProjection? = null
    @Volatile
    private var playbackAudioPump: PlaybackAudioPump? = null
    private var muxedStreamer: LiveScreenMuxedStreamer? = null
    @Volatile
    private var webrtcAudioFormat: WebRtcAudioFormat? = null
    @Volatile
    private var audioObserved: Boolean = false
    @Volatile
    private var videoFrameObserved: Boolean = false
    @Volatile
    private var isStopping: Boolean = false
    private var silenceWatchJob: Job? = null
    private var displayChangeJob: Job? = null
    private val audioPacingLock = Any()
    private var nextAudioInjectionNs: Long = 0L

    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    private var captureFps: Int = 20
    private var captureBitrateKbps: Int = 2_000
    private var audioStatus: LiveAudioStatus = LiveAudioStatus.AUDIO_UNAVAILABLE

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != 0) return
            displayChangeJob?.cancel()
            displayChangeJob = serviceScope.launch {
                delay(DISPLAY_CHANGE_DEBOUNCE_MS)
                applyDisplayChange()
            }
        }
    }

    private fun applyDisplayChange() {
        if (isStopping) return
        val capturer = screenCapturer ?: return
        val metrics = currentMetrics()
        val target = chooseCaptureSize(metrics.widthPixels, metrics.heightPixels)
        if (target.first == captureWidth && target.second == captureHeight) return
        captureWidth = target.first
        captureHeight = target.second
        captureBitrateKbps = estimateCaptureBitrateKbps(captureWidth, captureHeight, captureFps)
        runCatching { capturer.changeCaptureFormat(captureWidth, captureHeight, captureFps) }
        retuneActiveVideoSenders()
        container.debugLogRepository.log(
            "LiveScreen",
            "display changed width=$captureWidth height=$captureHeight fps=$captureFps bitrate=$captureBitrateKbps",
        )
        container.liveScreenManager.updateState {
            it.copy(
                width = captureWidth,
                height = captureHeight,
                bitrateKbps = captureBitrateKbps,
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture(null)
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val permissionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PERMISSION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PERMISSION_DATA)
                }
                if (permissionData == null || resultCode == 0) {
                    stopCapture(getString(SharedR.string.live_screen_permission_denied))
                    return START_NOT_STICKY
                }
                startForegroundNotification()
                startCapture(resultCode, permissionData)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture(null)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        createChannel()
        val openAppIntent = PendingIntent.getActivity(
            this,
            301,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            302,
            Intent(this, LiveScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(SharedR.string.live_screen_notification_title))
            .setContentText(getString(SharedR.string.live_screen_notification_body))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()

        val foregroundType = liveScreenForegroundType()
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val channelImportance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .getNotificationChannel(CHANNEL_ID)
                ?.importance
        } else {
            null
        }
        container.debugLogRepository.log(
            "LiveScreen",
            "starting foreground notification type=$foregroundType notificationsEnabled=$notificationsEnabled channelImportance=$channelImportance",
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundType,
        )
    }

    private fun startCapture(resultCode: Int, permissionData: Intent) {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            try {
                val metrics = currentMetrics()
                val target = chooseCaptureSize(metrics.widthPixels, metrics.heightPixels)
                captureWidth = target.first
                captureHeight = target.second
                captureFps = LIVE_CAPTURE_FPS
                captureBitrateKbps = estimateCaptureBitrateKbps(captureWidth, captureHeight, captureFps)
                audioStatus = detectInitialAudioStatus()
                audioObserved = false
                videoFrameObserved = false
                resetAudioInjectionPacing()

                if (MUXED_LIVE_STREAM_ENABLED) {
                    initializeMuxedCapture(resultCode, permissionData, metrics.densityDpi)
                } else {
                    initializeWebRtc(permissionData)
                    startVideoCapture()
                    startPlaybackAudioPumpWhenReady()
                }
                displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                displayManager?.registerDisplayListener(displayListener, null)

                container.debugLogRepository.log(
                    "LiveScreen",
                    "capture started width=$captureWidth height=$captureHeight fps=$captureFps bitrate=$captureBitrateKbps audio=$audioStatus",
                )
                container.liveScreenManager.updateState {
                    it.copy(
                        status = LiveScreenStatus.LIVE,
                        width = captureWidth,
                        height = captureHeight,
                        fps = captureFps,
                        bitrateKbps = captureBitrateKbps,
                        audioStatus = audioStatus,
                        lastError = null,
                    )
                }

                if (!MUXED_LIVE_STREAM_ENABLED) {
                    startSignalingLoop()
                }
            } catch (error: Exception) {
                container.debugLogRepository.log("LiveScreen", "startCapture failed", error)
                stopCapture(error.message ?: getString(SharedR.string.live_screen_failed))
            }
        }
    }

    private fun initializeMuxedCapture(resultCode: Int, permissionData: Intent, densityDpi: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("Live audio capture requires Android 10+")
        }
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, permissionData)
            ?: throw IllegalStateException("MediaProjection unavailable")
        activeProjection = projection
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    container.debugLogRepository.log("LiveScreen", "muxed media projection stopped")
                    stopCapture(getString(SharedR.string.live_screen_ended))
                }
            },
            null,
        )

        val captureAudio = audioStatus == LiveAudioStatus.AUDIO_SUPPORTED ||
            audioStatus == LiveAudioStatus.AUDIO_INITIALIZING
        val streamer = LiveScreenMuxedStreamer(
            context = this,
            projection = projection,
            width = captureWidth,
            height = captureHeight,
            densityDpi = densityDpi,
            fps = captureFps,
            videoBitrateKbps = captureBitrateKbps,
            captureAudio = captureAudio,
            onAudioStatus = { status, reason -> updateAudioStatus(status, reason) },
            onReady = { file ->
                container.debugLogRepository.log(
                    "LiveScreen",
                    "muxed live stream ready file=${file.name} bytes=${file.length()}",
                )
            },
            logger = { message, error ->
                container.debugLogRepository.log("LiveScreen", message, error)
            },
        )
        muxedStreamer = streamer
        if (!streamer.start()) {
            throw IllegalStateException("Unable to start muxed live screen stream")
        }
        container.liveScreenManager.updateMuxedStream(
            LiveMuxedStreamInfo(
                filePath = streamer.outputFile.absolutePath,
                width = captureWidth,
                height = captureHeight,
                fps = captureFps,
                bitrateKbps = captureBitrateKbps,
                fragmentDurationSeconds = streamer.fragmentDurationSeconds,
                startedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun initializeWebRtc(permissionData: Intent) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .createInitializationOptions(),
        )

        val egl = EglBase.create()
        val audioModule = JavaAudioDeviceModule.builder(this)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setUseStereoInput(AUDIO_CHANNEL_COUNT == 2)
            .setUseStereoOutput(AUDIO_CHANNEL_COUNT == 2)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioAttributes(mediaPlaybackAudioAttributes())
            .setAudioBufferCallback { buffer, _, channelCount, sampleRate, _, captureTimestampNs ->
                if (webrtcAudioFormat == null && sampleRate > 0 && channelCount > 0) {
                    val spec = WebRtcAudioFormat(sampleRate, channelCount, buffer.capacity())
                    webrtcAudioFormat = spec
                    container.debugLogRepository.log(
                        "LiveScreen",
                        "webrtc audio buffer format observed sampleRate=${spec.sampleRate} channels=${spec.channelCount} capacity=${spec.bufferCapacity}",
                    )
                }
                val injectionTimestampNs = paceInjectedAudioCallback(sampleRate, channelCount, buffer.capacity())
                val pump = playbackAudioPump
                if (pump != null) {
                    val peak = pump.pull(buffer)
                    if (peak > AUDIO_ACTIVITY_PEAK_THRESHOLD && !audioObserved) {
                        audioObserved = true
                        updateAudioStatus(LiveAudioStatus.AUDIO_LIVE, "pcm_activity_peak_$peak")
                    }
                } else {
                    writeSilence(buffer)
                }
                if (captureTimestampNs > 0) captureTimestampNs else injectionTimestampNs
            }
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                    container.debugLogRepository.log("LiveScreen", "WebRTC audio record init error=$errorMessage")
                }

                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                    errorMessage: String?,
                ) {
                    container.debugLogRepository.log(
                        "LiveScreen",
                        "WebRTC audio record start error code=$errorCode message=$errorMessage",
                    )
                }

                override fun onWebRtcAudioRecordError(errorMessage: String?) {
                    container.debugLogRepository.log("LiveScreen", "WebRTC audio record runtime error=$errorMessage")
                }
            })
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() {
                    container.debugLogRepository.log("LiveScreen", "WebRTC audio record started")
                    resetAudioInjectionPacing()
                    serviceScope.launch {
                        startPlaybackAudioPumpWhenReady()
                        startSilenceWatch()
                    }
                }

                override fun onWebRtcAudioRecordStop() {
                    container.debugLogRepository.log("LiveScreen", "WebRTC audio record stopped")
                    resetAudioInjectionPacing()
                }
            })
            .createAudioDeviceModule()
        audioModule.setAudioRecordEnabled(false)
        audioModule.setMicrophoneMute(false)
        audioModule.setSpeakerMute(true)
        container.debugLogRepository.log(
            "LiveScreen",
            "WebRTC audio device created for playback capture injection; microphone AudioRecord disabled",
        )

        val factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(egl.eglBaseContext, true, true),
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()

        val capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() {
                container.debugLogRepository.log("LiveScreen", "media projection stopped")
                stopCapture(getString(SharedR.string.live_screen_ended))
            }
        })
        val helper = SurfaceTextureHelper.create("DirectServeLiveScreen", egl.eglBaseContext)
        val source = factory.createVideoSource(capturer.isScreencast)
        capturer.initialize(helper, this, source.capturerObserver)
        val track = factory.createVideoTrack(VIDEO_TRACK_ID, source)
        track.setEnabled(true)
        track.addSink(localFrameProbe)
        val localAudioSource = if (
            WEBRTC_AUDIO_ENABLED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasRecordAudioPermission()
        ) {
            container.debugLogRepository.log("LiveScreen", "creating local WebRTC audio source")
            factory.createAudioSource(audioMediaConstraints())
        } else {
            if (WEBRTC_AUDIO_ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                container.debugLogRepository.log("LiveScreen", "skipping WebRTC audio source because RECORD_AUDIO is not granted")
            }
            null
        }
        val localAudioTrack = localAudioSource?.let {
            container.debugLogRepository.log("LiveScreen", "creating local WebRTC audio track")
            factory.createAudioTrack(AUDIO_TRACK_ID, it).apply {
                setEnabled(true)
            }
        }
        if (localAudioTrack == null) {
            updateAudioStatus(LiveAudioStatus.AUDIO_UNAVAILABLE, "audio_track_not_created")
        } else if (audioStatus == LiveAudioStatus.AUDIO_SUPPORTED || audioStatus == LiveAudioStatus.AUDIO_UNAVAILABLE) {
            updateAudioStatus(
                LiveAudioStatus.AUDIO_INITIALIZING,
                "audio_track_created",
            )
        }

        eglBase = egl
        peerFactory = factory
        audioDeviceModule = audioModule
        screenCapturer = capturer
        surfaceTextureHelper = helper
        videoSource = source
        videoTrack = track
        audioSource = localAudioSource
        audioTrack = localAudioTrack
    }

    private fun startVideoCapture() {
        screenCapturer?.startCapture(captureWidth, captureHeight, captureFps)
        activeProjection = screenCapturer?.mediaProjection
    }

    private fun startSignalingLoop() {
        signalingJob?.cancel()
        signalingJob = serviceScope.launch {
            while (isActive) {
                val viewerOffer = container.liveScreenManager.consumeViewerOffer()
                if (viewerOffer != null) {
                    container.debugLogRepository.log(
                        "LiveScreen",
                        "received browser offer viewerId=${viewerOffer.viewerId} viewerName=${viewerOffer.viewerName}",
                    )
                    handleOffer(viewerOffer)
                }

                val viewerId = activeViewerId
                if (viewerId != null) {
                    if (!remoteAnswerSet) {
                        val answer = container.liveScreenManager.consumeBrowserAnswer(viewerId)
                        if (answer != null) {
                            container.debugLogRepository.log("LiveScreen", "setting remote browser answer")
                            peerConnection?.setRemoteDescription(
                                LoggingSdpObserver(
                                    tag = "setRemoteAnswer",
                                    onSuccess = {
                                        remoteAnswerSet = true
                                        container.debugLogRepository.log("LiveScreen", "remote browser answer set")
                                        flushPendingViewerIce(viewerId)
                                    },
                                    onFailure = { error ->
                                        serviceScope.launch {
                                            container.debugLogRepository.log(
                                                "LiveScreen",
                                                "set remote browser answer failed message=$error",
                                            )
                                            container.liveScreenManager.updateViewerStatus(viewerId, LiveViewerStatus.ENDED)
                                            container.liveScreenManager.disconnectViewer(viewerId)
                                            activeViewerId = null
                                        }
                                    },
                                ),
                                SessionDescription(SessionDescription.Type.ANSWER, answer.sdp),
                            )
                        }
                    }
                    val candidates = container.liveScreenManager.drainViewerIceCandidates(viewerId)
                    candidates.forEach { candidate ->
                        if (remoteAnswerSet) {
                            addViewerIceCandidate(candidate)
                        } else {
                            pendingViewerIce += candidate
                            container.debugLogRepository.log(
                                "LiveScreen",
                                "queued browser ice until remote answer is set pending=${pendingViewerIce.size}",
                            )
                        }
                    }
                }

                delay(250)
            }
        }
    }

    private suspend fun createOfferForViewer(request: LiveWebRtcOfferRequest) {
        closePeerConnection()
        activeViewerId = request.viewerId
        remoteAnswerSet = false
        submittedAndroidOfferViewerId = null
        pendingViewerIce.clear()
        container.liveScreenManager.updateViewerStatus(request.viewerId, LiveViewerStatus.CONNECTING)

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }

        val observer = createPeerObserver(request.viewerId)
        val connection = peerFactory?.createPeerConnection(rtcConfig, observer)
        peerConnection = connection
        audioSenderAttached = false
        if (connection == null) {
            container.debugLogRepository.log("LiveScreen", "failed to create peer connection")
            container.liveScreenManager.updateViewerStatus(request.viewerId, LiveViewerStatus.ENDED)
            return
        }
        if (!attachSendOnlyMediaForOffer(connection, request.viewerId)) return

        container.debugLogRepository.log("LiveScreen", "createOffer requested")
        connection?.createOffer(
            LoggingSdpObserver(
                tag = "createOffer",
                onCreate = { description ->
                    container.debugLogRepository.log(
                        "LiveScreen",
                        "offer created ${summarizeSdpDirections(description.description)}",
                    )
                    container.debugLogRepository.log("LiveScreen", "setting local Android offer")
                    connection.setLocalDescription(
                        LoggingSdpObserver(
                            tag = "setLocalOffer",
                            onSuccess = {
                                container.debugLogRepository.log(
                                    "LiveScreen",
                                    "local Android offer set audioEnabled=$audioSenderAttached",
                                )
                                serviceScope.launch {
                                    submitAndroidOfferOnce(
                                        viewerId = request.viewerId,
                                        connection = connection,
                                        fallbackDescription = description,
                                        waitForIce = true,
                                        reason = "setLocalDescription_success",
                                    )
                                }
                            },
                            onFailure = { error ->
                                serviceScope.launch {
                                    container.debugLogRepository.log(
                                        "LiveScreen",
                                        "setLocalDescription offer failed message=$error",
                                    )
                                    container.liveScreenManager.updateViewerStatus(request.viewerId, LiveViewerStatus.ENDED)
                                    container.liveScreenManager.disconnectViewer(request.viewerId)
                                    activeViewerId = null
                                }
                            },
                        ),
                        description,
                    )
                    serviceScope.launch {
                        delay(LOCAL_DESCRIPTION_FALLBACK_MS)
                        submitAndroidOfferOnce(
                            viewerId = request.viewerId,
                            connection = connection,
                            fallbackDescription = description,
                            waitForIce = false,
                            reason = "setLocalDescription_callback_timeout",
                        )
                    }
                },
                onFailure = { error ->
                    serviceScope.launch {
                        container.debugLogRepository.log(
                            "LiveScreen",
                            "createOffer failed message=$error",
                        )
                        container.liveScreenManager.updateViewerStatus(request.viewerId, LiveViewerStatus.ENDED)
                        container.liveScreenManager.disconnectViewer(request.viewerId)
                        activeViewerId = null
                    }
                },
            ),
            MediaConstraints(),
        )
    }

    private fun attachSendOnlyMediaForOffer(connection: PeerConnection, viewerId: String): Boolean {
        val localVideoTrack = videoTrack
        if (localVideoTrack == null) {
            container.debugLogRepository.log("LiveScreen", "failed to attach video sender because video track is null")
            serviceScope.launch { container.liveScreenManager.updateViewerStatus(viewerId, LiveViewerStatus.ENDED) }
            return false
        }
        val videoAttached = runCatching {
            val transceiver = connection.addTransceiver(localVideoTrack, sendOnlyTransceiverInit())
            configureTransceiverForSend(transceiver, "video")
            tuneVideoSender(transceiver)
            container.debugLogRepository.log("LiveScreen", "video sendonly transceiver added to peer connection")
            true
        }.getOrElse { error ->
            container.debugLogRepository.log("LiveScreen", "failed to add video sendonly transceiver", error)
            false
        }
        if (!videoAttached) {
            serviceScope.launch { container.liveScreenManager.updateViewerStatus(viewerId, LiveViewerStatus.ENDED) }
            return false
        }

        val localAudioTrack = audioTrack
        audioSenderAttached = if (!WEBRTC_AUDIO_ENABLED || localAudioTrack == null) {
            container.debugLogRepository.log("LiveScreen", "skipping audio sendonly transceiver track=${localAudioTrack != null}")
            false
        } else {
            runCatching {
                val transceiver = connection.addTransceiver(localAudioTrack, sendOnlyTransceiverInit())
                configureTransceiverForSend(transceiver, "audio")
                container.debugLogRepository.log("LiveScreen", "audio sendonly transceiver added to peer connection")
                true
            }.getOrElse { error ->
                updateAudioStatus(LiveAudioStatus.AUDIO_FAILED, "audio_sendonly_transceiver_failed")
                container.debugLogRepository.log("LiveScreen", "failed to add audio sendonly transceiver", error)
                false
            }
        }
        container.debugLogRepository.log(
            "LiveScreen",
            "sendonly media attached transceivers=${summarizeTransceivers(connection)}",
        )
        return true
    }

    private suspend fun submitAndroidOfferOnce(
        viewerId: String,
        connection: PeerConnection,
        fallbackDescription: SessionDescription,
        waitForIce: Boolean,
        reason: String,
    ) {
        if (activeViewerId != viewerId) return
        if (submittedAndroidOfferViewerId == viewerId) return
        submittedAndroidOfferViewerId = viewerId
        if (waitForIce) {
            waitForLocalIceGathering(connection)
        }
        val localOffer = connection.localDescription ?: fallbackDescription
        container.debugLogRepository.log(
            "LiveScreen",
            "submitting Android offer reason=$reason ${summarizeSdpDirections(localOffer.description)} iceState=${connection.iceGatheringState()} hasCandidates=${localOffer.description.contains("a=candidate:")}",
        )
        container.liveScreenManager.submitAndroidOffer(
            viewerId = viewerId,
            sdp = localOffer.description,
            audioEnabled = audioSenderAttached,
        )
    }

    private fun maybeAddAudioTrack(connection: PeerConnection?) {
        if (!WEBRTC_AUDIO_ENABLED) {
            container.debugLogRepository.log("LiveScreen", "skipping audio track add because WebRTC audio is disabled")
            audioSenderAttached = false
            return
        }
        val track = audioTrack
        if (connection == null || track == null) {
            container.debugLogRepository.log(
                "LiveScreen",
                "skipping audio track add because connection=${connection != null} track=${track != null}",
            )
            audioSenderAttached = false
            return
        }
        runCatching {
            connection.addTrack(track, listOf(STREAM_ID))
            audioSenderAttached = true
            container.debugLogRepository.log("LiveScreen", "audio track added to peer connection")
        }.onFailure { error ->
            audioSenderAttached = false
            updateAudioStatus(LiveAudioStatus.AUDIO_FAILED, "audio_track_add_failed")
            container.debugLogRepository.log("LiveScreen", "failed to add audio track", error)
        }
    }

    private suspend fun handleOffer(offer: com.ghoststream.core.model.LiveWebRtcOffer) {
        closePeerConnection()
        activeViewerId = offer.viewerId
        remoteAnswerSet = false
        submittedAndroidAnswerViewerId = null
        container.liveScreenManager.updateViewerStatus(offer.viewerId, LiveViewerStatus.CONNECTING)

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }

        val observer = createPeerObserver(offer.viewerId)
        val connection = peerFactory?.createPeerConnection(rtcConfig, observer)
        peerConnection = connection
        audioSenderAttached = false
        if (connection == null) {
            container.debugLogRepository.log("LiveScreen", "failed to create peer connection")
            container.liveScreenManager.updateViewerStatus(offer.viewerId, LiveViewerStatus.ENDED)
            return
        }
        container.debugLogRepository.log(
            "LiveScreen",
            "setting remote offer ${summarizeSdpDirections(offer.sdp)}",
        )
        connection?.setRemoteDescription(
            LoggingSdpObserver(
                tag = "setRemoteOffer",
                onSuccess = {
                    container.debugLogRepository.log(
                        "LiveScreen",
                        "remote offer set; creating answer transceivers=${summarizeTransceivers(connection)}",
                    )
                    remoteAnswerSet = true
                    bindTracksToOfferedTransceivers(connection, offer.viewerId)
                    flushPendingViewerIce(offer.viewerId)
                    createAnswer(offer.viewerId)
                },
                onFailure = { error ->
                    serviceScope.launch {
                        container.debugLogRepository.log("LiveScreen", "setRemoteDescription failed message=$error")
                        container.liveScreenManager.updateViewerStatus(offer.viewerId, LiveViewerStatus.ENDED)
                        container.liveScreenManager.disconnectViewer(offer.viewerId)
                    }
                },
            ),
            SessionDescription(SessionDescription.Type.OFFER, offer.sdp),
        )
    }

    private fun preAttachLocalMediaTransceivers(connection: PeerConnection, viewerId: String): Boolean {
        val localVideoTrack = videoTrack
        if (localVideoTrack == null) {
            container.debugLogRepository.log("LiveScreen", "failed to pre-attach video sender because video track is null")
            serviceScope.launch { container.liveScreenManager.updateViewerStatus(viewerId, LiveViewerStatus.ENDED) }
            return false
        }
        val videoAttached = runCatching {
            val transceiver = connection.addTransceiver(localVideoTrack, sendOnlyTransceiverInit())
            configureTransceiverForSend(transceiver, "video")
            tuneVideoSender(transceiver)
            container.debugLogRepository.log("LiveScreen", "video sender pre-attached to peer connection")
            true
        }.getOrElse { error ->
            container.debugLogRepository.log("LiveScreen", "failed to pre-attach video sender", error)
            false
        }
        if (!videoAttached) return false

        val localAudioTrack = audioTrack
        audioSenderAttached = if (localAudioTrack == null) {
            container.debugLogRepository.log("LiveScreen", "skipping audio sender pre-attach because track=false")
            false
        } else {
            runCatching {
                val transceiver = connection.addTransceiver(localAudioTrack, sendOnlyTransceiverInit())
                configureTransceiverForSend(transceiver, "audio")
                container.debugLogRepository.log("LiveScreen", "audio sender pre-attached to peer connection")
                true
            }.getOrElse { error ->
                updateAudioStatus(LiveAudioStatus.AUDIO_FAILED, "audio_sender_pre_attach_failed")
                container.debugLogRepository.log("LiveScreen", "failed to pre-attach audio sender", error)
                false
            }
        }
        container.debugLogRepository.log(
            "LiveScreen",
            "local media pre-attached transceivers=${summarizeTransceivers(connection)}",
        )
        return true
    }

    private fun bindTracksToOfferedTransceivers(connection: PeerConnection, viewerId: String) {
        val localVideoTrack = videoTrack
        if (localVideoTrack == null) {
            container.debugLogRepository.log("LiveScreen", "failed to bind video sender because video track is null")
            serviceScope.launch { container.liveScreenManager.updateViewerStatus(viewerId, LiveViewerStatus.ENDED) }
            return
        }
        val videoBound = bindTrackToOfferedTransceiver(
            connection = connection,
            mediaType = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            track = localVideoTrack,
            label = "video",
        )
        if (!videoBound) {
            runCatching {
                val transceiver = connection.addTransceiver(localVideoTrack, sendOnlyTransceiverInit())
                configureTransceiverForSend(transceiver, "video")
                tuneVideoSender(transceiver)
                container.debugLogRepository.log("LiveScreen", "video sender attached via fallback transceiver")
            }.onFailure { error ->
                container.debugLogRepository.log("LiveScreen", "failed to attach fallback video sender", error)
            }
        }

        val localAudioTrack = audioTrack
        if (localAudioTrack == null) {
            audioSenderAttached = false
            container.debugLogRepository.log("LiveScreen", "skipping audio sender bind because track=false")
            return
        }
        val hasOfferedAudio = connection.transceivers.any { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
        if (hasOfferedAudio) {
            audioSenderAttached = bindTrackToOfferedTransceiver(
                connection = connection,
                mediaType = MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                track = localAudioTrack,
                label = "audio",
            )
        } else {
            audioSenderAttached = false
            container.debugLogRepository.log("LiveScreen", "skipping audio sender because browser offer did not request audio")
        }
    }

    private fun bindTrackToOfferedTransceiver(
        connection: PeerConnection,
        mediaType: MediaStreamTrack.MediaType,
        track: MediaStreamTrack,
        label: String,
    ): Boolean {
        val transceiver = connection.transceivers.firstOrNull { it.mediaType == mediaType }
        if (transceiver == null) {
            container.debugLogRepository.log("LiveScreen", "no offered $label transceiver found")
            return false
        }
        return runCatching {
            transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            configureTransceiverForSend(transceiver, label)
            val attached = transceiver.sender.setTrack(track, false)
            if (label == "video") tuneVideoSender(transceiver)
            container.debugLogRepository.log(
                "LiveScreen",
                "$label sender bound to offered transceiver mid=${transceiver.mid} direction=${transceiver.direction} attached=$attached",
            )
            attached
        }.getOrElse { error ->
            container.debugLogRepository.log("LiveScreen", "failed to bind $label sender to offered transceiver", error)
            false
        }
    }

    private fun configureTransceiverForSend(transceiver: RtpTransceiver, label: String) {
        val mediaType = transceiver.mediaType ?: return
        val codecs = preferredSenderCodecs(mediaType, label)
        if (codecs.isEmpty()) return
        val result = transceiver.setCodecPreferences(codecs)
        container.debugLogRepository.log(
            "LiveScreen",
            "$label codec preferences applied success=${result.isSuccess} codecs=${codecs.take(4).joinToString { it.name ?: it.mimeType ?: "unknown" }}",
        )
    }

    private fun preferredSenderCodecs(
        mediaType: MediaStreamTrack.MediaType,
        label: String,
    ): List<RtpCapabilities.CodecCapability> {
        val codecs = peerFactory?.getRtpSenderCapabilities(mediaType)?.codecs ?: return emptyList()
        fun rank(codec: RtpCapabilities.CodecCapability): Int {
            val name = (codec.name ?: codec.mimeType ?: "").uppercase()
            return when (label) {
                "video" -> when {
                    name.contains("H264") -> 0
                    name.contains("VP8") -> 1
                    name.contains("VP9") -> 2
                    name.contains("AV1") -> 3
                    name.contains("RTX") -> 20
                    name.contains("RED") -> 21
                    name.contains("ULPFEC") -> 22
                    else -> 10
                }
                "audio" -> when {
                    name.contains("OPUS") -> 0
                    else -> 100
                }
                else -> 10
            }
        }
        val filtered = when (label) {
            "audio" -> codecs.filter { codec ->
                val name = (codec.name ?: codec.mimeType ?: "").uppercase()
                name.contains("OPUS")
            }
            else -> codecs
        }
        return filtered.sortedWith(compareBy(::rank))
    }

    private fun tuneVideoSender(transceiver: RtpTransceiver) {
        val parameters = transceiver.sender.parameters ?: return
        val maxBitrate = captureBitrateKbps * 1_000
        val minBitrate = (maxBitrate / 3).coerceAtLeast(MIN_VIDEO_SENDER_BITRATE_BPS)
        parameters.degradationPreference = RtpParameters.DegradationPreference.BALANCED
        parameters.encodings.forEach { encoding ->
            encoding.active = true
            encoding.maxBitrateBps = maxBitrate
            encoding.minBitrateBps = minBitrate
            encoding.maxFramerate = captureFps
            encoding.scaleResolutionDownBy = 1.0
            encoding.numTemporalLayers = 2
        }
        val applied = transceiver.sender.setParameters(parameters)
        container.debugLogRepository.log(
            "LiveScreen",
            "video sender tuned applied=$applied fps=$captureFps minBitrate=$minBitrate maxBitrate=$maxBitrate encodings=${parameters.encodings.size}",
        )
    }

    private fun retuneActiveVideoSenders() {
        peerConnection
            ?.transceivers
            ?.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            ?.forEach(::tuneVideoSender)
    }

    private fun createPeerObserver(viewerId: String): PeerConnection.Observer =
        object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                container.debugLogRepository.log("LiveScreen", "ice connection state=$newState")
                serviceScope.launch {
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED,
                        -> container.liveScreenManager.updateViewerStatus(viewerId, LiveViewerStatus.LIVE)
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.CHECKING,
                        -> container.liveScreenManager.updateViewerStatus(viewerId, LiveViewerStatus.RECONNECTING)
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED,
                        -> {
                            container.liveScreenManager.updateViewerStatus(viewerId, LiveViewerStatus.ENDED)
                            container.liveScreenManager.disconnectViewer(viewerId)
                            activeViewerId = null
                        }
                        else -> Unit
                    }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                container.debugLogRepository.log("LiveScreen", "ice gathering state=$newState")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null || activeViewerId == null) return
                serviceScope.launch {
                    container.liveScreenManager.addAndroidIceCandidate(activeViewerId!!, candidate.toPayload())
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
            override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) = Unit
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) = Unit
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                container.debugLogRepository.log("LiveScreen", "peer connection state=$newState")
            }

            override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent?) = Unit
            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
        }

    private fun createAnswer(viewerId: String) {
        val constraints = MediaConstraints()
        val connection = peerConnection
        if (connection == null) {
            container.debugLogRepository.log("LiveScreen", "createAnswer skipped because peer connection is null")
            return
        }
        container.debugLogRepository.log("LiveScreen", "createAnswer requested")
        connection.createAnswer(
            LoggingSdpObserver(
                tag = "createAnswer",
                onCreate = { description ->
                    val localDescription = SessionDescription(
                        description.type,
                        tuneLiveAnswerSdp(
                            sdp = description.description,
                            videoBitrateKbps = captureBitrateKbps,
                            videoFps = captureFps,
                        ),
                    )
                    container.debugLogRepository.log(
                        "LiveScreen",
                        "answer created ${summarizeSdpDirections(localDescription.description)} bitrate=$captureBitrateKbps fps=$captureFps",
                    )
                    serviceScope.launch {
                        delay(LOCAL_DESCRIPTION_FALLBACK_MS)
                        submitAndroidAnswerOnce(
                            viewerId = viewerId,
                            fallbackDescription = localDescription,
                            waitForIce = false,
                            reason = "setLocalAnswer_callback_timeout",
                        )
                    }
                    container.debugLogRepository.log("LiveScreen", "setting local answer on WebRTC callback thread")
                    connection.setLocalDescription(
                        LoggingSdpObserver(
                            tag = "setLocalAnswer",
                            onSuccess = {
                                container.debugLogRepository.log(
                                    "LiveScreen",
                                    "local answer set audioEnabled=$audioSenderAttached",
                                )
                                serviceScope.launch {
                                    submitAndroidAnswerOnce(
                                        viewerId = viewerId,
                                        fallbackDescription = localDescription,
                                        waitForIce = true,
                                        reason = "setLocalAnswer_success",
                                    )
                                }
                            },
                            onFailure = { error ->
                                serviceScope.launch {
                                    container.debugLogRepository.log(
                                        "LiveScreen",
                                        "setLocalDescription answer failed message=$error",
                                    )
                                    container.liveScreenManager.updateViewerStatus(viewerId, LiveViewerStatus.ENDED)
                                    container.liveScreenManager.disconnectViewer(viewerId)
                                    activeViewerId = null
                                }
                            },
                        ),
                        localDescription,
                    )
                    container.debugLogRepository.log("LiveScreen", "setLocalDescription answer call returned")
                },
                onFailure = { error ->
                    serviceScope.launch {
                        container.debugLogRepository.log(
                            "LiveScreen",
                            "createAnswer failed message=$error",
                        )
                        container.liveScreenManager.updateViewerStatus(viewerId, LiveViewerStatus.ENDED)
                        container.liveScreenManager.disconnectViewer(viewerId)
                        activeViewerId = null
                    }
                },
            ),
            constraints,
        )
    }

    private suspend fun submitAndroidAnswerOnce(
        viewerId: String,
        fallbackDescription: SessionDescription,
        waitForIce: Boolean,
        reason: String,
    ) {
        if (activeViewerId != viewerId) return
        if (submittedAndroidAnswerViewerId == viewerId) return
        submittedAndroidAnswerViewerId = viewerId
        val connection = peerConnection
        if (waitForIce) {
            waitForLocalIceGathering(connection)
        }
        val localAnswer = connection?.localDescription ?: fallbackDescription
        container.debugLogRepository.log(
            "LiveScreen",
            "submitting Android answer reason=$reason ${summarizeSdpDirections(localAnswer.description)} iceState=${connection?.iceGatheringState()} hasCandidates=${localAnswer.description.contains("a=candidate:")}",
        )
        container.liveScreenManager.submitAndroidAnswer(
            viewerId = viewerId,
            sdp = localAnswer.description,
            audioEnabled = audioSenderAttached,
        )
    }

    private fun summarizeSdpDirections(sdp: String): String {
        fun directionFor(kind: String): String {
            val section = sdp.substringAfter("m=$kind", missingDelimiterValue = "")
            if (section.isBlank()) return "$kind=false"
            val mediaSection = section.substringBefore("\nm=", section)
            val direction = listOf("sendrecv", "sendonly", "recvonly", "inactive")
                .firstOrNull { mediaSection.contains("a=$it") }
                ?: "unspecified"
            return "$kind=$direction"
        }
        return "${directionFor("audio")} ${directionFor("video")}"
    }

    private fun summarizeTransceivers(connection: PeerConnection): String =
        connection.transceivers.joinToString(separator = ",") { transceiver ->
            "${transceiver.mediaType}:${transceiver.direction}:mid=${transceiver.mid}"
        }

    private suspend fun waitForLocalIceGathering(connection: PeerConnection?) {
        val peer = connection ?: return
        val startedAt = System.currentTimeMillis()
        while (
            peer.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE &&
            System.currentTimeMillis() - startedAt < ICE_GATHERING_SIGNAL_TIMEOUT_MS
        ) {
            delay(ICE_GATHERING_SIGNAL_POLL_MS)
        }
    }

    private fun closePeerConnection() {
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        audioSenderAttached = false
        remoteAnswerSet = false
        submittedAndroidOfferViewerId = null
        submittedAndroidAnswerViewerId = null
        pendingViewerIce.clear()
    }

    private fun flushPendingViewerIce(viewerId: String) {
        if (pendingViewerIce.isEmpty()) return
        val queued = pendingViewerIce.toList()
        pendingViewerIce.clear()
        container.debugLogRepository.log(
            "LiveScreen",
            "flushing queued browser ice viewerId=$viewerId count=${queued.size}",
        )
        queued.forEach(::addViewerIceCandidate)
    }

    private fun addViewerIceCandidate(candidate: LiveIceCandidatePayload) {
        runCatching {
            peerConnection?.addIceCandidate(candidate.toIceCandidate())
        }.onFailure { error ->
            container.debugLogRepository.log("LiveScreen", "failed to add remote ice", error)
        }
    }

    private fun maybeAttachAudioSender(connection: PeerConnection?) {
        if (!WEBRTC_AUDIO_ENABLED) {
            container.debugLogRepository.log("LiveScreen", "skipping audio sender attach because WebRTC audio is disabled")
            audioSenderAttached = false
            return
        }
        val track = audioTrack
        if (connection == null || track == null) {
            container.debugLogRepository.log(
                "LiveScreen",
                "skipping audio sender attach because connection=${connection != null} track=${track != null}",
            )
            audioSenderAttached = false
            return
        }
        runCatching {
            container.debugLogRepository.log("LiveScreen", "attaching audio sender to peer connection")
            val transceiver = connection.addTransceiver(track, sendOnlyTransceiverInit())
            configureTransceiverForSend(transceiver, "audio")
            audioSenderAttached = true
            container.debugLogRepository.log("LiveScreen", "audio sender attached")
        }.onFailure { error ->
            audioSenderAttached = false
            updateAudioStatus(LiveAudioStatus.AUDIO_FAILED, "audio_sender_attach_failed")
            container.debugLogRepository.log("LiveScreen", "failed to attach audio sender", error)
        }
    }

    private fun sendOnlyTransceiverInit(): RtpTransceiver.RtpTransceiverInit =
        RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(STREAM_ID),
        )

    private fun stopCapture(errorMessage: String?) {
        if (isStopping) {
            container.debugLogRepository.log("LiveScreen", "stopCapture ignored because cleanup is already running")
            return
        }
        isStopping = true
        captureJob?.cancel()
        captureJob = null
        signalingJob?.cancel()
        signalingJob = null
        silenceWatchJob?.cancel()
        silenceWatchJob = null
        displayChangeJob?.cancel()
        displayChangeJob = null
        activeViewerId?.let { viewerId ->
            serviceScope.launch {
                container.liveScreenManager.disconnectViewer(viewerId)
            }
        }
        activeViewerId = null
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        try {
            runCatching { playbackAudioPump?.stop() }
            playbackAudioPump = null
            closePeerConnection()
            container.debugLogRepository.log("LiveScreen", "releasing audio/video capture resources")
            runCatching { muxedStreamer?.stop() }
            runCatching { screenCapturer?.stopCapture() }
            runCatching { screenCapturer?.dispose() }
            runCatching { surfaceTextureHelper?.dispose() }
            runCatching { videoSource?.dispose() }
            runCatching { videoTrack?.dispose() }
            runCatching { audioTrack?.dispose() }
            runCatching { audioSource?.dispose() }
            runCatching { audioDeviceModule?.release() }
            runCatching { peerFactory?.dispose() }
            runCatching { eglBase?.release() }
            runCatching { activeProjection?.stop() }
            muxedStreamer = null
            screenCapturer = null
            surfaceTextureHelper = null
            videoSource = null
            videoTrack = null
            audioSource = null
            audioTrack = null
            audioDeviceModule = null
            peerFactory = null
            eglBase = null
            activeProjection = null
            audioObserved = false
            videoFrameObserved = false
            webrtcAudioFormat = null
            audioStatus = LiveAudioStatus.AUDIO_UNAVAILABLE
            resetAudioInjectionPacing()

            container.liveScreenManager.updateState {
                it.copy(
                    status = if (errorMessage.isNullOrBlank()) LiveScreenStatus.STOPPED else LiveScreenStatus.ERROR,
                    width = null,
                    height = null,
                    viewerCount = 0,
                    viewerName = null,
                    lastError = errorMessage,
                )
            }
            if (errorMessage.isNullOrBlank()) {
                container.liveScreenManager.clearStream()
            } else {
                container.liveScreenManager.updateMuxedStream(null)
            }
            if (!container.sessionManager.sessionState.value.isSharing) {
                serviceScope.launch {
                    runCatching { container.server.stop() }
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } finally {
            isStopping = false
        }
    }

    private fun currentMetrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return DisplayMetrics().also { metrics ->
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }
    }

    private fun chooseCaptureSize(width: Int, height: Int): Pair<Int, Int> {
        val maxDimension = LIVE_CAPTURE_LONG_EDGE.toFloat()
        val scale = if (maxOf(width, height) <= maxDimension) 1f else maxDimension / maxOf(width, height).toFloat()
        val scaledWidth = ((width * scale).toInt() / 2) * 2
        val scaledHeight = ((height * scale).toInt() / 2) * 2
        return scaledWidth.coerceAtLeast(640) to scaledHeight.coerceAtLeast(360)
    }

    private fun detectInitialAudioStatus(): LiveAudioStatus {
        if (!WEBRTC_AUDIO_ENABLED) {
            container.debugLogRepository.log("LiveScreen", "audio unavailable because live audio is disabled")
            return LiveAudioStatus.AUDIO_UNAVAILABLE
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            container.debugLogRepository.log("LiveScreen", "audio unavailable because playback capture requires Android 10+")
            return LiveAudioStatus.AUDIO_UNAVAILABLE
        }
        if (!hasRecordAudioPermission()) {
            container.debugLogRepository.log("LiveScreen", "audio unavailable because RECORD_AUDIO is not granted")
            return LiveAudioStatus.AUDIO_UNAVAILABLE
        }
        return LiveAudioStatus.AUDIO_SUPPORTED
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun audioMediaConstraints(): MediaConstraints =
        MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
        }

    private fun paceInjectedAudioCallback(sampleRate: Int, channelCount: Int, capacityBytes: Int): Long {
        val safeSampleRate = sampleRate.takeIf { it > 0 } ?: AUDIO_SAMPLE_RATE
        val safeChannelCount = channelCount.takeIf { it > 0 } ?: AUDIO_CHANNEL_COUNT
        val bytesPerFrame = (safeChannelCount * BYTES_PER_AUDIO_SAMPLE).coerceAtLeast(BYTES_PER_AUDIO_SAMPLE)
        val frames = (capacityBytes / bytesPerFrame).coerceAtLeast(1)
        val durationNs = ((frames * 1_000_000_000L) / safeSampleRate)
            .coerceIn(MIN_AUDIO_CALLBACK_INTERVAL_NS, MAX_AUDIO_CALLBACK_INTERVAL_NS)
        val now = System.nanoTime()
        val targetNs = synchronized(audioPacingLock) {
            val scheduledNs = nextAudioInjectionNs
            val baseNs = if (scheduledNs <= 0L || scheduledNs < now - durationNs) now else scheduledNs
            nextAudioInjectionNs = baseNs + durationNs
            baseNs
        }
        val waitNs = targetNs - now
        if (waitNs > 0L) {
            try {
                Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return targetNs
    }

    private fun resetAudioInjectionPacing() {
        synchronized(audioPacingLock) {
            nextAudioInjectionNs = 0L
        }
    }

    private fun mediaPlaybackAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                }
            }
            .build()

    private fun estimateCaptureBitrateKbps(width: Int, height: Int, fps: Int): Int {
        val pixelsPerSecond = width.toLong() * height.toLong() * fps.toLong()
        return (pixelsPerSecond / 4_500L)
            .toInt()
            .coerceIn(MIN_VIDEO_BITRATE_KBPS, MAX_VIDEO_BITRATE_KBPS)
    }

    private fun liveScreenForegroundType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasRecordAudioPermission()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    private suspend fun startPlaybackAudioPumpWhenReady() {
        if (!WEBRTC_AUDIO_ENABLED || audioTrack == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (playbackAudioPump != null) return

        var projection = activeProjection ?: screenCapturer?.mediaProjection
        repeat(PROJECTION_READY_RETRY_COUNT) {
            if (projection != null || playbackAudioPump != null || isStopping) return@repeat
            delay(PROJECTION_READY_RETRY_DELAY_MS)
            projection = screenCapturer?.mediaProjection
            activeProjection = projection
        }

        val readyProjection = projection
        if (readyProjection == null) {
            container.debugLogRepository.log("LiveScreen", "playback audio pump skipped because projection is null")
            updateAudioStatus(LiveAudioStatus.AUDIO_UNAVAILABLE, "no_projection")
            return
        }
        activeProjection = readyProjection

        val format = webrtcAudioFormat ?: WebRtcAudioFormat(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT, 0)
        startPlaybackAudioPump(readyProjection, format)
    }

    private fun startPlaybackAudioPump(projection: MediaProjection, format: WebRtcAudioFormat) {
        val pump = PlaybackAudioPump(
            projection = projection,
            sampleRate = format.sampleRate,
            channelCount = format.channelCount,
            onStatus = { status, reason -> updateAudioStatus(status, reason) },
            onActivity = {
                if (!audioObserved) {
                    audioObserved = true
                    updateAudioStatus(LiveAudioStatus.AUDIO_LIVE, "pump_activity")
                }
            },
            logger = { message, error ->
                container.debugLogRepository.log("LiveScreen", message, error)
            },
        )
        if (pump.start()) {
            playbackAudioPump = pump
            container.debugLogRepository.log(
                "LiveScreen",
                "playback audio pump started sampleRate=${format.sampleRate} channels=${format.channelCount}",
            )
        } else {
            container.debugLogRepository.log("LiveScreen", "playback audio pump failed to start")
        }
    }

    private fun startSilenceWatch() {
        silenceWatchJob?.cancel()
        silenceWatchJob = serviceScope.launch {
            if (audioTrack == null) return@launch
            delay(AUDIO_SILENCE_TIMEOUT_MS)
            if (!audioObserved && (
                    audioStatus == LiveAudioStatus.AUDIO_INITIALIZING ||
                        audioStatus == LiveAudioStatus.AUDIO_SUPPORTED
                    )
            ) {
                container.debugLogRepository.log("LiveScreen", "silence detection triggered after timeout")
                updateAudioStatus(LiveAudioStatus.AUDIO_SILENT, "silence_timeout")
            }
        }
    }

    private fun updateAudioStatus(status: LiveAudioStatus, reason: String) {
        audioStatus = status
        container.debugLogRepository.log(
            "LiveScreen",
            "audio state changed status=$status reason=$reason observed=$audioObserved track=${audioTrack != null}",
        )
        container.liveScreenManager.updateState { current ->
            current.copy(audioStatus = status)
        }
    }

    private fun writeSilence(buffer: java.nio.ByteBuffer) {
        buffer.clear()
        while (buffer.hasRemaining()) {
            buffer.put(0)
        }
    }

    private val localFrameProbe = object : VideoSink {
        override fun onFrame(frame: VideoFrame?) {
            if (frame == null || videoFrameObserved) return
            videoFrameObserved = true
            container.debugLogRepository.log(
                "LiveScreen",
                "local video frame observed width=${frame.rotatedWidth} height=${frame.rotatedHeight} rotation=${frame.rotation}",
            )
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(SharedR.string.live_screen_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(SharedR.string.live_screen_notification_channel_desc)
            enableVibration(false)
            setSound(null, null)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "directserve_live_screen_active"
        private const val NOTIFICATION_ID = 409
        private const val ACTION_START = "com.ghostgramlabs.directserve.action.START_LIVE_SCREEN"
        private const val ACTION_STOP = "com.ghostgramlabs.directserve.action.STOP_LIVE_SCREEN"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_PERMISSION_DATA = "permission_data"
        private const val STREAM_ID = "directserve-live"
        private const val VIDEO_TRACK_ID = "directserve-live-video"
        private const val AUDIO_TRACK_ID = "directserve-live-audio"
        private const val AUDIO_SILENCE_TIMEOUT_MS = 5_000L
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNEL_COUNT = 2
        private const val BYTES_PER_AUDIO_SAMPLE = 2
        private const val AUDIO_ACTIVITY_PEAK_THRESHOLD = 32
        private const val AUDIO_FORMAT_WAIT_RETRIES = 40
        private const val AUDIO_FORMAT_WAIT_DELAY_MS = 25L
        private const val MIN_AUDIO_CALLBACK_INTERVAL_NS = 1_000_000L
        private const val MAX_AUDIO_CALLBACK_INTERVAL_NS = 20_000_000L
        private const val LIVE_CAPTURE_FPS = 30
        private const val LIVE_CAPTURE_LONG_EDGE = 1_600
        private const val MIN_VIDEO_BITRATE_KBPS = 4_500
        private const val MAX_VIDEO_BITRATE_KBPS = 12_000
        private const val MIN_VIDEO_SENDER_BITRATE_BPS = 1_500_000
        private const val DISPLAY_CHANGE_DEBOUNCE_MS = 1_500L
        private const val PROJECTION_READY_RETRY_COUNT = 10
        private const val PROJECTION_READY_RETRY_DELAY_MS = 50L
        private const val ICE_GATHERING_SIGNAL_TIMEOUT_MS = 2_500L
        private const val ICE_GATHERING_SIGNAL_POLL_MS = 50L
        private const val LOCAL_DESCRIPTION_FALLBACK_MS = 1_200L
        private const val WEBRTC_AUDIO_ENABLED = true
        private const val MUXED_LIVE_STREAM_ENABLED = false

        fun start(context: Context, resultCode: Int, permissionData: Intent) {
            val intent = Intent(context, LiveScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PERMISSION_DATA, permissionData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LiveScreenCaptureService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}

private data class WebRtcAudioFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val bufferCapacity: Int,
)

private class LoggingSdpObserver(
    private val tag: String,
    private val onSuccess: (() -> Unit)? = null,
    private val onCreate: ((SessionDescription) -> Unit)? = null,
    private val onFailure: ((String) -> Unit)? = null,
) : SdpObserver {
    override fun onCreateSuccess(sessionDescription: SessionDescription?) {
        if (sessionDescription != null) {
            onCreate?.invoke(sessionDescription)
        } else {
            onFailure?.invoke("$tag create returned null")
        }
    }

    override fun onSetSuccess() {
        onSuccess?.invoke()
    }

    override fun onCreateFailure(message: String?) {
        onFailure?.invoke(message ?: "$tag create failed")
    }

    override fun onSetFailure(message: String?) {
        onFailure?.invoke(message ?: "$tag set failed")
    }
}

private fun LiveIceCandidatePayload.toIceCandidate(): IceCandidate = IceCandidate(
    sdpMid,
    sdpMLineIndex,
    candidate,
)

private fun IceCandidate.toPayload(): LiveIceCandidatePayload = LiveIceCandidatePayload(
    sdpMid = sdpMid,
    sdpMLineIndex = sdpMLineIndex,
    candidate = sdp,
)

private fun tuneLiveAnswerSdp(
    sdp: String,
    videoBitrateKbps: Int,
    videoFps: Int,
): String {
    val preferred = preferH264(sdp)
    val lines = preferred.split("\r\n").toMutableList()
    val videoIndex = lines.indexOfFirst { it.startsWith("m=video ") }
    if (videoIndex >= 0) {
        setMediaBandwidth(lines, videoIndex, videoBitrateKbps)
        ensureCodecFmtpParameters(
            lines = lines,
            mediaIndex = videoIndex,
            codecNames = setOf("H264", "VP8", "VP9"),
            parameters = listOf(
                "x-google-start-bitrate" to videoBitrateKbps.toString(),
                "x-google-min-bitrate" to (videoBitrateKbps / 3).coerceAtLeast(1_500).toString(),
                "x-google-max-bitrate" to videoBitrateKbps.toString(),
            ),
        )
        ensureMediaLine(lines, videoIndex, "a=framerate:$videoFps")
    }
    val audioIndex = lines.indexOfFirst { it.startsWith("m=audio ") }
    if (audioIndex >= 0) {
        ensureCodecFmtpParameters(
            lines = lines,
            mediaIndex = audioIndex,
            codecNames = setOf("OPUS"),
            parameters = listOf(
                "useinbandfec" to "1",
                "usedtx" to "0",
                "stereo" to "1",
                "sprop-stereo" to "1",
                "maxaveragebitrate" to "64000",
            ),
        )
    }
    return lines.joinToString("\r\n")
}

private fun setMediaBandwidth(lines: MutableList<String>, mediaIndex: Int, bitrateKbps: Int) {
    val sectionEnd = nextMediaIndex(lines, mediaIndex)
    var index = mediaIndex + 1
    while (index < sectionEnd && index < lines.size) {
        if (lines[index].startsWith("b=AS:") || lines[index].startsWith("b=TIAS:")) {
            lines.removeAt(index)
            continue
        }
        index += 1
    }
    val refreshedEnd = nextMediaIndex(lines, mediaIndex)
    val insertAfter = (mediaIndex + 1 until refreshedEnd)
        .lastOrNull { lines[it].startsWith("c=") }
        ?: mediaIndex
    lines.add(insertAfter + 1, "b=TIAS:${bitrateKbps * 1_000}")
    lines.add(insertAfter + 1, "b=AS:$bitrateKbps")
}

private fun ensureMediaLine(lines: MutableList<String>, mediaIndex: Int, line: String) {
    val sectionEnd = nextMediaIndex(lines, mediaIndex)
    if ((mediaIndex + 1 until sectionEnd).any { lines[it] == line }) return
    lines.add(sectionEnd, line)
}

private fun ensureCodecFmtpParameters(
    lines: MutableList<String>,
    mediaIndex: Int,
    codecNames: Set<String>,
    parameters: List<Pair<String, String>>,
) {
    val sectionEnd = nextMediaIndex(lines, mediaIndex)
    val payloads = (mediaIndex + 1 until sectionEnd)
        .mapNotNull { index ->
            val line = lines[index]
            if (!line.startsWith("a=rtpmap:")) return@mapNotNull null
            val payload = line.substringAfter("a=rtpmap:").substringBefore(' ')
            val codecName = line.substringAfter(' ', "").substringBefore('/').uppercase()
            if (codecNames.any { codecName.contains(it) }) payload else null
        }
        .toSet()
    if (payloads.isEmpty()) return

    payloads.forEach { payload ->
        val refreshedEnd = nextMediaIndex(lines, mediaIndex)
        val fmtpIndex = (mediaIndex + 1 until refreshedEnd)
            .firstOrNull { lines[it].startsWith("a=fmtp:$payload ") }
        if (fmtpIndex != null) {
            lines[fmtpIndex] = appendFmtpParameters(lines[fmtpIndex], parameters)
        } else {
            val rtpIndex = (mediaIndex + 1 until refreshedEnd)
                .firstOrNull { lines[it].startsWith("a=rtpmap:$payload ") }
                ?: return@forEach
            lines.add(
                rtpIndex + 1,
                "a=fmtp:$payload ${parameters.joinToString(";") { "${it.first}=${it.second}" }}",
            )
        }
    }
}

private fun appendFmtpParameters(
    line: String,
    parameters: List<Pair<String, String>>,
): String {
    val prefix = line.substringBefore(' ')
    val existing = line.substringAfter(' ', "")
    val parts = existing.split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toMutableList()
    parameters.forEach { (name, value) ->
        val index = parts.indexOfFirst { it.substringBefore('=').equals(name, ignoreCase = true) }
        val parameter = "$name=$value"
        if (index >= 0) {
            parts[index] = parameter
        } else {
            parts += parameter
        }
    }
    return "$prefix ${parts.joinToString(";")}"
}

private fun nextMediaIndex(lines: List<String>, mediaIndex: Int): Int {
    val next = (mediaIndex + 1 until lines.size).firstOrNull { lines[it].startsWith("m=") }
    return next ?: lines.size
}

private fun preferH264(sdp: String): String {
    val lines = sdp.split("\r\n").toMutableList()
    val h264Payloads = lines
        .filter { it.startsWith("a=rtpmap:") && it.contains("H264", ignoreCase = true) }
        .mapNotNull { line ->
            line.substringAfter("a=rtpmap:")
                .substringBefore(' ')
                .toIntOrNull()
        }

    if (h264Payloads.isEmpty()) return sdp

    val mediaIndex = lines.indexOfFirst { it.startsWith("m=video ") }
    if (mediaIndex < 0) return sdp

    val parts = lines[mediaIndex].split(" ").toMutableList()
    if (parts.size <= 3) return sdp
    val header = parts.take(3)
    val payloads = parts.drop(3).mapNotNull { it.toIntOrNull() }
    val reordered = buildList {
        h264Payloads.filter { it in payloads }.forEach(::add)
        payloads.filterNot { it in h264Payloads }.forEach(::add)
    }
    lines[mediaIndex] = (header + reordered.map(Int::toString)).joinToString(" ")
    return lines.joinToString("\r\n")
}
