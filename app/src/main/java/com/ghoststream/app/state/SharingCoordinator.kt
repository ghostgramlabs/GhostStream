package com.ghostgramlabs.directserve.state

import com.ghoststream.core.media.CompatibilityPipeline
import com.ghoststream.core.media.MediaAnalyzer
import com.ghoststream.core.model.DebugLogSink
import com.ghoststream.core.model.buildSessionAccessUrl
import com.ghoststream.core.model.NetworkAvailability
import com.ghoststream.core.model.NoOpDebugLogSink
import com.ghoststream.core.network.AndroidNetworkInspector
import com.ghoststream.core.network.discovery.AdvertisedSessionInfo
import com.ghoststream.core.network.discovery.NsdAdvertiser
import com.ghoststream.core.network.server.GhostStreamServer
import com.ghoststream.core.session.SessionManager
import com.ghoststream.core.settings.SettingsRepository
import com.ghoststream.core.storage.StorageRepository
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface SharePreflightResult {
    data object Ready : SharePreflightResult
    data object NoContent : SharePreflightResult
    data class NeedsNetwork(val availability: NetworkAvailability) : SharePreflightResult
    data class Failure(val message: String) : SharePreflightResult
}

sealed interface ShareStartResult {
    data class Started(val url: String?) : ShareStartResult
    data class Failure(val message: String) : ShareStartResult
}

class SharingCoordinator(
    private val settingsRepository: SettingsRepository,
    private val storageRepository: StorageRepository,
    private val sessionManager: SessionManager,
    private val networkInspector: AndroidNetworkInspector,
    private val server: GhostStreamServer,
    private val mediaAnalyzer: MediaAnalyzer,
    private val compatibilityPipeline: CompatibilityPipeline,
    private val nsdAdvertiser: NsdAdvertiser,
    private val debugLogSink: DebugLogSink = NoOpDebugLogSink,
) {

    suspend fun preflight(): SharePreflightResult {
        return runCatching {
            val library = storageRepository.libraryState.value
            sessionManager.refreshSelection(library.items, library.folders)
            debugLogSink.log("SharingCoordinator", "preflight items=${library.items.size} folders=${library.folders.size}")
            if (library.items.isEmpty()) {
                return SharePreflightResult.NoContent
            }
            val network = withContext(Dispatchers.IO) { networkInspector.inspect() }
            sessionManager.updateNetworkAvailability(network)
            debugLogSink.log(
                "SharingCoordinator",
                "preflight network ready=${network.isReady} type=${network.type} localAddress=${network.localAddress} helper=${network.helperText}",
            )
            if (!network.isReady) {
                SharePreflightResult.NeedsNetwork(network)
            } else {
                SharePreflightResult.Ready
            }
        }.getOrElse { e ->
            debugLogSink.log("SharingCoordinator", "preflight failed", e)
            SharePreflightResult.Failure("Preflight check failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun beginSharing(assumePreflightReady: Boolean = false): ShareStartResult {
        debugLogSink.log("SharingCoordinator", "beginSharing assumePreflightReady=$assumePreflightReady")
        if (!assumePreflightReady) {
            val preflight = preflight()
            if (preflight !is SharePreflightResult.Ready) {
                debugLogSink.log("SharingCoordinator", "beginSharing blocked by preflight result=$preflight")
                return when (preflight) {
                    SharePreflightResult.NoContent -> ShareStartResult.Failure("Add some content first to start sharing.")
                    is SharePreflightResult.NeedsNetwork -> ShareStartResult.Failure("Connect both devices to the same Wi-Fi or hotspot.")
                    is SharePreflightResult.Failure -> ShareStartResult.Failure(preflight.message)
                    SharePreflightResult.Ready -> error("Handled above")
                }
            }
        }

        val settings = settingsRepository.settings.first()
        val library = storageRepository.libraryState.value
        val existingNetwork = sessionManager.sessionState.value.networkAvailability
        val network = if (assumePreflightReady && existingNetwork.isReady) {
            existingNetwork
        } else {
            withContext(Dispatchers.IO) { networkInspector.inspect() }
        }

        return runCatching {
            debugLogSink.log("SharingCoordinator", "starting embedded server")
            val preferredPort = settings.preferredPort.coerceIn(1024, 65535)
            val binding = server.start(port = preferredPort)
            val sessionUrl = buildSessionAccessUrl(
                sessionUrl = binding.url,
                localAddress = network.localAddress,
                port = binding.port,
            ) ?: binding.url
            debugLogSink.log(
                "SharingCoordinator",
                "server started bindingUrl=${binding.url} resolvedUrl=$sessionUrl port=${binding.port} hostname=${binding.hostname} networkAddress=${network.localAddress}",
            )
            val pin = when {
                !settings.requireSessionPin -> null
                settings.autoGeneratePin -> Random.nextInt(1000, 9999).toString()
                else -> settings.manualPin.filter(Char::isDigit).padEnd(4, '0').take(6)
            }

            sessionManager.startSession(
                port = binding.port,
                sessionUrl = sessionUrl,
                hostname = binding.hostname,
                items = library.items,
                folders = library.folders,
                networkAvailability = network,
                authEnabled = settings.requireSessionPin,
                pin = pin,
            )
            debugLogSink.log("SharingCoordinator", "session started authEnabled=${settings.requireSessionPin} pinSet=${pin != null}")
            val sessionId = sessionManager.sessionState.value.sessionId
            if (sessionId != null) {
                val advertised = nsdAdvertiser.start(
                    AdvertisedSessionInfo(
                        port = binding.port,
                        sessionId = sessionId,
                        authRequired = settings.requireSessionPin,
                        browserSupported = true,
                        streamingSupported = library.items.any { item -> item.category != com.ghoststream.core.model.MediaCategory.FILE },
                        deviceLabel = android.os.Build.MODEL ?: "Android",
                    ),
                )
                if (advertised != null) {
                    sessionManager.updateAdvertisedAccess(
                        advertisedName = advertised.serviceName,
                        hostname = advertised.hostname,
                    )
                    debugLogSink.log(
                        "SharingCoordinator",
                        "nearby advertisement live serviceName=${advertised.serviceName} hostname=${advertised.hostname} displayUrl=${advertised.displayUrl}",
                    )
                }
            }
            ShareStartResult.Started(sessionUrl)
        }.getOrElse { e ->
            debugLogSink.log("SharingCoordinator", "server failed to start", e)
            ShareStartResult.Failure("Server failed to start: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun stopSharing(message: String = "Sharing stopped") {
        debugLogSink.log("SharingCoordinator", "stopSharing message=$message")
        val settings = settingsRepository.settings.first()
        runCatching { nsdAdvertiser.stop() }
        runCatching { server.stop() }
        compatibilityPipeline.clearTemporaryOutputs()
        if (settings.clearAuthOnStop || settings.ghostMode) {
            sessionManager.clearBrowserAuth()
        }
        if (settings.ghostMode) {
            mediaAnalyzer.clearTemporaryCache()
            storageRepository.clearSelection()
        }
        sessionManager.stopSession(
            message = message,
            recordRecentSession = settings.showRecentSessions,
        )
    }
}
