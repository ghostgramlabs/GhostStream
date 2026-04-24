package com.ghostgramlabs.directserve.state

import android.app.Application
import com.ghostgramlabs.directserve.BuildConfig
import com.ghostgramlabs.directserve.debug.DebugLogRepository
import com.ghostgramlabs.directserve.live.LiveScreenManager
import com.ghoststream.core.media.AndroidMediaAnalyzer
import com.ghoststream.core.media.CompatibilityPipeline
import com.ghoststream.core.media.MediaAnalyzer
import com.ghoststream.core.media.Media3FragmentedMp4CompatibilityWorker
import com.ghoststream.core.media.QueuedCompatibilityPipeline
import com.ghoststream.core.media.DefaultSmartPlaybackDecisionEngine
import com.ghoststream.core.media.TempPlaybackCache
import com.ghoststream.core.media.MediaSourceStabilizer
import com.ghoststream.core.network.AndroidNetworkInspector
import com.ghoststream.core.network.discovery.FriendlyUrlManager
import com.ghoststream.core.network.discovery.NsdAdvertiser
import com.ghoststream.core.network.discovery.NsdDiscoveryManager
import com.ghoststream.core.network.discovery.SessionEndpointResolver
import com.ghoststream.core.history.HistoryRepository
import com.ghoststream.core.history.RoomHistoryRepository
import com.ghoststream.core.network.server.GhostStreamServer
import com.ghoststream.core.network.server.KtorGhostStreamServer
import com.ghoststream.core.session.InMemorySessionManager
import com.ghoststream.core.session.SessionManager
import com.ghoststream.core.settings.DataStoreSettingsRepository
import com.ghoststream.core.settings.SettingsRepository
import com.ghoststream.core.storage.StorageRepository
import com.ghoststream.core.storage.device.AndroidStorageRepository

class AppContainer(
    val application: Application,
) {
    private val appContext = application.applicationContext

    val debugLogRepository: DebugLogRepository by lazy { DebugLogRepository(appContext, enabled = BuildConfig.DEBUG) }
    val historyRepository: HistoryRepository by lazy { RoomHistoryRepository(appContext) }
    val settingsRepository: SettingsRepository by lazy { DataStoreSettingsRepository(appContext) }
    val liveScreenManager: LiveScreenManager by lazy { LiveScreenManager() }
    val decisionEngine by lazy { DefaultSmartPlaybackDecisionEngine() }
    val mediaAnalyzer: MediaAnalyzer by lazy { AndroidMediaAnalyzer(appContext, decisionEngine, mediaSourceStabilizer) }
    private val tempPlaybackCache: TempPlaybackCache by lazy { TempPlaybackCache(appContext) }
    val mediaSourceStabilizer: MediaSourceStabilizer by lazy { MediaSourceStabilizer(appContext, tempPlaybackCache) }
    val compatibilityPipeline: CompatibilityPipeline by lazy {
         QueuedCompatibilityPipeline(
            cache = tempPlaybackCache,
            worker = Media3FragmentedMp4CompatibilityWorker(appContext, debugLogSink = debugLogRepository),
            stabilizer = mediaSourceStabilizer,
            decisionEngine = decisionEngine,
        )
    }
    val storageRepository: StorageRepository by lazy {
        AndroidStorageRepository(
            context = appContext,
            mediaAnalyzer = mediaAnalyzer,
            historyRepository = historyRepository,
        )
    }
    val sessionManager: SessionManager by lazy { InMemorySessionManager(debugLogRepository) }
    val networkInspector: AndroidNetworkInspector by lazy { AndroidNetworkInspector(appContext, debugLogRepository) }
    private val friendlyUrlManager: FriendlyUrlManager by lazy { FriendlyUrlManager() }
    private val sessionEndpointResolver: SessionEndpointResolver by lazy {
        SessionEndpointResolver(friendlyUrlManager = friendlyUrlManager)
    }
    val nsdAdvertiser: NsdAdvertiser by lazy {
        NsdAdvertiser(
            context = appContext,
            endpointResolver = sessionEndpointResolver,
            debugLogSink = debugLogRepository,
        )
    }
    val nsdDiscoveryManager: NsdDiscoveryManager by lazy {
        NsdDiscoveryManager(
            context = appContext,
            endpointResolver = sessionEndpointResolver,
            debugLogSink = debugLogRepository,
        )
    }
    val server: GhostStreamServer by lazy {
        KtorGhostStreamServer(
            context = appContext,
            sessionManager = sessionManager,
            storageRepository = storageRepository,
            settingsRepository = settingsRepository,
            mediaAnalyzer = mediaAnalyzer,
            compatibilityPipeline = compatibilityPipeline,
            networkInspector = networkInspector,
            historyRepository = historyRepository,
            liveScreenStore = liveScreenManager,
            debugLogSink = debugLogRepository,
            debugBrowserTracingEnabled = BuildConfig.DEBUG,
        )
    }
    val sharingCoordinator: SharingCoordinator by lazy {
        SharingCoordinator(
            application = application,
            settingsRepository = settingsRepository,
            storageRepository = storageRepository,
            sessionManager = sessionManager,
            networkInspector = networkInspector,
            server = server,
            mediaAnalyzer = mediaAnalyzer,
            compatibilityPipeline = compatibilityPipeline,
            nsdAdvertiser = nsdAdvertiser,
            liveScreenStore = liveScreenManager,
            debugLogSink = debugLogRepository,
        )
    }
}
