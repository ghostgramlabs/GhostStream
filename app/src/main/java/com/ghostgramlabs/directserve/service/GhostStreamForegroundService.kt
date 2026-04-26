package com.ghostgramlabs.directserve.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ghostgramlabs.directserve.GhostStreamApplication
import com.ghostgramlabs.directserve.MainActivity
import com.ghostgramlabs.directserve.R
import com.ghostgramlabs.directserve.core.resources.util.LocalizationUtils
import com.ghostgramlabs.directserve.core.resources.R as SharedR
import com.ghostgramlabs.directserve.state.ShareStartResult
import com.ghoststream.core.model.AppSettings
import com.ghoststream.core.model.ConnectedClient
import com.ghoststream.core.model.IncomingUploadCompletion
import com.ghoststream.core.model.IncomingUploadProgress
import com.ghoststream.core.model.QUICK_TEXT_PHONE_ID
import com.ghoststream.core.model.QuickTextMessage
import com.ghoststream.core.model.SessionState
import com.ghoststream.core.model.formatGeneratedNameWithIp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GhostStreamForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { (application as GhostStreamApplication).container }
    private var startupInProgress = false
    private var autoStopJob: Job? = null
    private var lastConnectedClientIds: Set<String>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val debugLogRepository by lazy { container.debugLogRepository }

    // ── Notification debounce state ──────────────────────────────────────────
    private var lastNotificationIsSharing: Boolean? = null
    private var lastNotificationUrl: String? = null
    private var lastNotificationClientCount: Int? = null
    private var lastNotificationUpdateMs: Long = 0L
    private var seenQuickTextIds: Set<String>? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        debugLogRepository.log("ForegroundService", "onCreate")
        serviceScope.launch {
            container.sessionManager.sessionState.collectLatest { state ->
                runCatching {
                    updateSharingLocks(state.isSharing)
                    if (!state.isSharing && !startupInProgress) {
                        debugLogRepository.log("ForegroundService", "session inactive; stopping service")
                        lastConnectedClientIds = null
                        lastNotificationIsSharing = null
                        lastNotificationUrl = null
                        lastNotificationClientCount = null
                        cancelActiveNotifications()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@runCatching
                    }
                    val previousClientIds = lastConnectedClientIds
                    val newClients = if (previousClientIds == null) {
                        emptyList()
                    } else {
                        state.connectedClients.filterNot { it.id in previousClientIds }
                    }

                    // ── DEBOUNCE: Only update foreground notification on meaningful changes ──
                    val now = System.currentTimeMillis()
                    val clientCount = state.connectedClients.size
                    val contentChanged = lastNotificationIsSharing != state.isSharing ||
                        lastNotificationUrl != state.sessionUrl ||
                        lastNotificationClientCount != clientCount ||
                        newClients.isNotEmpty()
                    val heartbeatExpired = now - lastNotificationUpdateMs > NOTIFICATION_HEARTBEAT_MS

                    if (contentChanged || heartbeatExpired) {
                        NotificationManagerCompat.from(this@GhostStreamForegroundService)
                            .notify(NOTIFICATION_ID, buildNotification(state))
                        lastNotificationIsSharing = state.isSharing
                        lastNotificationUrl = state.sessionUrl
                        lastNotificationClientCount = clientCount
                        lastNotificationUpdateMs = now
                        if (contentChanged) {
                            debugLogRepository.log(
                                "ForegroundService",
                                "notification updated isSharing=${state.isSharing} url=${state.sessionUrl} clients=$clientCount",
                            )
                        }
                    }

                    if (state.isSharing && newClients.isNotEmpty()) {
                        showDeviceConnectionNotification(newClients, state.connectedClients.size)
                    }
                    lastConnectedClientIds = state.connectedClients.mapTo(linkedSetOf()) { it.id }
                }
            }
        }
        serviceScope.launch {
            combine(
                container.settingsRepository.settings,
                container.sessionManager.sessionState,
            ) { settings, sessionState ->
                settings to sessionState
            }.collectLatest { (settings, sessionState) ->
                scheduleAutoStop(settings, sessionState)
            }
        }
        serviceScope.launch {
            container.sessionManager.pendingUploadRequest.collectLatest { request ->
                if (request != null) {
                    showUploadRequestNotification(request)
                } else {
                    cancelUploadRequestNotification()
                }
            }
        }
        serviceScope.launch {
            combine(
                container.settingsRepository.settings,
                container.sessionManager.incomingUploadProgress,
            ) { settings, progress -> settings to progress }
                .collectLatest { (settings, progress) ->
                    if (settings.notifyOnUploadRequest && progress != null) {
                        showIncomingUploadProgressNotification(progress)
                    } else {
                        cancelIncomingUploadProgressNotification()
                    }
                }
        }
        serviceScope.launch {
            combine(
                container.settingsRepository.settings,
                container.sessionManager.incomingUploadCompletion,
            ) { settings, completion -> settings to completion }
                .collectLatest { (settings, completion) ->
                    if (settings.notifyOnUploadRequest && completion != null) {
                        showIncomingUploadSuccessNotification(completion)
                        container.sessionManager.clearIncomingUploadCompletion(completion.requestId)
                    }
                }
        }
        serviceScope.launch {
            combine(
                container.settingsRepository.settings,
                container.historyRepository.quickTextMessages,
            ) { settings, messages -> settings to messages }
                .collectLatest { (settings, messages) ->
                    val previouslySeen = seenQuickTextIds
                    val currentIds = messages.mapTo(linkedSetOf()) { it.id }
                    if (previouslySeen == null) {
                        // First emission since service start — establish baseline without notifying.
                        seenQuickTextIds = currentIds
                        return@collectLatest
                    }
                    if (settings.quickTextEnabled && settings.notifyOnQuickText) {
                        messages
                            .filter { it.id !in previouslySeen && it.senderId != QUICK_TEXT_PHONE_ID }
                            .forEach { showQuickTextNotification(it) }
                    }
                    seenQuickTextIds = currentIds
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        debugLogRepository.log("ForegroundService", "onStartCommand action=${intent?.action} startupInProgress=$startupInProgress")
        when (intent?.action) {
            ACTION_STOP -> {
                startupInProgress = false
                serviceScope.launch {
                    debugLogRepository.log("ForegroundService", "stop action received")
                    container.sharingCoordinator.stopSharing()
                    cancelActiveNotifications()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_ACCEPT_UPLOAD -> {
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                if (requestId != null) {
                    serviceScope.launch {
                        container.sessionManager.resolveUploadRequest(requestId, true)
                    }
                }
            }

            ACTION_DECLINE_UPLOAD -> {
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                if (requestId != null) {
                    serviceScope.launch {
                        container.sessionManager.resolveUploadRequest(requestId, false)
                    }
                }
            }

            else -> {
                if (!startupInProgress) {
                    startupInProgress = true
                    try {
                        ServiceCompat.startForeground(
                            this,
                            NOTIFICATION_ID,
                            buildNotification(container.sessionManager.sessionState.value),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                        )
                    } catch (e: Exception) {
                        // ── Handle API 31+ Background Start and API 34+ Security Exceptions ──
                        val exceptionMessage = when (e) {
                            is SecurityException -> "SecurityException: Missing FGS permission or type mismatch"
                            else -> "ForegroundServiceStartNotAllowedException or other start failure"
                        }
                        debugLogRepository.log("ForegroundService", "startForeground critical failure: $exceptionMessage", e)
                        
                        startupInProgress = false
                        serviceScope.launch {
                            container.sharingCoordinator.stopSharing(
                                getString(SharedR.string.service_start_failed),
                            )
                            stopSelf()
                        }
                        return START_NOT_STICKY
                    }

                    // If the ViewModel already started the session, just keep the service alive
                    if (container.sessionManager.sessionState.value.isSharing) {
                        debugLogRepository.log("ForegroundService", "session already active when service started")
                        startupInProgress = false
                    } else {
                        // Service was started independently - need to begin sharing
                        serviceScope.launch {
                            debugLogRepository.log("ForegroundService", "service starting sharing directly")
                            when (val result = container.sharingCoordinator.beginSharing()) {
                                is ShareStartResult.Started -> {
                                    debugLogRepository.log("ForegroundService", "service started sharing url=${result.url}")
                                    startupInProgress = false
                                }

                                is ShareStartResult.Failure -> {
                                    debugLogRepository.log("ForegroundService", "service failed to start sharing message=${result.message}")
                                    startupInProgress = false
                                    container.sharingCoordinator.stopSharing(result.message)
                                    cancelActiveNotifications()
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                    stopSelf()
                                }
                            }
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        debugLogRepository.log("ForegroundService", "onDestroy")
        autoStopJob?.cancel()
        lastConnectedClientIds = null
        releaseSharingLocks()
        cancelActiveNotifications()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateSharingLocks(isSharing: Boolean) {
        if (isSharing) {
            acquireSharingLocks()
        } else {
            releaseSharingLocks()
        }
    }

    private fun acquireSharingLocks() {
        if (wakeLock?.isHeld != true) {
            runCatching {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "DirectServe:SharingWakeLock",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                debugLogRepository.log("ForegroundService", "acquired sharing wake lock")
            }.onFailure { error ->
                debugLogRepository.log("ForegroundService", "failed to acquire sharing wake lock", error)
            }
        }

        val wifiManager = runCatching {
            applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        }.getOrNull() ?: return

        if (wifiLock?.isHeld != true) {
            runCatching {
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "DirectServe:SharingWifiLock",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                debugLogRepository.log("ForegroundService", "acquired sharing Wi-Fi lock")
            }.onFailure { error ->
                debugLogRepository.log("ForegroundService", "failed to acquire sharing Wi-Fi lock", error)
            }
        }

        if (multicastLock?.isHeld != true) {
            runCatching {
                multicastLock = wifiManager.createMulticastLock("DirectServe:DlnaMulticastLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                debugLogRepository.log("ForegroundService", "acquired multicast lock")
            }.onFailure { error ->
                debugLogRepository.log("ForegroundService", "failed to acquire multicast lock", error)
            }
        }
    }

    private fun releaseSharingLocks() {
        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                debugLogRepository.log("ForegroundService", "released multicast lock")
            }
        }
        multicastLock = null

        runCatching {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                debugLogRepository.log("ForegroundService", "released sharing Wi-Fi lock")
            }
        }
        wifiLock = null

        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                debugLogRepository.log("ForegroundService", "released sharing wake lock")
            }
        }
        wakeLock = null
    }

    private fun scheduleAutoStop(settings: AppSettings, state: SessionState) {
        autoStopJob?.cancel()
        val timeoutMinutes = settings.autoStop.minutes
        if (!state.isSharing || timeoutMinutes == null) {
            return
        }

        val lastActivityEpochMs = state.transferStats.lastActivityEpochMs
            ?: state.transferStats.startedAtEpochMs
            ?: state.startedAtEpochMs
            ?: System.currentTimeMillis()
        val delayMs = (timeoutMinutes * 60_000L) - (System.currentTimeMillis() - lastActivityEpochMs)
        if (delayMs <= 0L) {
            autoStopJob = serviceScope.launch {
                debugLogRepository.log("ForegroundService", "auto-stop reached immediately minutes=$timeoutMinutes")
                stopForAutoStop()
            }
            return
        }

        autoStopJob = serviceScope.launch {
            debugLogRepository.log(
                "ForegroundService",
                "auto-stop scheduled minutes=$timeoutMinutes delayMs=$delayMs lastActivity=$lastActivityEpochMs",
            )
            delay(delayMs)
            if (container.sessionManager.sessionState.value.isSharing) {
                debugLogRepository.log("ForegroundService", "auto-stop firing after inactivity")
                stopForAutoStop()
            }
        }
    }

    private suspend fun stopForAutoStop() {
        container.sharingCoordinator.stopSharing(getString(SharedR.string.service_auto_stop_message))
        cancelActiveNotifications()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelActiveNotifications() {
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        NotificationManagerCompat.from(this).cancel(REQUEST_NOTIFICATION_ID)
        NotificationManagerCompat.from(this).cancel(CONNECTION_NOTIFICATION_ID)
        NotificationManagerCompat.from(this).cancel(UPLOAD_PROGRESS_NOTIFICATION_ID)
        NotificationManagerCompat.from(this).cancel(UPLOAD_SUCCESS_NOTIFICATION_ID)
    }

    private fun showQuickTextNotification(message: QuickTextMessage) {
        val openAppIntent = PendingIntent.getActivity(
            this,
            205,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = MainActivity.ACTION_OPEN_QUICK_TEXT
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = getString(SharedR.string.service_quick_text_title, message.senderName)
        val notification = NotificationCompat.Builder(this, QUICK_TEXT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(this)
            .notify(QUICK_TEXT_NOTIFICATION_ID_BASE + (message.id.hashCode() and 0xFFFF), notification)
    }

    private fun showUploadRequestNotification(request: com.ghoststream.core.model.UploadRequest) {
        val acceptIntent = PendingIntent.getService(
            this,
            201,
            Intent(this, GhostStreamForegroundService::class.java)
                .setAction(ACTION_ACCEPT_UPLOAD)
                .putExtra(EXTRA_REQUEST_ID, request.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val declineIntent = PendingIntent.getService(
            this,
            202,
            Intent(this, GhostStreamForegroundService::class.java)
                .setAction(ACTION_DECLINE_UPLOAD)
                .putExtra(EXTRA_REQUEST_ID, request.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val fileText = if (request.fileCount > 1) getString(SharedR.string.service_file_count, request.fileCount) else request.fileName
        val notification = NotificationCompat.Builder(this, REQUEST_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(SharedR.string.service_upload_request_title))
            .setContentText(getString(SharedR.string.service_upload_request_text, fileText, LocalizationUtils.formatBytes(this, request.sizeBytes), formatGeneratedNameWithIp(request.requesterIp)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setOngoing(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .addAction(android.R.drawable.ic_menu_save, getString(SharedR.string.common_accept), acceptIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(SharedR.string.common_decline), declineIntent)
            .build()

        NotificationManagerCompat.from(this).notify(REQUEST_NOTIFICATION_ID, notification)
    }

    private fun cancelUploadRequestNotification() {
        NotificationManagerCompat.from(this).cancel(REQUEST_NOTIFICATION_ID)
    }

    private fun showIncomingUploadProgressNotification(progress: IncomingUploadProgress) {
        val openAppIntent = PendingIntent.getActivity(
            this,
            203,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val fileLabel = if (progress.fileCount > 1) {
            getString(SharedR.string.service_file_index_pattern, progress.currentFileName, progress.currentFileIndex, progress.fileCount)
        } else {
            progress.currentFileName
        }
        val senderLabel = formatGeneratedNameWithIp(progress.requesterIp)
        val percent = progress.progressPercent
        val detail = if (percent != null) {
            getString(SharedR.string.service_upload_progress_text, percent, senderLabel)
        } else {
            getString(SharedR.string.service_upload_progress_text_unknown, senderLabel)
        }
        val notification = NotificationCompat.Builder(this, REQUEST_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(SharedR.string.service_upload_progress_title, fileLabel))
            .setContentText(detail)
            .setSubText(getString(SharedR.string.service_upload_progress_subtext_pattern, LocalizationUtils.formatBytes(this, progress.transferredBytes), LocalizationUtils.formatBytes(this, progress.totalSizeBytes)))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(
                if (progress.totalSizeBytes > 0L) 100 else 0,
                percent ?: 0,
                progress.totalSizeBytes <= 0L,
            )
            .build()

        NotificationManagerCompat.from(this).notify(UPLOAD_PROGRESS_NOTIFICATION_ID, notification)
    }

    private fun cancelIncomingUploadProgressNotification() {
        NotificationManagerCompat.from(this).cancel(UPLOAD_PROGRESS_NOTIFICATION_ID)
    }

    private fun showIncomingUploadSuccessNotification(completion: IncomingUploadCompletion) {
        val openAppIntent = PendingIntent.getActivity(
            this,
            204,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val fileLabel = if (completion.fileCount > 1) {
            getString(SharedR.string.service_file_count, completion.fileCount)
        } else {
            completion.fileName
        }
        val senderLabel = formatGeneratedNameWithIp(completion.requesterIp)
        val notification = NotificationCompat.Builder(this, REQUEST_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(SharedR.string.service_upload_success_title, fileLabel))
            .setContentText(getString(SharedR.string.service_upload_success_text, senderLabel))
            .setSubText(getString(SharedR.string.service_upload_success_subtext))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .build()

        NotificationManagerCompat.from(this).notify(UPLOAD_SUCCESS_NOTIFICATION_ID, notification)
    }

    private fun showDeviceConnectionNotification(
        clients: List<ConnectedClient>,
        totalConnectedCount: Int,
    ) {
        val openAppIntent = PendingIntent.getActivity(
            this,
            102,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = resources.getQuantityString(SharedR.plurals.service_new_devices_connected, clients.size, clients.size)
        val clientNames = clients.joinToString(", ") { formatGeneratedNameWithIp(it.ipAddress) }
        val detail = resources.getQuantityString(SharedR.plurals.service_devices_joined, clients.size, clientNames, totalConnectedCount)
        val notification = NotificationCompat.Builder(this, CONNECTION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(this).notify(CONNECTION_NOTIFICATION_ID, notification)
    }



    private fun buildNotification(state: SessionState): android.app.Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, GhostStreamForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val contentText = if (state.isSharing) {
            resources.getQuantityString(SharedR.plurals.service_devices_connected, state.connectedClients.size, state.connectedClients.size)
        } else {
            getString(SharedR.string.service_preparing_browser_access)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                getString(R.string.notification_stop),
                stopIntent,
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)

        val requestChannel = NotificationChannel(
            REQUEST_CHANNEL_ID,
            getString(SharedR.string.service_channel_requests_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(SharedR.string.service_channel_requests_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(requestChannel)

        val connectionChannel = NotificationChannel(
            CONNECTION_CHANNEL_ID,
            getString(SharedR.string.service_channel_connections_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(SharedR.string.service_channel_connections_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(connectionChannel)

        val quickTextChannel = NotificationChannel(
            QUICK_TEXT_CHANNEL_ID,
            getString(SharedR.string.service_channel_quick_text_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(SharedR.string.service_channel_quick_text_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(quickTextChannel)
    }

    companion object {
        /** Slow heartbeat for unchanged foreground notifications (ms) */
        private const val NOTIFICATION_HEARTBEAT_MS = 60_000L
        private const val CHANNEL_ID = "ghoststream_sharing"
        private const val REQUEST_CHANNEL_ID = "ghoststream_requests"
        private const val CONNECTION_CHANNEL_ID = "ghoststream_connections"
        private const val QUICK_TEXT_CHANNEL_ID = "ghoststream_quick_text"
        private const val NOTIFICATION_ID = 404
        private const val REQUEST_NOTIFICATION_ID = 405
        private const val CONNECTION_NOTIFICATION_ID = 406
        private const val UPLOAD_PROGRESS_NOTIFICATION_ID = 407
        private const val UPLOAD_SUCCESS_NOTIFICATION_ID = 408
        private const val QUICK_TEXT_NOTIFICATION_ID_BASE = 500_000
        private const val ACTION_START = "com.ghostgramlabs.directserve.action.START_SHARING"
        private const val ACTION_STOP = "com.ghostgramlabs.directserve.action.STOP_SHARING"
        private const val ACTION_ACCEPT_UPLOAD = "com.ghostgramlabs.directserve.action.ACCEPT_UPLOAD"
        private const val ACTION_DECLINE_UPLOAD = "com.ghostgramlabs.directserve.action.DECLINE_UPLOAD"
        private const val EXTRA_REQUEST_ID = "request_id"

        fun start(context: Context) {
            val intent = Intent(context, GhostStreamForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GhostStreamForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
