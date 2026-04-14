package com.ghoststream.core.network.server

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import com.ghostgramlabs.directserve.core.resources.R
import com.ghoststream.core.media.CompatibilityJob
import com.ghoststream.core.media.CompatibilityPipeline
import com.ghoststream.core.media.CompatibilityStatus
import com.ghoststream.core.media.MediaAnalyzer
import com.ghoststream.core.media.PlaybackResolution
import com.ghoststream.core.media.PlaybackSource
import com.ghoststream.core.history.HistoryRepository
import com.ghoststream.core.model.*
import com.ghoststream.core.network.AndroidNetworkInspector
import com.ghoststream.core.network.assets.WebAssetLoader
import com.ghoststream.core.session.SessionManager
import com.ghoststream.core.settings.SettingsRepository
import com.ghoststream.core.storage.StorageRepository
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveNullable
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import com.ghoststream.core.model.UploadRequest
import java.util.UUID
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class KtorGhostStreamServer(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val storageRepository: StorageRepository,
    private val settingsRepository: SettingsRepository,
    private val mediaAnalyzer: MediaAnalyzer,
    private val compatibilityPipeline: CompatibilityPipeline,
    private val networkInspector: AndroidNetworkInspector,
    private val historyRepository: HistoryRepository,
    private val assetLoader: WebAssetLoader = WebAssetLoader(context),
    private val json: Json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    },
    private val debugLogSink: DebugLogSink = NoOpDebugLogSink,
    private val debugBrowserTracingEnabled: Boolean = false,
) : GhostStreamServer {

    private var engine: ApplicationEngine? = null
    private val running = AtomicBoolean(false)
    /** Throttled compat poll logging: tracks last logged state key per item to avoid log spam */
    private val lastCompatLogKey = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun start(port: Int): ServerBinding {
        debugLogSink.log("LocalServer", "start requested port=$port running=${running.get()}")
        if (running.get()) {
            val currentPort = engine?.environment?.connectors?.firstOrNull()?.port ?: port
            val network = networkInspector.inspect()
            val currentUrl = buildSessionAccessUrl(
                sessionUrl = sessionManager.sessionState.value.sessionUrl,
                localAddress = network.localAddress,
                port = currentPort,
            ) ?: "http://127.0.0.1:$currentPort"
            debugLogSink.log("LocalServer", "already running currentPort=$currentPort currentUrl=$currentUrl")
            return ServerBinding(
                port = currentPort,
                url = currentUrl,
            )
        }

        val resolvedPort = if (port == 0) nextFreePort() else port
        val network = networkInspector.inspect()
        val address = network.localAddress ?: "127.0.0.1"
        debugLogSink.log(
            "LocalServer",
            "binding host=0.0.0.0 resolvedPort=$resolvedPort networkType=${network.type} address=$address ready=${network.isReady}",
        )

        engine = embeddedServer(
            factory = CIO,
            port = resolvedPort,
            host = "0.0.0.0",
        ) {
            install(ContentNegotiation) {
                json(json)
            }
            configureRouting()
        }.start(wait = false)
        running.set(true)
        debugLogSink.log("LocalServer", "engine started port=$resolvedPort url=http://$address:$resolvedPort")

        return ServerBinding(
            port = resolvedPort,
            url = "http://$address:$resolvedPort",
        )
    }

    override suspend fun stop() {
        debugLogSink.log("LocalServer", "stop requested running=${running.get()}")
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        engine = null
        running.set(false)
        debugLogSink.log("LocalServer", "engine stopped")
    }

    override fun isRunning(): Boolean = running.get()

    private fun Application.configureRouting() {
        routing {
            get("/") { call.serveShellPage() }
            get("/login") { call.serveShellPage() }
            get("/videos") { call.serveShellPage() }
            get("/photos") { call.serveShellPage() }
            get("/music") { call.serveShellPage() }
            get("/files") { call.serveShellPage() }
            get("/player/video/{id}") { call.serveShellPage() }
            get("/photo/{id}") { call.serveShellPage() }

            get("/app.css") {
                call.respondBytes(
                    bytes = assetLoader.readBytes("web/app.css"),
                    contentType = ContentType.Text.CSS,
                )
            }

            get("/plyr.css") {
                call.respondBytes(
                    bytes = assetLoader.readBytes("web/plyr.css"),
                    contentType = ContentType.Text.CSS,
                )
            }

            get("/app.js") {
                call.respondBytes(
                    bytes = assetLoader.readBytes("web/app.js"),
                    contentType = ContentType.Application.JavaScript,
                )
            }

            get("/plyr.min.js") {
                call.respondBytes(
                    bytes = assetLoader.readBytes("web/plyr.min.js"),
                    contentType = ContentType.Application.JavaScript,
                )
            }

            get("/hls.min.js") {
                call.respondBytes(
                    bytes = assetLoader.readBytes("web/hls.min.js"),
                    contentType = ContentType.Application.JavaScript,
                )
            }

            get("/plyr.svg") {
                call.respondBytes(
                    bytes = assetLoader.readBytes("web/plyr.svg"),
                    contentType = ContentType.parse("image/svg+xml"),
                )
            }

            get("/api/bootstrap") {
                val settings = settingsRepository.settings.first()
                val localizedContext = localizedContext(settings.languageTag)
                val clientIp = call.request.origin.remoteHost
                if (sessionManager.isBlocked(clientIp)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorPayload(localizedContext.getString(R.string.browser_device_blocked)),
                    )
                    return@get
                }
                val state = sessionManager.sessionState.value
                val isAuthorized = !state.authEnabled || sessionManager.validateToken(call.request.cookies[COOKIE_NAME])
                val recentCards = mutableListOf<BrowserItemCard>()
                val allowDownloads = !settings.preventDownload
                if (isAuthorized) {
                    for (item in state.selectedItems.take(8)) {
                        recentCards += BrowserItemCard.from(
                            item = item,
                            compatibilityJob = compatibilitySnapshotFor(item, triggerPreparation = false),
                            showThumbnails = settings.showThumbnails,
                            allowDownloads = allowDownloads,
                        )
                    }
                }
                val deviceName = DeviceNameGenerator.generateName(clientIp)
                call.respond(
                    BrowserBootstrap(
                        title = localizedContext.getString(R.string.browser_title),
                        subtitle = localizedContext.getString(R.string.browser_subtitle),
                        authEnabled = state.authEnabled,
                        sessionUrl = if (isAuthorized) {
                            buildSessionAccessUrl(
                                sessionUrl = state.sessionUrl,
                                localAddress = state.networkAvailability.localAddress,
                                port = state.serverPort,
                            )
                        } else {
                            null
                        },
                        sessionPort = if (isAuthorized) state.serverPort else null,
                        categories = BrowserCategories(
                            videos = if (isAuthorized) state.selectedItems.count { it.category == MediaCategory.VIDEO } else 0,
                            photos = if (isAuthorized) state.selectedItems.count { it.category == MediaCategory.PHOTO } else 0,
                            music = if (isAuthorized) state.selectedItems.count { it.category == MediaCategory.MUSIC } else 0,
                            files = if (isAuthorized) state.selectedItems.count { it.category == MediaCategory.FILE } else 0,
                        ),
                        recent = recentCards,
                        themeMode = settings.themeMode,
                        showThumbnails = settings.showThumbnails,
                        largeCards = settings.largeTvCards,
                        prominentDownloadButton = settings.prominentDownloadButton,
                        debugTracing = debugBrowserTracingEnabled,
                        preventDownload = settings.preventDownload,
                        deviceName = deviceName,
                        deviceIp = clientIp,
                        strings = mapOf(
                            "web_hero_eyebrow" to localizedContext.getString(R.string.web_hero_eyebrow),
                            "web_hero_title" to localizedContext.getString(R.string.web_hero_title),
                            "web_hero_desc1" to localizedContext.getString(R.string.web_hero_desc1),
                            "web_hero_desc2" to localizedContext.getString(R.string.web_hero_desc2),
                            "web_hero_desc3" to localizedContext.getString(R.string.web_hero_desc3),
                            "web_hero_stat_shared" to localizedContext.getString(R.string.web_hero_stat_shared),
                            "web_items" to localizedContext.getString(R.string.web_items),
                            "web_hero_stat_ready" to localizedContext.getString(R.string.web_hero_stat_ready),
                            "web_hero_stat_access" to localizedContext.getString(R.string.web_hero_stat_access),
                            "web_access_pin" to localizedContext.getString(R.string.web_access_pin),
                            "web_access_instant" to localizedContext.getString(R.string.web_access_instant),
                            "web_access_pin_desc" to localizedContext.getString(R.string.web_access_pin_desc),
                            "web_access_instant_desc" to localizedContext.getString(R.string.web_access_instant_desc),
                            "web_pin_entry_kicker" to localizedContext.getString(R.string.web_pin_entry_kicker),
                            "web_pin_entry_title" to localizedContext.getString(R.string.web_pin_entry_title),
                            "web_pin_entry_desc" to localizedContext.getString(R.string.web_pin_entry_desc),
                            "web_pin_entry_placeholder" to localizedContext.getString(R.string.web_pin_entry_placeholder),
                            "web_btn_videos" to localizedContext.getString(R.string.web_btn_videos),
                            "web_btn_download_all_files" to localizedContext.getString(R.string.web_btn_download_all_files),
                            "web_cat_videos" to localizedContext.getString(R.string.web_cat_videos),
                            "web_cat_photos" to localizedContext.getString(R.string.web_cat_photos),
                            "web_cat_music" to localizedContext.getString(R.string.web_cat_music),
                            "web_cat_files" to localizedContext.getString(R.string.web_cat_files),
                            "web_recent_title" to localizedContext.getString(R.string.web_recent_title),
                            "web_recent_meta" to localizedContext.getString(R.string.web_recent_meta),
                            "web_nav_home" to localizedContext.getString(R.string.web_nav_home),
                            "web_nav_drop_zone" to localizedContext.getString(R.string.web_nav_drop_zone),
                            "web_nav_logout" to localizedContext.getString(R.string.web_nav_logout),
                            "web_strip_session" to localizedContext.getString(R.string.web_strip_session),
                            "web_strip_device" to localizedContext.getString(R.string.web_strip_device),
                            "web_strip_link" to localizedContext.getString(R.string.web_strip_link),
                            "web_security_pin" to localizedContext.getString(R.string.web_security_pin),
                            "web_security_open" to localizedContext.getString(R.string.web_security_open),
                            "web_status_unknown" to localizedContext.getString(R.string.web_status_unknown),
                            "web_upload_title" to localizedContext.getString(R.string.web_upload_title),
                            "web_upload_subtitle" to localizedContext.getString(R.string.web_upload_subtitle),
                            "web_upload_prompt_title" to localizedContext.getString(R.string.web_upload_prompt_title),
                            "web_upload_prompt_desktop" to localizedContext.getString(R.string.web_upload_prompt_desktop),
                            "web_upload_prompt_mobile" to localizedContext.getString(R.string.web_upload_prompt_mobile),
                            "web_upload_button_browse" to localizedContext.getString(R.string.web_upload_button_browse),
                            "web_upload_target_kicker" to localizedContext.getString(R.string.web_upload_target_kicker),
                            "web_upload_target_status" to localizedContext.getString(R.string.web_upload_target_status),
                            "web_upload_how_kicker" to localizedContext.getString(R.string.web_upload_how_kicker),
                            "web_upload_how_title" to localizedContext.getString(R.string.web_upload_how_title),
                            "web_upload_how_body" to localizedContext.getString(R.string.web_upload_how_body),
                            "web_action_download" to localizedContext.getString(R.string.web_action_download),
                            "web_photo_view" to localizedContext.getString(R.string.web_photo_view),
                            "web_btn_download_all" to localizedContext.getString(R.string.web_btn_download_all),
                            "web_btn_download_selected" to localizedContext.getString(R.string.web_btn_download_selected),
                            "web_btn_download_original" to localizedContext.getString(R.string.web_btn_download_original),
                            "web_error_streaming_codec" to localizedContext.getString(R.string.web_error_streaming_codec),
                            "web_error_downloads_disabled" to localizedContext.getString(R.string.web_error_downloads_disabled),
                            "web_error_video_decode" to localizedContext.getString(R.string.web_error_video_decode),
                            "web_error_video_start" to localizedContext.getString(R.string.web_error_video_start),
                            "web_btn_continue" to localizedContext.getString(R.string.common_continue),
                            "web_btn_select_files" to localizedContext.getString(R.string.web_btn_select_files),
                            "web_status_selection_on" to localizedContext.getString(R.string.web_status_selection_on),
                            "web_btn_select_all" to localizedContext.getString(R.string.web_btn_select_all),
                            "web_btn_clear_selection" to localizedContext.getString(R.string.web_btn_clear_selection),
                            "web_player_slow_start_hint" to localizedContext.getString(R.string.web_player_slow_start_hint),
                            "web_selection_count" to localizedContext.getString(R.string.web_selection_count),
                            "web_library_desc_download" to localizedContext.getString(R.string.web_library_desc_download),
                            "web_library_desc_browse" to localizedContext.getString(R.string.web_library_desc_browse),
                            "web_search_placeholder" to localizedContext.getString(R.string.web_search_placeholder),
                            "web_library_empty" to localizedContext.getString(R.string.web_library_empty),
                            "library_title" to localizedContext.getString(R.string.library_title),
                            "web_upload_requesting" to localizedContext.getString(R.string.web_upload_requesting),
                            "web_upload_waiting" to localizedContext.getString(R.string.web_upload_waiting),
                            "web_upload_success_title" to localizedContext.getString(R.string.web_upload_success_title),
                            "web_upload_success_detail_single" to localizedContext.getString(R.string.web_upload_success_detail_single),
                            "web_upload_success_detail_multiple" to localizedContext.getString(R.string.web_upload_success_detail_multiple),
                            "web_upload_failed_title" to localizedContext.getString(R.string.web_upload_failed_title),
                            "web_upload_failed_detail" to localizedContext.getString(R.string.web_upload_failed_detail),
                            "web_player_error_open" to localizedContext.getString(R.string.web_player_error_open),
                            "web_player_opening" to localizedContext.getString(R.string.web_player_opening),
                            "web_player_ready" to localizedContext.getString(R.string.web_player_ready),
                            "web_player_starting" to localizedContext.getString(R.string.web_player_starting),
                            "web_player_wait_desc" to localizedContext.getString(R.string.web_player_wait_desc),
                            "web_player_ready_desc" to localizedContext.getString(R.string.web_player_ready_desc),
                            "web_player_starting_desc" to localizedContext.getString(R.string.web_player_starting_desc),
                            "web_player_try_again" to localizedContext.getString(R.string.web_player_try_again),
                            "web_player_status_opening" to localizedContext.getString(R.string.web_player_status_opening),
                            "web_player_status_ready" to localizedContext.getString(R.string.web_player_status_ready),
                            "web_player_status_playing" to localizedContext.getString(R.string.web_player_status_playing),
                            "web_btn_back" to localizedContext.getString(R.string.web_btn_back),
                            "web_status_subtitles_available" to localizedContext.getString(R.string.web_status_subtitles_available),
                            "web_now_playing" to localizedContext.getString(R.string.web_now_playing),
                            "common_play" to localizedContext.getString(R.string.common_play),
                            "common_pause" to localizedContext.getString(R.string.common_pause),
                            "common_resume" to localizedContext.getString(R.string.common_resume),
                        ),
                    ),
                )
            }

            get("/api/items") {
                if (!call.authorizeBrowserCall()) return@get
                val settings = settingsRepository.settings.first()
                val category = call.request.queryParameters["category"]?.lowercase()
                val query = call.request.queryParameters["q"]?.trim().orEmpty()
                val items = sessionManager.sessionState.value.selectedItems
                    .filter { item ->
                        when (category) {
                            null, "", "all" -> true
                            "videos" -> item.category == MediaCategory.VIDEO
                            "photos" -> item.category == MediaCategory.PHOTO
                            "music" -> item.category == MediaCategory.MUSIC
                            "files" -> item.category == MediaCategory.FILE
                            else -> true
                        }
                    }
                    .filter { item ->
                        query.isBlank() || item.displayName.contains(query, ignoreCase = true)
                    }
                    .sortedByDescending { it.dateAddedEpochMs }
                val cards = mutableListOf<BrowserItemCard>()
                val allowDownloads = !settings.preventDownload
                for (item in items) {
                    cards += BrowserItemCard.from(
                        item = item,
                        compatibilityJob = compatibilitySnapshotFor(item, triggerPreparation = false),
                        showThumbnails = settings.showThumbnails,
                        allowDownloads = allowDownloads,
                    )
                }
                call.respond(cards)
            }

            get("/api/item/{id}") {
                if (!call.authorizeBrowserCall()) return@get
                try {
                    val settings = settingsRepository.settings.first()
                    val item = resolveItem(call.parameters["id"]) ?: run {
                        call.respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_file_unavailable)))
                        return@get
                    }

                    // Only inspect — do not auto-trigger preparation here.
                    // The browser explicitly requests preparation via POST /api/compat/{id}/prepare,
                    // which prevents duplicate transcode jobs when the library state updates.
                    val job = compatibilitySnapshotFor(
                        item = item,
                        triggerPreparation = false,
                        prioritizePreparation = false,
                    )
                    val streamReady = compatibilityStreamReady(item)

                    debugLogSink.log(
                        "WebBrowser",
                        "details id=${item.id} name=${item.displayName} mode=${item.playbackDecision.mode} " +
                                "status=${job.status} streamReady=$streamReady complete=${job.directReady} " +
                                "asset=${job.preparedAsset?.filePath?.substringAfterLast('/') ?: "NONE"}",
                    )

                    call.respond(
                        BrowserItemDetails.from(
                            item = item,
                            compatibilityJob = job,
                            streamReady = streamReady,
                            allowDownloads = !settings.preventDownload,
                        ),
                    )
                } catch (e: Exception) {
                    debugLogSink.log("WebBrowser/details", "CRITICAL ERROR id=${call.parameters["id"]}", e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorPayload("Internal server error."))
                }
            }

            get("/api/compat/{id}") {
                if (!call.authorizeBrowserCall()) return@get
                val item = resolveItem(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_file_unavailable)))
                    return@get
                }
                val job = compatibilitySnapshotFor(item, triggerPreparation = false)
                val ready = compatibilityStreamReady(item)
                // Throttled logging: only log on state change or 5% progress bucket change
                val currentBucket = job.coarseProgressBucket
                val lastKey = lastCompatLogKey[item.id]
                val newKey = "${job.status}|$currentBucket|$ready"
                if (lastKey != newKey) {
                    lastCompatLogKey[item.id] = newKey
                    debugLogSink.log(
                        "WebCompat",
                        "poll id=${item.id} mode=${item.playbackDecision.mode} status=${job.status} ready=$ready complete=${job.directReady} progress=${job.progressPercent} asset=${job.preparedAsset?.filePath?.substringAfterLast('/')}",
                    )
                }
                call.respond(
                    CompatibilityStatusPayload.from(
                        job = job,
                        ready = ready,
                    ),
                )
            }

            post("/api/compat/{id}/prepare") {
                if (!call.authorizeBrowserCall()) return@post
                val item = resolveItem(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_file_unavailable)))
                    return@post
                }
                // If the browser reports that DIRECT play failed (force=true param),
                // escalate the file to REMUX so it gets a prepared compatible asset.
                val forceCompat = call.request.queryParameters["force"] == "true"
                val effectiveItem = if (forceCompat && item.playbackDecision.mode == PlaybackMode.DIRECT) {
                    debugLogSink.log("WebCompat", "direct_play_escalation id=${item.id} name=${item.displayName} — browser reported DIRECT failure, escalating to REMUX")
                    item.copy(
                        playbackDecision = item.playbackDecision.copy(
                            mode = PlaybackMode.REMUX,
                            reason = "Browser rejected direct playback; preparing compatible version",
                            compatibilityLabel = "Preparing compatible version",
                        ),
                    )
                } else {
                    item
                }
                val job = compatibilitySnapshotFor(effectiveItem, triggerPreparation = true, prioritizePreparation = true)
                val ready = compatibilityStreamReady(effectiveItem)
                debugLogSink.log(
                    "WebCompat",
                    "prepare id=${item.id} mode=${item.playbackDecision.mode} status=${job.status} ready=$ready complete=${job.directReady} progress=${job.progressPercent} asset=${job.preparedAsset?.filePath}",
                )
                call.respond(
                    CompatibilityStatusPayload.from(
                        job = job,
                        ready = ready,
                    ),
                )
            }

            post("/api/compat/{id}/seek") {
                if (!call.authorizeBrowserCall()) return@post
                val offsetMs = call.request.queryParameters["offsetMs"]?.toLongOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorPayload("Missing offsetMs parameter."))
                    return@post
                }
                val item = resolveItem(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_file_unavailable)))
                    return@post
                }
                
                val job = compatibilityPipeline.requestSeek(item, offsetMs)
                val ready = compatibilityStreamReady(item)
                debugLogSink.log(
                    "WebSeek",
                    "seek id=${item.id} offsetMs=$offsetMs status=${job.status} ready=$ready",
                )
                call.respond(
                    CompatibilityStatusPayload.from(
                        job = job,
                        ready = ready,
                    ),
                )
            }

            get("/api/compat/{id}/file") {
                if (!call.authorizeBrowserCall()) return@get
                val itemId = call.parameters["id"]
                val item = resolveItem(itemId) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload(localizedContext().getString(R.string.browser_file_unavailable)))
                    return@get
                }
                val job = compatibilityPipeline.currentJob(item.id) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload(localizedContext().getString(R.string.browser_optimized_unavailable)))
                    return@get
                }
                val preparedAsset = job.preparedAsset ?: run {
                    call.respond(HttpStatusCode.Accepted, ErrorPayload(localizedContext().getString(R.string.browser_video_part_preparing)))
                    return@get
                }

                // Hard Gate: Reject direct file requests if the preparation is not 100% complete.
                // Growing files MUST be played via HLS (/hls/...) to ensure correct duration.
                if (!preparedAsset.isComplete && !job.directReady) {
                    debugLogSink.log("WebCompat/file", "REJECTED id=${item.id} reason=incomplete")
                    call.respond(HttpStatusCode.Conflict, ErrorPayload("Direct playback is unavailable until conversion is finalized. Please use HLS."))
                    return@get
                }

                call.streamCachedFile(
                    item = item,
                    playbackSource = PlaybackSource.CachedFile(
                        filePath = preparedAsset.filePath,
                        mimeType = "video/mp4",
                        sizeBytes = preparedAsset.sizeBytes,
                        isComplete = true, // We are certain here because of the gate above
                        allowGrowing = false, // Never allow growing for the direct-file endpoint
                    ),
                    asAttachment = false,
                    activity = ClientActivity.WATCHING_VIDEO,
                )
            }

            post("/api/compat/{id}/report_error") {
                if (!call.authorizeBrowserCall()) return@post
                val itemId = call.parameters["id"] ?: return@post
                val item = resolveItem(itemId) ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                
                debugLogSink.log("WebCompat/Error", "Client reported playback failure for id=${item.id} name=${item.displayName}")
                
                // Invalidate the current asset. This will clear the cache and reset the job.
                compatibilityPipeline.invalidate(item.id)
                
                // Re-inspect to trigger a fresh decision. 
                // The decision engine will see the source again and the next check will
                // start a new prep job if needed.
                compatibilityPipeline.inspect(item)
                
                call.respond(HttpStatusCode.NoContent)
            }

            post("/api/debug/browser") {
                if (!debugBrowserTracingEnabled) {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_debug_unavailable)))
                    return@post
                }
                if (!call.authorizeBrowserCall()) return@post
                val payload = call.receiveNullable<BrowserDebugPayload>()
                if (payload == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_debug_missing_payload)))
                    return@post
                }
                debugLogSink.log(
                    "WebTrace",
                    "host=${call.remoteHost()} route=${payload.route} event=${payload.event} details=${payload.details.orEmpty()} ua=${call.request.header(HttpHeaders.UserAgent).orEmpty()}",
                )
                call.respond(AuthResult(success = true))
            }

            post("/auth/login") {
                val payload = call.receiveNullable<LoginPayload>()
                val enteredPin = payload?.pin.orEmpty()
                val ipAddress = call.remoteHost()
                if (!sessionManager.isPinValid(enteredPin)) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_pin_mismatch)))
                    return@post
                }

                val token = sessionManager.generateToken(ipAddress)
                call.response.cookies.append(
                    Cookie(
                        name = COOKIE_NAME,
                        value = token,
                        path = "/",
                        httpOnly = true,
                    ),
                )
                sessionManager.observeClient(ipAddress, call.request.header(HttpHeaders.UserAgent), ClientActivity.BROWSING)
                call.respond(AuthResult(success = true))
            }

            post("/auth/logout") {
                call.response.cookies.appendExpired(COOKIE_NAME, path = "/")
                call.respond(AuthResult(success = true))
            }

            get("/thumb/{id}") {
                if (!call.authorizeBrowserCall()) return@get
                val settings = settingsRepository.settings.first()
                if (!settings.showThumbnails) {
                    debugLogSink.log("WebThumb", "blocked id=${call.parameters["id"]} reason=disabled")
                    call.respond(HttpStatusCode.NotFound, ErrorPayload("Preview unavailable"))
                    return@get
                }

                // Deprioritize non-essential thumbnail work during heavy prepare jobs.
                // If a TRANSCODE or TRANSMUX job is actively PREPARING/ANALYZING, defer
                // video frame extraction (which is CPU-heavy) to avoid competing for resources.
                val hasHeavyPrepareJob = compatibilityPipeline.jobs.value.values.any { job ->
                    (job.status == CompatibilityStatus.PREPARING || job.status == CompatibilityStatus.ANALYZING) &&
                        (job.decision.mode == com.ghoststream.core.model.PlaybackMode.TRANSCODE ||
                         job.decision.mode == com.ghoststream.core.model.PlaybackMode.TRANSMUX)
                }

                val item = resolveItem(call.parameters["id"]) ?: run {
                    debugLogSink.log("WebThumb", "missing id=${call.parameters["id"]}")
                    call.respond(HttpStatusCode.NotFound, ErrorPayload("Preview unavailable"))
                    return@get
                }

                val timeMs = call.request.queryParameters["timeMs"]?.toLongOrNull()

                // During heavy prepare, skip frame-at-time extraction (expensive) and only
                // serve cached/cheap poster thumbnails. This frees CPU for the active transcode.
                val bytesOrNull = if (hasHeavyPrepareJob && timeMs != null) {
                    // Skip scrubbing frame extraction during heavy prepare — just serve poster
                    mediaAnalyzer.loadThumbnailBytes(item)
                } else if (timeMs != null) {
                    mediaAnalyzer.extractFrameAtMs(item, timeMs) ?: mediaAnalyzer.loadThumbnailBytes(item)
                } else {
                    mediaAnalyzer.loadThumbnailBytes(item)
                }

                if (bytesOrNull == null) {
                    // Don't log thumbnail misses during heavy prepare (reduces noise)
                    if (!hasHeavyPrepareJob) {
                        debugLogSink.log("WebThumb", "empty id=${item.id} name=${item.displayName} timeMs=$timeMs")
                    }
                    call.respond(HttpStatusCode.NotFound, ErrorPayload("Preview unavailable"))
                    return@get
                }

                if (!hasHeavyPrepareJob) {
                    debugLogSink.log("WebThumb", "served id=${item.id} bytes=${bytesOrNull.size} timeMs=$timeMs")
                }
                // Don't call observeClient for thumbnail requests — this was causing
                // SessionState updates on every thumb fetch, which cascaded into
                // notification updates. Thumbnail fetches are passive reads, not activity.
                call.respondBytes(bytesOrNull, ContentType.Image.JPEG)
            }

            get("/download/{id}") {
                if (!call.authorizeBrowserCall()) return@get
                val settings = settingsRepository.settings.first()
                if (settings.preventDownload) {
                    call.respond(HttpStatusCode.Forbidden, ErrorPayload(localizedContext().getString(R.string.web_error_downloads_disabled)))
                    return@get
                }
                call.streamItem(
                    itemId = call.parameters["id"],
                    asAttachment = true,
                    activity = ClientActivity.DOWNLOADING,
                )
            }

            get("/stream/{id}") {
                if (!call.authorizeBrowserCall()) return@get
                val itemId = call.parameters["id"]
                val item = resolveItem(itemId) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload(localizedContext().getString(R.string.browser_file_unavailable)))
                    return@get
                }

                // Hard Gate: Prevent browsers from trying to play incompatible raw containers (MKV/TS)
                // during preparation. They must use HLS or wait for the finalized MP4.
                if (item.category == MediaCategory.VIDEO && item.playbackDecision.mode != PlaybackMode.DIRECT) {
                    debugLogSink.log("WebStream", "REJECTED id=${item.id} name=${item.displayName} mode=${item.playbackDecision.mode} reason=incompatible-raw")
                    call.respond(HttpStatusCode.Forbidden, ErrorPayload("Direct stream of this container is disabled for compatibility. Use the prepared stream instead."))
                    return@get
                }

                val activity = when (item.category) {
                    MediaCategory.VIDEO -> ClientActivity.WATCHING_VIDEO
                    MediaCategory.PHOTO -> ClientActivity.VIEWING_PHOTO
                    MediaCategory.MUSIC -> ClientActivity.PLAYING_MUSIC
                    MediaCategory.FILE -> ClientActivity.BROWSING
                }
                call.streamItem(
                    itemId = itemId,
                    asAttachment = false,
                    activity = activity,
                )
            }

            // Master playlist — advertises the stream with an explicit CODECS hint.
            // The codec string is read dynamically from the fMP4 moov box so it exactly
            // matches what the Android encoder produced.  A hardcoded Baseline string
            // caused bufferAppendError when the hardware encoder used Main/High Profile
            // or when DefaultEncoderFactory fallback produced HEVC instead of H.264.
            get("/hls/{id}/master.m3u8") {
                if (!call.authorizeBrowserCall()) return@get
                try {
                    val source = call.resolveHlsSource(call.parameters["id"]) ?: return@get
                    val ua = call.request.header(HttpHeaders.UserAgent) ?: ""
                    // Read the video codec string from the fMP4 init segment (fast, non-blocking —
                    // the moov box is only a few KB).  Returns null if the file is not yet written
                    // or the codec cannot be determined; buildHlsMasterPlaylist falls back gracefully.
                    val hlsIndex = withContext(Dispatchers.IO) {
                        try {
                            FragmentedMp4HlsIndexer.read(
                                source.file,
                                fragmentDurationSeconds = HLS_SEGMENT_DURATION_SECONDS,
                            )
                        } catch (e: Exception) {
                            debugLogSink.log("WebHls/master", "Failed to index moov for id=${source.item.id}", e)
                            null
                        }
                    }
                    val detectedVideoCodec = hlsIndex?.videoCodecString
                    // ── DEBUG ────────────────────────────────────────────────────────────────
                    debugLogSink.log(
                        "WebHls/master",
                        "id=${source.item.id} " +
                                "mode=${source.item.playbackDecision.mode} " +
                                "file=${source.file.name} " +
                                "fileBytes=${source.file.length()} " +
                                "detectedCodec=${detectedVideoCodec ?: "NULL→fallback:avc1.640028"} " +
                                "initBytes=${hlsIndex?.initSegmentLength ?: "n/a"} " +
                                "segments=${hlsIndex?.segments?.size ?: "n/a"}",
                    )
                    val masterPlaylist = buildHlsMasterPlaylist(
                        itemId = source.item.id,
                        detectedVideoCodec = detectedVideoCodec,
                        width = hlsIndex?.width,
                        height = hlsIndex?.height,
                    )
                    debugLogSink.log("WebHls/master", "serving:\n$masterPlaylist")
                    // ────────────────────────────────────────────────────────────────────────
                    // Both Apple-style and IANA content types are accepted by all HLS players.
                    val hlsContentType = if (ua.contains("Safari", ignoreCase = true) && !ua.contains("Chrome", ignoreCase = true)) {
                        "application/vnd.apple.mpegurl"
                    } else {
                        "application/x-mpegURL"
                    }
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                    call.respondText(
                        text = masterPlaylist,
                        contentType = ContentType.parse(hlsContentType),
                    )
                } catch (e: Exception) {
                    debugLogSink.log("WebHls/master", "CRITICAL ERROR id=${call.parameters["id"]}", e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorPayload("Internal server error."))
                }
            }

            get("/hls/{id}/playlist.m3u8") {
                if (!call.authorizeBrowserCall()) return@get
                try {
                    val source = call.resolveHlsSource(call.parameters["id"]) ?: return@get
                    // Require a minimum number of segments before serving the playlist.
                    // This prevents the player from starting with too little buffer and
                    // immediately stalling on devices where transcoding lags behind.
                    val index = awaitHlsIndex(
                        itemId = source.item.id,
                        file = source.file,
                        requireFirstSegment = true,
                        requiredSegmentIndex = MIN_SEGMENTS_BEFORE_PLAY - 1,
                    ) ?: run {
                        debugLogSink.log("WebHls", "playlist pending id=${source.item.id}")
                        call.respond(HttpStatusCode.Accepted, ErrorPayload(localizedContext().getString(R.string.browser_hls_not_ready)))
                        return@get
                    }
                    if (index.segments.isEmpty()) {
                        debugLogSink.log("WebHls", "playlist empty id=${source.item.id} init=${index.initSegmentLength} file=${index.fileLength}")
                        call.respond(HttpStatusCode.Accepted, ErrorPayload(localizedContext().getString(R.string.browser_hls_not_ready)))
                        return@get
                    }
                    debugLogSink.log(
                        "WebHls",
                        "playlist served id=${source.item.id} segments=${index.segments.size} init=${index.initSegmentLength} complete=${source.job.directReady}",
                    )
                    sessionManager.observeClient(
                        call.remoteHost(),
                        call.request.header(HttpHeaders.UserAgent),
                        ClientActivity.WATCHING_VIDEO,
                    )
                    val ua = call.request.header(HttpHeaders.UserAgent) ?: ""
                    val hlsContentType = if (ua.contains("Safari", ignoreCase = true) && !ua.contains("Chrome", ignoreCase = true)) {
                        "application/vnd.apple.mpegurl"
                    } else {
                        "application/x-mpegURL"
                    }
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                    call.respondText(
                        text = buildHlsPlaylist(
                            itemId = source.item.id,
                            index = index,
                            complete = source.job.preparedAsset?.isComplete == true || source.job.status == CompatibilityStatus.READY,
                        ),
                        contentType = ContentType.parse(hlsContentType),
                    )
                } catch (e: Exception) {
                    debugLogSink.log("WebHls/playlist", "CRITICAL ERROR id=${call.parameters["id"]}", e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorPayload("Internal server error."))
                }
            }

            get("/hls/{id}/init.mp4") {
                if (!call.authorizeBrowserCall()) return@get
                try {
                    val source = call.resolveHlsSource(call.parameters["id"]) ?: return@get
                    val index = awaitHlsIndex(
                        itemId = source.item.id,
                        file = source.file,
                        requireFirstSegment = false,
                    ) ?: run {
                        debugLogSink.log("WebHls", "init pending id=${source.item.id}")
                        call.respond(HttpStatusCode.Accepted, ErrorPayload(localizedContext().getString(R.string.browser_hls_not_ready)))
                        return@get
                    }
                    if (index.initSegmentLength <= 0L) {
                        debugLogSink.log("WebHls", "init empty id=${source.item.id}")
                        call.respond(HttpStatusCode.Accepted, ErrorPayload(localizedContext().getString(R.string.browser_hls_not_ready)))
                        return@get
                    }
                    debugLogSink.log(
                        "WebHls/init",
                        "id=${source.item.id} " +
                                "initBytes=${index.initSegmentLength} " +
                                "detectedCodec=${index.videoCodecString ?: "NULL"} " +
                                "fileBytes=${source.file.length()} " +
                                "segments=${index.segments.size}",
                    )
                    call.streamFileSlice(
                        file = source.file,
                        mimeType = "video/mp4",
                        byteRange = 0L until index.initSegmentLength,
                        activity = ClientActivity.WATCHING_VIDEO,
                    )
                } catch (e: Exception) {
                    debugLogSink.log("WebHls/init", "CRITICAL ERROR id=${call.parameters["id"]}", e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorPayload("Internal server error."))
                }
            }

            get("/hls/{id}/segment/{index}.m4s") {
                if (!call.authorizeBrowserCall()) return@get
                try {
                    val source = call.resolveHlsSource(call.parameters["id"]) ?: return@get
                    val indexInManifest = call.parameters["index"]?.toIntOrNull() ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorPayload("That video segment is invalid."))
                        return@get
                    }

                    // Map the manifest segment index to the actual segment index in the
                    // current fragmented MP4 file (which may have been started at an offset).
                    val jobStartSegIndex = (source.job.startOffsetMs / 1000.0 / HLS_SEGMENT_DURATION_SECONDS).toInt()
                    val targetFileSegIndex = indexInManifest - jobStartSegIndex

                    if (targetFileSegIndex < 0) {
                        // This segment belongs to the portion of the video BEFORE the current
                        // seek point. Since the transcoder has jumped ahead, we cannot
                        // serve these segments from the current job.
                        call.respond(HttpStatusCode.NotFound, ErrorPayload("Segment is before seek point"))
                        return@get
                    }

                    val index = awaitHlsIndex(
                        itemId = source.item.id,
                        file = source.file,
                        requireFirstSegment = true,
                        requiredSegmentIndex = indexInManifest,
                    ) ?: run {
                        debugLogSink.log("WebHls", "segment pending id=${source.item.id} index=$indexInManifest")
                        call.respond(HttpStatusCode.Accepted, ErrorPayload("Preparing the next HLS segment."))
                        return@get
                    }
                    val segment = index.segments.getOrNull(targetFileSegIndex) ?: run {
                        val completed = source.job.preparedAsset?.isComplete == true || source.job.status == CompatibilityStatus.READY
                        debugLogSink.log("WebHls", "segment missing id=${source.item.id} index=$indexInManifest target=$targetFileSegIndex available=${index.segments.size} complete=$completed")
                        val status = if (completed) HttpStatusCode.NotFound else HttpStatusCode.Accepted
                        val message = if (completed) "That video segment is no longer available." else "Preparing the next HLS segment."
                        call.respond(status, ErrorPayload(message))
                        return@get
                    }
                    // Read the raw segment bytes so we can patch the TFHD box before serving.
                    // Media3 InAppMuxer writes tfhd.base-data-offset as an absolute file offset,
                    // which MSE forbids.  MseTfhdPatcher rewrites it to use default-base-is-moof.
                    val rawSegmentBytes = withContext(Dispatchers.IO) {
                        try {
                            java.io.RandomAccessFile(source.file, "r").use { raf ->
                                raf.seek(segment.offset)
                                ByteArray(segment.length.toInt()).also { raf.readFully(it) }
                            }
                        } catch (e: Exception) {
                            debugLogSink.log("WebHls", "Failed to read segment id=${source.item.id} index=$targetFileSegIndex", e)
                            null
                        }
                    }
                    if (rawSegmentBytes == null) {
                        call.respond(HttpStatusCode.InternalServerError, ErrorPayload("Could not read segment."))
                        return@get
                    }
                    val segmentBytes = try {
                        MseTfhdPatcher.patch(rawSegmentBytes, segment.offset)
                    } catch (e: Exception) {
                        debugLogSink.log("WebHls", "Patching failed id=${source.item.id} index=$targetFileSegIndex", e)
                        rawSegmentBytes // Fallback to unpatched; might fail in browser but better than 500
                    }
                    val patched = segmentBytes !== rawSegmentBytes
                    val savedBytes = rawSegmentBytes.size - segmentBytes.size
                    debugLogSink.log(
                        "WebHls",
                        "segment served id=${source.item.id} manifestIndex=$indexInManifest fileIndex=$targetFileSegIndex " +
                                "bytes=${segment.length} patchedForMse=$patched${if (patched) " saved=$savedBytes" else ""}",
                    )
                    call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                    call.respondBytes(
                        bytes = segmentBytes,
                        contentType = ContentType.parse("video/mp4"),
                        status = HttpStatusCode.OK,
                    )
                } catch (e: Exception) {
                    debugLogSink.log("WebHls/segment", "CRITICAL ERROR id=${call.parameters["id"]} index=${call.parameters["index"]}", e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorPayload("Internal server error."))
                }
            }


            get("/subtitle/{id}") {
                if (!call.authorizeBrowserCall()) return@get
                val item = resolveItem(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload("Subtitle unavailable"))
                    return@get
                }
                val subtitleId = item.subtitleMatch?.subtitleItemId ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload("Subtitle unavailable"))
                    return@get
                }
                val subtitleItem = resolveItem(subtitleId) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload("Subtitle unavailable"))
                    return@get
                }
                val text = readText(Uri.parse(subtitleItem.uri)) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload("Subtitle unavailable"))
                    return@get
                }
                call.respondText(text = convertToWebVtt(text), contentType = ContentType.parse("text/vtt"))
            }

            post("/api/upload/request") {
                if (!call.authorizeBrowserCall()) return@post
                val payload = call.receiveNullable<UploadRequestPayload>() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorPayload("Invalid upload request."))
                    return@post
                }
                val requestId = UUID.randomUUID().toString()
                val request = UploadRequest(
                    id = requestId,
                    fileName = payload.fileName,
                    fileCount = payload.fileCount,
                    sizeBytes = payload.sizeBytes,
                    requesterIp = call.remoteHost(),
                    requestedAtEpochMs = System.currentTimeMillis(),
                )
                sessionManager.registerUploadRequest(request)
                
                val settings = settingsRepository.settings.first()
                if (settings.requireUploadApproval) {
                    sessionManager.submitUploadRequest(request)
                    val accepted = sessionManager.waitForUploadResolution(requestId)
                    if (!accepted) {
                        call.respond(HttpStatusCode.Forbidden, ErrorPayload("The host declined your file transfer."))
                        return@post
                    }
                }
                
                call.respond(UploadRequestResponse(requestId = requestId, accepted = true))
            }

            post("/api/upload/execute/{id}") {
                if (!call.authorizeBrowserCall()) return@post
                val requestId = call.parameters["id"] ?: return@post

                val multipart = call.receiveMultipart()
                val uploadedUris = mutableListOf<Uri>()
                var totalUploadedBytes = 0L
                var currentFileIndex = 0

                try {
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            currentFileIndex += 1
                            val fileName = part.originalFileName ?: "uploaded_file"
                            val mimeType = part.contentType?.toString() ?: "application/octet-stream"
                            sessionManager.onIncomingUploadStarted(
                                requestId = requestId,
                                fileName = fileName,
                                currentFileIndex = currentFileIndex,
                            )

                            part.streamProvider().use { input ->
                                storageRepository.saveUploadedFile(
                                    fileName = fileName,
                                    mimeType = mimeType,
                                    content = input,
                                    peer = call.remoteHost(),
                                    onBytesCopied = { bytesCopied ->
                                        totalUploadedBytes += bytesCopied
                                        sessionManager.onIncomingUploadProgress(
                                            requestId = requestId,
                                            fileName = fileName,
                                            currentFileIndex = currentFileIndex,
                                            transferredBytes = totalUploadedBytes,
                                        )
                                    },
                                )?.let { uri ->
                                    uploadedUris.add(uri)
                                }
                            }
                        }
                        part.dispose()
                    }

                    if (uploadedUris.isNotEmpty()) {
                        storageRepository.addFiles(uploadedUris)
                        sessionManager.completeIncomingUpload(requestId)
                        sessionManager.clearIncomingUpload(requestId)
                        call.respond(AuthResult(success = true))
                    } else {
                        sessionManager.clearIncomingUpload(requestId)
                        call.respond(HttpStatusCode.InternalServerError, ErrorPayload("No files were successfully saved."))
                    }
                } catch (error: Exception) {
                    sessionManager.clearIncomingUpload(requestId)
                    throw error
                }
            }

            post("/api/upload/cancel/{id}") {
                if (!call.authorizeBrowserCall()) return@post
                val requestId = call.parameters["id"] ?: return@post
                sessionManager.resolveUploadRequest(requestId, accepted = false)
                sessionManager.clearIncomingUpload(requestId)
                call.respond(AuthResult(success = true))
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.serveShellPage() {
        val localizedContext = localizedContext()
        if (sessionManager.isBlocked(remoteHost())) {
            respond(HttpStatusCode.Forbidden, ErrorPayload(localizedContext.getString(R.string.browser_device_blocked)))
            return
        }
        val state = sessionManager.sessionState.value
        if (state.authEnabled && request.path() != "/login" && !sessionManager.validateToken(request.cookies[COOKIE_NAME])) {
            respondRedirect("/login")
            return
        }
        val html = assetLoader.readText("web/index.html")
            .replace("__SESSION_TITLE__", localizedContext.getString(R.string.browser_title))
            .replace("__SESSION_SUBTITLE__", localizedContext.getString(R.string.browser_subtitle))
        respondText(html, ContentType.Text.Html)
    }

    private suspend fun io.ktor.server.application.ApplicationCall.authorizeBrowserCall(): Boolean {
        val localizedContext = localizedContext()
        val ipAddress = remoteHost()
        if (sessionManager.isBlocked(ipAddress)) {
            respond(HttpStatusCode.Forbidden, ErrorPayload(localizedContext.getString(R.string.browser_device_blocked)))
            return false
        }
        val state = sessionManager.sessionState.value
        if (state.authEnabled && !sessionManager.validateToken(request.cookies[COOKIE_NAME])) {
            respond(HttpStatusCode.Unauthorized, ErrorPayload(localizedContext.getString(R.string.browser_enter_pin)))
            return false
        }
        sessionManager.observeClient(ipAddress, request.header(HttpHeaders.UserAgent), ClientActivity.BROWSING)
        return true
    }

    private suspend fun localizedContext(): Context = localizedContext(settingsRepository.settings.first().languageTag)

    private fun localizedContext(languageTag: String?): Context {
        if (languageTag.isNullOrBlank()) return context
        val locale = Locale.forLanguageTag(languageTag)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    private suspend fun compatibilitySnapshotFor(
        item: SharedItem,
        triggerPreparation: Boolean,
        prioritizePreparation: Boolean = false,
    ): CompatibilityJob {
        return if (triggerPreparation && item.playbackDecision.mode != PlaybackMode.DIRECT) {
            compatibilityPipeline.requestPreparation(item, prioritize = prioritizePreparation)
        } else {
            compatibilityPipeline.inspect(item)
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.streamItem(
        itemId: String?,
        asAttachment: Boolean,
        activity: ClientActivity,
    ) {
        val item = resolveItem(itemId) ?: run {
            respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_file_unavailable)))
            return
        }
        if (!item.isAvailable) {
            respond(HttpStatusCode.Gone, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_file_unavailable)))
            return
        }

        val playbackSource = if (asAttachment) {
            PlaybackSource.OriginalUri(
                uriString = item.uri,
                mimeType = item.mimeType ?: "application/octet-stream",
                sizeBytes = item.sizeBytes,
            )
        } else {
            when (val resolution = compatibilityPipeline.resolvePlayback(item)) {
                is PlaybackResolution.Ready -> resolution.source
                is PlaybackResolution.Pending -> {
                    respond(HttpStatusCode.Accepted, ErrorPayload(resolution.job.message))
                    return
                }
                is PlaybackResolution.Failed -> {
                    respond(HttpStatusCode.Conflict, ErrorPayload(resolution.job.message))
                    return
                }
            }
        }

        when (playbackSource) {
            is PlaybackSource.OriginalUri -> streamOriginalUri(
                item = item,
                playbackSource = playbackSource,
                asAttachment = asAttachment,
                activity = activity,
            )

            is PlaybackSource.CachedFile -> streamCachedFile(
                item = item,
                playbackSource = playbackSource,
                asAttachment = asAttachment,
                activity = activity,
            )
        }
    }

    private fun resolveItem(itemId: String?): SharedItem? {
        return itemId?.let(storageRepository::findItemById)
    }

    private suspend fun io.ktor.server.application.ApplicationCall.streamOriginalUri(
        item: SharedItem,
        playbackSource: PlaybackSource.OriginalUri,
        asAttachment: Boolean,
        activity: ClientActivity,
    ) {
        val resolver = context.contentResolver
        val uri = Uri.parse(playbackSource.uriString)
        val descriptor = resolver.openAssetFileDescriptor(uri, "r") ?: run {
            respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_file_unavailable)))
            return
        }

        descriptor.use { assetDescriptor ->
            val totalLength = if (assetDescriptor.length >= 0) assetDescriptor.length else playbackSource.sizeBytes
            val range = parseRange(request.header(HttpHeaders.Range), totalLength)
            val status = if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK
            val lengthToSend = range?.let { (it.last - it.first + 1).coerceAtLeast(0) } ?: totalLength
            val mimeType = playbackSource.mimeType ?: item.playbackDecision.browserMimeType ?: item.mimeType ?: "application/octet-stream"
            val callerHost = remoteHost()

            sessionManager.onTransferStarted(callerHost, activity, asAttachment)

            // Log outgoing transfer to history if it's a full download or start of a stream
            if (asAttachment || range == null || range.first == 0L) {
                historyRepository.addRecord(
                    TransferRecord(
                        id = UUID.randomUUID().toString(),
                        name = item.displayName,
                        direction = TransferDirection.SENT,
                        sizeBytes = totalLength,
                        timestampMs = System.currentTimeMillis(),
                        peer = callerHost,
                        category = item.category,
                    )
                )
            }

            try {
                respond(object : OutgoingContent.WriteChannelContent() {
                    override val status: HttpStatusCode = if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK
                    override val contentType: ContentType = ContentType.parse(mimeType)
                    override val contentLength: Long = lengthToSend
                    override val headers = io.ktor.http.Headers.build {
                        append(HttpHeaders.AcceptRanges, "bytes")
                        if (range != null) {
                            append(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/$totalLength")
                        }
                        if (asAttachment) {
                            append(
                                HttpHeaders.ContentDisposition,
                                ContentDisposition.Attachment.withParameter(
                                    ContentDisposition.Parameters.FileName,
                                    item.displayName,
                                ).toString(),
                            )
                        }
                    }

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        withContext(Dispatchers.IO) {
                            assetDescriptor.createInputStream().use { input ->
                                seekStream(input, range?.first ?: 0L)
                                val buffer = ByteArray(STREAMING_BUFFER_SIZE)
                                var remaining = lengthToSend
                                while (remaining > 0) {
                                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                    val read = input.read(buffer, 0, toRead)
                                    if (read <= 0) break
                                    channel.writeFully(buffer, 0, read)
                                    channel.flush()
                                    remaining -= read
                                    sessionManager.onTransferProgress(callerHost, read.toLong(), activity)
                                }
                            }
                        }
                    }
                })
            } finally {
                sessionManager.onTransferCompleted(callerHost, activity, asAttachment)
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.streamCachedFile(
        item: SharedItem,
        playbackSource: PlaybackSource.CachedFile,
        asAttachment: Boolean,
        activity: ClientActivity,
        expectedTotalSize: Long? = null,
    ) {
        val file = File(playbackSource.filePath)
        if (!file.exists()) {
            respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_optimized_unavailable)))
            return
        }

        // Suspicious File Check: If a "complete" file is suspiciously small (e.g. < 10KB),
        // it is likely a legacy stub or a failed transcode from a pre-Atomic Rename run.
        // We reject it here so the client triggers a fresh preparation.
        if (playbackSource.isComplete && file.length() < 10 * 1024L) {
            debugLogSink.log("WebCompat/Gate", "REJECTED id=${item.id} reason=suspicious_size size=${file.length()}")
            respond(HttpStatusCode.Conflict, ErrorPayload("The cached asset is incomplete or corrupt.  Retrying preparation..."))
            return
        }
        if (playbackSource.allowGrowing && !playbackSource.isComplete) {
            streamGrowingCachedFile(
                item = item,
                file = file,
                mimeType = playbackSource.mimeType ?: item.playbackDecision.browserMimeType ?: "application/octet-stream",
                asAttachment = asAttachment,
                activity = activity,
                expectedTotalSize = expectedTotalSize ?: playbackSource.sizeBytes.takeIf { it > 0 },
            )
            return
        }
        val totalLength = if (expectedTotalSize != null && expectedTotalSize > file.length()) expectedTotalSize else file.length()
        val range = parseRange(request.header(HttpHeaders.Range), totalLength)
        val lengthToSend = range?.let { (it.last - it.first + 1).coerceAtLeast(0) } ?: totalLength
        val mimeType = playbackSource.mimeType ?: item.playbackDecision.browserMimeType ?: "application/octet-stream"
        val callerHost = remoteHost()

        sessionManager.onTransferStarted(callerHost, activity, asAttachment)
        try {
            respond(object : OutgoingContent.WriteChannelContent() {
                override val status: HttpStatusCode = if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK
                override val contentType: ContentType = ContentType.parse(mimeType)
                override val contentLength: Long = lengthToSend
                override val headers = io.ktor.http.Headers.build {
                    append(HttpHeaders.AcceptRanges, "bytes")
                    if (range != null) {
                        append(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/$totalLength")
                    }
                    if (asAttachment) {
                        append(
                            HttpHeaders.ContentDisposition,
                            ContentDisposition.Attachment.withParameter(
                                ContentDisposition.Parameters.FileName,
                                item.displayName,
                            ).toString(),
                        )
                    }
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    withContext(Dispatchers.IO) {
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(range?.first ?: 0L)
                            val buffer = ByteArray(STREAMING_BUFFER_SIZE)
                            var remaining = lengthToSend
                            while (remaining > 0) {
                                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                val read = raf.read(buffer, 0, toRead)
                                if (read <= 0) break
                                channel.writeFully(buffer, 0, read)
                                channel.flush()
                                remaining -= read
                                sessionManager.onTransferProgress(callerHost, read.toLong(), activity)
                            }
                        }
                    }
                }
            })
        } finally {
            sessionManager.onTransferCompleted(callerHost, activity, asAttachment)
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.streamGrowingCachedFile(
        item: SharedItem,
        file: File,
        mimeType: String,
        asAttachment: Boolean,
        activity: ClientActivity,
        expectedTotalSize: Long? = null,
    ) {
        val requestedRange = parseRequestedGrowingRange(request.header(HttpHeaders.Range))
        if (requestedRange != null) {
            val availableLength = waitForGrowingFileOffset(
                itemId = item.id,
                file = file,
                requiredOffset = requestedRange.start,
            )
            if (availableLength <= requestedRange.start) {
                response.headers.append(HttpHeaders.ContentRange, "bytes */$availableLength")
                respond(
                    HttpStatusCode.RequestedRangeNotSatisfiable,
                    ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_video_part_preparing)),
                )
                return
            }

            val totalSizeForHeader = expectedTotalSize ?: availableLength
            val range = requestedRange.start..minOf(
                requestedRange.endInclusive ?: (totalSizeForHeader - 1),
                availableLength - 1,
            )
            val callerHost = remoteHost()
            sessionManager.onTransferStarted(callerHost, activity, asAttachment)
            try {
                respond(object : OutgoingContent.WriteChannelContent() {
                    override val status: HttpStatusCode = HttpStatusCode.PartialContent
                    override val contentType: ContentType = ContentType.parse(mimeType)
                    override val contentLength: Long = range.last - range.first + 1
                    override val headers = io.ktor.http.Headers.build {
                        append(HttpHeaders.AcceptRanges, "bytes")
                        append(HttpHeaders.CacheControl, "no-store")
                        append(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/$totalSizeForHeader")
                        if (asAttachment) {
                            append(
                                HttpHeaders.ContentDisposition,
                                ContentDisposition.Attachment.withParameter(
                                    ContentDisposition.Parameters.FileName,
                                    item.displayName,
                                ).toString(),
                            )
                        }
                    }

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        withContext(Dispatchers.IO) {
                            RandomAccessFile(file, "r").use { raf ->
                                raf.seek(range.first)
                                val buffer = ByteArray(STREAMING_BUFFER_SIZE)
                                var remaining = contentLength
                                while (remaining > 0) {
                                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                    val read = raf.read(buffer, 0, toRead)
                                    if (read <= 0) break
                                    channel.writeFully(buffer, 0, read)
                                    channel.flush()
                                    remaining -= read
                                    sessionManager.onTransferProgress(callerHost, read.toLong(), activity)
                                }
                            }
                        }
                    }
                })
            } finally {
                sessionManager.onTransferCompleted(callerHost, activity, asAttachment)
            }
            return
        }

        response.headers.append(HttpHeaders.AcceptRanges, "bytes")
        response.headers.append(HttpHeaders.CacheControl, "no-store")
        if (asAttachment) {
            response.headers.append(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    item.displayName,
                ).toString(),
            )
        }

        sessionManager.onTransferStarted(remoteHost(), activity, asAttachment)
        try {
            respondOutputStream(
                contentType = ContentType.parse(mimeType),
                status = HttpStatusCode.OK,
            ) {
                streamGrowingFile(
                    itemId = item.id,
                    file = file,
                    onChunk = { bytes ->
                        sessionManager.onTransferProgress(this@streamGrowingCachedFile.remoteHost(), bytes, activity)
                    },
                    output = this,
                )
            }
        } finally {
            sessionManager.onTransferCompleted(remoteHost(), activity, asAttachment)
        }
    }

    private fun compatibilityStreamReady(item: SharedItem): Boolean {
        val job = compatibilityPipeline.currentJob(item.id) ?: return item.playbackDecision.mode == PlaybackMode.DIRECT
        if (item.playbackDecision.mode == PlaybackMode.DIRECT) return true

        // Fully complete: always ready regardless of how we got here.
        if (job.status == CompatibilityStatus.READY || job.preparedAsset?.isComplete == true) return true

        // Not started, permanently failed, or stalled: not ready.
        if (job.status == CompatibilityStatus.FAILED || job.status == CompatibilityStatus.STALLED || job.status == CompatibilityStatus.IDLE) return false

        // If no output file has been recorded yet (job is QUEUED or just started), not ready.
        val preparedAsset = job.preparedAsset ?: return false

        // For non-video or non-fMP4 (e.g. audio remux) fall back to the old canServePlayback
        // gate, which uses the raw byte-count threshold.
        if (item.category != MediaCategory.VIDEO || !preparedAsset.isFragmentedMp4) {
            return job.canServePlayback
        }

        // For in-progress fragmented MP4 video we bypass the 4 MB byte-count threshold
        // (which previously caused 16+ seconds of waiting before playback started) and
        // instead check the HLS index directly.  As soon as MIN_SEGMENTS_BEFORE_PLAY
        // complete moof+mdat fragments exist in the growing file the player can start —
        // hls.js will continue fetching new segments as transcoding writes them,
        // and the EVENT playlist keeps growing until #EXT-X-ENDLIST is appended on
        // completion.
        val file = File(preparedAsset.filePath)
        if (!file.exists()) return false
        val index = runCatching {
            FragmentedMp4HlsIndexer.read(file, fragmentDurationSeconds = HLS_SEGMENT_DURATION_SECONDS)
        }.getOrNull()
        val hlsSegmentsReady = index?.initSegmentLength?.let { it > 0L } == true &&
            index.segments.size >= MIN_SEGMENTS_BEFORE_PLAY
        if (hlsSegmentsReady) return true

        // Safety fallback: if the HLS indexer cannot yet detect MIN_SEGMENTS_BEFORE_PLAY
        // complete segments (e.g. the fMP4 hasn't written its first fragment yet or the
        // indexer returned an unexpected structure), fall back to the old byte-count gate.
        // awaitHlsIndex in the playlist endpoint will keep polling until real segments
        // appear, so this only moves the "green light" to the client earlier — it doesn't
        // bypass the actual segment requirement for serving the playlist.
        return job.canServePlayback
    }

    private suspend fun io.ktor.server.application.ApplicationCall.streamFileSlice(
        file: File,
        mimeType: String,
        byteRange: LongRange,
        activity: ClientActivity,
    ) {
        val start = byteRange.first
        val endInclusive = byteRange.last
        if (!file.exists() || start < 0L || endInclusive < start || endInclusive >= file.length()) {
            respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_video_part_unavailable)))
            return
        }

        val callerHost = remoteHost()
        val lengthToSend = endInclusive - start + 1
        sessionManager.onTransferStarted(callerHost, activity, isDownload = false)
        try {
            respond(object : OutgoingContent.WriteChannelContent() {
                override val status: HttpStatusCode = HttpStatusCode.PartialContent
                override val contentType: ContentType = ContentType.parse(mimeType)
                override val contentLength: Long = lengthToSend
                override val headers = io.ktor.http.Headers.build {
                    append(HttpHeaders.AcceptRanges, "bytes")
                    append(HttpHeaders.CacheControl, "no-store")
                    append(HttpHeaders.ContentRange, "bytes $start-$endInclusive/${file.length()}")
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    withContext(Dispatchers.IO) {
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(start)
                            val buffer = ByteArray(STREAMING_BUFFER_SIZE)
                            var remaining = lengthToSend
                            while (remaining > 0) {
                                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                val read = raf.read(buffer, 0, toRead)
                                if (read <= 0) break
                                channel.writeFully(buffer, 0, read)
                                channel.flush()
                                remaining -= read
                                sessionManager.onTransferProgress(callerHost, read.toLong(), activity)
                            }
                        }
                    }
                }
            })
        } finally {
            sessionManager.onTransferCompleted(callerHost, activity, wasDownload = false)
        }
    }

    private fun io.ktor.server.application.ApplicationCall.remoteHost(): String {
        return request.origin.remoteHost
    }

    private suspend fun io.ktor.server.application.ApplicationCall.resolveHlsSource(
        itemId: String?,
    ): HlsPlaybackSource? {
        val item = resolveItem(itemId) ?: run {
            respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_file_unavailable)))
            return null
        }
        if (!item.isAvailable) {
            // Soft check: some background scans might mark items unavailable due to transient
            // permission errors. Double check live before failing with a fatal 410 Gone.
            val liveCheck = storageRepository.verifyAvailability(item)
            if (!liveCheck) {
                debugLogSink.log("WebHls/410", "item truly gone id=${item.id} name=${item.displayName}")
                respond(HttpStatusCode.Gone, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_file_unavailable)))
                return null
            }
            debugLogSink.log("WebHls/resilient", "item was marked unavailable in DB but live check passed id=${item.id}")
        }
        if (item.category != MediaCategory.VIDEO || item.playbackDecision.mode == PlaybackMode.DIRECT) {
            respond(HttpStatusCode.Conflict, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_hls_not_needed)))
            return null
        }

        val job = compatibilitySnapshotFor(item, triggerPreparation = true, prioritizePreparation = true)
        if (job.status == CompatibilityStatus.FAILED || job.status == CompatibilityStatus.STALLED) {
            respond(HttpStatusCode.Conflict, ErrorPayload(job.message))
            return null
        }
        val preparedAsset = job.preparedAsset ?: run {
            respond(HttpStatusCode.Accepted, ErrorPayload(job.message))
            return null
        }
        if (!preparedAsset.isFragmentedMp4) {
            respond(HttpStatusCode.Conflict, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_hls_not_ready)))
            return null
        }
        val file = File(preparedAsset.filePath)
        if (!file.exists()) {
            respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_optimized_unavailable)))
            return null
        }
        return HlsPlaybackSource(
            item = item,
            job = job,
            file = file,
        )
    }

    // Suspend version — replaces Thread.sleep with coroutine-friendly delay so we
    // never block a Ktor dispatcher thread while waiting for the next segment to
    // be written to the growing fMP4 output file.
    //
    // ROBUSTNESS: All calls to FragmentedMp4HlsIndexer.read() are wrapped in
    // runCatching so that an I/O exception or unexpected file format never propagates
    // as an unhandled exception that would cause the Ktor route handler to return 500.
    //
    // FINALIZED JOBS: When a job is complete (status=READY / isComplete=true) but the
    // index is still null or doesn't have the required segment, we do NOT immediately
    // return null (which would cause a 202 "not ready" response that confuses hls.js).
    // Instead we retry up to HLS_FINALIZED_RETRY_COUNT times with a short delay to
    // account for file-system flush latency or transient I/O errors.
    private suspend fun awaitHlsIndex(
        itemId: String,
        file: File,
        requireFirstSegment: Boolean,
        requiredSegmentIndex: Int? = null,
    ): FragmentedMp4HlsIndex? {
        fun readIndex() = runCatching {
            FragmentedMp4HlsIndexer.read(file, fragmentDurationSeconds = HLS_SEGMENT_DURATION_SECONDS)
        }.getOrNull()

        fun FragmentedMp4HlsIndex?.meetsRequirements(): Boolean {
            val job = compatibilityPipeline.currentJob(itemId)
            val startSegIndex = ((job?.startOffsetMs ?: 0L) / 1000.0 / HLS_SEGMENT_DURATION_SECONDS).toInt()
            
            val hasInitSegment = this?.initSegmentLength?.let { it > 0L } == true
            val hasFirstSegment = !requireFirstSegment || (this?.segments?.isNotEmpty() == true)
            
            // Map the manifest index to the actual index in the current file.
            val mappedRequiredIndex = requiredSegmentIndex?.let { it - startSegIndex }
            val hasRequiredSegment = mappedRequiredIndex == null ||
                (mappedRequiredIndex >= 0 && this?.segments?.getOrNull(mappedRequiredIndex) != null)
                
            return hasInitSegment && hasFirstSegment && hasRequiredSegment
        }

        var idlePolls = 0
        while (idlePolls < MAX_HLS_INDEX_IDLE_POLLS) {
            val index = withContext(Dispatchers.IO) { readIndex() }
            if (index.meetsRequirements()) return index

            val job = compatibilityPipeline.currentJob(itemId)
            val finalized = job?.preparedAsset?.isComplete == true || job?.status == CompatibilityStatus.READY
            val failed = job?.status == CompatibilityStatus.FAILED || job?.status == CompatibilityStatus.STALLED
            if (finalized || failed) {
                // The job is done. The segments must exist — do a few extra retries to
                // handle OS file-flush latency or transient read errors before giving up.
                // Returning null here would cause the endpoint to send 202, which hls.js
                // treats as a fatal network error.
                repeat(HLS_FINALIZED_RETRY_COUNT) { attempt ->
                    delay(HLS_FINALIZED_RETRY_INTERVAL_MS)
                    val retryIndex = withContext(Dispatchers.IO) { readIndex() }
                    if (retryIndex.meetsRequirements()) return retryIndex
                    debugLogSink.log(
                        "WebHls",
                        "awaitHlsIndex retry ${attempt + 1}/$HLS_FINALIZED_RETRY_COUNT id=$itemId " +
                            "finalized=$finalized failed=$failed segments=${retryIndex?.segments?.size ?: -1}",
                    )
                }
                // Return whatever we have as a last resort; the endpoint will decide
                // whether to serve it or return 202.
                return withContext(Dispatchers.IO) { readIndex() }
            }

            idlePolls += 1
            delay(HLS_INDEX_POLL_INTERVAL_MS)
        }
        return withContext(Dispatchers.IO) { readIndex() }
    }

    /**
     * HLS Master Playlist — contains an explicit CODECS string so that hls.js and native
     * HLS players configure MSE / hardware decoders with the correct codec, avoiding the
     * bufferAppendError that occurs when the codec is guessed from the raw segment bytes.
     *
     * The video codec string is read directly from the fMP4 init segment's moov box so it
     * exactly matches what the Android encoder produced — Android hardware encoders typically
     * output Main or High Profile H.264, not Baseline, and may produce HEVC via fallback.
     * A hardcoded "avc1.42E028" would mismatch and cause bufferAppendError in hls.js.
     */
    private fun buildHlsMasterPlaylist(
        itemId: String,
        detectedVideoCodec: String?,
        width: Int? = null,
        height: Int? = null,
    ): String {
        // Use the codec detected from the fMP4 moov box.
        // Fall back to High Profile L4.0 which covers the broadest range of Android encoder output.
        val videoCodec = detectedVideoCodec ?: "avc1.640028"
        // mp4a.40.2 = MPEG-4 Audio Object Type 2 = AAC-LC (universally supported)
        val audioCodec = "mp4a.40.2"
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            val streamInfParts = mutableListOf<String>()
            streamInfParts += "BANDWIDTH=2000000"
            streamInfParts += "CODECS=\"$videoCodec,$audioCodec\""
            if (width != null && height != null) {
                streamInfParts += "RESOLUTION=${width}x$height"
            }
            appendLine("#EXT-X-STREAM-INF:${streamInfParts.joinToString(",")}")
            appendLine("/hls/$itemId/playlist.m3u8")
        }
    }

    private fun buildHlsPlaylist(
        itemId: String,
        index: FragmentedMp4HlsIndex,
        complete: Boolean,
    ): String {
        val item = storageRepository.findItemById(itemId)
        var durationMs = item?.durationMs ?: 0L

        // Fallback: If duration is missing (e.g. indexed before the fix), try to read it now.
        if (durationMs <= 0L && item != null) {
            runCatching {
                val uri = android.net.Uri.parse(item.uri)
                mediaAnalyzer.readDurationMs(uri, item.mimeType)?.let {
                    durationMs = it
                }
            }
        }

        debugLogSink.log(
            "KtorGhostStreamServer",
            "building hls playlist id=$itemId name=${item?.displayName} durationMs=$durationMs mode=${item?.playbackDecision?.mode} label=${item?.playbackDecision?.compatibilityLabel} segments=${index.segments.size} complete=$complete"
        )

        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:$HLS_TARGET_DURATION_SECONDS")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            // We use VOD type even if growing to ensure the browser shows the full timeline immediately.
            // For segments that aren't ready yet, the server will block in awaitHlsIndex until they appear.
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            // Signals to iOS Safari that it should start at the beginning of the VOD timeline.
            appendLine("#EXT-X-START:TIME-OFFSET=0")
            appendLine("#EXT-X-MAP:URI=\"/hls/$itemId/init.mp4\"")

            // Determine how many segments will exist in total.
            // We use the maximum of (calculated segments from duration) and (actual recorded segments).
            // This prevents video truncation if the metadata duration is inaccurately short.
            val segmentsCountFromDuration = if (durationMs > 0) {
                (java.lang.Math.ceil(durationMs / 1000.0 / HLS_SEGMENT_DURATION_SECONDS)).toInt()
            } else {
                0
            }
            val totalLoopCount = if (complete) {
                index.segments.size
            } else {
                maxOf(segmentsCountFromDuration, index.segments.size)
            }

            // Fill the playlist with all segments (indexed ones first, then virtual ones).
            repeat(totalLoopCount) { segmentIndex ->
                // Use the indexed duration if available, else use the default target duration.
                val duration = index.segments.getOrNull(segmentIndex)?.durationSeconds ?: HLS_SEGMENT_DURATION_SECONDS.toDouble()
                appendLine("#EXTINF:${"%.3f".format(java.util.Locale.US, duration)},")
                appendLine("/hls/$itemId/segment/$segmentIndex.m4s")
            }

            // Only add ENDLIST if the job is truly finished.
            // Appending this early tells the browser the stream is over, causing truncation.
            if (complete) {
                appendLine("#EXT-X-ENDLIST")
            }
        }
    }

    private fun parseRange(rangeHeader: String?, totalLength: Long): LongRange? {
        if (rangeHeader.isNullOrBlank() || !rangeHeader.startsWith("bytes=") || totalLength <= 0) return null
        val raw = rangeHeader.removePrefix("bytes=")
        val start = raw.substringBefore('-').toLongOrNull() ?: 0L
        val end = raw.substringAfter('-', "").toLongOrNull()?.coerceAtMost(totalLength - 1) ?: (totalLength - 1)
        return if (start in 0..end) start..end else null
    }

    private fun parseRequestedGrowingRange(rangeHeader: String?): RequestedGrowingRange? {
        if (rangeHeader.isNullOrBlank() || !rangeHeader.startsWith("bytes=")) return null
        val raw = rangeHeader.removePrefix("bytes=").substringBefore(',').trim()
        if (raw.isBlank()) return null
        val startPart = raw.substringBefore('-')
        if (startPart.isBlank()) return null
        val start = startPart.toLongOrNull() ?: return null
        val endInclusive = raw.substringAfter('-', "").takeIf { it.isNotBlank() }?.toLongOrNull()
        return RequestedGrowingRange(
            start = start,
            endInclusive = endInclusive,
        )
    }

    // Called inside Ktor's respondOutputStream lambda which already runs on a blocking
    // IO thread, so Thread.sleep is safe here (it does not block a coroutine scheduler
    // thread — it blocks the dedicated IO thread that Ktor allocated for the response).
    private fun waitForGrowingFileOffset(
        itemId: String,
        file: File,
        requiredOffset: Long,
    ): Long {
        var idlePolls = 0
        while (idlePolls < MAX_GROWING_FILE_IDLE_POLLS) {
            val available = file.length()
            if (available > requiredOffset) {
                return available
            }

            val job = compatibilityPipeline.currentJob(itemId)
            val finalized = job?.preparedAsset?.isComplete == true || job?.status == CompatibilityStatus.READY
            val failed = job?.status == CompatibilityStatus.FAILED || job?.status == CompatibilityStatus.STALLED
            if (finalized || failed) {
                return available
            }

            idlePolls += 1
            Thread.sleep(GROWING_FILE_POLL_INTERVAL_MS)
        }
        return file.length()
    }

    /**
     * Seek an [InputStream] to the given byte offset.
     * [InputStream.skip] is unreliable on Android content-provider streams — it
     * may skip fewer bytes than requested. This method calls skip in a loop
     * and falls back to [InputStream.read] if skip stalls, guaranteeing all
     * bytes are consumed.
     */
    private fun seekStream(input: InputStream, offset: Long) {
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                // skip() returned 0 or -1; fall back to reading to advance
                val readBuf = ByteArray(minOf(8192L, remaining).toInt())
                val read = input.read(readBuf)
                if (read <= 0) break // genuine EOF
                remaining -= read
            }
        }
    }

    private fun streamGrowingFile(
        itemId: String,
        file: File,
        onChunk: (Long) -> Unit,
        output: java.io.OutputStream,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var written = 0L
        var idlePolls = 0

        while (idlePolls < MAX_GROWING_FILE_IDLE_POLLS) {
            val available = file.length()
            if (available > written) {
                val readSucceeded = runCatching {
                    RandomAccessFile(file, "r").use { handle ->
                        handle.seek(written)
                        while (handle.filePointer < available) {
                            val bytesToRead = minOf(buffer.size.toLong(), available - handle.filePointer).toInt()
                            val read = handle.read(buffer, 0, bytesToRead)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            output.flush()
                            written += read
                            onChunk(read.toLong())
                        }
                    }
                }.isSuccess
                if (!readSucceeded) break
                idlePolls = 0
            }

            val job = compatibilityPipeline.currentJob(itemId)
            val finalized = job?.preparedAsset?.isComplete == true || job?.status == CompatibilityStatus.READY
            val failed = job?.status == CompatibilityStatus.FAILED || job?.status == CompatibilityStatus.STALLED
            if ((finalized || failed) && file.length() <= written) {
                break
            }
            if (available <= written) {
                idlePolls += 1
                Thread.sleep(GROWING_FILE_POLL_INTERVAL_MS)
            }
        }
    }



    private fun readText(uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    private fun convertToWebVtt(input: String): String {
        if (input.startsWith("WEBVTT")) return input
        return buildString {
            appendLine("WEBVTT")
            appendLine()
            append(input.replace(',', '.'))
        }
    }

    private fun nextFreePort(): Int {
        return ServerSocket(0).use { socket -> socket.localPort }
    }

    @Serializable
    private data class LoginPayload(
        val pin: String,
    )

    @Serializable
    private data class AuthResult(
        val success: Boolean,
    )

    @Serializable
    private data class BrowserDebugPayload(
        val event: String,
        val route: String,
        val details: String? = null,
    )

    @Serializable
    private data class ErrorPayload(
        val message: String,
    )

    @Serializable
    private data class UploadRequestPayload(
        val fileName: String,
        val fileCount: Int,
        val sizeBytes: Long,
    )
    

    @Serializable
    private data class UploadRequestResponse(
        val requestId: String,
        val accepted: Boolean,
    )

    @Serializable
    private data class BrowserBootstrap(
        val title: String,
        val subtitle: String,
        val authEnabled: Boolean,
        val sessionUrl: String?,
        val sessionPort: Int?,
        val categories: BrowserCategories,
        val recent: List<BrowserItemCard>,
        val themeMode: ThemeMode,
        val showThumbnails: Boolean,
        val largeCards: Boolean,
        val prominentDownloadButton: Boolean,
        val debugTracing: Boolean,
        val preventDownload: Boolean,
        val deviceName: String,
        val deviceIp: String,
        val strings: Map<String, String>,
    )

    @Serializable
    private data class BrowserCategories(
        val videos: Int,
        val photos: Int,
        val music: Int,
        val files: Int,
    )

    @Serializable
    private data class BrowserItemCard(
        val id: String,
        val title: String,
        val category: String,
        val sizeBytes: Long,
        val durationMs: Long?,
        val thumbnailUrl: String?,
        val streamUrl: String,
        val downloadUrl: String?,
        val subtitleUrl: String?,
        val compatibilityLabel: String?,
        val compatibilityStatus: CompatibilityStatus? = null,
    ) {
        companion object {
            fun from(
                item: SharedItem,
                compatibilityJob: CompatibilityJob,
                showThumbnails: Boolean,
                allowDownloads: Boolean,
            ): BrowserItemCard = BrowserItemCard(
                id = item.id,
                title = item.displayName,
                category = item.category.name.lowercase(),
                sizeBytes = item.sizeBytes,
                durationMs = item.durationMs,
                thumbnailUrl = if (
                    showThumbnails &&
                    (item.category == MediaCategory.PHOTO || item.category == MediaCategory.VIDEO)
                ) {
                    "/thumb/${item.id}"
                } else {
                    null
                },
                streamUrl = "/stream/${item.id}",
                downloadUrl = if (allowDownloads) "/download/${item.id}" else null,
                subtitleUrl = item.subtitleMatch?.let { "/subtitle/${item.id}" },
                compatibilityLabel = item.playbackDecision.compatibilityLabel,
                compatibilityStatus = compatibilityJob.status.takeIf { item.playbackDecision.mode != PlaybackMode.DIRECT },
            )
        }
    }

    @Serializable
    private data class BrowserItemDetails(
        val id: String,
        val title: String,
        val mimeType: String?,
        val category: String,
        val streamUrl: String,
        val hlsUrl: String? = null,
        val downloadUrl: String?,
        val subtitleUrl: String?,
        val durationMs: Long?,
        val sizeBytes: Long,
        val compatibility: String?,
        val reason: String,
        val playbackMode: PlaybackMode,
        val compatibilityStatus: CompatibilityStatus? = null,
        val compatibilityMessage: String? = null,
        val compatibilityProgressPercent: Int? = null,
        val compatibilityComplete: Boolean,
        val streamReady: Boolean,
        // Non-null when the prepared MP4 is fully ready and can be played directly
        // via <video src> without going through HLS or MSE.  The browser plays it
        // natively so the TFHD base-data-offset field is not a problem.
        val preparedMp4Url: String? = null,
    ) {
        companion object {
            fun from(
                item: SharedItem,
                compatibilityJob: CompatibilityJob,
                streamReady: Boolean,
                allowDownloads: Boolean,
            ): BrowserItemDetails {
                val isComplete = compatibilityJob.status == CompatibilityStatus.READY || compatibilityJob.preparedAsset?.isComplete == true
                val hasProgressedAsset = compatibilityJob.preparedAsset != null

                return BrowserItemDetails(
                    id = item.id,
                    title = item.displayName,
                    mimeType = item.mimeType,
                    category = item.category.name.lowercase(),
                    streamUrl = "/stream/${item.id}",
                    // Use HLS only for TRANSMUX and TRANSCODE modes when not yet complete.
                    // REMUX (Faststart) and DIRECT always use direct file playback.
                    hlsUrl = if (item.category == MediaCategory.VIDEO && !isComplete && 
                        (item.playbackDecision.mode == PlaybackMode.TRANSMUX || item.playbackDecision.mode == PlaybackMode.TRANSCODE)) {
                        "/hls/${item.id}/master.m3u8"
                    } els