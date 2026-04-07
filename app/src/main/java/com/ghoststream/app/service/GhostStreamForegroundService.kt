package com.ghostgramlabs.directserve.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ghostgramlabs.directserve.GhostStreamApplication
import com.ghostgramlabs.directserve.MainActivity
import com.ghostgramlabs.directserve.R
import com.ghostgramlabs.directserve.state.ShareStartResult
import com.ghoststream.core.model.AppSettings
import com.ghoststream.core.model.ConnectedClient
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
    private val debugLogRepository by lazy { container.debugLogRepository }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        debugLogRepository.log("ForegroundService", "onCreate")
        serviceScope.launch {
            container.sessionManager.sessionState.collectLatest { state ->
                runCatching {
                    val previousClientIds = lastConnectedClientIds
                    val newClients = if (previousClientIds == null) {
                        emptyList()
                    } else {
                        state.connectedClients.filterNot { it.id in previousClientIds }
                    }
                    NotificationManagerCompat.from(this@GhostStreamForegroundService)
                        .notify(NOTIFICATION_ID, buildNotification(state))
                    if (state.isSharing && newClients.isNotEmpty()) {
                        showDeviceConnectionNotification(newClients, state.connectedClients.size)
                    }
                    lastConnectedClientIds = state.connectedClients.mapTo(linkedSetOf()) { it.id }
                    debugLogRepository.log(
                        "ForegroundService",
                        "notification updated isSharing=${state.isSharing} url=${state.sessionUrl} port=${state.serverPort}",
                    )
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        debugLogRepository.log("ForegroundService", "onStartCommand action=${intent?.action} startupInProgress=$startupInProgress")
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    debugLogRepository.log("ForegroundService", "stop action received")
                    container.sharingCoordinator.stopSharing()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
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
                    val startForegroundResult = runCatching {
                        ServiceCompat.startForeground(
                            this,
                            NOTIFICATION_ID,
                            buildNotification(container.sessionManager.sessionState.value),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                        )
                    }
                    if (startForegroundResult.isFailure) {
                        debugLogRepository.log("ForegroundService", "startForeground failed", startForegroundResult.exceptionOrNull())
                        startupInProgress = false
                        serviceScope.launch {
                            container.sharingCoordinator.stopSharing(
                                "DirectServe couldn't start background sharing on this device.",
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
        serviceScope.cancel()
        super.onDestroy()
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
        container.sharingCoordinator.stopSharing("Sharing stopped after inactivity.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

        val fileText = if (request.fileCount > 1) "${request.fileCount} files" else request.fileName
        val notification = NotificationCompat.Builder(this, REQUEST_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Incoming Transfer")
            .setContentText("$fileText (${formatBytes(request.sizeBytes)}) from ${formatGeneratedNameWithIp(request.requesterIp)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setOngoing(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .addAction(0, "Accept", acceptIntent)
            .addAction(0, "Decline", declineIntent)
            .build()

        NotificationManagerCompat.from(this).notify(REQUEST_NOTIFICATION_ID, notification)
    }

    private fun cancelUploadRequestNotification() {
        NotificationManagerCompat.from(this).cancel(REQUEST_NOTIFICATION_ID)
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
        val title = if (clients.size == 1) {
            "New device connected"
        } else {
            "${clients.size} new devices connected"
        }
        val clientNames = clients.joinToString(", ") { formatGeneratedNameWithIp(it.ipAddress) }
        val detail = if (clients.size == 1) {
            "$clientNames joined your DirectServe session."
        } else {
            "$clientNames joined. $totalConnectedCount devices are connected now."
        }
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

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return if (value >= 100 || unitIndex == 0) {
            "${value.toInt()} ${units[unitIndex]}"
        } else {
            String.format("%.1f %s", value, units[unitIndex])
        }
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
            when (state.connectedClients.size) {
                0 -> "Waiting for devices..."
                1 -> "${formatGeneratedNameWithIp(state.connectedClients.first().ipAddress)} connected"
                else -> "${state.connectedClients.size} devices connected"
            }
        } else {
            "Preparing browser access..."
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
            "Transfer Requests",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifications for incoming file transfers from PCs"
            enableVibration(true)
        }
        manager.createNotificationChannel(requestChannel)

        val connectionChannel = NotificationChannel(
            CONNECTION_CHANNEL_ID,
            "Device Connections",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifications when a new device joins your DirectServe session"
            enableVibration(true)
        }
        manager.createNotificationChannel(connectionChannel)
    }

    companion object {
        private const val CHANNEL_ID = "ghoststream_sharing"
        private const val REQUEST_CHANNEL_ID = "ghoststream_requests"
        private const val CONNECTION_CHANNEL_ID = "ghoststream_connections"
        private const val NOTIFICATION_ID = 404
        private const val REQUEST_NOTIFICATION_ID = 405
        private const val CONNECTION_NOTIFICATION_ID = 406
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
