package com.ghoststream.app.state

import com.ghoststream.core.media.CompatibilityPipeline
import com.ghoststream.core.media.MediaAnalyzer
import com.ghoststream.core.model.NetworkAvailability
import com.ghoststream.core.network.AndroidNetworkInspector
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
) {

    suspend fun preflight(): SharePreflightResult {
        return runCatching {
            val library = storageRepository.libraryState.value
            sessionManager.refreshSelection(library.items, library.folders)
            if (library.items.isEmpty()) {
                return SharePreflightResult.NoContent
            }
            val network = withContext(Dispatchers.IO) { networkInspector.inspect() }
            sessionManager.updateNetworkAvailability(network)
            if (!network.isReady) {
                SharePreflightResult.NeedsNetwork(network)
            } else {
                SharePreflightResult.Ready
            }
        }.getOrElse { e ->
            SharePreflightResult.Failure("Preflight check failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun beginSharing(assumePreflightReady: Boolean = false): ShareStartResult {
        if (!assumePreflightReady) {
            val preflight = preflight()
            if (preflight !is SharePreflightResult.Ready) {
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
        val network = withContext(Dispatchers.IO) { networkInspector.inspect() }

        return runCatching {
            val binding = server.start(port = 0)
            val pin = when {
                !settings.requireSessionPin -> null
                settings.autoGeneratePin -> Random.nextInt(1000, 9999).toString()
                else -> settings.manualPin.filter(Char::isDigit).padEnd(4, '0').take(6)
            }

            sessionManager.startSession(
                port = binding.port,
                sessionUrl = binding.url,
                hostname = binding.hostname,
                items = library.items,
                folders = library.folders,
                networkAvailability = network,
                authEnabled = settings.requireSessionPin,
                pin = pin,
            )
            ShareStartResult.Started(binding.url)
        }.getOrElse { e ->
            ShareStartResult.Failure("Server failed to start: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun stopSharing(message: String = "Sharing stopped") {
        val settings = settingsRepository.settings.first()
        runCatching { server.stop() }
        compatibilityPipeline.clearTemporaryOutputs()
        if (settings.clearAuthOnStop || settings.ghostMode) {
            sessionManager.clearBrowserAuth()
        }
        if (settings.ghostMode) {
            mediaAnalyzer.clearTemporaryCache()
        }
        sessionManager.stopSession(message)
    }
}
