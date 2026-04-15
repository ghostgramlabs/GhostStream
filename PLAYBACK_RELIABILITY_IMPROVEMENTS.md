# GhostStream — Playback Reliability Improvement Plan

> Based on a full audit of the actual source code across `core/media`, `core/network`, and `webassets/src/main/assets/web/`.

---

## Root Cause Analysis

After reading the full codebase, six specific root causes explain why playback is "sometimes unreliable":

### 1. TRANSCODE mode deliberately bypasses HLS (Critical)

In `app.js` there is an explicit comment:

```javascript
// Chrome's MSE rejects those fMP4 segments with bufferAppendError due to
// codec signaling issues in the init segment. Progressive MP4 streaming
// via item.streamUrl works correctly with native byte-range seeking.
// HLS remains the safer path for REMUX, including Apple devices.
if (item.playbackMode === "TRANSCODE") return false;
```

So for TRANSCODE: both REMUX and TRANSCODE produce identical fMP4 via `InAppMuxer`, but only REMUX gets HLS. TRANSCODE falls back to progressive growing-file streaming (`/stream/{id}`). That growing file path is inherently fragile because the browser's video element must guess content length, and range requests for bytes not yet written get a 416 response, causing the player to give up.

The real root cause here is the MSE codec signaling issue in the fMP4 init segment — specifically the `avcC` / `esds` box not having the right codec profile string that Chrome's MSE needs. **This needs to be fixed at the muxer level, not worked around.**

### 2. Growing fMP4 progressive streaming is fragile

`streamGrowingCachedFile` in `KtorGhostStreamServer.kt` serves range requests against a file that's still being written by `Media3FragmentedMp4CompatibilityWorker`. It answers with `Content-Range: bytes x-y/currentLength`, but `currentLength` changes every few hundred milliseconds. Different browsers react differently: Chrome's video element retries with a new range request; Safari may stall; TV browsers often give up entirely on a 416.

### 3. Mobile browsers lose HLS.js entirely

`shouldUseNativeVideoPlayer` returns `true` for any mobile browser (not just Apple), which means Android Chrome on a phone uses a native `<video>` element with direct stream URL — no hls.js error recovery, no MSE, just the fragile progressive stream. This is why "sometimes it works, sometimes it doesn't" — mobile is the most unreliable path.

### 4. HLS.js is configured with defaults, not tuned for growing segments

The current `new window.Hls({})` call passes no configuration. The default settings are not designed for a server that writes segments in real time:
- Default `fragLoadingMaxRetry: 6` but `fragLoadingRetryDelay: 1000ms` → long wait before retrying a segment that hasn't been written yet
- Default `manifestLoadingMaxRetry: 1` → playlist update failures aren't retried enough

### 5. No minimum segment buffer before playlist is served

`/hls/{id}/playlist.m3u8` is served as soon as `requireFirstSegment = true` (one segment is ready). This means the player starts with 1–2 seconds of buffered video. If the Android device is busy, transcoding can lag, and the player runs out of segments and stalls.

### 6. Decision engine doesn't distinguish VP8/VP9 WebM

`DefaultSmartPlaybackDecisionEngine` only allows DIRECT for `video/avc` (H.264). VP9 WebM files, which Chrome can play natively without any transcoding, are classified as TRANSCODE unnecessarily. This wastes resources and creates unnecessary unreliability.

---

## Improvements — Ordered by Impact

---

### Fix 1: Resolve the MSE Codec Signaling Issue for TRANSCODE (Highest Impact)

**Where:** `Media3FragmentedMp4CompatibilityWorker.kt` and `app.js`

The MSE `bufferAppendError` for TRANSCODE content happens because the `avcC` box in the fMP4 init segment uses a codec profile that Chrome's MSE rejects. The fix is to force H.264 **Baseline Profile, Level 3.1** — the most universally accepted profile — and let TRANSCODE also use HLS.

In `Media3FragmentedMp4CompatibilityWorker.kt`, add explicit encoding parameters:

```kotlin
if (item.playbackDecision.mode == PlaybackMode.TRANSCODE) {
    builder
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        // Add this — force Baseline profile for universal MSE compatibility
        .setEncoderFactory(
            DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setProfile(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                        .setLevel(MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                        .build()
                )
                .build()
        )
}
```

Then in `app.js`, once TRANSCODE produces proper Baseline H.264 fMP4, remove the TRANSCODE exclusion and let it use HLS:

```javascript
// BEFORE — blocks TRANSCODE from HLS
function shouldUseManagedHlsPlayback(item) {
  if (item.playbackMode === "TRANSCODE") return false; // ← remove this
  return canUseManagedHls(item);
}

// AFTER
function shouldUseManagedHlsPlayback(item) {
  return canUseManagedHls(item);
}
```

This makes TRANSCODE go through the same HLS path as REMUX, giving it proper error recovery through hls.js.

> **Why this works:** HLS segments are served from a known byte range of the fMP4 file and hls.js validates the init segment separately. If the codec profile is correct, MSE accepts it. The HLS path is significantly more reliable than the progressive growing-file path.

---

### Fix 2: Add a CODECS String to the HLS Playlist

**Where:** `KtorGhostStreamServer.kt` → `buildHlsPlaylist()`

HLS.js and native HLS players use the `CODECS` attribute in the playlist to pre-validate codec support before fetching any segment. Without it, the player blindly tries to load the init segment, discovers an incompatible codec, and errors out.

Find the `buildHlsPlaylist` function and add the codec string:

```kotlin
private fun buildHlsPlaylist(itemId: String, index: FragmentedMp4HlsIndex, complete: Boolean): String {
    val sb = StringBuilder()
    sb.appendLine("#EXTM3U")
    sb.appendLine("#EXT-X-VERSION:7")  // Upgrade from version 3 to 7 — enables fMP4 segments
    // Add codec hint: avc1.42E01E = H.264 Baseline L3.0, mp4a.40.2 = AAC-LC
    sb.appendLine("#EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS=\"avc1.42E01E,mp4a.40.2\"")
    sb.appendLine("#EXT-X-TARGETDURATION:${index.segments.maxOfOrNull { it.durationSeconds.toLong() + 1 } ?: 3}")
    sb.appendLine("#EXT-X-MAP:URI=\"/hls/$itemId/init.mp4\"")
    // ... rest of segments
    if (complete) sb.appendLine("#EXT-X-ENDLIST")
    return sb.toString()
}
```

Also serve the playlist with `Content-Type: application/vnd.apple.mpegurl` for Apple and `application/x-mpegURL` for others. Currently the server always sends `application/vnd.apple.mpegurl`, which some non-Apple devices reject.

```kotlin
val contentTypeStr = if (call.isAppleDevice()) {
    "application/vnd.apple.mpegurl"
} else {
    "application/x-mpegURL"
}
call.respondText(text = buildHlsPlaylist(...), contentType = ContentType.parse(contentTypeStr))
```

---

### Fix 3: Tune HLS.js Configuration for Growing Segments

**Where:** `app.js` → the `new window.Hls({...})` call (around line 870)

Current code passes no configuration. Replace with:

```javascript
const hls = new window.Hls({
  enableWorker: true,
  lowLatencyMode: false,

  // Retry settings — essential for growing segment files
  fragLoadingMaxRetry: 8,
  fragLoadingRetryDelay: 500,        // Retry faster (default is 1000ms)
  fragLoadingMaxRetryTimeout: 5000,
  manifestLoadingMaxRetry: 6,
  manifestLoadingRetryDelay: 500,
  levelLoadingMaxRetry: 6,
  levelLoadingRetryDelay: 500,

  // Buffer tuning
  maxBufferLength: 30,               // 30 seconds of buffer target
  maxMaxBufferLength: 90,            // Allow up to 90s when network is fast
  backBufferLength: 20,              // Keep 20s behind for seeking

  // Don't abandon segments that are still being written
  fragLoadingTimeOut: 20000,         // 20s timeout (default 20s — keep this)
  manifestLoadingTimeOut: 10000,

  // Prevents the player from switching "quality levels" (we only have one)
  startLevel: 0,
  autoLevelEnabled: false,

  // fMP4 segments — tell hls.js to expect them
  // (hls.js auto-detects this from EXT-X-MAP, but being explicit helps)
  progressive: false,
});
```

---

### Fix 4: Enable HLS.js on Mobile Browsers (Not Just Desktop)

**Where:** `app.js` → `shouldUseNativeVideoPlayer()`

Currently mobile browsers bypass hls.js entirely. This is the wrong tradeoff — hls.js is much better at handling growing segment files than a native `<video>` element.

```javascript
// BEFORE
function shouldUseNativeVideoPlayer(item) {
  return isMobileBrowser() || shouldUseNativeHlsPlayback(item);
}

// AFTER — only Apple iOS gets native HLS (Safari requires it)
function shouldUseNativeVideoPlayer(item) {
  return shouldUseNativeHlsPlayback(item); // only Safari/iOS native HLS
}
```

This means Android Chrome on phones, Samsung Internet, Chrome on tablets, and desktop browsers all go through hls.js, which has proper error recovery and retry logic.

The native `<video>` path should only be used for:
1. Apple iOS/iPadOS Safari (which requires native HLS)
2. DIRECT mode files (no HLS needed at all)

---

### Fix 5: Require a Minimum Segment Buffer Before Serving Playlist

**Where:** `KtorGhostStreamServer.kt` → `/hls/{id}/playlist.m3u8` handler

Currently the playlist is served when just 1 segment is ready. If the transcoder is slow, the player immediately starves. Require at least 2 segments (4 seconds at 2s fragments) before the first playlist is issued:

```kotlin
get("/hls/{id}/playlist.m3u8") {
    if (!call.authorizeBrowserCall()) return@get
    val source = call.resolveHlsSource(call.parameters["id"]) ?: return@get
    
    // Require at least 2 segments before starting playback
    // This ensures the player won't immediately stall on slow devices
    val MIN_SEGMENTS_BEFORE_START = 2
    val index = awaitHlsIndex(
        itemId = source.item.id,
        file = source.file,
        requireFirstSegment = true,
        requiredSegmentIndex = MIN_SEGMENTS_BEFORE_START - 1, // 0-indexed
    ) ?: run {
        call.respond(HttpStatusCode.Accepted, ErrorPayload(...))
        return@get
    }
    // ... rest of handler
}
```

On the client side, keep polling `/api/compat/{id}` and only load the HLS URL once `progressPercent >= 15` to give the transcoder a running start:

```javascript
// In pollCompat callback, before loading HLS source:
if (status.progressPercent != null && status.progressPercent < 12) {
  // Not enough buffer yet — keep showing the "opening" screen
  scheduleCompatPoll(id, item, delay);
  return;
}
// Now safe to start player
```

---

### Fix 6: Fix the Growing File Range Request Reliability

**Where:** `KtorGhostStreamServer.kt` → `streamGrowingCachedFile()`

This is the fallback path for TRANSCODE (and applies while fMP4 is growing). The current approach sends `Content-Range: bytes x-y/currentLength`, but `currentLength` changes between requests. Some clients (TV browsers, older Androids) get confused.

The more reliable pattern for a growing file is to NOT specify a final content length, and stream from the requested offset to whatever is currently available:

```kotlin
// Instead of lying about the total size, respond with open-ended range
// Content-Range: bytes 0-16383/*  (the * means "unknown total size")
append(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/*")
```

And for clients that request without a Range header (they want the whole file), respond with chunked transfer encoding:

```kotlin
// No Content-Length header — use chunked transfer
// Ktor does this automatically when you don't set contentLength
respond(object : OutgoingContent.WriteChannelContent() {
    override val contentType = ContentType.parse(mimeType)
    // Do NOT set override val contentLength — let Ktor use chunked
    override suspend fun writeTo(channel: ByteWriteChannel) {
        // Stream bytes as they become available, periodically checking
        // if the file has grown, until isComplete == true
    }
})
```

> **Long-term:** Once Fix 1 is in place, TRANSCODE never hits this path anymore. Fix 6 only matters as a safety net for edge cases.

---

### Fix 7: VP9/WebM Native Pass-Through in Decision Engine

**Where:** `DefaultSmartPlaybackDecisionEngine.kt` and `AndroidMediaAnalyzer.kt`

VP9+Opus or VP9+Vorbis in WebM containers can be played natively by Chrome, Edge, and Firefox without any transcoding. Currently they get classified as TRANSCODE.

In `AndroidMediaAnalyzer.kt`, extend the browser compatibility check:

```kotlin
// Current check — too narrow
val browserVideoCompatible = trackInspection.videoTrackMimeType == null || 
    trackInspection.videoTrackMimeType == "video/avc"

// Extended check — add VP8 and VP9
val browserVideoCompatible = trackInspection.videoTrackMimeType == null || 
    trackInspection.videoTrackMimeType == "video/avc" ||      // H.264
    trackInspection.videoTrackMimeType == "video/x-vnd.on2.vp8" || // VP8
    trackInspection.videoTrackMimeType == "video/x-vnd.on2.vp9"    // VP9

val browserAudioCompatible = trackInspection.audioTrackMimeType == null ||
    trackInspection.audioTrackMimeType == "audio/mp4a-latm" ||  // AAC
    trackInspection.audioTrackMimeType == "audio/mpeg" ||        // MP3
    trackInspection.audioTrackMimeType == "audio/vorbis" ||      // Vorbis (WebM)
    trackInspection.audioTrackMimeType == "audio/opus"           // Opus (WebM)
```

And in `DefaultSmartPlaybackDecisionEngine.kt`, when the container is WEBM with VP8/VP9:

```kotlin
inspection.container == MediaContainer.WEBM && browserVideoCompatible && browserAudioCompatible -> PlaybackDecision(
    mode = PlaybackMode.DIRECT,
    browserMimeType = "video/webm",
    reason = "WebM is natively supported by most browsers",
)
```

This eliminates unnecessary transcoding for a large class of files.

---

### Fix 8: Client Capability Probe API (Forward-Looking)

**Where:** New endpoint in `KtorGhostStreamServer.kt` + new JS call in `app.js`

The server currently makes codec decisions at analysis time using only User-Agent. A more reliable approach is for the browser to report what it can actually play before the stream is resolved.

Add a new endpoint:
```
POST /api/caps
Body: { "canPlayH264": true, "canPlayVP9": true, "canPlayHEVC": false, "supportsHLS": true, "supportsMSE": true }
```

In `app.js`, call this right after the bootstrap:

```javascript
async function reportClientCapabilities() {
  const caps = {
    canPlayH264: Boolean(
      document.createElement("video").canPlayType('video/mp4; codecs="avc1.42E01E"')
    ),
    canPlayVP9: Boolean(
      document.createElement("video").canPlayType('video/webm; codecs="vp9"')
    ),
    canPlayHEVC: Boolean(
      window.MediaSource?.isTypeSupported?.('video/mp4; codecs="hvc1"')
    ),
    supportsHLS: Boolean(
      document.createElement("video").canPlayType("application/vnd.apple.mpegurl")
    ),
    supportsMSE: Boolean(window.MediaSource),
    userAgent: navigator.userAgent,
  };
  await fetch("/api/caps", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(caps),
  }).catch(() => {}); // Fire-and-forget, don't block playback
}
```

The server can then store these per-IP and use them to fine-tune decisions — for instance, serving HEVC directly to Safari 13+ macOS devices that support it natively.

---

## Implementation Priority

| Fix | Effort | Impact | Do First? |
|-----|--------|--------|-----------|
| Fix 1 — H.264 Baseline + TRANSCODE uses HLS | Medium | Critical | ✅ Yes |
| Fix 2 — CODECS string in HLS playlist | Low | High | ✅ Yes |
| Fix 4 — Enable hls.js on mobile | Low | High | ✅ Yes |
| Fix 3 — Tune hls.js config | Low | Medium | ✅ Yes |
| Fix 5 — Minimum segment buffer | Low | Medium | ✅ Yes |
| Fix 7 — VP9/WebM direct pass-through | Medium | Medium | Soon |
| Fix 6 — Growing file range requests | Medium | Low (after Fix 1) | Later |
| Fix 8 — Client capability API | High | Medium (future-proofing) | Later |

---

## Summary of the Core Problem in One Sentence

TRANSCODE mode was blocked from HLS due to a codec signaling bug in the fMP4 init segment, so it falls through to a fragile growing-file progressive stream — fixing the codec profile (Fix 1) + adding the CODECS hint (Fix 2) + enabling hls.js on mobile (Fix 4) will fix the vast majority of reported playback failures across all device types.
