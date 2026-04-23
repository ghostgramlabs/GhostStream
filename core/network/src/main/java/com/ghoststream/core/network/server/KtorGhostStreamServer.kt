package com.ghoststream.core.network.server

import android.content.Context
import android.os.Build
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import com.ghostgramlabs.directserve.core.resources.R
import com.ghoststream.core.media.CompatibilityJob
import com.ghoststream.core.media.CompatibilityPipeline
import com.ghoststream.core.media.QueuedCompatibilityPipeline
import com.ghoststream.core.media.CompatibilityStatus
import com.ghoststream.core.media.MediaAnalyzer
import com.ghoststream.core.media.PlaybackResolution
import com.ghoststream.core.media.PlaybackSource
import com.ghoststream.core.media.JobPriority
import com.ghoststream.core.media.EffectivePlaybackMode
import com.ghoststream.core.media.ClientCapabilities
import com.ghoststream.core.media.FragmentedMp4HlsIndex
import com.ghoststream.core.media.FragmentedMp4HlsIndexer
import com.ghoststream.core.media.HlsReadinessValidator
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
import io.ktor.server.response.header
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import com.ghoststream.core.model.UploadRequest
import java.util.UUID
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.text.SimpleDateFormat
import java.util.Date
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.util.date.GMTDate
import java.io.File
import java.io.InputStream
import kotlin.io.copyTo
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

    private val dlnaService by lazy {
        val session = sessionManager.sessionState.value
        DlnaService(
            deviceName = context.getString(R.string.dlna_server_name_template, Build.MODEL),
            deviceUuid = UUID.nameUUIDFromBytes(context.packageName.toByteArray()).toString(),
            serverUrl = "http://${session.networkAvailability.localAddress ?: "127.0.0.1"}:${session.serverPort ?: 0}"
        )
    }

    /** Cache of client capabilities by remote host (IP). */
    private val capabilityCache = java.util.concurrent.ConcurrentHashMap<String, ClientCapabilities>()
    /** Cache of playback decisions by item + client so passive polling does not re-decide repeatedly. */
    private val decisionCache = java.util.concurrent.ConcurrentHashMap<String, PlaybackDecision>()
    /** Per-client playback overrides used to avoid repeating known-bad browser choices within the same session. */
    private val playbackOverrideCache = java.util.concurrent.ConcurrentHashMap<String, PlaybackMode>()
    private val thumbnailPlaceholderBytes by lazy { buildThumbnailPlaceholderBytes() }

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
        playbackOverrideCache.clear()
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
            get("/dlna/description.xml") {
                call.respondText(dlnaService.getDeviceDescription(), ContentType.parse("text/xml"))
            }
            get("/dlna/ContentDirectory.xml") {
                call.respondText(dlnaService.getContentDirectoryScpd(), ContentType.parse("text/xml"))
            }
            get("/dlna/ConnectionManager.xml") {
                call.respondText(dlnaService.getConnectionManagerScpd(), ContentType.parse("text/xml"))
            }
            post("/dlna/control/ContentDirectory") {
                val soapAction = call.request.header("SOAPACTION") ?: ""
                val body = call.receiveNullable<String>() ?: ""
                
                if (soapAction.contains("Browse")) {
                    val objectId = Regex("<ObjectID>(.*?)</ObjectID>").find(body)?.groupValues?.get(1) ?: "0"
                    val browseFlag = Regex("<BrowseFlag>(.*?)</BrowseFlag>").find(body)?.groupValues?.get(1) ?: "BrowseDirectChildren"
                    
                    val state = sessionManager.sessionState.value
                    val resultXml = if (browseFlag == "BrowseMetadata") {
                        // Return metadata for the object itself
                        if (objectId == "0") {
                            dlnaService.buildDidlLite(emptyList(), emptyList()) // Root metadata
                        } else {
                            // Find item or folder
                            val id = objectId.substringAfter(":")
                            if (objectId.startsWith("folder:")) {
                                dlnaService.buildDidlLite(state.selectedFolders.filter { it.id == id }, emptyList())
                            } else {
                                dlnaService.buildDidlLite(emptyList(), state.selectedItems.filter { it.id == id })
                            }
                        }
                    } else {
                        // Browse children
                        when (objectId) {
                            "0" -> dlnaService.buildDidlLite(state.selectedFolders, state.selectedItems.filter { it.sourceFolderId == null })
                            else -> {
                                val folderId = objectId.substringAfter(":")
                                dlnaService.buildDidlLite(emptyList(), state.selectedItems.filter { it.sourceFolderId == folderId })
                            }
                        }
                    }

                    val response = dlnaService.buildSoapResponse("""
                        <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                            <Result>${resultXml.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</Result>
                            <NumberReturned>1</NumberReturned>
                            <TotalMatches>1</TotalMatches>
                            <UpdateID>1</UpdateID>
                        </u:BrowseResponse>
                    """.trimIndent())
                    call.respondText(response, ContentType.parse("text/xml"))
                } else if (soapAction.contains("GetSortCapabilities")) {
                    val response = dlnaService.buildSoapResponse("""
                        <u:GetSortCapabilitiesResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                            <SortCaps>dc:title,dc:date,upnp:class</SortCaps>
                        </u:GetSortCapabilitiesResponse>
                    """.trimIndent())
                    call.respondText(response, ContentType.parse("text/xml"))
                }
            }
            post("/dlna/control/ConnectionManager") {
                val soapAction = call.request.header("SOAPACTION") ?: ""
                if (soapAction.contains("GetProtocolInfo")) {
                    val response = dlnaService.buildSoapResponse("""
                        <u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
                            <Source>http-get:*:video/mp4:*,http-get:*:audio/mpeg:*,http-get:*:image/jpeg:*</Source>
                            <Sink></Sink>
                        </u:GetProtocolInfoResponse>
                    """.trimIndent())
                    call.respondText(response, ContentType.parse("text/xml"))
                }
            }

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

            get("/uppy.min.js") {
                call.respondBytes(
                    bytes = assetLoader.readBytes("web/uppy.min.js"),
                    contentType = ContentType.Application.JavaScript,
                )
            }

            get("/uppy.min.css") {
                call.respondBytes(
                    bytes = assetLoader.readBytes("web/uppy.min.css"),
                    contentType = ContentType.Text.CSS,
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
                    for (item in state.selectedItems.filter { it.isEnabledBySettings(settings) }.take(8)) {
                        recentCards += BrowserItemCard.from(
                            item = item,
                            compatibilityJob = compatibilitySnapshotFor(call.request.origin.remoteHost, item, triggerPreparation = false),
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
                            videos = if (isAuthorized && settings.shareVideos) state.selectedItems.count { it.category == MediaCategory.VIDEO } else 0,
                            photos = if (isAuthorized && settings.sharePhotos) state.selectedItems.count { it.category == MediaCategory.PHOTO } else 0,
                            music = if (isAuthorized && settings.shareMusic) state.selectedItems.count { it.category == MediaCategory.MUSIC } else 0,
                            files = if (isAuthorized && settings.shareFiles) state.selectedItems.count { it.category == MediaCategory.FILE } else 0,
                            folders = if (isAuthorized) state.selectedFolders.size else 0,
                        ),
                        enabledCategories = EnabledCategories(
                            videos = settings.shareVideos,
                            photos = settings.sharePhotos,
                            music = settings.shareMusic,
                            files = settings.shareFiles,
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
                            "web_nav_media" to localizedContext.getString(R.string.web_nav_media),
                            "web_nav_files" to localizedContext.getString(R.string.web_nav_files),
                            "web_nav_send" to localizedContext.getString(R.string.web_nav_send),
                            "web_nav_logout" to localizedContext.getString(R.string.web_nav_logout),
                            "web_strip_session" to localizedContext.getString(R.string.web_strip_session),
                            "web_strip_device" to localizedContext.getString(R.string.web_strip_device),
                            "web_strip_link" to localizedContext.getString(R.string.web_strip_link),
                            "web_security_pin" to localizedContext.getString(R.string.web_security_pin),
                            "web_security_open" to localizedContext.getString(R.string.web_security_open),
                            "web_status_unknown" to localizedContext.getString(R.string.web_status_unknown),
                            "web_send_files_to_device" to localizedContext.getString(R.string.web_send_files_to_device),
                            "web_upload_title" to localizedContext.getString(R.string.web_upload_title),
                            "web_upload_subtitle" to localizedContext.getString(R.string.web_upload_subtitle),
                            "web_upload_prompt_title" to localizedContext.getString(R.string.web_upload_prompt_title),
                            "web_upload_prompt_desktop" to localizedContext.getString(R.string.web_upload_prompt_desktop),
                            "web_upload_prompt_mobile" to localizedContext.getString(R.string.web_upload_prompt_mobile),
                            "web_upload_button_browse" to localizedContext.getString(R.string.web_upload_button_browse),
                            "web_upload_button_photos" to localizedContext.getString(R.string.web_upload_button_photos),
                            "web_upload_button_any_file" to localizedContext.getString(R.string.web_upload_button_any_file),
                            "web_upload_target_kicker" to localizedContext.getString(R.string.web_upload_target_kicker),
                            "web_upload_target_status" to localizedContext.getString(R.string.web_upload_target_status),
                            "web_upload_how_kicker" to localizedContext.getString(R.string.web_upload_how_kicker),
                            "web_upload_how_title" to localizedContext.getString(R.string.web_upload_how_title),
                            "web_upload_how_body" to localizedContext.getString(R.string.web_upload_how_body),
                            "web_action_download" to localizedContext.getString(R.string.web_action_download),
                            "web_action_view" to localizedContext.getString(R.string.web_action_view),
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
                            "web_folders_title" to localizedContext.getString(R.string.web_folders_title),
                            "web_folder_items" to localizedContext.getString(R.string.web_folder_items),
                            "web_btn_back_to_folders" to localizedContext.getString(R.string.web_btn_back_to_folders),
                            "web_all_media" to localizedContext.getString(R.string.web_all_media),
                            "library_title" to localizedContext.getString(R.string.library_title),
                            "web_upload_requesting" to localizedContext.getString(R.string.web_upload_requesting),
                            "web_upload_waiting" to localizedContext.getString(R.string.web_upload_waiting),
                            "web_upload_success_title" to localizedContext.getString(R.string.web_upload_success_title),
                            "web_upload_success_detail_single" to localizedContext.getString(R.string.web_upload_success_detail_single),
                            "web_upload_success_detail_multiple" to localizedContext.getString(R.string.web_upload_success_detail_multiple),
                            "web_upload_failed_title" to localizedContext.getString(R.string.web_upload_failed_title),
                            "web_upload_failed_detail" to localizedContext.getString(R.string.web_upload_failed_detail),
                            "web_upload_preparing_transfer" to localizedContext.getString(R.string.web_upload_preparing_transfer),
                            "web_upload_connecting" to localizedContext.getString(R.string.web_upload_connecting),
                            "web_cancel" to localizedContext.getString(R.string.web_cancel),
                            "web_player_error_open" to localizedContext.getString(R.string.web_player_error_open),
                            "web_player_opening" to localizedContext.getString(R.string.web_player_opening),
                            "web_player_ready" to localizedContext.getString(R.string.web_player_ready),
                            "web_player_starting" to localizedContext.getString(R.string.web_player_starting),
                            "web_player_wait_desc" to localizedContext.getString(R.string.web_player_wait_desc),
                            "web_player_ready_desc" to localizedContext.getString(R.string.web_player_ready_desc),
                            "web_player_starting_desc" to localizedContext.getString(R.string.web_player_starting_desc),
                            "web_player_progress_preparing" to localizedContext.getString(R.string.web_player_progress_preparing),
                            "web_player_progress_converting" to localizedContext.getString(R.string.web_player_progress_converting),
                            "web_player_try_again" to localizedContext.getString(R.string.web_player_try_again),
                            "web_player_status_opening" to localizedContext.getString(R.string.web_player_status_opening),
                            "web_player_status_ready" to localizedContext.getString(R.string.web_player_status_ready),
                            "web_player_status_playing" to localizedContext.getString(R.string.web_player_status_playing),
                            "web_player_status_failed" to localizedContext.getString(R.string.web_player_status_failed),
                            "web_prepare_video" to localizedContext.getString(R.string.web_prepare_video),
                            "web_progress_percent" to localizedContext.getString(R.string.web_progress_percent),
                            "web_media_open_category" to localizedContext.getString(R.string.web_media_open_category),
                            "web_media_empty" to localizedContext.getString(R.string.web_media_empty),
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
                val folderId = call.request.queryParameters["folderId"]
                val query = call.request.queryParameters["q"]?.trim().orEmpty()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_BROWSER_ITEMS_PAGE_SIZE)
                val items = sessionManager.sessionState.value.selectedItems
                    .filter { item -> item.isEnabledBySettings(settings) }
                    .filter { item ->
                        when (category) {
                            null, "", "all" -> true
                            "media" -> item.category == MediaCategory.VIDEO ||
                                item.category == MediaCategory.PHOTO ||
                                item.category == MediaCategory.MUSIC
                            "videos" -> item.category == MediaCategory.VIDEO
                            "photos" -> item.category == MediaCategory.PHOTO
                            "music" -> item.category == MediaCategory.MUSIC
                            "files" -> item.category == MediaCategory.FILE
                            else -> true
                        }
                    }
                    .filter { item ->
                        folderId == null || item.sourceFolderId == folderId
                    }
                    .filter { item ->
                        query.isBlank() || item.displayName.contains(query, ignoreCase = true)
                    }
                    .sortedByDescending { it.dateAddedEpochMs }
                val pagedItems = if (offset != null || limit != null) {
                    items.drop(offset ?: 0).take(limit ?: DEFAULT_BROWSER_ITEMS_PAGE_SIZE)
                } else {
                    items
                }
                val cards = mutableListOf<BrowserItemCard>()
                val allowDownloads = !settings.preventDownload
                for (item in pagedItems) {
                    cards += BrowserItemCard.from(
                        item = item,
                        compatibilityJob = compatibilitySnapshotFor(call.request.origin.remoteHost, item, triggerPreparation = false),
                        showThumbnails = settings.showThumbnails,
                        allowDownloads = allowDownloads,
                    )
                }
                if (offset != null || limit != null) {
                    val safeOffset = offset ?: 0
                    call.respond(
                        BrowserItemsPage(
                            items = cards,
                            totalCount = items.size,
                            offset = safeOffset,
                            limit = limit ?: DEFAULT_BROWSER_ITEMS_PAGE_SIZE,
                            hasMore = safeOffset + cards.size < items.size,
                        ),
                    )
                } else {
                    call.respond(cards)
                }
            }

            get("/api/folders") {
                if (!call.authorizeBrowserCall()) return@get
                val state = sessionManager.sessionState.value
                val query = call.request.queryParameters["q"]?.trim().orEmpty()
                val folders = state.selectedFolders
                    .filter { folder ->
                        query.isBlank() || folder.displayName.contains(query, ignoreCase = true)
                    }
                    .sortedBy { it.displayName.lowercase() }

                call.respond(folders)
            }

            get("/api/item/{id}") {
                if (!call.authorizeBrowserCall()) return@get
                try {
                    val settings = settingsRepository.settings.first()
                    val item = resolveItem(call.parameters["id"]) ?: run {
                        call.respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_file_unavailable)))
                        return@get
                    }

                    // Only inspect â€” do not auto-trigger preparation here.
                    // The browser explicitly requests preparation via POST /api/compat/{id}/prepare,
                    // which prevents duplicate transcode jobs when the library state updates.
                    val job = compatibilitySnapshotFor(
                        host = call.request.origin.remoteHost,
                        item = item,
                        triggerPreparation = false,
                        priority = JobPriority.LOW
                    )
                    val allowInProgressHls = supportsInProgressHls(
                        host = call.request.origin.remoteHost,
                        userAgent = call.request.header(HttpHeaders.UserAgent),
                    )
                    val streamReady = compatibilityStreamReady(job, allowInProgressHls = allowInProgressHls)
                    val hlsUrl = compatibilityHlsUrl(job, allowInProgressHls = allowInProgressHls)

                    debugLogSink.log(
                        "WebBrowser",
                        "details id=${item.id} name=${item.displayName} mode=${job.decision.mode} " +
                                "status=${job.status} streamReady=$streamReady complete=${job.directReady} " +
                                "asset=${job.preparedAsset?.filePath?.substringAfterLast('/') ?: "NONE"} hls=${hlsUrl != null}",
                    )

                    call.respond(
                        BrowserItemDetails.from(
                            item = item,
                            compatibilityJob = job,
                            streamReady = streamReady,
                            hlsUrl = hlsUrl,
                            allowDownloads = !settings.preventDownload,
                            isAppleClient = isAppleClient(
                                call.request.header(HttpHeaders.UserAgent),
                                capabilityCache[call.request.origin.remoteHost],
                            ),
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
                val job = compatibilitySnapshotFor(call.request.origin.remoteHost, item, triggerPreparation = false)
                val allowInProgressHls = supportsInProgressHls(
                    host = call.request.origin.remoteHost,
                    userAgent = call.request.header(HttpHeaders.UserAgent),
                )
                val ready = compatibilityStreamReady(job, allowInProgressHls = allowInProgressHls)
                // Throttled logging: only log on state change or 5% progress bucket change
                val currentBucket = job.coarseProgressBucket
                val lastKey = lastCompatLogKey[item.id]
                val newKey = "${job.status}|$currentBucket|$ready"
                if (lastKey != newKey) {
                    lastCompatLogKey[item.id] = newKey
                    debugLogSink.log(
                        "WebCompat",
                        "poll id=${item.id} mode=${job.decision.mode} status=${job.status} ready=$ready complete=${job.directReady} progress=${job.progressPercent} asset=${job.preparedAsset?.filePath?.substringAfterLast('/')}",
                    )
                }
                call.respond(
                    CompatibilityStatusPayload.from(
                        job = job,
                        ready = ready,
                        hlsUrl = compatibilityHlsUrl(job, allowInProgressHls = allowInProgressHls),
                        isAppleClient = isAppleClient(
                            call.request.header(HttpHeaders.UserAgent),
                            capabilityCache[call.request.origin.remoteHost],
                        ),
                    ),
                )
            }

            post("/api/compat/{id}/retry") {
                if (!call.authorizeBrowserCall()) return@post
                val item = resolveItem(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_file_unavailable)))
                    return@post
                }
                val host = call.request.origin.remoteHost
                val currentMode = compatibilityPipeline.currentJob(item.id)?.decision?.mode
                    ?: applyPlaybackOverride(host, item).playbackDecision.mode
                rememberPlaybackOverride(
                    host = host,
                    item = item,
                    mode = nextBrowserFallbackMode(currentMode),
                    reason = "manual_retry",
                )
                compatibilityPipeline.invalidate(item.id)
                val job = compatibilitySnapshotFor(
                    host = host,
                    item = item,
                    triggerPreparation = true,
                    priority = JobPriority.HIGH,
                )

                call.respond(
                    CompatibilityStatusPayload.from(
                        job = job,
                        ready = compatibilityStreamReady(
                            job,
                            allowInProgressHls = supportsInProgressHls(
                                host = call.request.origin.remoteHost,
                                userAgent = call.request.header(HttpHeaders.UserAgent),
                            ),
                        ),
                        hlsUrl = compatibilityHlsUrl(
                            job,
                            allowInProgressHls = supportsInProgressHls(
                                host = call.request.origin.remoteHost,
                                userAgent = call.request.header(HttpHeaders.UserAgent),
                            ),
                        ),
                        isAppleClient = isAppleClient(
                            call.request.header(HttpHeaders.UserAgent),
                            capabilityCache[call.request.origin.remoteHost],
                        ),
                    )
                )
            }

            post("/api/compat/{id}/prepare") {
                if (!call.authorizeBrowserCall()) return@post
                val item = resolveItem(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_file_unavailable)))
                    return@post
                }
                val host = call.request.origin.remoteHost
                // If the browser reports that DIRECT play failed (force=true param),
                // escalate the file to REMUX so it gets a prepared compatible asset.
                val forceCompat = call.request.queryParameters["force"] == "true"
                if (forceCompat) {
                    val currentJob = compatibilityPipeline.currentJob(item.id)
                    val currentMode = currentJob?.decision?.mode
                        ?: applyPlaybackOverride(host, item).playbackDecision.mode
                    // Only escalate + invalidate when the current decision is still
                    // DIRECT. Browsers replay video.error repeatedly from stale
                    // player state after fallback has already happened, and every
                    // repeated force=true was bumping the ladder again and
                    // truncating the in-flight or finalized cache file — causing
                    // an endless "worker finishes, client restarts, file → 0B"
                    // loop that showed up in logs as "Missing #EXTM3U".
                    if (currentMode == PlaybackMode.DIRECT) {
                        debugLogSink.log("WebCompat", "direct_play_escalation id=${item.id} name=${item.displayName} - browser reported DIRECT failure, escalating")
                        rememberPlaybackOverride(
                            host = host,
                            item = item,
                            mode = nextBrowserFallbackMode(currentMode),
                            reason = "browser_decode_failed",
                        )
                        compatibilityPipeline.invalidate(item.id)
                    } else {
                        debugLogSink.log(
                            "WebCompat",
                            "direct_play_escalation_ignored id=${item.id} name=${item.displayName} currentMode=$currentMode status=${currentJob?.status} - job already in compatibility mode, not re-escalating",
                        )
                    }
                }
                val job = compatibilitySnapshotFor(host, item, triggerPreparation = true, priority = JobPriority.HIGH)
                val allowInProgressHls = supportsInProgressHls(
                    host = host,
                    userAgent = call.request.header(HttpHeaders.UserAgent),
                )
                val ready = compatibilityStreamReady(job, allowInProgressHls = allowInProgressHls)
                debugLogSink.log(
                    "WebCompat",
                    "prepare id=${item.id} mode=${job.decision.mode} status=${job.status} ready=$ready complete=${job.directReady} progress=${job.progressPercent} asset=${job.preparedAsset?.filePath}",
                )
                call.respond(
                    CompatibilityStatusPayload.from(
                        job = job,
                        ready = ready,
                        hlsUrl = compatibilityHlsUrl(job, allowInProgressHls = allowInProgressHls),
                        isAppleClient = isAppleClient(
                            call.request.header(HttpHeaders.UserAgent),
                            capabilityCache[host],
                        ),
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
                val allowInProgressHls = supportsInProgressHls(
                    host = call.request.origin.remoteHost,
                    userAgent = call.request.header(HttpHeaders.UserAgent),
                )
                val ready = compatibilityStreamReady(job, allowInProgressHls = allowInProgressHls)
                debugLogSink.log(
                    "WebSeek",
                    "seek id=${item.id} offsetMs=$offsetMs status=${job.status} ready=$ready",
                )
                call.respond(
                    CompatibilityStatusPayload.from(
                        job = job,
                        ready = ready,
                        hlsUrl = compatibilityHlsUrl(job, allowInProgressHls = allowInProgressHls),
                        isAppleClient = isAppleClient(
                            call.request.header(HttpHeaders.UserAgent),
                            capabilityCache[call.request.origin.remoteHost],
                        ),
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

                // Apple Gate: Safari/iOS/macOS must use HLS for any non-DIRECT playback
                // because progressive MP4 fed mid-encode triggers MSE-style stalls (Safari's
                // partial-content handling chokes on torn moof boxes the same way as Chromium).
                // Reject the endpoint outright for Apple clients on non-DIRECT decisions and
                // tell the player to switch to HLS.
                if (job.decision.mode != PlaybackMode.DIRECT &&
                    isAppleClient(
                        call.request.header(HttpHeaders.UserAgent),
                        capabilityCache[call.request.origin.remoteHost],
                    )
                ) {
                    debugLogSink.log("WebCompat/file", "REJECTED id=${item.id} reason=apple_use_hls")
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorPayload("Apple clients must use HLS for prepared playback."),
                    )
                    return@get
                }

                // Hard Gate: Only expose the prepared-file endpoint once the worker has marked
                // the growing asset as playable for this session. PLAYABLE_NOW, directReady,
                // and streamable all qualify here.
                if (!preparedAsset.isComplete &&
                    job.status != CompatibilityStatus.PLAYABLE_NOW &&
                    !job.directReady &&
                    !job.streamable
                ) {
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
                        isComplete = preparedAsset.isComplete,
                        allowGrowing = !preparedAsset.isComplete,
                        isFragmentedMp4 = preparedAsset.isFragmentedMp4,
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
                val host = call.request.origin.remoteHost
                debugLogSink.log("WebCompat/Error", "Client reported playback failure for id=${item.id} name=${item.displayName}")
                val currentMode = compatibilityPipeline.currentJob(item.id)?.decision?.mode
                    ?: applyPlaybackOverride(host, item).playbackDecision.mode
                rememberPlaybackOverride(
                    host = host,
                    item = item,
                    mode = nextBrowserFallbackMode(currentMode),
                    reason = "client_reported_failure",
                )
                compatibilityPipeline.invalidate(item.id)

                // Re-inspect using the updated per-host override so the next explicit play/retry
                // does not fall back to the browser choice that already failed in this session.
                compatibilitySnapshotFor(host = host, item = item, triggerPreparation = false)
                
                call.respond(HttpStatusCode.NoContent)
            }

            
            post("/api/telemetry/capabilities") {
                if (!call.authorizeBrowserCall()) return@post
                val caps = call.receiveNullable<ClientCapabilities>()
                if (caps != null) {
                    val ip = call.request.origin.remoteHost
                    capabilityCache[ip] = caps
                    decisionCache.keys.removeAll { it.startsWith("$ip|") }
                    debugLogSink.log("Telemetry", "Capabilities recorded for $ip: browser=${caps.browserFamily} hevc=${caps.supportsHevc} mse=${caps.supportsMse}")
                }
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
                call.response.cookies.append(
                    Cookie(
                        name = COOKIE_NAME,
                        value = "",
                        path = "/",
                        httpOnly = true,
                        expires = GMTDate.START,
                        maxAge = 0,
                    ),
                )
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
                val requestedSizePx = call.request.queryParameters["size"]?.toIntOrNull()?.coerceIn(
                    MIN_BROWSER_THUMBNAIL_SIZE_PX,
                    MAX_BROWSER_THUMBNAIL_SIZE_PX,
                )
                val posterSizePx = requestedSizePx ?: DEFAULT_CARD_THUMBNAIL_SIZE_PX
                val frameSizePx = requestedSizePx ?: DEFAULT_SCRUB_THUMBNAIL_SIZE_PX

                // During heavy prepare, skip frame-at-time extraction (expensive) and only
                // serve cached/cheap poster thumbnails. This frees CPU for the active transcode.
                val bytesOrNull = if (hasHeavyPrepareJob && timeMs != null) {
                    // Skip scrubbing frame extraction during heavy prepare â€” just serve poster
                    mediaAnalyzer.loadThumbnailBytes(item, posterSizePx)
                } else if (timeMs != null) {
                    mediaAnalyzer.extractFrameAtMs(item, timeMs, frameSizePx) ?: mediaAnalyzer.loadThumbnailBytes(item, posterSizePx)
                } else {
                    mediaAnalyzer.loadThumbnailBytes(item, posterSizePx)
                }

                if (bytesOrNull == null) {
                    if (!hasHeavyPrepareJob) {
                        debugLogSink.log("WebThumb", "fallback id=${item.id} name=${item.displayName} timeMs=$timeMs")
                    }
                    call.respondBytes(thumbnailPlaceholderBytes, ContentType.Image.JPEG)
                    return@get
                }

                if (!hasHeavyPrepareJob) {
                    debugLogSink.log("WebThumb", "served id=${item.id} bytes=${bytesOrNull.size} timeMs=$timeMs")
                }
                // Don't call observeClient for thumbnail requests â€” this was causing
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

            get("/api/download/zip") {
                if (!call.authorizeBrowserCall()) return@get
                val localizedContext = localizedContext()
                val settings = settingsRepository.settings.first()
                if (settings.preventDownload) {
                    call.respond(HttpStatusCode.Forbidden, ErrorPayload(localizedContext.getString(R.string.web_error_downloads_disabled)))
                    return@get
                }
                
                val ids = call.request.queryParameters["ids"]?.split(",")?.filter { it.isNotBlank() }
                val category = call.request.queryParameters["category"]?.lowercase()
                val query = call.request.queryParameters["query"]?.lowercase()
                val folderId = call.request.queryParameters["folderId"]

                val items = when {
                    !ids.isNullOrEmpty() -> ids.mapNotNull { storageRepository.findItemById(it) }
                    category != null || folderId != null || query != null -> {
                        // Resolve items by filter on the server side to avoid sending thousands of IDs in URL
                        // We use the session state as the authoritative source of shared items
                        sessionManager.sessionState.value.selectedItems.filter { item ->
                            val matchesCategory = when (category) {
                                null, "", "all" -> true
                                "media" -> item.category == MediaCategory.VIDEO ||
                                    item.category == MediaCategory.PHOTO ||
                                    item.category == MediaCategory.MUSIC
                                "videos" -> item.category == MediaCategory.VIDEO
                                "photos" -> item.category == MediaCategory.PHOTO
                                "music" -> item.category == MediaCategory.MUSIC
                                "files" -> item.category == MediaCategory.FILE
                                else -> true
                            }
                            val matchesQuery = query.isNullOrBlank() || 
                                item.displayName.contains(query, ignoreCase = true)
                            val matchesFolder = folderId == null || item.sourceFolderId == folderId
                            
                            matchesCategory && matchesQuery && matchesFolder
                        }
                    }
                    else -> emptyList()
                }

                if (items.isEmpty()) {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload(localizedContext.getString(R.string.browser_file_unavailable)))
                    return@get
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(Date())
                val zipFileName = "DirectServe_$timestamp.zip"

                call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, zipFileName).toString())
                
                call.respondOutputStream(ContentType.Application.Zip, HttpStatusCode.OK) {
                    ZipOutputStream(this).use { zip ->
                        val usedNames = mutableSetOf<String>()
                        items.forEach { item ->
                            var entryName = item.displayName
                            // Basic collision avoidance
                            var attempt = 1
                            while (usedNames.contains(entryName)) {
                                val base = item.displayName.substringBeforeLast(".")
                                val ext = item.displayName.substringAfterLast(".", "")
                                entryName = if (ext.isNotEmpty()) "$base ($attempt).$ext" else "${item.displayName} ($attempt)"
                                attempt++
                            }
                            usedNames.add(entryName)

                            try {
                                addFileToZip(item, entryName, zip)
                                sessionManager.onTransferProgress(call.remoteHost(), item.sizeBytes, ClientActivity.DOWNLOADING)
                            } catch (e: Exception) {
                                debugLogSink.log("WebZip", "Failed to add ${item.displayName} to zip", e)
                            }
                        }
                        zip.flush()
                    }
                }
            }

            get("/stream/{id}") {
                if (!call.authorizeBrowserCall()) return@get
                val itemId = call.parameters["id"]
                val item = resolveItem(itemId) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload(localizedContext().getString(R.string.browser_file_unavailable)))
                    return@get
                }

                val effectiveJob = compatibilitySnapshotFor(
                    host = call.request.origin.remoteHost,
                    item = item,
                    triggerPreparation = false,
                )

                // Hard Gate: Prevent browsers from trying to play incompatible raw containers (MKV/TS)
                // during preparation. They must use HLS or wait for the finalized MP4.
                if (item.category == MediaCategory.VIDEO && effectiveJob.decision.mode != PlaybackMode.DIRECT) {
                    debugLogSink.log("WebStream", "REJECTED id=${item.id} name=${item.displayName} mode=${effectiveJob.decision.mode} reason=incompatible-raw")
                    call.respond(HttpStatusCode.Forbidden, ErrorPayload("Direct stream of this container is disabled for compatibility. Use the prepared stream instead."))
                    return@get
                }

                val activity = when (item.category) {
                    MediaCategory.VIDEO -> ClientActivity.WATCHING_VIDEO
                    MediaCategory.PHOTO -> ClientActivity.VIEWING_PHOTO
                    MediaCategory.MUSIC -> ClientActivity.PLAYING_MUSIC
                    MediaCategory.FILE -> ClientActivity.BROWSING
                }
                debugLogSink.log("WebStream", "serving id=${item.id} name=${item.displayName} category=${item.category} mime=${item.mimeType}")
                call.streamItem(
                    itemId = itemId,
                    asAttachment = false,
                    activity = activity,
                )
            }

            // Master playlist â€” advertises the stream with an explicit CODECS hint.
            // The codec string is read dynamically from the fMP4 moov box so it exactly
            // matches what the Android encoder produced.  A hardcoded Baseline string
            // caused bufferAppendError when the hardware encoder used Main/High Profile
            // or when DefaultEncoderFactory fallback produced HEVC instead of H.264.
            get("/hls/{id}/master.m3u8") {
                if (!call.authorizeBrowserCall()) return@get
                try {
                    val source = call.resolveHlsSource(call.parameters["id"]) ?: return@get
                    val support = resolveInProgressHlsSupport(
                        host = call.request.origin.remoteHost,
                        userAgent = call.request.header(HttpHeaders.UserAgent),
                    )
                    val allowInProgressHls = support.allowed
                    if (!compatibilityStreamReady(source.job, allowInProgressHls = allowInProgressHls)) {
                        debugLogSink.log(
                            "WebHls",
                            "hls_start_blocked id=${source.item.id} reason=stream_not_safe browser=${support.reason} status=${source.job.status}",
                        )
                        // hls.js treats 425 TooEarly as a fatal networkError. Use 503 +
                        // Retry-After so it retries without blowing up the pipeline.
                        call.response.headers.append(HttpHeaders.RetryAfter, "1")
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorPayload(localizedContext().getString(R.string.browser_hls_not_ready)))
                        return@get
                    }
                    // Media Heartbeat: master.m3u8 is the first media-bearing request in the
                    // HLS flow. Marking it prevents preemption during the MSE startup window
                    // before the first segment/init request arrives.
                    compatibilityPipeline.markMediaServed(source.item.id)
                    debugLogSink.log(
                        "WebHls",
                        "hls_start_allowed id=${source.item.id} browser=${support.reason} status=${source.job.status}",
                    )
                    call.response.header("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
                    call.response.header("transferMode.dlna.org", "Streaming")
                    call.response.header("realTimeInfo.dlna.org", "DLNA.ORG_TLAG=0")
                    val ua = call.request.header(HttpHeaders.UserAgent) ?: ""
                    // Read the video codec string from the fMP4 init segment (fast, non-blocking â€”
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
                    val detectedAudioCodec = hlsIndex?.audioCodecString
                    // â”€â”€ DEBUG â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    debugLogSink.log(
                        "WebHls/master",
                        "id=${source.item.id} " +
                                "mode=${source.job.decision.mode} " +
                                "file=${source.file.name} " +
                                "fileBytes=${source.file.length()} " +
                                "detectedCodec=${detectedVideoCodec ?: "NULLâ†’fallback:avc1.640028"} " +
                                "initBytes=${hlsIndex?.initSegmentLength ?: "n/a"} " +
                                "segments=${hlsIndex?.segments?.size ?: "n/a"}",
                    )
                    val masterPlaylist = buildHlsMasterPlaylist(
                        itemId = source.item.id,
                        detectedVideoCodec = detectedVideoCodec,
                        detectedAudioCodec = detectedAudioCodec,
                        width = hlsIndex?.width,
                        height = hlsIndex?.height,
                    )
                    debugLogSink.log("WebHls/master", "serving:\n$masterPlaylist")
                    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

                    // Media Heartbeat: protects this preparation session from preemption
                    compatibilityPipeline.markMediaServed(source.item.id)

                    if (index.segments.isEmpty()) {
                        debugLogSink.log("WebHls", "playlist empty id=${source.item.id} init=${index.initSegmentLength} file=${index.fileLength}")
                        call.respond(HttpStatusCode.Accepted, ErrorPayload(localizedContext().getString(R.string.browser_hls_not_ready)))
                        return@get
                    }
                    debugLogSink.log(
                        "WebHls",
                        "playlist_published id=${source.item.id} committedSegments=${index.segments.size} init=${index.initSegmentLength} complete=${source.job.directReady}",
                    )
                    sessionManager.observeClient(
                        call.remoteHost(),
                        call.request.header(HttpHeaders.UserAgent),
                        ClientActivity.WATCHING_VIDEO,
                    )
                    call.response.header("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
                    call.response.header("transferMode.dlna.org", "Streaming")
                    call.response.header("realTimeInfo.dlna.org", "DLNA.ORG_TLAG=0")
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
                            job = source.job,
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

                    // Media Heartbeat: protects this preparation session from preemption
                    compatibilityPipeline.markMediaServed(source.item.id)

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

                    // Segments in the manifest are 0-indexed from the start of the current
                    // fMP4 file. The file always starts at segment 0 regardless of seek offset
                    // (timestamps are adjusted in-flight via MseTfhdPatcher.patchTimestamps).
                    val targetFileSegIndex = indexInManifest

                    // Media Heartbeat: protects this preparation session from preemption
                    compatibilityPipeline.markMediaServed(source.item.id)

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
                        debugLogSink.log("WebHls/Speculative", "segment wait timeout id=${source.item.id} index=$indexInManifest")
                        
                        if (completed) {
                            call.respond(HttpStatusCode.NotFound, ErrorPayload("Segment not found in finalized file."))
                        } else {
                            // Long-Tail HLS Manifest: We advertised this segment but it's not ready yet.
                            // Send 503 Service Unavailable with Retry-After to tell the browser
                            // to back off and try again shortly without treating it as a fatal error.
                            call.response.headers.append(HttpHeaders.RetryAfter, "1")
                            call.respond(HttpStatusCode.ServiceUnavailable, ErrorPayload("Segment is still being prepared."))
                        }
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
                        val afterTfhd = MseTfhdPatcher.patch(rawSegmentBytes, segment.offset)
                        MseTfhdPatcher.patchTimestamps(afterTfhd, source.job.startOffsetMs, index.timescales)
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

    /**
     * Resolves the current compatibility state for a specific item/client combination.
     * 
     * ARCHITECTURAL GUARDRAIL:
     * - [triggerPreparation] MUST ONLY be true when called from explicit Play/Seek intent routes.
     * - Passive browsing (Discovery, Bootstrap, Library) MUST ALWAYS pass triggerPreparation = false.
     * - JobPriority.LOW is reserved for manual bulk prepare and must not be used with triggerPreparation=true 
     *   unless specifically intended for non-immediate background work.
     */
    private suspend fun compatibilitySnapshotFor(
        host: String,
        item: SharedItem,
        triggerPreparation: Boolean,
        priority: JobPriority = JobPriority.LOW,
    ): CompatibilityJob {
        val caps = capabilityCache[host]
        val forcedBrowserFallback = triggerPreparation &&
            item.playbackDecision.mode != PlaybackMode.DIRECT &&
            item.playbackDecision.reason.contains("Browser rejected direct playback", ignoreCase = true)
        val refreshedInspection = runCatching {
            if (
                item.category == MediaCategory.VIDEO &&
                (
                    triggerPreparation ||
                        item.metadata["video_codec"].isNullOrBlank() ||
                        item.metadata["video_codec"] == "unknown" ||
                        item.metadata["audio_codec"] == "unknown" ||
                        item.metadata["container"].isNullOrBlank()
                    )
            ) {
                mediaAnalyzer.inspect(Uri.parse(item.uri), item.mimeType, item.displayName)
            } else {
                null
            }
        }.getOrNull()
        val inspectedItem = refreshedInspection?.let { inspection ->
            item.copy(
                metadata = item.metadata + buildMap {
                    put("container", inspection.container.name)
                    inspection.videoTrackMimeType?.let { put("video_codec", it) }
                    inspection.audioTrackMimeType?.let { put("audio_codec", it) }
                },
            )
        } ?: item

        val effectiveDecision = when {
            caps == null || forcedBrowserFallback -> inspectedItem.playbackDecision
            else -> {
                val cacheKey = buildDecisionCacheKey(host, inspectedItem, caps)
                decisionCache[cacheKey] ?: (refreshedInspection?.let { mediaAnalyzer.decidePlayback(it, caps) }
                    ?: mediaAnalyzer.decidePlayback(
                        Uri.parse(inspectedItem.uri),
                        inspectedItem.mimeType,
                        inspectedItem.displayName,
                        caps,
                    )).also { decision ->
                    decisionCache[cacheKey] = decision
                    debugLogSink.log(
                        "PlaybackDecision",
                        "id=${inspectedItem.id} host=$host mode=${decision.mode} reason=${decision.reason} " +
                            "container=${inspectedItem.metadata["container"] ?: inspectedItem.mimeType ?: "unknown"} " +
                            "mime=${inspectedItem.mimeType} video=${inspectedItem.metadata["video_codec"] ?: "unknown"} audio=${inspectedItem.metadata["audio_codec"] ?: "unknown"}",
                    )
                }
            }
        }
        val effectiveItem = applyPlaybackOverride(
            host = host,
            item = if (effectiveDecision == inspectedItem.playbackDecision) inspectedItem else inspectedItem.copy(playbackDecision = effectiveDecision),
        )
        return if (triggerPreparation && effectiveItem.playbackDecision.mode != PlaybackMode.DIRECT) {
            compatibilityPipeline.requestPreparation(effectiveItem, priority, caps)
        } else {
            compatibilityPipeline.inspect(effectiveItem, caps)
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.streamItem(
        itemId: String?,
        asAttachment: Boolean,
        activity: ClientActivity,
    ) {
        response.apply {
            header("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
            header("transferMode.dlna.org", "Streaming")
            header("realTimeInfo.dlna.org", "DLNA.ORG_TLAG=0")
        }
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
            when (val resolution = compatibilityPipeline.resolvePlayback(item, capabilityCache[remoteHost()])) {
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
        response.apply {
            header("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
            header("transferMode.dlna.org", "Streaming")
            header("realTimeInfo.dlna.org", "DLNA.ORG_TLAG=0")
        }
        val resolver = context.contentResolver
        val descriptor = try {
            resolver.openAssetFileDescriptor(Uri.parse(playbackSource.uriString), "r")
        } catch (e: Exception) {
            respond(HttpStatusCode.NotFound, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_file_unavailable)))
            return
        }

        // Media Heartbeat: protects this preparation session from preemption
        compatibilityPipeline.markMediaServed(item.id)

        descriptor?.use { assetDescriptor ->
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

        // Media Heartbeat: protects this preparation session from preemption
        compatibilityPipeline.markMediaServed(item.id)

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
                isFragmentedMp4 = playbackSource.isFragmentedMp4,
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
        isFragmentedMp4: Boolean = false,
    ) {
        val requestedRange = parseRequestedGrowingRange(request.header(HttpHeaders.Range))
        if (requestedRange != null) {
            val availableLength = waitForGrowingFileOffset(
                itemId = item.id,
                file = file,
                requiredOffset = requestedRange.start,
                isFragmentedMp4 = isFragmentedMp4,
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

    private fun compatibilityStreamReady(job: CompatibilityJob, allowInProgressHls: Boolean = false): Boolean {
        if (job.decision.mode == PlaybackMode.DIRECT) return true
        if (job.status == CompatibilityStatus.READY ||
            job.status == CompatibilityStatus.PLAYABLE_NOW ||
            job.preparedAsset?.isComplete == true ||
            job.directReady
        ) {
            return true
        }
        return allowInProgressHls &&
            job.preparedAsset?.isFragmentedMp4 == true &&
            job.hlsReady &&
            job.streamable
    }

    private fun compatibilityHlsUrl(job: CompatibilityJob, allowInProgressHls: Boolean): String? {
        if (!allowInProgressHls) return null
        if (job.status == CompatibilityStatus.READY || job.preparedAsset?.isComplete == true || job.directReady) return null
        if (job.preparedAsset?.isFragmentedMp4 != true || !job.hlsReady || !job.streamable) return null
        return "/hls/${job.itemId}/master.m3u8"
    }

    private fun playbackOverrideKey(host: String, itemId: String): String = "$host|$itemId"

    private fun playbackModeRank(mode: PlaybackMode): Int = when (mode) {
        PlaybackMode.DIRECT -> 0
        PlaybackMode.REMUX -> 1
        PlaybackMode.TRANSMUX -> 2
        PlaybackMode.TRANSCODE -> 3
    }

    // One-step escalation ladder. Skipping REMUX→TRANSMUX (jumping straight to
    // TRANSCODE) used to make every browser-reported failure pay a full re-encode
    // even when a cheaper repackage would have worked. Walk the ladder one rung at
    // a time; TRANSCODE is the terminal mode.
    private fun nextBrowserFallbackMode(mode: PlaybackMode): PlaybackMode = when (mode) {
        PlaybackMode.DIRECT -> PlaybackMode.REMUX
        PlaybackMode.REMUX -> PlaybackMode.TRANSMUX
        PlaybackMode.TRANSMUX -> PlaybackMode.TRANSCODE
        PlaybackMode.TRANSCODE -> PlaybackMode.TRANSCODE
    }

    private fun rememberPlaybackOverride(
        host: String,
        item: SharedItem,
        mode: PlaybackMode,
        reason: String,
    ) {
        val key = playbackOverrideKey(host, item.id)
        val currentMode = playbackOverrideCache[key] ?: item.playbackDecision.mode
        if (playbackModeRank(mode) <= playbackModeRank(currentMode)) return
        playbackOverrideCache[key] = mode
        debugLogSink.log(
            "WebCompat",
            "playback_override id=${item.id} host=$host from=$currentMode to=$mode reason=$reason",
        )
    }

    private fun applyPlaybackOverride(host: String, item: SharedItem): SharedItem {
        val overrideMode = playbackOverrideCache[playbackOverrideKey(host, item.id)] ?: return item
        if (playbackModeRank(overrideMode) <= playbackModeRank(item.playbackDecision.mode)) {
            return item
        }
        val overrideReason = when (overrideMode) {
            PlaybackMode.REMUX -> "Browser rejected direct playback; preparing compatible version"
            PlaybackMode.TRANSCODE -> "Browser rejected earlier playback attempts; transcoding for compatibility"
            else -> item.playbackDecision.reason
        }
        val overrideLabel = when (overrideMode) {
            PlaybackMode.REMUX -> "Preparing compatible version"
            PlaybackMode.TRANSCODE -> "Transcoding for compatibility"
            else -> item.playbackDecision.compatibilityLabel
        }
        return item.copy(
            playbackDecision = item.playbackDecision.copy(
                mode = overrideMode,
                reason = overrideReason,
                compatibilityLabel = overrideLabel,
            ),
        )
    }

    private data class InProgressHlsSupport(
        val allowed: Boolean,
        val reason: String,
    )

    private fun supportsInProgressHls(host: String, userAgent: String?): Boolean {
        return resolveInProgressHlsSupport(host, userAgent).allowed
    }

    private fun resolveInProgressHlsSupport(host: String, userAgent: String?): InProgressHlsSupport {
        val ua = userAgent.orEmpty()
        val caps = capabilityCache[host]
        if (ua.isBlank() && caps == null) {
            return InProgressHlsSupport(allowed = false, reason = "missing_client_capabilities")
        }

        val isChromiumDesktop =
            (ua.contains("Chrome", ignoreCase = true) ||
                ua.contains("Chromium", ignoreCase = true) ||
                ua.contains("Edg/", ignoreCase = true)) &&
                !ua.contains("Android", ignoreCase = true) &&
                !ua.contains("Mobile", ignoreCase = true)
        val isAppleFamily = isAppleClient(ua, caps)
        val isTvBrowser = TV_BROWSER_UA_REGEX.containsMatchIn(ua) ||
            (TV_TOKEN_REGEX.containsMatchIn(ua) && !APPLE_TV_EXCLUSION_UA_REGEX.containsMatchIn(ua))
        // Safari/iOS/macOS/AppleTV always support native HLS — it's a platform guarantee
        // independent of capability telemetry. Don't gate on caps.supportsHlsNatively here;
        // when caps haven't arrived yet (or report stale data) the gate would force the player
        // onto a non-existent MSE/MP4 path and stall. Apple = HLS, full stop.
        if (isAppleFamily) {
            return InProgressHlsSupport(allowed = true, reason = "apple_native_hls")
        }
        val nativeHlsCandidate =
            caps?.supportsHlsNatively == true &&
                (isTvBrowser ||
                    caps.browserFamily.equals("Safari", ignoreCase = true) ||
                    caps.os.equals("iOS", ignoreCase = true) ||
                    caps.os.equals("macOS", ignoreCase = true))
        if (nativeHlsCandidate) {
            return InProgressHlsSupport(allowed = true, reason = "native_hls")
        }
        val managedHlsCandidate =
            (caps?.supportsMse != false) &&
                isChromiumDesktop &&
                !isTvBrowser
        if (managedHlsCandidate) {
            return InProgressHlsSupport(allowed = true, reason = "desktop_chromium")
        }
        val reason = when {
            caps?.supportsHlsNatively == true && isTvBrowser ->
                "tv_native_hls_waiting_for_streamability"
            isTvBrowser -> "tv_requires_native_hls"
            !isChromiumDesktop -> "browser_not_supported"
            caps?.supportsMse == false -> "mse_not_supported"
            else -> "stream_not_supported"
        }
        return InProgressHlsSupport(allowed = false, reason = reason)
    }

    private fun isAppleClient(userAgent: String?, caps: ClientCapabilities? = null): Boolean {
        val ua = userAgent.orEmpty()
        if (caps?.os.equals("iOS", ignoreCase = true) ||
            caps?.os.equals("macOS", ignoreCase = true) ||
            caps?.browserFamily.equals("Safari", ignoreCase = true)
        ) {
            return true
        }
        return ua.contains("iPhone", ignoreCase = true) ||
            ua.contains("iPad", ignoreCase = true) ||
            ua.contains("iPod", ignoreCase = true) ||
            ua.contains("AppleTV", ignoreCase = true) ||
            (ua.contains("Safari", ignoreCase = true) &&
                !ua.contains("Chrome", ignoreCase = true) &&
                !ua.contains("Chromium", ignoreCase = true) &&
                !ua.contains("Edg/", ignoreCase = true))
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
        if (item.category != MediaCategory.VIDEO) {
            respond(HttpStatusCode.Conflict, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_hls_not_needed)))
            return null
        }

        val job = compatibilitySnapshotFor(request.origin.remoteHost, item, triggerPreparation = true, priority = JobPriority.HIGH)
        if (job.decision.mode == PlaybackMode.DIRECT) {
            respond(HttpStatusCode.Conflict, ErrorPayload(this@KtorGhostStreamServer.context.getString(R.string.browser_hls_not_needed)))
            return null
        }
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
        val effectiveItem = if (job.decision == item.playbackDecision) item else item.copy(playbackDecision = job.decision)
        return HlsPlaybackSource(
            item = effectiveItem,
            job = job,
            file = file,
        )
    }

    // Suspend version â€” replaces Thread.sleep with coroutine-friendly delay so we
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
            val hasInitSegment = this?.initSegmentLength?.let { it > 0L } == true
            val hasFirstSegment = !requireFirstSegment || (this?.segments?.isNotEmpty() == true)
            val hasRequiredSegment = requiredSegmentIndex == null ||
                this?.segments?.getOrNull(requiredSegmentIndex) != null
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
                // The job is done. The segments must exist â€” do a few extra retries to
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
     * HLS Master Playlist â€” contains an explicit CODECS string so that hls.js and native
     * HLS players configure MSE / hardware decoders with the correct codec, avoiding the
     * bufferAppendError that occurs when the codec is guessed from the raw segment bytes.
     *
     * The video codec string is read directly from the fMP4 init segment's moov box so it
     * exactly matches what the Android encoder produced â€” Android hardware encoders typically
     * output Main or High Profile H.264, not Baseline, and may produce HEVC via fallback.
     * A hardcoded "avc1.42E028" would mismatch and cause bufferAppendError in hls.js.
     */
    private fun buildHlsMasterPlaylist(
        itemId: String,
        detectedVideoCodec: String?,
        detectedAudioCodec: String?,
        width: Int? = null,
        height: Int? = null,
    ): String {
        // Use the codec list detected from the fMP4 moov box.
        // Fall back to High Profile L4.0 for video when detection is unavailable.
        val codecList = buildList {
            add(detectedVideoCodec ?: "avc1.640028")
            detectedAudioCodec?.let(::add)
        }.joinToString(",")
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            val streamInfParts = mutableListOf<String>()
            streamInfParts += "BANDWIDTH=2000000"
            streamInfParts += "CODECS=\"$codecList\""
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
        job: CompatibilityJob,
    ): String {
        val item = storageRepository.findItemById(itemId)
        var durationMs = job.totalDurationMs ?: item?.durationMs ?: 0L

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
            "building hls playlist id=$itemId name=${item?.displayName} durationMs=$durationMs mode=${job.decision.mode} label=${job.decision.compatibilityLabel} segments=${index.segments.size} ready=${job.status == CompatibilityStatus.READY}"
        )

        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:$HLS_TARGET_DURATION_SECONDS")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine(
                if (job.status == CompatibilityStatus.READY || job.preparedAsset?.isComplete == true) {
                    "#EXT-X-PLAYLIST-TYPE:VOD"
                } else {
                    "#EXT-X-PLAYLIST-TYPE:EVENT"
                }
            )
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            appendLine("#EXT-X-MAP:URI=\"/hls/$itemId/init.mp4\"")

            // Only publish committed segments. Never advertise speculative segments that
            // are still being written, because MSE clients may try to append them
            // immediately and transition into an unrecoverable error state.
            repeat(index.segments.size) { segmentIndex ->
                val duration = index.segments[segmentIndex].durationSeconds
                appendLine("#EXTINF:${"%.3f".format(java.util.Locale.US, duration)},")
                appendLine("/hls/$itemId/segment/$segmentIndex.m4s")
            }

            // Only add ENDLIST if the job is truly finished.
            if (job.status == CompatibilityStatus.READY || job.preparedAsset?.isComplete == true) {
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
    // thread â€” it blocks the dedicated IO thread that Ktor allocated for the response).
    private fun waitForGrowingFileOffset(
        itemId: String,
        file: File,
        requiredOffset: Long,
        isFragmentedMp4: Boolean = false,
    ): Long {
        var idlePolls = 0
        while (idlePolls < MAX_GROWING_FILE_IDLE_POLLS) {
            val rawLength = file.length()
            val job = compatibilityPipeline.currentJob(itemId)
            val finalized = job?.preparedAsset?.isComplete == true || job?.status == CompatibilityStatus.READY
            val failed = job?.status == CompatibilityStatus.FAILED || job?.status == CompatibilityStatus.STALLED

            // For an in-progress fragmented MP4 (Media3 InAppMuxer output) the writer head
            // can be in the middle of a moof/mdat box. Serving past the last fully-committed
            // segment hands torn boxes to the browser MSE and the player stalls (the regression
            // the user is seeing as "nothing autoplaying" — playback starts then freezes a few
            // seconds in). Cap to the end of the last complete moof+mdat fragment.
            val available = if (isFragmentedMp4 && !finalized) {
                fragmentedSafeOffset(file) ?: rawLength
            } else {
                rawLength
            }

            if (available > requiredOffset) {
                return available
            }
            if (finalized || failed) {
                return available
            }
            // Far-future seek into a growing fragmented MP4: the writer hasn't
            // gotten anywhere near here, and waiting the full 90 s budget will
            // not help. Return fast so the browser receives 416 quickly and
            // the source-switch fallback (HLS) can engage instead of stalling.
            if (isFragmentedMp4 && requiredOffset > available * 4L && available > 0L) {
                return available
            }

            idlePolls += 1
            Thread.sleep(GROWING_FILE_POLL_INTERVAL_MS)
        }
        val rawLength = file.length()
        return if (isFragmentedMp4) fragmentedSafeOffset(file) ?: rawLength else rawLength
    }

    private fun fragmentedSafeOffset(file: File): Long? {
        val index = runCatching {
            FragmentedMp4HlsIndexer.read(file, fragmentDurationSeconds = HLS_SEGMENT_DURATION_SECONDS)
        }.getOrNull() ?: return null
        if (index.initSegmentLength <= 0L) return null
        val segments = index.segments
        if (segments.isEmpty()) return index.initSegmentLength
        val last = segments.last()
        return last.offset + last.length
    }

    /**
     * Seek an [InputStream] to the given byte offset.
     * [InputStream.skip] is unreliable on Android content-provider streams â€” it
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



    private fun addFileToZip(item: SharedItem, entryName: String, zip: ZipOutputStream) {
        val entry = ZipEntry(entryName)
        zip.putNextEntry(entry)
        context.contentResolver.openInputStream(Uri.parse(item.uri))?.use { input ->
            input.copyTo(zip)
        }
        zip.flush()
        zip.closeEntry()
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

    private fun buildDecisionCacheKey(
        host: String,
        item: SharedItem,
        caps: ClientCapabilities,
    ): String {
        return listOf(
            host,
            item.id,
            item.sizeBytes.toString(),
            (item.lastModifiedEpochMs ?: 0L).toString(),
            item.uri,
            caps.browserFamily,
            caps.os,
            caps.supportsAvc.toString(),
            caps.supportsHevc.toString(),
            caps.supportsVp9.toString(),
            caps.supportsAv1.toString(),
            caps.supportsAac.toString(),
            caps.supportsMp3.toString(),
            caps.supportsOpus.toString(),
            caps.supportsAc3.toString(),
            caps.supportsEac3.toString(),
            caps.supportsMse.toString(),
            caps.supportsHlsNatively.toString(),
            caps.supportsHdr.toString(),
            caps.isPowerEfficient.toString(),
        ).joinToString("|")
    }

    private fun buildThumbnailPlaceholderBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#1F2937"))
        return java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
            bitmap.recycle()
            output.toByteArray()
        }
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
        val enabledCategories: EnabledCategories,
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
        val folders: Int,
    )

    @Serializable
    private data class EnabledCategories(
        val videos: Boolean,
        val photos: Boolean,
        val music: Boolean,
        val files: Boolean,
    )

    @Serializable
    private data class BrowserItemCard(
        val id: String,
        val title: String,
        val category: String,
        val mimeType: String,
        val sizeBytes: Long,
        val durationMs: Long?,
        val thumbnailUrl: String?,
        val streamUrl: String,
        val downloadUrl: String?,
        val subtitleUrl: String?,
        val compatibilityLabel: String?,
        val compatibilityStatus: CompatibilityStatus? = null,
        val width: Int? = null,
        val height: Int? = null,
        val totalDurationMs: Long? = null,
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
                mimeType = item.mimeType ?: "application/octet-stream",
                sizeBytes = item.sizeBytes,
                durationMs = item.durationMs,
                thumbnailUrl = if (
                    showThumbnails &&
                    (
                        item.category == MediaCategory.PHOTO ||
                            item.category == MediaCategory.VIDEO ||
                            item.mimeType == "application/pdf" ||
                            item.displayName.endsWith(".pdf", ignoreCase = true)
                    )
                ) {
                    "/thumb/${item.id}?size=$DEFAULT_CARD_THUMBNAIL_SIZE_PX"
                } else {
                    null
                },
                streamUrl = "/stream/${item.id}",
                downloadUrl = if (allowDownloads) "/download/${item.id}" else null,
                subtitleUrl = item.subtitleMatch?.let { "/subtitle/${item.id}" },
                compatibilityLabel = item.playbackDecision.compatibilityLabel,
                compatibilityStatus = compatibilityJob.status.takeIf { item.playbackDecision.mode != PlaybackMode.DIRECT },
                width = compatibilityJob.width,
                height = compatibilityJob.height,
                totalDurationMs = compatibilityJob.totalDurationMs ?: item.durationMs,
            )
        }
    }

    @Serializable
    private data class BrowserItemsPage(
        val items: List<BrowserItemCard>,
        val totalCount: Int,
        val offset: Int,
        val limit: Int,
        val hasMore: Boolean,
    )

    @Serializable
    private data class BrowserItemDetails(
        val id: String,
        val title: String,
        val mimeType: String?,
        val container: String? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
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
        val effectivePlaybackMode: EffectivePlaybackMode,
        val compatibilityStatus: CompatibilityStatus? = null,
        val compatibilityMessage: String? = null,
        val compatibilityProgressPercent: Int? = null,
        val compatibilityComplete: Boolean,
        val streamReady: Boolean,
        // Non-null when the prepared MP4 is fully ready and can be played directly
        // via <video src> without going through HLS or MSE.  The browser plays it
        // natively so the TFHD base-data-offset field is not a problem.
        val preparedMp4Url: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val totalDurationMs: Long? = null,
    ) {
        companion object {
            fun from(
                item: SharedItem,
                compatibilityJob: CompatibilityJob,
                streamReady: Boolean,
                hlsUrl: String?,
                allowDownloads: Boolean,
                isAppleClient: Boolean,
            ): BrowserItemDetails {
                val isComplete = compatibilityJob.isFinalized
                val hasProgressedAsset = compatibilityJob.preparedAsset != null
                val decision = compatibilityJob.decision

                return BrowserItemDetails(
                    id = item.id,
                    title = item.displayName,
                    mimeType = item.mimeType,
                    container = item.metadata["container"],
                    videoCodec = item.metadata["video_codec"],
                    audioCodec = item.metadata["audio_codec"],
                    category = item.category.name.lowercase(),
                    streamUrl = "/stream/${item.id}",
                    hlsUrl = hlsUrl,
                    downloadUrl = if (allowDownloads) "/download/${item.id}" else null,
                    subtitleUrl = item.subtitleMatch?.let { "/subtitle/${item.id}" },
                    durationMs = item.durationMs,
                    sizeBytes = item.sizeBytes,
                    compatibility = decision.compatibilityLabel,
                    reason = decision.reason,
                    playbackMode = decision.mode,
                    effectivePlaybackMode = compatibilityJob.effectivePlaybackMode,
                    compatibilityStatus = compatibilityJob.status.takeIf { decision.mode != PlaybackMode.DIRECT },
                    compatibilityMessage = compatibilityJob.message.takeIf { decision.mode != PlaybackMode.DIRECT },
                    compatibilityProgressPercent = compatibilityJob.progressPercent,
                    compatibilityComplete = isComplete,
                    streamReady = streamReady,
                    preparedMp4Url = if (
                        streamReady &&
                        hasProgressedAsset &&
                        decision.mode != PlaybackMode.DIRECT &&
                        !isAppleClient
                    ) {
                        "/api/compat/${item.id}/file"
                    } else {
                        null
                    },
                    width = compatibilityJob.width,
                    height = compatibilityJob.height,
                    totalDurationMs = compatibilityJob.totalDurationMs ?: item.durationMs,
                )
            }
        }
    }

    @Serializable
    private data class CompatibilityStatusPayload(
        val itemId: String,
        val playbackMode: PlaybackMode,
        val status: CompatibilityStatus,
        val message: String,
        val effectivePlaybackMode: EffectivePlaybackMode,
        val progressPercent: Int? = null,
        val ready: Boolean,
        val compatibilityComplete: Boolean,
        val preparedMp4Url: String? = null,
        val hlsUrl: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val totalDurationMs: Long? = null,
    ) {
        companion object {
            fun from(
                job: CompatibilityJob,
                ready: Boolean,
                hlsUrl: String?,
                isAppleClient: Boolean,
            ): CompatibilityStatusPayload {
                val isComplete = job.isFinalized
                val canExposeMp4 = ready &&
                    job.decision.mode != PlaybackMode.DIRECT &&
                    job.preparedAsset != null &&
                    !isAppleClient
                return CompatibilityStatusPayload(
                    itemId = job.itemId,
                    playbackMode = job.decision.mode,
                    status = job.status,
                    message = job.message,
                    effectivePlaybackMode = job.effectivePlaybackMode,
                    progressPercent = job.progressPercent,
                    ready = ready,
                    compatibilityComplete = isComplete,
                    preparedMp4Url = if (canExposeMp4) "/api/compat/${job.itemId}/file" else null,
                    hlsUrl = hlsUrl,
                    width = job.width,
                    height = job.height,
                    totalDurationMs = job.totalDurationMs,
                )
            }
        }
    }

    private data class HlsPlaybackSource(
        val item: SharedItem,
        val job: CompatibilityJob,
        val file: File,
    )

    private data class RequestedGrowingRange(
        val start: Long,
        val endInclusive: Long?,
    )

    companion object {
        const val DEFAULT_BROWSER_ITEMS_PAGE_SIZE = 24
        const val MAX_BROWSER_ITEMS_PAGE_SIZE = 100
        const val DEFAULT_CARD_THUMBNAIL_SIZE_PX = 320
        const val DEFAULT_SCRUB_THUMBNAIL_SIZE_PX = 320
        const val MIN_BROWSER_THUMBNAIL_SIZE_PX = 96
        const val MAX_BROWSER_THUMBNAIL_SIZE_PX = 640
        const val COOKIE_NAME = "ghost_session"
        const val GROWING_FILE_POLL_INTERVAL_MS = 300L
        const val MAX_GROWING_FILE_IDLE_POLLS = 300
        const val HLS_INDEX_POLL_INTERVAL_MS = 250L
        const val MAX_HLS_INDEX_IDLE_POLLS = 80
        const val HLS_SEGMENT_DURATION_SECONDS = 2.0
        const val HLS_TARGET_DURATION_SECONDS = 3
        const val STREAMING_BUFFER_SIZE = 64 * 1024
        const val MIN_SEGMENTS_BEFORE_PLAY = 2
        const val HLS_FINALIZED_RETRY_COUNT = 5
        const val HLS_FINALIZED_RETRY_INTERVAL_MS = 400L
        val TV_BROWSER_UA_REGEX = Regex("Tizen|webOS|Web0S|HbbTV|SmartTV|SMART-TV|NetCast|DLNA|Roku|AFTB|AFTS|AFTN|AFTT|FireTV|CrKey|OPR/.*TV|ANT_", RegexOption.IGNORE_CASE)
        val TV_TOKEN_REGEX = Regex("\\bTV\\b", RegexOption.IGNORE_CASE)
        val APPLE_TV_EXCLUSION_UA_REGEX = Regex("iPhone|iPad|AppleTV", RegexOption.IGNORE_CASE)
    }
}

private fun SharedItem.isEnabledBySettings(settings: AppSettings): Boolean = when (category) {
    MediaCategory.VIDEO -> settings.shareVideos
    MediaCategory.PHOTO -> settings.sharePhotos
    MediaCategory.MUSIC -> settings.shareMusic
    MediaCategory.FILE -> settings.shareFiles
}
