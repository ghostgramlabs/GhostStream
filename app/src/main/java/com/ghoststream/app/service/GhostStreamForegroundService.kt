package com.ghoststream.app.service

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
import com.ghoststream.app.GhostStreamApplication
import com.ghoststream.app.MainActivity
import com.ghoststream.app.R
import com.ghoststream.app.state.ShareStartResult
import com.ghoststream.core.model.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GhostStreamForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { (application as GhostStreamApplication).container }
    private var startupInProgress = false
    private val debugLogRepository by lazy { container.debugLogRepository }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        debugLogRepository.log("ForegroundService", "onCreate")
        serviceScope.launch {
            container.sessionManager.sessionState.collectLatest { state ->
                runCatching {
                    NotificationManagerCompat.from(this@GhostStreamForegroundService)
                        .notify(NOTIFICATION_ID, buildNotification(state))
                    debugLogRepository.log(
                        "ForegroundService",
                        "notification updated isSharing=${state.isSharing} url=${state.sessionUrl} port=${state.serverPort}",
                    )
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
                                "GhostStream couldn't start background sharing on this device.",
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
        serviceScope.cancel()
        super.onDestroy()
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
            val count = state.connectedClients.size
            if (count == 0) "Waiting for devices..." else "$count devices connected"
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
    }

    companion object {
        private const val CHANNEL_ID = "ghoststream_sharing"
        private const val NOTIFICATION_ID = 404
        private const val ACTION_START = "com.ghoststream.app.action.START_SHARING"
        private const val ACTION_STOP = "com.ghoststream.app.action.STOP_SHARING"

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
