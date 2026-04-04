package com.ghoststream.core.network.server

import android.content.Context
import android.net.Uri
import com.ghoststream.core.media.CompatibilityJob
import com.ghoststream.core.media.CompatibilityPipeline
import com.ghoststream.core.media.CompatibilityStatus
import com.ghoststream.core.media.MediaAnalyzer
import com.ghoststream.core.media.PlaybackResolution
import com.ghoststream.core.media.PlaybackSource
import com.ghoststream.core.model.ClientActivity
import com.ghoststream.core.model.DebugLogSink
import com.ghoststream.core.model.MediaCategory
import com.ghoststream.core.model.NoOpDebugLogSink
import com.ghoststream.core.model.PlaybackMode
import com.ghoststream.core.model.SharedItem
import com.ghoststream.core.model.ThemeMode
import com.ghoststream.core.model.buildSessionAccessUrl
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
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
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
    private val assetLoader: WebAssetLoader = WebAssetLoader(context),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val debugLogSink: DebugLogSink = NoOpDebugLogSink,
) : GhostStreamServer {

    private var engine: ApplicationEngine? = null
    private val running = AtomicBoolean(false)

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

            get("/plyr.svg") {
                call.respondBytes(
                    bytes = assetLoader.readBytes("web/plyr.svg"),
                    contentType = ContentType.parse("image/svg+xml"),
                )
            }

            get("/api/bootstrap") {
                if (!call.authorizeBrowserCall()) return@get
                val settings = settingsRepository.settings.first()
                val state = sessionManager.sessionState.value
                val recentCards = mutableListOf<BrowserItemCard>()
                for (item in state.selectedItems.take(8)) {
                    recentCards += BrowserItemCard.from(
                        item = item,
                        compatibilityJob = compatibilitySnapshotFor(item, triggerPreparation = false),
                        showThumbnails = settings.showThumbnails,
                    )
                }
                call.respond(
                    BrowserBootstrap(
                        title = "GhostStream",
                        subtitle = "Stream & share files offline",
                        authEnabled = state.authEnabled,
                        sessionUrl = buildSessionAccessUrl(
                            sessionUrl = state.sessionUrl,
                            localAddress = state.networkAvailability.localAddress,
                            port = state.serverPort,
                        ),
                        sessionPort = state.serverPort,
                        categories = BrowserCategories(
                            videos = state.selectedItems.count { it.category == MediaCategory.VIDEO },
                            photos = state.selectedItems.count { it.category == MediaCategory.PHOTO },
                            music = state.selectedItems.count { it.category == MediaCategory.MUSIC },
                            files = state.selectedItems.count { it.category == MediaCategory.FILE },
                        ),
                        recent = recentCards,
                        themeMode = settings.themeMode,
                        showThumbnails = settings.showThumbnails,
                        largeCards = settings.largeTvCards,
                        prominentDownloadButton = settings.prominentDownloadButton,
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
                for (item in items) {
                    cards += BrowserItemCard.from(
                        item = item,
                        compatibilityJob = compatibilitySnapshotFor(item, triggerPreparation = false),
                        showThumbnails = settings.showThumbnails,
                    )
                }
                call.respond(cards)
            }

            get("/api/item/{id}") {
                if (!call.authorizeBrowserCall()) return@get
                val item = resolveItem(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload("This file is no longer available on your device."))
                    return@get
                }
                call.respond(
                    BrowserItemDetails.from(
                        item = item,
                        compatibilityJob = compatibilitySnapshotFor(
                            item = item,
                            triggerPreparation = item.category == MediaCategory.VIDEO && item.playbackDecision.mode != PlaybackMode.DIRECT,
                        ),
                    ),
                )
            }

            get("/api/compat/{id}") {
                if (!call.authorizeBrowserCall()) return@get
                val item = resolveItem(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload("This file is no longer available on your device."))
                    return@get
                }
                call.respond(CompatibilityStatusPayload.from(compatibilitySnapshotFor(item, triggerPreparation = false)))
            }

            post("/api/compat/{id}/prepare") {
                if (!call.authorizeBrowserCall()) return@post
                val item = resolveItem(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload("This file is no longer available on your device."))
                    return@post
                }
                call.respond(CompatibilityStatusPayload.from(compatibilitySnapshotFor(item, triggerPreparation = true)))
            }

            post("/auth/login") {
                val payload = call.receiveNullable<LoginPayload>()
                val enteredPin = payload?.pin.orEmpty()
                val ipAddress = call.remoteHost()
                if (!sessionManager.isPinValid(enteredPin)) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorPayload("That PIN didn't match. Please try again."))
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
                    call.respond(HttpStatusCode.NotFound, ErrorPayload("Preview unavailable"))
                    return@get
                }
                val item = resolveItem(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload("Preview unavailable"))
                    return@get
                }
                val bytes = mediaAnalyzer.loadThumbnailBytes(item) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorPayload("Preview unavailable"))
                    return@get
                }
                sessionManager.observeClient(call.remoteHost(), call.request.header(HttpHeaders.UserAgent), ClientActivity.BROWSING)
                call.respondBytes(bytes, ContentType.Image.JPEG)
            }

            get("/download/{id}") {
                if (!call.authorizeBrowserCall()) return@get
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
                    call.respond(HttpStatusCode.NotFound, ErrorPayload("This file is no longer available on your device."))
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

            get("/hls/{id}/playlist.m3u8") {
                if (!call.authorizeBrowserCall()) return@get
                val source = call.resolveHlsSource(call.parameters["id"]) ?: return@get
                val index = awaitHlsIndex(
                    itemId = source.item.id,
                    file = source.file,
                    requireFirstSegment = true,
                ) ?: run {
                    call.respond(HttpStatusCode.Accepted, ErrorPayload("Preparing the HLS stream for iPhone playback."))
                    return@get
                }
                if (index.segments.isEmpty()) {
                    call.respond(HttpStatusCode.Accepted, ErrorPayload("Preparing the HLS stream for iPhone playback."))
                    return@get
                }
                sessionManager.observeClient(
                    call.remoteHost(),
                    call.request.header(HttpHeaders.UserAgent),
                    ClientActivity.WATCHING_VIDEO,
                )
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respondText(
                    text = buildHlsPlaylist(
                        itemId = source.item.id,
                        index = index,
                        complete = source.job.preparedAsset?.isComplete == true || source.job.status == CompatibilityStatus.READY,
                    ),
                    contentType = ContentType.parse("application/vnd.apple.mpegurl"),
                )
            }

            get("/hls/{id}/init.mp4") {
                if (!call.authorizeBrowserCall()) return@get
                val source = call.resolveHlsSource(call.parameters["id"]) ?: return@get
                val index = awaitHlsIndex(
                    itemId = source.item.id,
                    file = source.file,
                    requireFirstSegment = false,
                ) ?: run {
                    call.respond(HttpStatusCode.Accepted, ErrorPayload("Preparing the HLS stream for iPhone playback."))
                    return@get
                }
                if (index.initSegmentLength <= 0L) {
                    call.respond(HttpStatusCode.Accepted, ErrorPayload("Preparing the HLS stream for iPhone playback."))
                    return@get
                }
                call.streamFileSlice(
                    file = source.file,
                    mimeType = "video/mp4",
                    byteRange = 0L until index.initSegmentLength,
                    activity = ClientActivity.WATCHING_VIDEO,
                )
            }

            get("/hls/{id}/segment/{index}.m4s") {
                if (!call.authorizeBrowserCall()) return@get
                val source = call.resolveHlsSource(call.parameters["id"]) ?: return@get
                val segmentIndex = call.parameters["index"]?.toIntOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorPayload("That video segment is invalid."))
                    return@get
                }
                val index = awaitHlsIndex(
                    itemId = source.item.id,
                    file = source.file,
                    requireFirstSegment = true,
                    requiredSegmentIndex = segmentIndex,
                ) ?: run {
                    call.respond(HttpStatusCode.Accepted, ErrorPayload("Preparing the next HLS segment."))
                    return@get
                }
                val segment = index.segments.getOrNull(segmentIndex) ?: run {
                    val completed = source.job.preparedAsset?.isComplete == true || source.job.status == CompatibilityStatus.READY
                    val status = if (completed) HttpStatusCode.NotFound else HttpStatusCode.Accepted
                    val message = if (completed) "That video segment is no longer available." else "Preparing the next HLS segment."
                    call.respond(status, ErrorPayload(message))
                    return@get
                }
                call.streamFileSlice(
                    file = source.file,
                    mimeType = "video/mp4",
                    byteRange = segment.offset until (segment.offset + segment.length),
                    activity = ClientActivity.WATCHING_VIDEO,
                )
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
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.serveShellPage() {
        if (sessionManager.isBlocked(remoteHost())) {
            respond(HttpStatusCode.Forbidden, ErrorPayload("This device can't access the session."))
            return
        }
        val state = sessionManager.sessionState.value
        if (state.authEnabled && request.path() != "/login" && !sessionManager.validateToken(request.cookies[COOKIE_NAME])) {
            respondRedirect("/login")
            return
        }
        val html = assetLoader.readText("web/index.html")
            .replace("__SESSION_TITLE__", "GhostStream")
            .replace("__SESSION_SUBTITLE__", "Stream & share files offline")
        respondText(html, ContentType.Text.Html)
    }

    private suspend fun io.ktor.server.application.ApplicationCall.authorizeBrowserCall(): Boolean {
        val ipAddress = remoteHost()
        if (sessionManager.isBlocked(ipAddress)) {
            respond(HttpStatusCode.Forbidden, ErrorPayload("This device can't access the session."))
            return false
        }
        val state = sessionManager.sessionState.value
        if (state.authEnabled && !sessionManager.validateToken(request.cookies[COOKIE_NAME])) {
            respond(HttpStatusCode.Unauthorized, ErrorPayload("Enter the session PIN to continue."))
            return false
        }
        sessionManager.observeClient(ipAddress, request.header(HttpHeaders.UserAgent), ClientActivity.BROWSING)
        return true
    }

    private suspend fun compatibilitySnapshotFor(
        item: SharedItem,
        triggerPreparation: Boolean,
    ): CompatibilityJob {
        return if (triggerPreparation && item.playbackDecision.mode != PlaybackMode.DIRECT) {
            compatibilityPipeline.requestPreparation(item)
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
            respond(HttpStatusCode.NotFound, ErrorPayload("This file is no longer available on your device."))
            return
        }
        if (!item.isAvailable) {
            respond(HttpStatusCode.Gone, ErrorPayload("This file is no longer available on your device."))
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
            respond(HttpStatusCode.NotFound, ErrorPayload("This file is no longer available on your device."))
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
    ) {
        val file = File(playbackSource.filePath)
        if (!file.exists()) {
            respond(HttpStatusCode.NotFound, ErrorPayload("Optimized playback is no longer available."))
            return
        }
        if (playbackSource.allowGrowing && !playbackSource.isComplete) {
            streamGrowingCachedFile(
                item = item,
                file = file,
                mimeType = playbackSource.mimeType ?: item.playbackDecision.browserMimeType ?: "application/octet-stream",
                asAttachment = asAttachment,
                activity = activity,
            )
            return
        }
        val totalLength = file.length()
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
                    ErrorPayload("That part of the video is still being prepared."),
                )
                return
            }

            val range = requestedRange.start..minOf(
                requestedRange.endInclusive ?: (availableLength - 1),
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
                        append(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/$availableLength")
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

    private suspend fun io.ktor.server.application.ApplicationCall.streamFileSlice(
        file: File,
        mimeType: String,
        byteRange: LongRange,
        activity: ClientActivity,
    ) {
        val start = byteRange.first
        val endInclusive = byteRange.last
        if (!file.exists() || start < 0L || endInclusive < start || endInclusive >= file.length()) {
            respond(HttpStatusCode.NotFound, ErrorPayload("This part of the video is no longer available."))
            return
        }

        val callerHost = remoteHost()
        val lengthToSend = endInclusive - start + 1
        sessionManager.onTransferStarted(callerHost, activity, isDownload = false)
        try {
            respond(object : OutgoingContent.WriteChannelContent() {
                override val status: HttpStatusCode = HttpStatusCode.OK
                override val contentType: ContentType = ContentType.parse(mimeType)
                override val contentLength: Long = lengthToSend
                override val headers = io.ktor.http.Headers.build {
                    append(HttpHeaders.AcceptRanges, "bytes")
                    append(HttpHeaders.CacheControl, "no-store")
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
            respond(HttpStatusCode.NotFound, ErrorPayload("This file is no longer available on your device."))
            return null
        }
        if (!item.isAvailable) {
            respond(HttpStatusCode.Gone, ErrorPayload("This file is no longer available on your device."))
            return null
        }
        if (item.category != MediaCategory.VIDEO || item.playbackDecision.mode == PlaybackMode.DIRECT) {
            respond(HttpStatusCode.Conflict, ErrorPayload("This video doesn't need HLS playback."))
            return null
        }

        val job = compatibilitySnapshotFor(item, triggerPreparation = true)
        if (job.status == CompatibilityStatus.FAILED) {
            respond(HttpStatusCode.Conflict, ErrorPayload(job.message))
            return null
        }
        val preparedAsset = job.preparedAsset ?: run {
            respond(HttpStatusCode.Accepted, ErrorPayload(job.message))
            return null
        }
        if (!preparedAsset.isFragmentedMp4) {
            respond(HttpStatusCode.Conflict, ErrorPayload("This compatibility stream is not HLS-ready yet."))
            return null
        }
        val file = File(preparedAsset.filePath)
        if (!file.exists()) {
            respond(HttpStatusCode.NotFound, ErrorPayload("Optimized playback is no longer available."))
            return null
        }
        return HlsPlaybackSource(
            item = item,
            job = job,
            file = file,
        )
    }

    private fun awaitHlsIndex(
        itemId: String,
        file: File,
        requireFirstSegment: Boolean,
        requiredSegmentIndex: Int? = null,
    ): FragmentedMp4HlsIndex? {
        var idlePolls = 0
        while (idlePolls < MAX_HLS_INDEX_IDLE_POLLS) {
            val index = FragmentedMp4HlsIndexer.read(file, fragmentDurationSeconds = HLS_SEGMENT_DURATION_SECONDS)
            val hasInitSegment = index?.initSegmentLength?.let { it > 0L } == true
            val hasFirstSegment = !requireFirstSegment || (index?.segments?.isNotEmpty() == true)
            val hasRequiredSegment = requiredSegmentIndex == null || (index?.segments?.getOrNull(requiredSegmentIndex) != null)
            if (hasInitSegment && hasFirstSegment && hasRequiredSegment) {
                return index
            }

            val job = compatibilityPipeline.currentJob(itemId)
            val finalized = job?.preparedAsset?.isComplete == true || job?.status == CompatibilityStatus.READY
            val failed = job?.status == CompatibilityStatus.FAILED
            if (finalized || failed) {
                return index
            }

            idlePolls += 1
            Thread.sleep(HLS_INDEX_POLL_INTERVAL_MS)
        }
        return FragmentedMp4HlsIndexer.read(file, fragmentDurationSeconds = HLS_SEGMENT_DURATION_SECONDS)
    }

    private fun buildHlsPlaylist(
        itemId: String,
        index: FragmentedMp4HlsIndex,
        complete: Boolean,
    ): String {
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:$HLS_TARGET_DURATION_SECONDS")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-PLAYLIST-TYPE:EVENT")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            appendLine("#EXT-X-MAP:URI=\"/hls/$itemId/init.mp4\"")
            index.segments.forEach { segment ->
                appendLine("#EXTINF:${"%.3f".format(java.util.Locale.US, segment.durationSeconds)},")
                appendLine("/hls/$itemId/segment/${segment.index}.m4s")
            }
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
            val failed = job?.status == CompatibilityStatus.FAILED
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
            val failed = job?.status == CompatibilityStatus.FAILED
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
    private data class ErrorPayload(
        val message: String,
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
        val downloadUrl: String,
        val subtitleUrl: String?,
        val compatibilityLabel: String?,
        val compatibilityStatus: CompatibilityStatus? = null,
    ) {
        companion object {
            fun from(
                item: SharedItem,
                compatibilityJob: CompatibilityJob,
                showThumbnails: Boolean,
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
                downloadUrl = "/download/${item.id}",
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
        val downloadUrl: String,
        val subtitleUrl: String?,
        val durationMs: Long?,
        val sizeBytes: Long,
        val compatibility: String?,
        val reason: String,
        val playbackMode: PlaybackMode,
        val compatibilityStatus: CompatibilityStatus? = null,
        val compatibilityMessage: String? = null,
        val compatibilityProgressPercent: Int? = null,
        val compatibilityComplete: Boolean = true,
        val streamReady: Boolean = true,
    ) {
        companion object {
            fun from(item: SharedItem, compatibilityJob: CompatibilityJob): BrowserItemDetails = BrowserItemDetails(
                id = item.id,
                title = item.displayName,
                mimeType = item.mimeType,
                category = item.category.name.lowercase(),
                streamUrl = "/stream/${item.id}",
                hlsUrl = if (item.category == MediaCategory.VIDEO && item.playbackDecision.mode != PlaybackMode.DIRECT) {
                    "/hls/${item.id}/playlist.m3u8"
                } else {
                    null
                },
                downloadUrl = "/download/${item.id}",
                subtitleUrl = item.subtitleMatch?.let { "/subtitle/${item.id}" },
                durationMs = item.durationMs,
                sizeBytes = item.sizeBytes,
                compatibility = item.playbackDecision.compatibilityLabel,
                reason = item.playbackDecision.reason,
                playbackMode = item.playbackDecision.mode,
                compatibilityStatus = compatibilityJob.status.takeIf { item.playbackDecision.mode != PlaybackMode.DIRECT },
                compatibilityMessage = compatibilityJob.message.takeIf { item.playbackDecision.mode != PlaybackMode.DIRECT },
                compatibilityProgressPercent = compatibilityJob.progressPercent,
                compatibilityComplete = compatibilityJob.status == CompatibilityStatus.READY || compatibilityJob.preparedAsset?.isComplete == true,
                streamReady = item.playbackDecision.mode == PlaybackMode.DIRECT || compatibilityJob.canServePlayback,
            )
        }
    }

    @Serializable
    private data class CompatibilityStatusPayload(
        val itemId: String,
        val status: CompatibilityStatus,
        val message: String,
        val progressPercent: Int? = null,
        val ready: Boolean,
        val complete: Boolean,
    ) {
        companion object {
            fun from(job: CompatibilityJob): CompatibilityStatusPayload = CompatibilityStatusPayload(
                itemId = job.itemId,
                status = job.status,
                message = job.message,
                progressPercent = job.progressPercent,
                ready = job.canServePlayback,
                complete = job.status == CompatibilityStatus.READY || job.preparedAsset?.isComplete == true,
            )
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

    private companion object {
        const val COOKIE_NAME = "ghost_session"
        const val GROWING_FILE_POLL_INTERVAL_MS = 300L
        const val MAX_GROWING_FILE_IDLE_POLLS = 300
        const val HLS_INDEX_POLL_INTERVAL_MS = 250L
        const val MAX_HLS_INDEX_IDLE_POLLS = 80
        const val HLS_SEGMENT_DURATION_SECONDS = 2.0
        const val HLS_TARGET_DURATION_SECONDS = 3
        const val STREAMING_BUFFER_SIZE = 64 * 1024
    }
}
