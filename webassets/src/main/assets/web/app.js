const app = document.getElementById("app");
const sessionTitle = app.dataset.title || "DirectServe";
const sessionSubtitle = app.dataset.subtitle || "Local-only streaming";

const state = {
  bootstrap: null,
  debugTracing: false,
  query: "",
  selected: new Set(),
  selectMode: false,
  libraryItems: [],
  nowPlaying: null,
  plyr: null,
  plyrItemId: null,
  hls: null,
  hlsItemId: null,
  uppy: null,
  musicPlayers: [],
  pendingUploadFiles: [],
  searchTimer: null,
  compatPollToken: 0,
  compatPollTimer: null,
  compatMountToken: 0,
  compatPlaybackFailures: {},
  compatProgressMemory: {},
  compatItem: null,
  playerSourceLocks: {},
  lastReportedCapabilities: null,
  ratioLocked: {}, // Track locked ratios per itemId to prevent placeholder overwrite
};

const routes = {
  "/": renderHome,
  "/login": () => renderLogin(),
  "/videos": () => renderLibrary("videos", "Videos"),
  "/photos": () => renderLibrary("photos", "Photos"),
  "/music": () => renderLibrary("music", "Music"),
  "/files": () => renderLibrary("files", "Files"),
  "/upload": renderUpload,
};

function gsStr(key, defaultVal, ...args) {
  let s = state.bootstrap?.strings?.[key] || defaultVal;
  if (!s) return "";
  args.forEach((arg, i) => {
    s = s.replace(`%${i + 1}$d`, arg).replace(`%${i + 1}$s`, arg);
  });
  return s;
}

window.addEventListener("popstate", () => boot());
document.addEventListener("click", (event) => {
  const link = event.target.closest("[data-link]");
  if (!link) return;
  event.preventDefault();
  navigate(link.getAttribute("href"));
});

document.addEventListener("error", (event) => {
  const target = event.target;
  if (target instanceof HTMLImageElement && target.classList.contains("gs-card-img")) {
    debugTrace("thumbnail_error", `src=${target.currentSrc || target.src} route=${location.pathname}`);
  }
}, true);

// Drag and Drop Logic
let dragCounter = 0;
window.addEventListener("dragenter", (e) => {
  e.preventDefault();
  dragCounter++;
  if (dragCounter === 1) {
    document.body.classList.add("gs-dragging");
  }
});
window.addEventListener("dragover", (e) => {
  e.preventDefault();
});
window.addEventListener("dragleave", (e) => {
  e.preventDefault();
  dragCounter--;
  if (dragCounter === 0) {
    document.body.classList.remove("gs-dragging");
  }
});
window.addEventListener("drop", (e) => {
  e.preventDefault();
  dragCounter = 0;
  document.body.classList.remove("gs-dragging");
  const files = Array.from(e.dataTransfer.files || []);
  if (files && files.length > 0) {
    queueUploadFiles(files);
  }
});

let currentUploadXhr = null;

async function handleFilesUpload(files, hooks = {}) {
  if (!files || files.length === 0) return;
  const overlay = document.getElementById("uploadOverlay");
  const title = document.getElementById("uploadTitle");
  const progress = document.getElementById("uploadProgress");
  const status = document.getElementById("uploadStatus");
  const cancelBtn = document.getElementById("cancelUploadBtn");

  const showOverlay = (show) => overlay?.classList.toggle("is-visible", show);
  const setProgress = (percent) => {
    if (progress) progress.style.width = `${percent}%`;
  };

  const totalSize = files.reduce((acc, f) => acc + f.size, 0);
  const fileCount = files.length;
  const mainFileName = fileCount === 1 ? files[0].name : `${fileCount} files`;

  showOverlay(true);
  title.textContent = gsStr("web_upload_requesting", "Requesting permission");
  status.innerHTML = `<span class="gs-upload-waiting-dots">${gsStr("web_upload_waiting", "Waiting for DirectServe")}</span>`;
  setProgress(0);

  let requestId = null;

  cancelBtn.onclick = async () => {
    if (currentUploadXhr) {
      currentUploadXhr.abort();
      currentUploadXhr = null;
    }
    if (requestId) {
      try {
        await api("/api/upload/cancel/" + requestId, { method: "POST" });
      } catch (e) {}
    }
    showOverlay(false);
  };

  try {
    hooks.onStart?.({ files, fileCount, totalSize });
    const response = await api("/api/upload/request", {
      method: "POST",
      body: JSON.stringify({
        fileName: mainFileName,
        fileCount: fileCount,
        sizeBytes: totalSize,
      }),
    });

    requestId = response.requestId;

    if (!response.accepted) {
      throw new Error("Upload transfer was denied by the device owner.");
    }

    title.textContent = fileCount === 1 ? gsStr("web_upload_selection_single", `Sending ${files[0].name}`, files[0].name) : gsStr("web_upload_selection_multiple", `Sending ${fileCount} files`, fileCount);
    status.textContent = gsStr("web_status_init_progress", "0%");
    
    currentUploadXhr = new XMLHttpRequest();
    currentUploadXhr.open("POST", "/api/upload/execute/" + requestId);
    
    currentUploadXhr.upload.onprogress = (e) => {
      if (e.lengthComputable) {
        const percent = Math.round((e.loaded / e.total) * 100);
        setProgress(percent);
        status.textContent = `${percent}% (${fmtBytes(e.loaded)} / ${fmtBytes(e.total)})`;
        hooks.onProgress?.({
          files,
          loaded: e.loaded,
          total: e.total,
          percent,
        });
      }
    };

    const uploadPromise = new Promise((resolve, reject) => {
      currentUploadXhr.onload = () => {
        if (currentUploadXhr.status >= 200 && currentUploadXhr.status < 300) {
          resolve();
        } else {
          reject(new Error("Upload failed (" + currentUploadXhr.status + ")"));
        }
      };
      currentUploadXhr.onerror = () => reject(new Error("Network error - please check your connection."));
      currentUploadXhr.onabort = () => reject(new Error("Transfer cancelled."));
    });

    const formData = new FormData();
    files.forEach((file) => {
      formData.append("files", file);
    });
    currentUploadXhr.send(formData);

    await uploadPromise;
    
    title.textContent = gsStr("web_upload_success_title", "Success!");
    status.textContent = fileCount === 1 ? gsStr("web_upload_success_detail_single", "File is ready on DirectServe.") : gsStr("web_upload_success_detail_multiple", "Files are ready on DirectServe.");
    setProgress(100);
    hooks.onSuccess?.({ files, fileCount, totalSize });
    
    setTimeout(() => {
      showOverlay(false);
      const path = location.pathname;
      if (path === "/" || path === "/videos" || path === "/photos" || path === "/music" || path === "/files") {
        boot();
      }
    }, 1500);

  } catch (error) {
    title.textContent = gsStr("web_upload_failed_title", "Transfer failed");
    status.textContent = error.message || gsStr("web_upload_failed_detail", "Upload request was denied or failed.");
    hooks.onError?.(error);
    setTimeout(() => showOverlay(false), 3000);
  } finally {
    currentUploadXhr = null;
    hooks.onSettled?.();
  }
}

async function boot() {
  cancelCompatPolling();
  destroyUppy();
  destroyPlyr();
  destroyHls();
  destroyMusicPlayers();
  const path = location.pathname;

  try {
    state.bootstrap = await api("/api/bootstrap");
    applyBootstrapUi();
    await reportClientCapabilities();
    debugTrace("bootstrap_loaded", `route=${path} auth=${state.bootstrap?.authEnabled} theme=${state.bootstrap?.themeMode}`);
    if (path === "/login") {
      renderLogin();
      return;
    }
    if (path.startsWith("/player/video/")) {
      renderVideoPlayer(path.split("/").pop());
      return;
    }
    if (path.startsWith("/photo/")) {
      renderPhotoViewer(path.split("/").pop());
      return;
    }
    (routes[path] || renderHome)();
  } catch (error) {
    if (error.status === 401) {
      navigate("/login", true);
      return;
    }
    renderError(error.message || "Unable to load DirectServe.");
  }
}

function applyBootstrapUi() {
  const bootstrap = state.bootstrap;
  state.debugTracing = Boolean(bootstrap?.debugTracing);
  const themeMode = bootstrap?.themeMode || "SYSTEM";
  document.documentElement.style.colorScheme =
    themeMode === "DARK" ? "dark" : themeMode === "LIGHT" ? "light" : "";
  document.body.classList.toggle("gs-theme-dark", themeMode === "DARK");
  document.body.classList.toggle("gs-theme-light", themeMode === "LIGHT");
  document.body.classList.toggle("gs-large-cards", Boolean(bootstrap?.largeCards));
  document.body.classList.toggle("gs-prominent-downloads", Boolean(bootstrap?.prominentDownloadButton));
}

function debugTrace(event, details = "") {
  if (!state.debugTracing) return;
  fetch("/api/debug/browser", {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
    },
    keepalive: true,
    body: JSON.stringify({
      event,
      route: location.pathname,
      details,
    }),
  }).catch(() => {});
}

function navigate(path, replace = false) {
  cancelCompatPolling();
  history[replace ? "replaceState" : "pushState"]({}, "", path);
  boot();
}

function isMobileBrowser() {
  const ua = navigator.userAgent || "";
  const mobileUa = /Android|iPhone|iPad|iPod|Mobile|Windows Phone|Opera Mini/i.test(ua);
  const coarsePointer = window.matchMedia?.("(pointer: coarse)")?.matches;
  const narrowViewport = window.innerWidth <= 900;
  return Boolean(mobileUa || (coarsePointer && narrowViewport));
}

function isMediaPath(path) {
  return path === "/" || path === "/videos" || path === "/photos" || path === "/music";
}

// ---------------------------------------------------------------------------
// Device / capability detection
// ---------------------------------------------------------------------------

/**
 * Returns true for Apple handheld devices and iPad in desktop mode.
 * Used as the legacy name; prefer isAppleDevice() for broader Apple detection.
 */
function isAppleMobileReceiver() {
  const ua = navigator.userAgent || "";
  const appleHandheld = /iPhone|iPad|iPod/i.test(ua);
  const iPadDesktopMode = navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1;
  return Boolean(appleHandheld || iPadDesktopMode);
}

/**
 * Returns true for any Apple device that uses Safari's native HLS engine:
 * iPhone, iPad, iPod (iOS/iPadOS), macOS Safari, and Apple TV.
 */
function isAppleDevice() {
  const ua = navigator.userAgent || "";
  const appleHandheld = /iPhone|iPad|iPod/i.test(ua);
  const iPadDesktopMode = navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1;
  // macOS Safari: has "Macintosh" + "Safari" but NOT "Chrome" or "Firefox"
  const macSafari = /Macintosh/i.test(ua) && /Safari/i.test(ua) && !/Chrome/i.test(ua) && !/Firefox/i.test(ua);
  const appleTV = /AppleTV/i.test(ua);
  return Boolean(appleHandheld || iPadDesktopMode || macSafari || appleTV);
}

/**
 * Returns true for Smart TV browsers that have a native HLS player but limited
 * JavaScript capabilities (no hls.js Web Workers, limited MSE support, etc.).
 */
function isTvBrowser() {
  const ua = navigator.userAgent || "";
  // Samsung Tizen, LG webOS, HbbTV (European broadcast TVs), Roku, Amazon Fire TV,
  // Android TV, Chromecast, and generic "SmartTV" / "SMART-TV" identifiers.
  return /Tizen|webOS|Web0S|HbbTV|SmartTV|SMART-TV|NetCast|DLNA|Roku|AFTB|AFTS|AFTN|AFTT|FireTV|CrKey|OPR\/.*TV|ANT_/i.test(ua)
    || (/\bTV\b/i.test(ua) && !/iPhone|iPad|AppleTV/i.test(ua));
}

function detectBrowserFamily() {
  const ua = navigator.userAgent || "";
  if (/Edg\//i.test(ua)) return "Edge";
  if (/Chrome|Chromium|CriOS/i.test(ua)) return "Chrome";
  if (/Safari/i.test(ua) && !/Chrome|Chromium|CriOS|Edg\//i.test(ua)) return "Safari";
  if (/Firefox|FxiOS/i.test(ua)) return "Firefox";
  return "Unknown";
}

function detectBrowserOs() {
  const ua = navigator.userAgent || "";
  if (/iPhone|iPad|iPod/i.test(ua)) return "iOS";
  if (/Macintosh/i.test(ua)) return "macOS";
  if (/Android/i.test(ua)) return "Android";
  if (/Windows/i.test(ua)) return "Windows";
  if (/Linux/i.test(ua)) return "Linux";
  return "Unknown";
}

function isDesktopChromiumBrowser() {
  const ua = navigator.userAgent || "";
  const chromiumFamily = /Chrome|Chromium|Edg\//i.test(ua);
  const mobileFamily = /Android|iPhone|iPad|iPod|Mobile/i.test(ua);
  return Boolean(chromiumFamily && !mobileFamily && !isAppleDevice() && !isTvBrowser());
}

function canPlayMimeType(video, mimeType) {
  if (!video || !mimeType || typeof video.canPlayType !== "function") return false;
  try {
    return Boolean(video.canPlayType(mimeType).replace(/no/i, ""));
  } catch (_) {
    return false;
  }
}

function buildClientCapabilities() {
  const video = document.createElement("video");
  const mediaSource = window.MediaSource || window.ManagedMediaSource;
  const supportsMse = Boolean(mediaSource && typeof mediaSource.isTypeSupported === "function");
  const supportsHlsNatively = canPlayMimeType(video, "application/vnd.apple.mpegurl") ||
    canPlayMimeType(video, "application/x-mpegURL");
  const supportsHevc = canPlayMimeType(video, 'video/mp4; codecs="hvc1.1.6.L120.B0"') ||
    canPlayMimeType(video, 'video/mp4; codecs="hev1.1.6.L120.B0"');
  const supportsVp9 = canPlayMimeType(video, 'video/webm; codecs="vp9"') ||
    canPlayMimeType(video, 'video/mp4; codecs="vp09.00.40.08"');
  const supportsAv1 = canPlayMimeType(video, 'video/mp4; codecs="av01.0.04M.08"');
  const supportsAac = canPlayMimeType(video, 'audio/mp4; codecs="mp4a.40.2"');
  const supportsMp3 = canPlayMimeType(video, "audio/mpeg");
  const supportsOpus = canPlayMimeType(video, 'audio/webm; codecs="opus"') ||
    canPlayMimeType(video, 'audio/ogg; codecs="opus"');
  const supportsAc3 = canPlayMimeType(video, 'audio/mp4; codecs="ac-3"');
  const supportsEac3 = canPlayMimeType(video, 'audio/mp4; codecs="ec-3"');
  return {
    browserFamily: detectBrowserFamily(),
    os: detectBrowserOs(),
    supportsAvc: true,
    supportsHevc,
    supportsVp9,
    supportsAv1,
    supportsAac,
    supportsMp3,
    supportsOpus,
    supportsAc3,
    supportsEac3,
    supportsMse,
    supportsHlsNatively,
    supportsHdr: supportsHevc && isAppleDevice(),
    isPowerEfficient: !isMobileBrowser(),
  };
}

async function reportClientCapabilities() {
  const capabilities = buildClientCapabilities();
  const signature = JSON.stringify(capabilities);
  if (state.lastReportedCapabilities === signature) return capabilities;
  try {
    await api("/api/telemetry/capabilities", {
      method: "POST",
      body: JSON.stringify(capabilities),
    });
    state.lastReportedCapabilities = signature;
  } catch (_) {}
  return capabilities;
}

/**
 * Returns true when the prepared compat MP4 is ready for direct <video src> playback.
 * This is the primary path for REMUX/TRANSCODE videos once the job reaches READY.
 * No hls.js and no MSE are involved — the browser plays the file natively, bypassing
 * the TFHD base-data-offset restriction that caused bufferAppendError via MSE/hls.js.
 */
function shouldUseDirectCompatMp4(item) {
  // The server only exposes preparedMp4Url when it considers the file directly playable
  // for this session. That may now happen before the background finalize/reuse step reaches
  // compatibilityComplete=true, so we must not block on the finalization flag here.
  return Boolean(
    item.preparedMp4Url && 
    item.playbackMode !== "DIRECT"
  );
}

function rememberCompatProgress(itemId, status, progressPercent) {
  if (!itemId) return;
  if (Number.isFinite(progressPercent)) {
    state.compatProgressMemory[itemId] = Math.max(0, Math.min(100, progressPercent));
    return;
  }
  if (status === "READY") {
    state.compatProgressMemory[itemId] = 100;
    return;
  }
  if (isPreparationActiveStatus(status) && state.compatProgressMemory[itemId] == null) {
    state.compatProgressMemory[itemId] = 0;
    return;
  }
  if (status === "FAILED" || status === "STALLED" || status === "IDLE") {
    delete state.compatProgressMemory[itemId];
  }
}

function compatProgressValue(item) {
  const explicit = item.compatibilityProgressPercent;
  if (Number.isFinite(explicit)) return Math.max(0, Math.min(100, explicit));
  if (item.id && Number.isFinite(state.compatProgressMemory[item.id])) {
    return state.compatProgressMemory[item.id];
  }
  return 0;
}

function shouldRenderDetailProgress(item) {
  const status = item.compatibilityStatus || item.status;
  if (!isPreparationActiveStatus(status)) return false;
  return item.playbackMode === "TRANSCODE" || item.playbackMode === "TRANSMUX" || item.playbackMode === "REMUX";
}

function compatibilityProgressLabel(item) {
  if (item.playbackMode === "TRANSCODE") {
    return gsStr("web_player_progress_converting", "Converting for this device");
  }
  return gsStr("web_player_progress_preparing", "Preparing video");
}

function renderCompatibilityProgress(item) {
  if (!shouldRenderDetailProgress(item)) {
    debugTrace(
      "ui_progress_hidden",
      `mode=${item.playbackMode} status=${item.compatibilityStatus || item.status || "IDLE"} reason=inactive`,
    );
    return `<p class="gs-meta" data-compat-status>${compatibilityStatusText(item, false)}</p>`;
  }
  const progress = compatProgressValue(item);
  debugTrace(
    "ui_progress_render",
    `mode=${item.playbackMode} status=${item.compatibilityStatus || item.status} progress=${progress}`,
  );
  return `
    <div class="gs-compat-progress" data-compat-progress-block>
      <div class="gs-compat-progress-head">
        <span class="gs-meta" data-compat-progress-label>${compatibilityProgressLabel(item)}</span>
        <strong data-compat-progress-value>${progress}%</strong>
      </div>
      <div class="gs-compat-progress-track" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${progress}">
        <span class="gs-compat-progress-fill" data-compat-progress-fill style="width:${progress}%"></span>
      </div>
    </div>
  `;
}

function resolveStablePlayerSource(item) {
  if (item.playbackMode === "DIRECT") {
    return {
      kind: "direct",
      url: item.streamUrl,
      mimeType: item.mimeType || "video/mp4",
    };
  }
  if (shouldUseDirectCompatMp4(item)) {
    return {
      kind: "prepared_mp4",
      url: item.preparedMp4Url,
      mimeType: "video/mp4",
    };
  }
  if (shouldUseNativeHlsPlayback(item)) {
    return {
      kind: "native_hls",
      url: item.hlsUrl,
      mimeType: "application/vnd.apple.mpegurl",
    };
  }
  if (shouldUseManagedHlsPlayback(item)) {
    return {
      kind: "managed_hls",
      url: item.hlsUrl,
      mimeType: "application/x-mpegURL",
    };
  }
  return null;
}

function lockPlayerSource(item) {
  const candidate = resolveStablePlayerSource(item);
  const existing = state.playerSourceLocks[item.id];
  if (!candidate) {
    if (existing) {
      debugTrace("player_rehydrate_blocked", `id=${item.id} reason=waiting_for_stable_source locked=${existing.kind}`);
    }
    return null;
  }
  if (existing && existing.kind === candidate.kind && existing.url === candidate.url) {
    debugTrace("player_rehydrate_blocked", `id=${item.id} reason=source_already_locked source=${existing.kind}`);
    return existing;
  }
  if (
    existing &&
    existing.kind === "direct" &&
    (candidate.kind === "prepared_mp4" || candidate.kind === "managed_hls" || candidate.kind === "native_hls")
  ) {
    debugTrace("player_source_upgraded", `id=${item.id} from=${existing.kind} to=${candidate.kind}`);
    state.playerSourceLocks[item.id] = candidate;
    return candidate;
  }
  if (existing && (existing.kind !== candidate.kind || existing.url !== candidate.url)) {
    debugTrace(
      "player_rehydrate_blocked",
      `id=${item.id} reason=source_locked source=${existing.kind} requested=${candidate.kind}`,
    );
    return existing;
  }
  debugTrace("playback_source_selected", `id=${item.id} mode=${item.playbackMode} source=${candidate.kind}`);
  state.playerSourceLocks[item.id] = candidate;
  debugTrace("player_source_locked", `id=${item.id} source=${candidate.kind}`);
  return candidate;
}

function playbackContextSummary(item, selectedSource = null) {
  return [
    `container=${item.container || item.mimeType || "unknown"}`,
    `video=${item.videoCodec || "unknown"}`,
    `audio=${item.audioCodec || "unknown"}`,
    `plan=${item.playbackMode || "unknown"}`,
    `status=${item.compatibilityStatus || "none"}`,
    `source=${selectedSource?.kind || "unknown"}`,
  ].join(" ");
}

function nativeHlsEligibility(item) {
  if (!item) return { allowed: false, reason: "missing_item" };
  if (shouldUseDirectCompatMp4(item) || item.compatibilityComplete) {
    return { allowed: false, reason: "prepared_mp4_preferred" };
  }
  if (!item.hlsUrl) return { allowed: false, reason: "no_hls_url" };
  if (!item.streamReady) return { allowed: false, reason: "not_streamable" };
  if (!isAppleDevice() && !isTvBrowser()) return { allowed: false, reason: "native_hls_not_required" };
  if (!buildClientCapabilities().supportsHlsNatively) return { allowed: false, reason: "native_hls_not_supported" };
  return { allowed: true, reason: isTvBrowser() ? "native_tv_hls" : "native_apple_hls" };
}

function shouldUseNativeHlsPlayback(item) {
  return nativeHlsEligibility(item).allowed;
}

function managedHlsEligibility(item) {
  if (!item) return { allowed: false, reason: "missing_item" };
  if (shouldUseDirectCompatMp4(item) || item.compatibilityComplete) {
    return { allowed: false, reason: "prepared_mp4_preferred" };
  }
  if (!item.hlsUrl) return { allowed: false, reason: "no_hls_url" };
  if (!item.streamReady) return { allowed: false, reason: "not_streamable" };
  if (isAppleDevice()) return { allowed: false, reason: "apple_disabled" };
  if (isTvBrowser()) return { allowed: false, reason: "tv_disabled" };
  if (!isDesktopChromiumBrowser()) return { allowed: false, reason: "browser_not_supported" };
  if (!(window.Hls && typeof window.Hls.isSupported === "function" && window.Hls.isSupported())) {
    return { allowed: false, reason: "hlsjs_not_supported" };
  }
  return { allowed: true, reason: "desktop_chromium" };
}

/**
 * Returns true when hls.js should manage HLS playback (non-Apple, non-TV).
 * Only used while the compat job is still in progress (preparedMp4Url is null).
 * Once READY, shouldUseDirectCompatMp4 takes over and hls.js is not started.
 */
function canUseManagedHls(item) {
  return managedHlsEligibility(item).allowed;
}

function shouldUseManagedHlsPlayback(item) {
  return canUseManagedHls(item);
}

/**
 * Returns true when the native <video> element controls should be used (no Plyr).
 * This is only true for Apple devices and TVs that use native HLS —
 * their video players don't need or benefit from the Plyr overlay.
 * Android Chrome, desktop browsers etc. all get Plyr + hls.js.
 */
function shouldUseNativeVideoPlayer(item) {
  const locked = state.playerSourceLocks[item?.id];
  return locked?.kind === "native_hls" || shouldUseNativeHlsPlayback(item);
}

function shouldUseHlsPlayback(item) {
  return shouldUseNativeHlsPlayback(item) || shouldUseManagedHlsPlayback(item);
}

function shouldUseHlsForActiveSession(item) {
  const locked = state.playerSourceLocks[item?.id];
  return locked?.kind === "managed_hls" || locked?.kind === "native_hls" || shouldUseHlsPlayback(item);
}

/**
 * Returns the URL to probe when checking if a stream is ready.
 * For direct compat MP4 (READY), probe the compat-mp4 URL with a Range header.
 * For HLS paths (native or managed), probe the HLS playlist URL (no Range).
 * For direct/progressive, probe the stream URL with a Range header.
 */
function resolveVideoSourceUrl(item) {
  const source = item.selectedSourceType
    ? { kind: item.selectedSourceType, url: item.selectedSourceUrl }
    : (state.playerSourceLocks[item.id] || resolveStablePlayerSource(item));
  return source?.url || item.streamUrl;
}

async function probeCompatiblePlaybackSource(item) {
  if (item.playbackMode === "DIRECT") return true;
  // Direct compat MP4: always available when preparedMp4Url is set (server only exposes
  // it when status=READY), so skip the probe round-trip.
  if (shouldUseDirectCompatMp4(item)) return true;
  const sourceUrl = resolveVideoSourceUrl(item);
  if (!sourceUrl) return false;

  const controller = typeof AbortController === "function" ? new AbortController() : null;
  const timeoutId = controller ? setTimeout(() => controller.abort(), 2200) : null;
  // HLS playlist URLs (native or managed) must NOT use a Range header — they return
  // the full playlist text. Only progressive MP4 streams support byte-range requests.
  const selectedSourceKind = item.selectedSourceType ||
    state.playerSourceLocks[item.id]?.kind ||
    resolveStablePlayerSource(item)?.kind;
  const useHls = selectedSourceKind === "managed_hls" || selectedSourceKind === "native_hls" || shouldUseNativeHlsPlayback(item);
  const headers = useHls ? {} : { Range: "bytes=0-1" };
  let response = null;
  try {
    response = await fetch(sourceUrl, {
      credentials: "include",
      cache: "no-store",
      headers,
      signal: controller?.signal,
    });
    return response.ok || response.status === 206;
  } catch (_) {
    return false;
  } finally {
    if (timeoutId) clearTimeout(timeoutId);
    try {
      response?.body?.cancel?.();
    } catch (_) {}
  }
}

function shouldStartCompatibilityPlayback(item, job = null) {
  if (item.playbackMode === "DIRECT") return true;

  const effectiveStatus = job?.status || item.compatibilityStatus;
  if (effectiveStatus === "FAILED" || effectiveStatus === "STALLED") return false;
  const effectiveItem = {
    ...item,
    playbackMode: job?.playbackMode || item.playbackMode,
    compatibilityStatus: effectiveStatus,
    compatibilityComplete: job?.compatibilityComplete ?? item.compatibilityComplete,
    preparedMp4Url: job?.preparedMp4Url || item.preparedMp4Url,
    hlsUrl: job?.hlsUrl || item.hlsUrl,
    streamReady: Boolean(job?.ready ?? item.streamReady),
  };
  if (effectiveItem.effectivePlaybackMode === "PREPARED_MP4" || effectiveItem.effectivePlaybackMode === "LIVE_HLS") {
    return true;
  }
  if (job?.preparedMp4Url || shouldUseDirectCompatMp4(effectiveItem)) return true;
  if (effectiveStatus === "PLAYABLE_NOW" && (effectiveItem.preparedMp4Url || effectiveItem.hlsUrl || effectiveItem.streamReady)) {
    return true;
  }
  if (effectiveItem.compatibilityComplete || effectiveStatus === "READY") return true;
  if (shouldUseNativeHlsPlayback(effectiveItem) || shouldUseManagedHlsPlayback(effectiveItem)) return true;
  return false;
}

function compatibilityHeadline(item, streamLive = item.streamReady) {
  if (item.compatibilityStatus === "FAILED") {
    return gsStr("web_player_error_open", "This video could not be opened");
  }
  if (item.compatibilityStatus === "STALLED") {
    return gsStr("web_player_stalled", "Preparation appears stuck");
  }
  if (item.compatibilityStatus === "ANALYZING") {
    return gsStr("web_player_analyzing", "Analyzing video...");
  }
  if (item.compatibilityStatus === "FINALIZING") {
    return gsStr("web_player_finalizing", "Finalizing browser stream...");
  }
  if (item.compatibilityStatus === "PLAYABLE_NOW") {
    return gsStr("web_player_starting", "Starting playback...");
  }
  if ((item.compatibilityStatus === "IDLE" || !item.compatibilityStatus) && item.playbackMode !== "DIRECT" && !streamLive) {
    return "This video needs preparation for browser playback";
  }
  if (!streamLive) {
    return gsStr("web_player_opening", "Preparing for web playback...");
  }
  if (item.compatibilityComplete || item.compatibilityStatus === "READY") {
    return gsStr("web_player_ready", "Playback Ready");
  }
  return gsStr("web_player_starting", "Starting playback...");
}

function compatibilityBody(item, streamLive = item.streamReady) {
  if (item.compatibilityStatus === "FAILED") {
    return gsStr("web_error_streaming_codec", "This file's codec is not supported by the Android server for streaming. Please download.");
  }
  if (item.compatibilityStatus === "STALLED") {
    return gsStr("web_player_stalled_desc", "The file may be too complex for this device. Try downloading instead.");
  }
  if (item.compatibilityStatus === "ANALYZING") {
    return gsStr("web_player_analyzing_desc", "Checking video format and codecs...");
  }
  if (item.compatibilityStatus === "FINALIZING") {
    return gsStr("web_player_finalizing_desc", "Almost ready. Completing the browser-compatible stream.");
  }
  if (item.compatibilityStatus === "PLAYABLE_NOW") {
    return gsStr("web_player_starting_desc", "The video is starting now. Background optimization will continue.");
  }
  if ((item.compatibilityStatus === "IDLE" || !item.compatibilityStatus) && item.playbackMode !== "DIRECT" && !streamLive) {
    return "Prepare this video when you want to watch it in the browser.";
  }
  if (!streamLive) {
    return gsStr("web_player_wait_desc", "Preparing a browser-compatible version. Keep this page open.");
  }
  if (item.compatibilityComplete || item.compatibilityStatus === "READY") {
    return gsStr("web_player_ready_desc", "This video has been optimized for your browser.");
  }
  return gsStr("web_player_starting_desc", "The video is starting now. If it pauses, wait a moment or try again.");
}

function compatibilityBadgeLabel(item, streamLive = item.streamReady) {
  if (item.compatibilityStatus === "FAILED") {
    return gsStr("web_player_try_again", "Try again");
  }
  if (item.compatibilityStatus === "STALLED") {
    return gsStr("web_player_status_stalled", "Stuck");
  }
  if (item.compatibilityStatus === "ANALYZING") {
    return gsStr("web_player_status_analyzing", "Analyzing");
  }
  if (item.compatibilityStatus === "PLAYABLE_NOW") {
    return gsStr("web_player_status_playing", "Playing");
  }
  if (item.compatibilityStatus === "FINALIZING") {
    return gsStr("web_player_status_finalizing", "Finalizing");
  }
  if ((item.compatibilityStatus === "IDLE" || !item.compatibilityStatus) && item.playbackMode !== "DIRECT" && !streamLive) {
    return "Prepare";
  }
  if (!streamLive) {
    return gsStr("web_player_status_opening", "Preparing");
  }
  if (item.compatibilityComplete || item.compatibilityStatus === "READY") {
    return gsStr("web_player_status_ready", "Ready");
  }
  return gsStr("web_player_status_playing", "Playing");
}

function isPreparationActiveStatus(status) {
  return status === "QUEUED" || status === "ANALYZING" || status === "PREPARING" || status === "FINALIZING" || status === "PLAYABLE_NOW";
}

function compatibilityStatusText(item, streamLive = item.streamReady) {
  if (item.compatibilityStatus === "FAILED" || item.compatibilityStatus === "STALLED") return gsStr("web_player_status_failed", "Playback unavailable");
  if (item.compatibilityComplete || item.compatibilityStatus === "READY") return gsStr("web_player_status_ready", "Ready");
  if (item.compatibilityStatus === "PLAYABLE_NOW") return gsStr("web_player_status_playing", "Playing");
  if (isPreparationActiveStatus(item.compatibilityStatus)) return gsStr("web_player_status_opening", "Preparing");
  if (streamLive) return gsStr("web_player_status_playing", "Playing");
  if (item.playbackMode !== "DIRECT") return gsStr("web_prepare_video", "Prepare video");
  return gsStr("web_player_status_ready", "Ready");
}

function shouldRenderCardProgress(item) {
  return item.category === "video" && shouldRenderDetailProgress(item);
}

function renderCardProgress(item) {
  if (!shouldRenderCardProgress(item)) return "";
  const progress = compatProgressValue(item);
  return `
    <div class="gs-card-progress">
      <div class="gs-card-progress-head">
        <span class="gs-meta">${compatibilityProgressLabel(item)}</span>
        <strong>${gsStr("web_progress_percent", "%1$d%% complete", progress)}</strong>
      </div>
      <div class="gs-compat-progress-track" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${progress}">
        <span class="gs-compat-progress-fill" style="width:${progress}%"></span>
      </div>
    </div>
  `;
}

async function api(url, options = {}) {
  const response = await fetch(url, {
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
    ...options,
  });
  if (!response.ok) {
    let payload = {};
    try {
      payload = await response.json();
    } catch (_) {}
    const error = new Error(payload.message || `Request failed (${response.status})`);
    error.status = response.status;
    throw error;
  }
  return (response.headers.get("content-type") || "").includes("json") ? response.json() : response.text();
}

function shell(content, options = {}) {
  destroyUppy();
  destroyPlyr();
  destroyHls();
  destroyMusicPlayers();
  if (options.resetNowPlaying !== false) {
    state.nowPlaying = null;
  }
  const bootstrap = state.bootstrap;
  const path = location.pathname;
  const mediaActive = isMediaPath(path);
  const mediaSubnav = mediaActive
    ? `
      <div class="gs-media-subnav">
        <a class="gs-media-tab${path === "/" ? " on" : ""}" data-link href="/">${gsStr("web_nav_media", "Media")}</a>
        <a class="gs-media-tab${path === "/videos" ? " on" : ""}" data-link href="/videos">${gsStr("web_cat_videos", "Videos")}</a>
        <a class="gs-media-tab${path === "/photos" ? " on" : ""}" data-link href="/photos">${gsStr("web_cat_photos", "Photos")}</a>
        <a class="gs-media-tab${path === "/music" ? " on" : ""}" data-link href="/music">${gsStr("web_cat_music", "Music")}</a>
      </div>
    `
    : "";
  const securityLabel = bootstrap?.authEnabled ? gsStr("web_security_pin", "PIN protected") : gsStr("web_security_open", "Open on local network");
  const sessionLink = bootstrap?.sessionUrl
    ? `
      <div class="gs-inline-note">
        <span class="gs-inline-note-label">${gsStr("web_strip_link", "Link")}</span>
        <span class="gs-status-link">${esc(bootstrap.sessionUrl)}</span>
      </div>
    `
    : "";

  app.innerHTML = `
    <div class="gs-shell">
      <nav class="gs-nav">
        <a class="gs-logo" data-link href="/">
          <span class="gs-logo-mark"></span>
          <span>${esc(bootstrap?.title || sessionTitle)}</span>
        </a>
        <div class="gs-nav-links">
          <a class="gs-tab${mediaActive ? " on" : ""}" data-link href="/">${gsStr("web_nav_media", "Media")}</a>
          <a class="gs-tab${path === "/files" ? " on" : ""}" data-link href="/files">${gsStr("web_nav_files", "Files")}</a>
          <a class="gs-tab${path === "/upload" ? " on" : ""}" data-link href="/upload">${gsStr("web_nav_send", "Send")}</a>
        </div>
        <div class="gs-nav-meta">
          <span class="gs-status-pill">${securityLabel}</span>
          ${bootstrap?.authEnabled ? `<button class="gs-btn gs-btn-sm" id="logoutBtn">${gsStr("web_nav_logout", "Log out")}</button>` : ""}
        </div>
      </nav>
      <div class="gs-status-strip">
        <div class="gs-inline-note">
          <span class="gs-inline-note-label">${gsStr("web_strip_session", "Session")}</span>
          <span>${esc(bootstrap?.subtitle || sessionSubtitle)}</span>
        </div>
        <div class="gs-inline-note">
          <span class="gs-inline-note-label">${gsStr("web_strip_device", "Device")}</span>
          <span>${bootstrap?.deviceName ? `${esc(bootstrap.deviceName)} • ${esc(bootstrap.deviceIp)}` : gsStr("web_status_unknown", "Unknown")}</span>
        </div>
        ${sessionLink}
      </div>
      ${mediaSubnav}
      <main class="gs-main">${content}</main>
      <div class="gs-now${state.nowPlaying ? " is-visible" : ""}" id="nowPlayingBar"></div>
      
      <div class="gs-drop-indicator" id="dropIndicator">
        <div class="gs-drop-indicator-icon">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
        </div>
        <h2>${gsStr("web_upload_prompt_title", "Drop media here or choose files")}</h2>
        <p>${gsStr("web_send_files_to_device", "Send files to this device")}</p>
      </div>

      <div class="gs-upload-overlay" id="uploadOverlay">
        <div class="gs-upload-card">
          <div class="gs-upload-header">
            <div class="gs-logo-mark"></div>
            <h3 class="gs-upload-title" id="uploadTitle">${gsStr("web_upload_preparing_transfer", "Preparing transfer")}</h3>
          </div>
          <div class="gs-upload-progress-container">
            <div class="gs-upload-progress-fill" id="uploadProgress"></div>
          </div>
          <div class="gs-upload-status" id="uploadStatus">${gsStr("web_upload_connecting", "Connecting...")}</div>
          <div class="gs-upload-actions" id="uploadActions">
            <button class="gs-btn gs-btn-sm" id="cancelUploadBtn">${gsStr("web_cancel", "Cancel")}</button>
          </div>
        </div>
      </div>
    </div>`;

  document.getElementById("logoutBtn")?.addEventListener("click", async () => {
    await api("/auth/logout", { method: "POST" });
    navigate("/login", true);
  });
  renderNowPlayingBar();
}

function renderHome() {
  const bootstrap = state.bootstrap;
  const categories = [
    { key: "videos", label: gsStr("web_cat_videos", "Videos"), count: bootstrap.categories.videos, href: "/videos" },
    { key: "photos", label: gsStr("web_cat_photos", "Photos"), count: bootstrap.categories.photos, href: "/photos" },
    { key: "music", label: gsStr("web_cat_music", "Music"), count: bootstrap.categories.music, href: "/music" },
  ];
  const total = categories.reduce((sum, category) => sum + category.count, 0);

  shell(`
    <section class="gs-hero">
      <div class="gs-hero-copy">
        <span class="gs-eyebrow">${gsStr("web_hero_eyebrow", "DirectServe session")}</span>
        <h1>${gsStr("web_hero_title", "Media")}</h1>
        <p>${gsStr("web_hero_desc1", "Watch videos, open photos, and play music from this share.")}
        ${gsStr("web_hero_desc2", "Files and sending stay one tap away.")}
        ${gsStr("web_hero_desc3", "Everything stays on the same local network.")}</p>
      </div>
      <div class="gs-hero-side">
        <div class="gs-hero-summary">
          <div class="gs-hero-stat">
            <span class="gs-inline-note-label">${gsStr("web_hero_stat_shared", "Shared now")}</span>
            <strong>${total} ${gsStr("web_items", "items")}</strong>
            <span class="gs-meta">${gsStr("web_hero_stat_ready", "Ready on this local session")}</span>
          </div>
          <div class="gs-hero-stat">
            <span class="gs-inline-note-label">${gsStr("web_hero_stat_access", "Access")}</span>
            <strong>${bootstrap?.authEnabled ? gsStr("web_access_pin", "PIN required") : gsStr("web_access_instant", "Instant open")}</strong>
            <span class="gs-meta">${bootstrap?.authEnabled ? gsStr("web_access_pin_desc", "Enter the code from the host phone") : gsStr("web_access_instant_desc", "Open in any browser on this network")}</span>
          </div>
        </div>
        <div class="gs-hero-actions">
          <a class="gs-btn gs-btn-accent" data-link href="/files">${gsStr("web_nav_files", "Files")}</a>
          <a class="gs-btn" data-link href="/upload">${gsStr("web_nav_send", "Send")}</a>
        </div>
      </div>
    </section>

    <section class="gs-category-grid">
      ${categories.map((category) => `
        <a class="gs-category-card" data-link href="${category.href}">
          <span class="gs-category-kicker">${category.label}</span>
          <strong>${category.count}</strong>
          <span class="gs-category-meta">${gsStr("web_media_open_category", "Open %1$s", category.label)}</span>
        </a>
      `).join("")}
    </section>

    ${bootstrap.recent.length ? `
      <section class="gs-section">
        <div class="gs-section-head">
          <h2>${gsStr("web_recent_title", "Recently added")}</h2>
          <span class="gs-section-meta">${bootstrap.recent.length} ${gsStr("web_recent_meta", "highlighted items")}</span>
        </div>
        <div class="gs-grid">${bootstrap.recent.map((item) => card(item)).join("")}</div>
      </section>
    ` : `<section class="gs-section"><div class="gs-empty">${gsStr("web_media_empty", "No media is shared right now.")}</div></section>`}
  `);
}

async function renderLibrary(category, title) {
  const allowDownloads = !state.bootstrap?.preventDownload;
  shell(`
    <section class="gs-section">
      <div class="gs-section-head">
        <h2>${esc(title)}</h2>
        <span class="gs-section-meta">${allowDownloads ? gsStr("web_library_desc_download") : gsStr("web_library_desc_browse")}</span>
      </div>
      <div class="gs-control-card">
        <div class="gs-toolbar">
          <input class="gs-search" id="libSearch" placeholder="${gsStr("web_search_placeholder")}" value="${esc(state.query)}">
          <div class="gs-toolbar-actions">
            <button class="gs-btn" id="selectBtn">${state.selectMode ? gsStr("web_status_selection_on") : gsStr("web_btn_select_files")}</button>
            ${allowDownloads ? `<button class="gs-btn gs-btn-download" id="downloadAllBtn">${gsStr("web_btn_download_all", "Download all")}</button>` : ""}
          </div>
        </div>
        <div class="gs-select-bar${state.selectMode ? " is-visible" : ""}" id="selectBar">
          <span id="selectCount">0 selected</span>
          <div class="gs-toolbar-actions">
            <button class="gs-btn gs-btn-sm" id="selectAllBtn">${gsStr("web_btn_select_all")}</button>
            <button class="gs-btn gs-btn-sm" id="clearSelectBtn">${gsStr("web_btn_clear_selection")}</button>
            ${allowDownloads ? `<button class="gs-btn gs-btn-accent gs-btn-sm" id="downloadSelectedBtn">${gsStr("web_btn_download_selected", "Download selected")}</button>` : ""}
          </div>
        </div>
      </div>
      <div class="gs-grid" id="grid">${skeletons(6)}</div>
    </section>
  `);

  document.getElementById("libSearch")?.addEventListener("input", (event) => {
    state.query = event.target.value;
    clearTimeout(state.searchTimer);
    state.searchTimer = setTimeout(() => renderLibrary(category, title), 180);
  });

  state.libraryItems = await api(`/api/items?category=${encodeURIComponent(category)}&q=${encodeURIComponent(state.query || "")}`);
  const grid = document.getElementById("grid");
  grid.innerHTML = state.libraryItems.length
    ? state.libraryItems.map((item) => card(item, true)).join("")
    : `<div class="gs-empty">${gsStr("web_library_empty")}</div>`;

  attachMusicPlayers();
  bindSelectableCards();
  bindLibraryControls();
  updateSelectionUi();
}

function bindLibraryControls() {
  document.getElementById("selectBtn")?.addEventListener("click", () => {
    state.selectMode = !state.selectMode;
    if (!state.selectMode) {
      state.selected.clear();
    }
    renderLibrary(location.pathname.slice(1), titleForPath(location.pathname));
  });

  document.getElementById("downloadAllBtn")?.addEventListener("click", () => {
    downloadItems(state.libraryItems);
  });

  document.getElementById("selectAllBtn")?.addEventListener("click", () => {
    state.libraryItems.forEach((item) => state.selected.add(item.id));
    updateSelectionUi();
  });

  document.getElementById("clearSelectBtn")?.addEventListener("click", () => {
    state.selected.clear();
    updateSelectionUi();
  });

  document.getElementById("downloadSelectedBtn")?.addEventListener("click", () => {
    const selectedItems = state.libraryItems.filter((item) => state.selected.has(item.id));
    downloadItems(selectedItems);
  });
}

function bindSelectableCards() {
  document.querySelectorAll("[data-select-card]").forEach((cardElement) => {
    cardElement.addEventListener("click", (event) => {
      if (!state.selectMode) return;
      if (event.target.closest("a,button,audio")) return;
      toggleSelected(cardElement.dataset.selectCard);
    });
  });

  document.querySelectorAll("[data-select-toggle]").forEach((toggle) => {
    toggle.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      toggleSelected(toggle.dataset.selectToggle);
    });
  });
}

function toggleSelected(itemId) {
  if (state.selected.has(itemId)) {
    state.selected.delete(itemId);
  } else {
    state.selected.add(itemId);
  }
  updateSelectionUi();
}

function updateSelectionUi() {
  const count = state.selected.size;
  const selectBar = document.getElementById("selectBar");
  const selectCount = document.getElementById("selectCount");
  if (selectBar) {
    selectBar.classList.toggle("is-visible", state.selectMode);
  }
  if (selectCount) {
    selectCount.textContent = gsStr("web_selection_count", `${count} selected`, count);
  }
  document.querySelectorAll("[data-select-card]").forEach((cardElement) => {
    cardElement.classList.toggle("is-selected", state.selected.has(cardElement.dataset.selectCard));
  });
}

function downloadItems(items) {
  if (!items.length) return;
  if (state.bootstrap?.preventDownload) {
    alert(gsStr("web_error_downloads_disabled", "Downloads are disabled by the device owner."));
    return;
  }
  items.filter((item) => item.downloadUrl).forEach((item, index) => {
    setTimeout(() => {
      const anchor = document.createElement("a");
      anchor.href = item.downloadUrl;
      anchor.download = item.title || "";
      anchor.style.display = "none";
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
    }, index * 280);
  });
}

async function renderVideoPlayer(id) {
  const item = await api(`/api/item/${id}`);
  delete state.playerSourceLocks[id];
  const allowDownloads = !state.bootstrap?.preventDownload && Boolean(item.downloadUrl);
  rememberCompatProgress(item.id, item.compatibilityStatus, item.compatibilityProgressPercent);
  const nativeHls = nativeHlsEligibility(item);
  const hlsEligibility = managedHlsEligibility(item);
  debugTrace(
    "browser_detected",
    `route=/player/video/${id} apple=${isAppleDevice()} tv=${isTvBrowser()} chromiumDesktop=${isDesktopChromiumBrowser()} ua=${navigator.userAgent || ""}`,
  );
  debugTrace(
    nativeHls.allowed || hlsEligibility.allowed ? "inprogress_hls_enabled" : "inprogress_hls_disabled",
    `route=/player/video/${id} native=${nativeHls.reason} managed=${hlsEligibility.reason}`,
  );
  
  // Decide the initial view state
  const isDirect = item.playbackMode === "DIRECT";
  const isStreamLive = Boolean(item.streamReady);
  const isPreparedReady = Boolean(item.preparedMp4Url);
  const isTerminalFailure = item.compatibilityStatus === "FAILED" || item.compatibilityStatus === "STALLED";
  const isPreparationActive = isPreparationActiveStatus(item.compatibilityStatus);
  const showPlayerImmediately = !isTerminalFailure && (
    isDirect ||
    isPreparedReady ||
    shouldUseNativeHlsPlayback(item) ||
    shouldUseManagedHlsPlayback(item)
  );
  state.compatItem = item;
  
  shell(`
    <section class="gs-section">
      <div class="gs-player">
        <div class="gs-player-top">
          <div>
            <h2>${esc(item.title)}</h2>
            <div class="gs-meta">${fmtBytes(item.sizeBytes)}${item.durationMs ? ` | ${fmtDur(item.durationMs)}` : ""}</div>
          </div>
          <div class="gs-toolbar-actions">
            <a class="gs-btn gs-btn-sm" data-link href="/videos">${gsStr("web_btn_back", "Back to videos")}</a>
            ${allowDownloads ? `<a class="gs-btn gs-btn-download" href="${item.downloadUrl}">${gsStr("web_btn_download_original", "Download original")}</a>` : ""}
          </div>
        </div>
        <div id="playerStage">${renderVideoStage(item, showPlayerImmediately)}</div>
        ${!isDirect ? `
          <div class="gs-compat-inline${isStreamLive ? " is-visible" : ""}" id="compatInline">
            <span class="gs-badge" data-compat-badge>${compatibilityBadgeLabel(item, isStreamLive)}</span>
            <div class="gs-compat-inline-copy">
              <strong data-compat-title>${compatibilityHeadline(item, isStreamLive)}</strong>
              <p class="gs-meta" data-compat-message>${esc(compatibilityBody(item, isStreamLive))}</p>
            </div>
            <span class="gs-meta" data-compat-status>${compatibilityStatusText(item, isStreamLive)}</span>
          </div>
        ` : ""}
      </div>
    </section>
  `);

  if (showPlayerImmediately) {
    ensureCompatiblePlayerMounted(item);
  } else if (isPreparationActive) {
    pollCompat(id, item, { startPreparation: false });
  }
}

function renderVideoStage(item, showPlayer) {
  const status = item.compatibilityStatus || item.status;
  const isActive = isPreparationActiveStatus(status);
  return showPlayer
    ? videoMarkup(item)
    : `
      <div class="gs-compat-card" id="compatStageCard">
        <div class="gs-logo-mark${status === "FAILED" || status === "STALLED" || !isActive ? "" : " gs-spinner"}"></div>
        <span class="gs-badge" data-compat-badge>${compatibilityBadgeLabel(item, false)}</span>
        <h3 data-compat-title>${compatibilityHeadline(item, false)}</h3>
        <p data-compat-message>${esc(compatibilityBody(item, false))}</p>
        <div class="gs-compat-progress-shell" data-compat-progress-wrap>${renderCompatibilityProgress(item)}</div>
        <div class="gs-toolbar-actions gs-mt-2">
          ${status === "FAILED" || status === "STALLED"
            ? `<button class="gs-btn gs-btn-accent gs-btn-sm" onclick="retryPreparation('${item.id}')">Retry</button>`
            : isActive
              ? `<button class="gs-btn gs-btn-sm" disabled>Preparing...</button>`
              : item.playbackMode !== "DIRECT"
                ? `<button class="gs-btn gs-btn-accent gs-btn-sm" onclick="startPreparation('${item.id}')">Prepare for browser</button>`
                : ""}
          ${!state.bootstrap?.preventDownload ? `<a class="gs-btn gs-btn-sm" href="${item.downloadUrl}">Try original (may fail)</a>` : ""}
        </div>
      </div>
    `;
}

function videoMarkup(item) {
  const allowDownloads = !state.bootstrap?.preventDownload && Boolean(item.downloadUrl);
  const useNativePlayer = shouldUseNativeVideoPlayer(item);
  const nativeClass = useNativePlayer ? " gs-native-video" : "";
  const preload = useNativePlayer ? "metadata" : "auto";
  const lockedSource = state.playerSourceLocks[item.id] || resolveStablePlayerSource(item);
  const selectedSourceType = item.selectedSourceType || lockedSource?.kind || null;
  const sourceUrl = selectedSourceType === "managed_hls" ? null : (item.selectedSourceUrl || lockedSource?.url || null);
  const sourceAttribute = sourceUrl ? ` src="${sourceUrl}"` : "";

  return `
    <div class="gs-video-wrap">
      <video id="vPlayer" class="gs-plyr-target${nativeClass}" controls playsinline webkit-playsinline="true" x-webkit-airplay="allow" preload="${preload}"${sourceAttribute}>
        ${item.subtitleUrl ? `<track kind="subtitles" src="${item.subtitleUrl}" srclang="en" label="Subtitle" default>` : ""}
      </video>
      <div class="gs-video-error" id="vError">
        <strong>Video needs attention</strong>
        <p id="vErrorText">This browser could not start the video.</p>
        <div class="gs-toolbar-actions">
          <button class="gs-btn gs-btn-accent gs-btn-sm" id="retryVideoBtn">Try again</button>
          ${item.playbackMode !== "DIRECT" ? `<button class="gs-btn gs-btn-sm" onclick="retryPreparation('${item.id}')">Reset & Retry Optimization</button>` : ""}
          ${allowDownloads ? `<a class="gs-btn gs-btn-download gs-btn-sm" href="${item.downloadUrl}">${gsStr("web_btn_download_original", "Download original")}</a>` : ""}
        </div>
      </div>
    </div>
  `;
}

function canUseManagedHlsFallback(item, managedHlsAvailable) {
  return managedHlsAvailable && Boolean(item?.hlsUrl);
}

function showCompatibilityWaitingStage(item) {
  destroyPlyr();
  destroyHls();
  delete state.playerSourceLocks[item.id];
  const stage = document.getElementById("playerStage");
  if (!stage) return;
  stage.innerHTML = renderVideoStage({
    ...item,
    streamReady: false,
    compatibilityComplete: false,
  });
}

function hydrateVideoPlayer(item, options = {}) {
  const video = document.getElementById("vPlayer");
  if (!video || video.dataset.bound === "true") return;
  destroyPlyr();
  destroyHls();
  video.dataset.bound = "true";
  const selectedSource = state.playerSourceLocks[item.id] || resolveStablePlayerSource(item);
  if (!selectedSource) {
    debugTrace("player_rehydrate_blocked", `id=${item.id} reason=no_locked_source`);
    return;
  }
  video.dataset.sourceType = selectedSource.kind;
  video.dataset.sourceUrl = selectedSource.url || "";
  const useNativePlayer = shouldUseNativeVideoPlayer(item);
  const useDirectMp4 = selectedSource.kind === "prepared_mp4" || selectedSource.kind === "direct";
  const useNativeHls = selectedSource.kind === "native_hls" && shouldUseNativeHlsPlayback(item);
  const useManagedHls = selectedSource.kind === "managed_hls" && shouldUseManagedHlsPlayback(item);
  const managedHlsAvailable = canUseManagedHls(item);
  const allowManagedHlsFallback = canUseManagedHlsFallback(item, managedHlsAvailable);
  const errorCard = document.getElementById("vError");
  const errorText = document.getElementById("vErrorText");
  
  // Aspect Ratio Reset: clear previous ratio to prevent portrait/landscape leakage
  const playerStructural = video.closest(".gs-player");
  const wrapStructural = video.closest(".gs-video-wrap");
  if (playerStructural) playerStructural.style.aspectRatio = "";
  if (wrapStructural) wrapStructural.style.aspectRatio = "";
  video.style.aspectRatio = "";

  let autoRetryUsed = false;
  let managedHlsFallbackUsed = false;
  debugTrace(
    "player_hydrate",
    `id=${item.id} mode=${item.playbackMode} directMp4=${useDirectMp4} ` +
    `source=${selectedSource.kind} ` +
    `nativePlayer=${useNativePlayer} managedHls=${useManagedHls} ` +
    `nativeHls=${shouldUseNativeHlsPlayback(item)} ` +
    `managedHlsAvailable=${managedHlsAvailable} ` +
    `hasPreparedUrl=${Boolean(item.preparedMp4Url)} ` +
    `hasHlsUrl=${Boolean(item.hlsUrl)} ` +
    `compComplete=${item.compatibilityComplete} ` +
    `streamReady=${item.streamReady}`
  );
  debugTrace("player_context", `id=${item.id} ${playbackContextSummary(item, selectedSource)}`);

  if (!useNativePlayer && typeof window.Plyr === "function") {
    const plyrOptions = {
      iconUrl: "/plyr.svg",
    };
    
    // Initialize with the true total duration from analyzed metadata
    if (item.totalDurationMs) {
      plyrOptions.duration = item.totalDurationMs / 1000;
    }
    
    state.plyr = new window.Plyr(video, plyrOptions);
    state.plyrItemId = item.id;
    
    // Explicitly set ratio from metadata if available
    if (item.width && item.height) {
      state.ratioLocked[item.id] = `${item.width}:${item.height}`;
      state.plyr.ratio = `${item.width}:${item.height}`;
      const wrapStructural = video.closest(".gs-video-wrap");
      if (wrapStructural) {
        wrapStructural.style.aspectRatio = `${item.width} / ${item.height}`;
        if (item.height > item.width) {
          wrapStructural.classList.add("is-portrait");
        }
      }
    }
    
    setupScrubbingPreviews(item, state.plyr);
  }

  const restartPlayback = () => {
    if (location.pathname !== `/player/video/${item.id}`) return;
    const stage = document.getElementById("playerStage");
    if (!stage) return;
    destroyPlyr();
    destroyHls();
    stage.innerHTML = videoMarkup(item);
    hydrateVideoPlayer(item, { autoplay: true });
  };

  const startManagedHls = () => {
    if (!allowManagedHlsFallback || !item.hlsUrl) return false;
    state.compatItem = item;
    debugTrace("hls_start", `id=${item.id} url=${item.hlsUrl}`);
    destroyHls();
    video.removeAttribute("src");
    video.load();
    const hls = new window.Hls({
      enableWorker: true,
      lowLatencyMode: false,

      // Buffer settings — build up a comfortable buffer before playback starts,
      // and keep a back-buffer for seeking backwards without re-requesting segments.
      maxBufferLength: 30,          // target 30 s ahead
      maxMaxBufferLength: 90,       // allow up to 90 s when the network is fast
      backBufferLength: 30,         // keep 30 s behind for seeking

      // Retry settings tuned for a local-only server writing segments in real time.
      // Faster retry delay means the player recovers from "segment not yet written"
      // situations without a noticeable stall on the screen.
      fragLoadingMaxRetry: 8,
      fragLoadingRetryDelay: 400,       // retry quickly (default 1000 ms is too slow)
      fragLoadingMaxRetryTimeout: 4000,
      manifestLoadingMaxRetry: 6,
      manifestLoadingRetryDelay: 400,
      levelLoadingMaxRetry: 6,
      levelLoadingRetryDelay: 400,

      // Generous fragment-load timeout — on slow Android devices the transcoder can
      // take a moment to finish writing a segment.
      fragLoadingTimeOut: 20000,
      manifestLoadingTimeOut: 10000,

      // We only ever have one quality level (no ABR needed).
      startLevel: 0,
      autoLevelEnabled: false,

      // Don't switch between quality levels to avoid unnecessary playlist refreshes.
      capLevelToPlayerSize: false,
    });
    state.hls = hls;
    state.hlsItemId = item.id;
    let mediaRecoveryAttempts = 0;
    let bufferAppendErrors = 0;
    const MAX_MEDIA_RECOVERIES = 2;
    const MAX_BUFFER_APPEND_ERRORS = 3;

    const showHlsError = (message) => {
      if (errorCard) errorCard.classList.add("is-visible");
      if (errorText) errorText.textContent = message || "This video is still getting ready. Try again in a moment.";
      destroyHls();
    };

    hls.attachMedia(video);
    hls.on(window.Hls.Events.MEDIA_ATTACHED, () => {
      debugTrace("hls_media_attached", `id=${item.id}`);
      hls.loadSource(item.hlsUrl);
    });
    hls.on(window.Hls.Events.MANIFEST_PARSED, () => {
      debugTrace("hls_manifest_parsed", `id=${item.id}`);
      if (options.autoplay) {
        video.play().catch(() => {});
      }
    });
    hls.on(window.Hls.Events.ERROR, (_, data) => {
      // ── DETAILED DEBUG LOG ────────────────────────────────────────────────────
      // Capture everything hls.js knows about the error so codec/MSE issues can
      // be diagnosed from the browser console (F12 → Console tab).
      const errMsg   = data?.error?.message || "";
      const errName  = data?.error?.name    || "";
      const fragUrl  = data?.frag?.url      || "";
      const fragSn   = data?.frag?.sn       ?? "";
      const bufType  = data?.buffer         || "";
      const mediaErr = video?.error ? `MediaError(${video.error.code}: ${video.error.message})` : "none";
      const mseState = video?.readyState    ?? "";
      console.group?.(`[DirectServe HLS] ${data?.details || "error"}`);
      console.log?.("fatal:", Boolean(data?.fatal));
      console.log?.("type:", data?.type || "");
      console.log?.("details:", data?.details || "");
      console.log?.("error name:", errName);
      console.log?.("error message:", errMsg);
      console.log?.("fragment url:", fragUrl);
      console.log?.("fragment seq:", fragSn);
      console.log?.("buffer type:", bufType);
      console.log?.("video.error:", mediaErr);
      console.log?.("video.readyState:", mseState);
      console.log?.("full data:", data);
      console.groupEnd?.();
      // Also send to server debug endpoint so it appears in the app's debug log.
      debugTrace(
        "hls_error",
        `id=${item.id} fatal=${Boolean(data?.fatal)} type=${data?.type || ""} ` +
        `details=${data?.details || ""} errName=${errName} errMsg=${errMsg} ` +
        `fragUrl=${fragUrl} fragSn=${fragSn} bufType=${bufType} mediaErr=${mediaErr}`,
      );
      // ─────────────────────────────────────────────────────────────────────────

      // Track non-fatal buffer append errors — these signal MSE codec incompatibility.
      // hls.js will eventually escalate them to fatal, but the cycle of recoverMediaError()
      // → MEDIA_ATTACHED → loadSource() → segment 0 → bufferAppendError creates an
      // infinite loop. Break out early if too many occur before any progress.
      if (!data?.fatal) {
        const detail = data?.details || "";
        if (detail === "bufferAppendError" || detail === "bufferAppendingError") {
          bufferAppendErrors += 1;
          if (bufferAppendErrors >= MAX_BUFFER_APPEND_ERRORS) {
            debugTrace("hls_error", `id=${item.id} fatal=true type=bufferAppend_threshold details=exceeded`);
            // If the server has already finished transcoding, switch to direct MP4
            // playback immediately — no HLS/MSE involved, so no TFHD or codec issues.
            // We use state.compatItem to ensure we have the LATEST updated URL from polling.
            const currentItem = state.compatItem || item;
            if (currentItem.preparedMp4Url) {
              debugTrace("prepared_asset_reused", `id=${item.id} asset=${currentItem.preparedMp4Url.substringAfterLast('/')}`);
              debugTrace("hls_error_direct_mp4_fallback", `id=${item.id} url=${currentItem.preparedMp4Url}`);
              destroyHls();
              const plyrObj = state.plyr;
              if (plyrObj) {
                plyrObj.source = {
                  type: "video",
                  sources: [{ src: currentItem.preparedMp4Url, type: "video/mp4" }]
                };
              } else {
                video.src = currentItem.preparedMp4Url;
                video.load();
              }
              if (options.autoplay) {
                video.play().catch(() => {});
              }
              return;
            }
            showHlsError(
              state.bootstrap?.preventDownload
                ? "This browser could not decode the video stream."
                : gsStr("web_error_video_decode", "This browser could not decode the video stream. Try downloading the original file."),
            );
          }
        }
        return;
      }

      if (data.type === window.Hls.ErrorTypes.NETWORK_ERROR) {
        const status = data.response?.status;
        if (status === 410) {
          // 410 Gone: The server believes the item is missing. 
          // Attempt a one-time recovery with a short delay to allow the server to re-verify.
          console.log("[DirectServe HLS] 410 Gone detected, attempting recovery...");
          setTimeout(() => {
            if (item.hlsUrl) {
              hls.loadSource(item.hlsUrl);
            }
          }, 1000);
          return;
        }
        showHlsError("A network error occurred while loading the video.");
        return;
      }
      showHlsError("This video is still getting ready. Try again in a moment.");
    });
    return true;
  };

  if (useNativeHls) {
    debugTrace("hls_start", `id=${item.id} url=${selectedSource.url} kind=native`);
    video.src = selectedSource.url;
    video.load();
    if (options.autoplay) {
      video.play().catch(() => {});
    }
  }

  if (useManagedHls) {
    if (!startManagedHls()) {
      debugTrace("player_rehydrate_blocked", `id=${item.id} reason=hls_init_failed`);
      return;
    }
  }

  const clearVideoError = () => {
    if (errorCard) errorCard.classList.remove("is-visible");
  };

  const markPlaybackStable = () => {
    state.compatPlaybackFailures[item.id] = 0;
    clearVideoError();
  };

  // Keyboard Shortcuts (Desktop-only experience)
  const handleKeydown = (e) => {
    if (e.target.tagName === "INPUT" || e.target.tagName === "TEXTAREA") return;
    const player = state.plyr || video;
    switch (e.code) {
      case "Space":
        e.preventDefault();
        player.paused ? player.play() : player.pause();
        break;
      case "ArrowLeft":
        e.preventDefault();
        player.currentTime = Math.max(0, player.currentTime - 10);
        break;
      case "ArrowRight":
        e.preventDefault();
        player.currentTime = Math.min(player.duration || Infinity, player.currentTime + 10);
        break;
      case "KeyF":
        e.preventDefault();
        if (state.plyr) state.plyr.fullscreen.toggle();
        else if (video.requestFullscreen) video.requestFullscreen();
        break;
      case "KeyM":
        e.preventDefault();
        player.muted = !player.muted;
        break;
    }
  };
  window.addEventListener("keydown", handleKeydown);
  
  // Clean up keydown listener on next navigation
  const oldCancel = cancelCompatPolling;
  cancelCompatPolling = () => {
    window.removeEventListener("keydown", handleKeydown);
    oldCancel();
  };

  video.addEventListener("loadedmetadata", markPlaybackStable);
  video.addEventListener("canplay", markPlaybackStable);
  video.addEventListener("playing", markPlaybackStable);
  video.addEventListener("loadedmetadata", () => {
    const w = video.videoWidth;
    const h = video.videoHeight;
    const src = video.currentSrc || "";
    
    // RATIO PROTECTION:
    // 1. If we have authoritative oriented dimensions from the server, Lock them immediately.
    // 2. Ignore metadata events if the current source is a placeholder (blank.mp4 / bootstrap).
    // 3. Ignore 32x20 dimensions which are common native player default placeholders.
    const metadataRatio = (item.width && item.height) ? `${item.width}:${item.height}` : null;
    const isPlaceholder = src.includes("blank.mp4") || src.includes("blob:") == false && src.includes("/api/compat/") == false && src.includes("/hls/") == false;
    const isDefaultUnstretched = (w === 32 && h === 20);

    if (state.ratioLocked[item.id]) {
        debugTrace("ratio_override_blocked", `id=${item.id} locked=${state.ratioLocked[item.id]} attempt=${w}x${h}`);
        if (state.plyr && state.plyr.ratio !== state.ratioLocked[item.id]) {
            state.plyr.ratio = state.ratioLocked[item.id];
        }
        return;
    }

    if (metadataRatio) {
        state.ratioLocked[item.id] = metadataRatio;
        debugTrace("ratio_locked_authoritative", `id=${item.id} ratio=${metadataRatio}`);
        if (state.plyr) state.plyr.ratio = metadataRatio;
        const wrapStructural = video.closest(".gs-video-wrap");
        if (wrapStructural) {
            wrapStructural.style.aspectRatio = metadataRatio.replace(":", " / ");
            if (item.height > item.width) {
                wrapStructural.classList.add("is-portrait");
            }
        }
        return;
    }

    if (w && h && !isPlaceholder && !isDefaultUnstretched) {
        const ratioStr = `${w}:${h}`;
        state.ratioLocked[item.id] = ratioStr;
        debugTrace("ratio_locked_real", `id=${item.id} ratio=${ratioStr} source=${src}`);
        
        if (state.plyr) {
            state.plyr.ratio = ratioStr;
        } else {
            video.style.aspectRatio = `${w} / ${h}`;
        }
    }
  });
  video.addEventListener("canplay", () => {
    debugTrace("video_canplay", `id=${item.id} readyState=${video.readyState}`);
  });
  video.addEventListener("playing", () => {
    debugTrace("video_playing", `id=${item.id} currentTime=${video.currentTime.toFixed(2)}`);
  });

  video.addEventListener("seeked", async () => {
    // Only handle seeks if we are using the HLS compatibility pipeline.
    // Native MP4 (DIRECT) already supports byte-range seeking out of the box.
    if (item.playbackMode === "DIRECT" || !state.hls || state.hlsItemId !== item.id) return;

    const currentTime = video.currentTime;
    // We check if the target time is already within the browser's buffer.
    // If it's not, it means the transcoder hasn't reached this point yet.
    let isBuffered = false;
    for (let i = 0; i < video.buffered.length; i++) {
      if (currentTime >= video.buffered.start(i) && currentTime <= video.buffered.end(i) + 1) {
        isBuffered = true;
        break;
      }
    }

    if (!isBuffered) {
      debugTrace("video_seek_restart", `id=${item.id} offset=${currentTime.toFixed(1)}s`);
      
      // If the user seeks beyond the currently prepared percentage, show a specific state.
      const duration = (item.totalDurationMs || item.durationMs || 0) / 1000;
      const progressPercent = item.compatibilityProgressPercent || 0;
      const preparedSeconds = (progressPercent / 100) * duration;
      
      if (currentTime > preparedSeconds + 2 && !item.compatibilityComplete) {
         showCompatibilityWaitingStage({ 
            ...item, 
            streamReady: false,
            compatibilityMessage: gsStr("web_player_preparing_segment", "Preparing this segment for you...") 
         });
      }

      try {
        const url = `/api/compat/${item.id}/seek?offsetMs=${Math.floor(currentTime * 1000)}`;
        const resp = await fetch(url, { method: "POST" });
        if (resp.ok) {
          // Tell hls.js to reload the manifest to find the new segments at the offset.
          state.hls.loadSource(item.hlsUrl);
          // Standard hls.js behavior will start from the beginning of the manifest,
          // so we ensure the video element stays at our target time.
          video.currentTime = currentTime;
          video.play().catch(() => {});
        }
      } catch (err) {
        console.error("Seeking failed:", err);
      }
    }
  });
  video.addEventListener("error", () => {
    const mediaError = video.error
      ? `MediaError(code=${video.error.code} message=${video.error.message || ""})`
      : "none";
    const sourceType = video.dataset.sourceType || "unknown";
    debugTrace(
      "video_error",
      `id=${item.id} mode=${item.playbackMode} code=${video.error?.code || ""} readyState=${video.readyState} currentSrc=${video.currentSrc} ${playbackContextSummary(item, { kind: sourceType })} browserError=${mediaError}`,
    );
    if (allowManagedHlsFallback && !managedHlsFallbackUsed && !state.hls) {
      managedHlsFallbackUsed = true;
      debugTrace("video_error_hls_fallback", `id=${item.id}`);
      clearVideoError();
      if (startManagedHls() && options.autoplay) {
        setTimeout(() => {
          video.play().catch(() => {});
        }, 200);
      }
      return;
    }
    // Only DIRECT gets an automatic fallback into compatibility preparation.
    // If a compatibility path itself fails, the user must explicitly retry.
    const failureCount = (state.compatPlaybackFailures[item.id] || 0) + 1;
    state.compatPlaybackFailures[item.id] = failureCount;
    if (item.playbackMode === "DIRECT" && failureCount <= 2) {
      debugTrace("video_error_direct_compat_fallback", `id=${item.id} failures=${failureCount} triggering compat preparation`);
      showCompatibilityWaitingStage({
        ...item,
        streamReady: false,
        compatibilityComplete: false,
      });
      pollCompat(item.id, {
        ...item,
        streamReady: false,
        compatibilityComplete: false,
      }, { forceCompat: true, startPreparation: true });
      debugTrace("compat_failed", `id=${item.id} reason=${item.compatibilityMessage || "unknown"}`);
      return;
    }
    if (errorCard) errorCard.classList.add("is-visible");
    debugTrace(
      "browser_playback_failed",
      `id=${item.id} ${playbackContextSummary(item, { kind: sourceType })} browserError=${mediaError}`,
    );
    if (errorText) {
      errorText.textContent = item.playbackMode === "DIRECT"
        ? (state.bootstrap?.preventDownload
          ? gsStr("web_error_video_start_no_dl", "This browser could not play the video. The server is preparing a compatible version.")
          : gsStr("web_error_video_start", "This browser could not start the video. Try again or download the original file."))
        : "This video is still opening. Try again in a moment.";
    }
    if (item.playbackMode === "DIRECT" && !autoRetryUsed) {
      autoRetryUsed = true;
      setTimeout(() => {
        clearVideoError();
        restartPlayback();
      }, 1800);
    }
  });
  document.getElementById("retryVideoBtn")?.addEventListener("click", () => {
    debugTrace("video_retry_clicked", `id=${item.id} mode=${item.playbackMode} hasHls=${Boolean(state.hls && state.hlsItemId === item.id)}`);
    clearVideoError();
    state.compatPlaybackFailures[item.id] = 0;
    if (item.playbackMode !== "DIRECT") {
      showCompatibilityWaitingStage({
        ...item,
        streamReady: false,
        compatibilityComplete: false,
      });
      retryPreparation(item.id);
      return;
    }
    restartPlayback();
  });

  if (options.autoplay) {
    setTimeout(() => {
      if (state.plyr && state.plyrItemId === item.id) {
        state.plyr.play().catch(() => {});
      } else {
        video.play().catch(() => {});
      }
    }, 180);
  }

  if (allowManagedHlsFallback && !useManagedHls) {
    setTimeout(() => {
      if (location.pathname !== `/player/video/${item.id}`) return;
      if (managedHlsFallbackUsed || state.hls) return;
      const hasPlayableState = video.readyState >= 2 || !video.paused;
      if (hasPlayableState) return;
      managedHlsFallbackUsed = true;
      debugTrace("video_delayed_hls_fallback", `id=${item.id}`);
      clearVideoError();
      if (startManagedHls() && options.autoplay) {
        setTimeout(() => {
          video.play().catch(() => {});
        }, 200);
      }
    }, 2500);
  }
}

function updateCompatElements(job, streamLive) {
  const progressItem = {
    id: job.itemId,
    playbackMode: job.playbackMode || state.compatItem?.playbackMode,
    compatibilityStatus: job.status,
    compatibilityComplete: job.complete || job.compatibilityComplete,
    compatibilityProgressPercent: job.progressPercent,
  };
  rememberCompatProgress(progressItem.id, progressItem.compatibilityStatus, progressItem.compatibilityProgressPercent);
  document.querySelectorAll("[data-compat-message]").forEach((element) => {
    element.textContent = compatibilityBody({
      compatibilityMessage: job.message,
      compatibilityStatus: job.status,
      compatibilityComplete: job.complete || job.compatibilityComplete,
      streamReady: streamLive,
    }, streamLive);
  });
  document.querySelectorAll("[data-compat-status]").forEach((element) => {
    element.textContent = compatibilityStatusText({
      compatibilityStatus: job.status,
      compatibilityComplete: job.complete || job.compatibilityComplete,
      streamReady: streamLive,
    }, streamLive);
  });
  document.querySelectorAll("[data-compat-progress-wrap]").forEach((element) => {
    element.innerHTML = renderCompatibilityProgress(progressItem);
  });
  document.querySelectorAll("[data-compat-badge]").forEach((element) => {
    element.textContent = compatibilityBadgeLabel({
      compatibilityStatus: job.status,
      compatibilityComplete: job.complete || job.compatibilityComplete,
      streamReady: streamLive,
    }, streamLive);
  });
  document.querySelectorAll("[data-compat-title]").forEach((element) => {
    element.textContent = compatibilityHeadline({
      compatibilityStatus: job.status,
      compatibilityComplete: job.complete || job.compatibilityComplete,
      streamReady: streamLive,
    }, streamLive);
  });
  
  // If we just transitioned to FAILED, ensure the retry button appears in the stage card
  const stage = document.getElementById("compatStageCard");
  if (stage && job.status === "FAILED") {
    const actions = stage.querySelector(".gs-toolbar-actions");
    if (actions && !actions.querySelector(".gs-btn-accent")) {
       const btn = document.createElement("button");
       btn.className = "gs-btn gs-btn-accent gs-btn-sm";
       btn.textContent = "Retry";
       btn.onclick = () => retryPreparation(job.itemId);
       actions.prepend(btn);
       
       const spinner = stage.querySelector(".gs-spinner");
       if (spinner) spinner.classList.remove("gs-spinner");
       
       const progress = stage.querySelector("[data-compat-status]");
       if (progress) progress.textContent = "Stopped";
    }
  }

  const inline = document.getElementById("compatInline");
  if (inline) {
    inline.classList.toggle("is-visible", streamLive);
  }
}

async function retryPreparation(id) {
  debugTrace("compat_retry_clicked", `id=${id}`);
  try {
    const job = await api(`/api/compat/${id}/retry`, { method: "POST" });
    const path = location.pathname;
    if (path === `/player/video/${id}`) {
      // Re-initialize the player view
      renderVideoPlayer(id);
    }
  } catch (error) {
    console.error("Manual retry failed:", error);
    alert("Unable to restart optimization: " + error.message);
  }
}

async function ensureCompatiblePlayerMounted(item) {
  const mountToken = ++state.compatMountToken;
  const selectedSource = lockPlayerSource(item);
  if (!selectedSource) {
    debugTrace(
      "player_rehydrate_blocked",
      `id=${item.id} reason=source_not_ready mode=${item.playbackMode} status=${item.compatibilityStatus || "IDLE"}`,
    );
    return false;
  }
  const ready = await probeCompatiblePlaybackSource({
    ...item,
    selectedSourceUrl: selectedSource.url,
  });
  if (mountToken !== state.compatMountToken || location.pathname !== `/player/video/${item.id}`) {
    return false;
  }
  if (!ready) {
    debugTrace("player_rehydrate_blocked", `id=${item.id} reason=source_probe_failed source=${selectedSource.kind}`);
    return false;
  }
  const lockedItem = {
    ...item,
    selectedSourceUrl: selectedSource.url,
    selectedSourceType: selectedSource.kind,
  };
  state.compatItem = lockedItem;
  const existingVideo = document.getElementById("vPlayer");
  const stage = document.getElementById("playerStage");
  if (existingVideo) {
    const currentSourceType = existingVideo.dataset.sourceType || null;
    const currentSourceUrl = existingVideo.dataset.sourceUrl || null;
    if (currentSourceType === selectedSource.kind && currentSourceUrl === selectedSource.url) {
      hydrateVideoPlayer(lockedItem);
      return true;
    }
    if (!stage) return false;
    destroyPlyr();
    destroyHls();
    stage.innerHTML = videoMarkup(lockedItem);
    hydrateVideoPlayer(lockedItem, { autoplay: true });
    return true;
  }
  if (!stage) return false;
  stage.innerHTML = videoMarkup(lockedItem);
  hydrateVideoPlayer(lockedItem, { autoplay: true });
  return true;
}

async function renderPhotoViewer(id) {
  const item = await api(`/api/item/${id}`);
  const allowDownloads = !state.bootstrap?.preventDownload && Boolean(item.downloadUrl);
  let prev = null;
  let next = null;
  try {
    const all = await api("/api/items?category=photos");
    const index = all.findIndex((photo) => photo.id === id);
    if (index > 0) prev = all[index - 1].id;
    if (index >= 0 && index < all.length - 1) next = all[index + 1].id;
  } catch (_) {}

  shell(`
    <section class="gs-section">
      <div class="gs-player">
        <div class="gs-player-top">
          <div>
            <h2>${esc(item.title)}</h2>
            <div class="gs-meta">${fmtBytes(item.sizeBytes)}</div>
          </div>
          <div class="gs-toolbar-actions">
            ${prev ? `<a class="gs-btn gs-btn-sm" data-link href="/photo/${prev}">Previous</a>` : ""}
            ${next ? `<a class="gs-btn gs-btn-sm" data-link href="/photo/${next}">Next</a>` : ""}
            <a class="gs-btn gs-btn-sm" data-link href="/photos">Gallery</a>
            ${allowDownloads ? `<a class="gs-btn gs-btn-download" href="${item.downloadUrl}">${gsStr("web_btn_download_original", "Download original")}</a>` : ""}
          </div>
        </div>
        ${(item.mimeType === "application/pdf" || String(item.title).toLowerCase().endsWith(".pdf"))
          ? `<iframe class="gs-preview gs-preview-pdf" src="${item.streamUrl}" title="${esc(item.title)}"></iframe>`
          : (item.category === "photo" || (item.mimeType && item.mimeType.startsWith("image/")))
            ? `<img class="gs-preview" src="${item.streamUrl}" alt="${esc(item.title)}">`
            : `<div class="gs-preview gs-no-preview">No preview available for this file type</div>`}
      </div>
    </section>
  `);
}

function renderLogin(errorMessage = "") {
  const bootstrap = state.bootstrap;
  app.innerHTML = `
    <div class="gs-center">
      <div class="gs-login">
        <div class="gs-logo gs-login-logo">
          <span class="gs-logo-mark"></span>
          <span>${esc(bootstrap?.title || sessionTitle)}</span>
        </div>
        ${bootstrap?.subtitle ? `<span class="gs-subtitle">${esc(bootstrap.subtitle)}</span>` : ""}
        <span class="gs-eyebrow">${gsStr("web_pin_entry_kicker", "PIN protected session")}</span>
        <h1>${gsStr("web_pin_entry_title", "Enter access PIN")}</h1>
        <p class="gs-meta">${gsStr("web_pin_entry_desc", "Enter the PIN shown on the host phone to unlock this session.")}</p>
        <form id="loginForm">
          <input id="pinInput" class="gs-pin" inputmode="numeric" maxlength="6" placeholder="${gsStr("web_pin_entry_placeholder", "Enter PIN")}" autofocus>
          ${errorMessage ? `<p class="gs-error-text">${esc(errorMessage)}</p>` : ""}
          <button class="gs-btn gs-btn-accent gs-btn-block" type="submit">${gsStr('web_btn_continue')}</button>
        </form>
      </div>
    </div>`;

  document.getElementById("loginForm")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
      const loginData = { pin: document.getElementById("pinInput").value.trim() };
      await api("/auth/login", {
        method: "POST",
        body: JSON.stringify(loginData),
      });
      navigate("/", true);
    } catch (error) {
      renderLogin(error.message || "That PIN did not match.");
    }
  });
}

function renderError(message) {
  app.innerHTML = `
    <div class="gs-center">
      <div class="gs-login">
        <h1>${esc(sessionTitle)}</h1>
        <p class="gs-error-text">${esc(message)}</p>
      </div>
    </div>`;
}

function card(item, selectable = false) {
  const allowDownloads = !state.bootstrap?.preventDownload && Boolean(item.downloadUrl);
  const actionBtnClass = state.bootstrap?.prominentDownloadButton
    ? "gs-btn gs-btn-sm"
    : "gs-btn gs-btn-accent gs-btn-sm";
  const downloadBtnClass = state.bootstrap?.prominentDownloadButton
    ? "gs-btn gs-btn-download gs-btn-sm"
    : "gs-btn gs-btn-sm";
  const action = item.category === "video"
    ? (() => {
        if (
          item.playbackMode === "DIRECT" ||
          item.compatibilityStatus === "READY" ||
          item.compatibilityStatus === "PLAYABLE_NOW" ||
          item.effectivePlaybackMode === "PREPARED_MP4" ||
          item.effectivePlaybackMode === "LIVE_HLS"
        ) {
          return `<a class="${actionBtnClass}" data-link href="/player/video/${item.id}">${gsStr("common_play", "Play")}</a>`;
        }
        if (item.compatibilityStatus === "FAILED" || item.compatibilityStatus === "STALLED") {
          return `<a class="${actionBtnClass}" data-link href="/player/video/${item.id}">${gsStr("web_player_try_again", "Retry")}</a>`;
        }
        if (isPreparationActiveStatus(item.compatibilityStatus)) {
          return `<span class="${actionBtnClass}" aria-disabled="true">${gsStr("web_player_status_opening", "Preparing")}</span>`;
        }
        return `<a class="${actionBtnClass}" data-link href="/player/video/${item.id}">${gsStr("web_prepare_video", "Prepare video")}</a>`;
      })()
    : item.category === "photo"
      ? `<a class="${actionBtnClass}" data-link href="/photo/${item.id}">${gsStr("web_photo_view", "View")}</a>`
      : item.category === "music"
        ? `<button class="${actionBtnClass} music-play-btn" data-audio-item-id="${item.id}" data-title="${esc(item.title)}">${gsStr("common_play", "Play")}</button>`
        : item.title.toLowerCase().endsWith(".pdf")
          ? `<a class="${actionBtnClass}" data-link href="/photo/${item.id}">${gsStr("web_photo_view", "View")}</a>`
          : "";
  const showSlowStartHint = item.category === "video" && item.playbackMode !== "DIRECT";

  return `
    <article class="gs-card${selectable && state.selectMode ? " gs-card-selectable" : ""}${state.selected.has(item.id) ? " is-selected" : ""}" data-select-card="${selectable ? item.id : ""}">
      ${selectable ? `<button class="gs-card-toggle${state.selectMode ? " is-visible" : ""}" data-select-toggle="${item.id}">${state.selected.has(item.id) ? "Selected" : "Select"}</button>` : ""}
      ${item.thumbnailUrl
        ? `<img class="gs-card-img" loading="lazy" src="${item.thumbnailUrl}" alt="">`
        : `<div class="gs-card-img gs-card-placeholder"><span>${esc(item.category.toUpperCase())}</span></div>`}
      <div class="gs-card-body">
        <div class="gs-card-topline">
          <span class="gs-card-type">${esc(item.category)}</span>
        </div>
        <div class="gs-card-title">${esc(item.title)}</div>
        <div class="gs-meta">${fmtBytes(item.sizeBytes)}${item.durationMs ? ` | ${fmtDur(item.durationMs)}` : ""}</div>
        ${showSlowStartHint ? `<div class="gs-card-caption">${gsStr("web_player_slow_start_hint")}</div>` : ""}
        ${renderCardProgress(item)}
        ${item.category === "music" ? `
          <div class="gs-music-row">
            <audio class="gs-audio-player" preload="none" src="${item.streamUrl}">
              Your browser does not support audio playback.
            </audio>
          </div>
        ` : ""}
        <div class="gs-card-actions">
          ${action}
          ${allowDownloads ? `<a class="${downloadBtnClass}" href="${item.downloadUrl}">${gsStr("web_action_download", "Download")}</a>` : ""}
        </div>
      </div>
    </article>`;
}

function attachMusicPlayers() {
  destroyMusicPlayers();
  document.querySelectorAll(".gs-audio-player").forEach((audio) => {
    const cardElement = audio.closest(".gs-card");
    const button = cardElement?.querySelector(".music-play-btn");
    const title = button?.dataset.title || cardElement?.querySelector(".gs-card-title")?.textContent || "Music";
    const player = typeof window.Plyr === "function"
      ? new window.Plyr(audio, {
          iconUrl: "/plyr.svg",
          controls: ["play", "progress", "current-time", "mute", "volume"],
          settings: [],
          storage: {
            enabled: false,
          },
          keyboard: {
            focused: false,
            global: false,
          },
        })
      : null;

    if (player) {
      state.musicPlayers.push(player);
    }

    const mediaElement = player?.media || audio;

    mediaElement.addEventListener("play", () => {
      document.querySelectorAll(".gs-audio-player").forEach((element) => {
        if (element !== audio) {
          element.pause();
        }
      });
      state.musicPlayers.forEach((other) => {
        if (other?.media && other.media !== audio) {
          other.pause();
        }
      });
      document.querySelectorAll(".music-play-btn").forEach((element) => {
        element.textContent = gsStr("common_play", "Play");
      });
      if (button) button.textContent = gsStr("common_pause", "Pause");
      setNowPlaying({
        type: "Music",
        title,
        element: mediaElement,
        route: location.pathname,
      });
    });

    mediaElement.addEventListener("pause", () => {
      if (mediaElement.ended) return;
      if (button) button.textContent = gsStr("common_play", "Play");
      clearNowPlaying(mediaElement);
    });

    mediaElement.addEventListener("ended", () => {
      if (button) button.textContent = gsStr("common_play", "Play");
      clearNowPlaying(mediaElement);
    });

    button?.addEventListener("click", () => {
      if (!mediaElement.paused) {
        mediaElement.pause();
        return;
      }
      if (player) {
        player.play().catch(() => {});
      } else {
        mediaElement.play().catch(() => {});
      }
    });
  });
}

async function pollCompat(id, item, options = {}) {
  cancelCompatPolling();
  const route = `/player/video/${id}`;
  const token = ++state.compatPollToken;
  const shouldStartPreparation = Boolean(options.startPreparation);
  let lastTraceKey = "";
  let lastTracedBucket = -1;

  /**
   * Adaptive polling interval based on current state.
   * - First 3 polls: 500ms (fast startup)
   * - Normal preparing: 1000ms
   * - Long-running (>20 polls or progress <50%): 1500ms
   * - Very long (>60 polls): 2000ms
   * - READY/FAILED/STALLED: stop polling
   */
  function getAdaptiveInterval(attempts, job) {
    if (job.status === "FAILED" || job.status === "STALLED" || job.status === "IDLE") return 0;
    if (attempts < 3) return 500;
    if (attempts > 60) return 2000;
    if (attempts > 20 || (job.progressPercent != null && job.progressPercent < 50 && attempts > 10)) return 1500;
    return 1000;
  }

  const applyCompatState = async (job) => {
    // 202 Accepted handling: The server might return an ErrorPayload while waiting for 
    // the HLS duration threshold (4-6s) to be met. We treat this as "Opening".
    const normalizedJob = {
      itemId: job.itemId || id,
      playbackMode: job.playbackMode || item.playbackMode,
      status: job.status || "ANALYZING", // Default to an active state if status is missing (202 response)
      message: job.message || "Preparing stream...",
      progressPercent: job.progressPercent,
      compatibilityComplete: job.compatibilityComplete || false,
      ready: job.ready || false,
      effectivePlaybackMode: job.effectivePlaybackMode,
      preparedMp4Url: job.preparedMp4Url,
      hlsUrl: job.hlsUrl,
      width: job.width,
      height: job.height,
      totalDurationMs: job.totalDurationMs
    };

    const canStartPlayback = shouldStartCompatibilityPlayback(item, normalizedJob);

    // Only trace compat_status on meaningful changes: state change, 5% bucket, or ready/failed
    const progressBucket = normalizedJob.progressPercent != null ? Math.floor(normalizedJob.progressPercent / 5) * 5 : null;
    const traceKey = `${normalizedJob.status}|${progressBucket ?? ""}|${canStartPlayback}|${normalizedJob.compatibilityComplete}`;
    if (traceKey !== lastTraceKey) {
      // Only log if status changed, bucket changed, or terminal state
      const bucketChanged = progressBucket !== null && progressBucket !== lastTracedBucket;
      const statusChanged = lastTraceKey === "" || traceKey.split("|")[0] !== lastTraceKey.split("|")[0];
      const isTerminal = normalizedJob.status === "READY" || normalizedJob.status === "FAILED" || normalizedJob.status === "STALLED";
      if (statusChanged || bucketChanged || isTerminal || canStartPlayback) {
        lastTraceKey = traceKey;
        lastTracedBucket = progressBucket ?? lastTracedBucket;
        debugTrace(
          "compat_status",
          `id=${id} status=${normalizedJob.status} progress=${normalizedJob.progressPercent ?? ""} ready=${canStartPlayback} complete=${normalizedJob.compatibilityComplete}`,
        );
      }
    }
    const nextItem = {
      ...item,
      playbackMode: normalizedJob.playbackMode || item.playbackMode,
      streamReady: canStartPlayback,
      effectivePlaybackMode: normalizedJob.effectivePlaybackMode || item.effectivePlaybackMode,
      compatibilityStatus: normalizedJob.status,
      compatibilityMessage: normalizedJob.message,
      compatibilityProgressPercent: normalizedJob.progressPercent,
      compatibilityComplete: normalizedJob.compatibilityComplete,
      preparedMp4Url: normalizedJob.preparedMp4Url || null,
      hlsUrl: normalizedJob.hlsUrl || null,
      width: normalizedJob.width || item.width,
      height: normalizedJob.height || item.height,
      totalDurationMs: normalizedJob.totalDurationMs || item.totalDurationMs || item.durationMs,
    };

    state.compatItem = nextItem;
    updateCompatElements(normalizedJob, canStartPlayback);

    if (canStartPlayback) {
      const mounted = await ensureCompatiblePlayerMounted(nextItem);
      if (mounted) {
        cancelCompatPolling();
        return true;
      }
      updateCompatElements(normalizedJob, false);
      return false;
    }
    if (normalizedJob.status === "FAILED" || normalizedJob.status === "STALLED") {
      updateCompatElements(normalizedJob, false);
      cancelCompatPolling();
      return true;
    }
    return false;
  };

  if (shouldStartPreparation) {
    try {
      const forceParam = options.forceCompat ? "?force=true" : "";
      debugTrace("compat_prepare_request", `id=${id} force=${!!options.forceCompat}`);
      const prepareJob = await api(`/api/compat/${id}/prepare${forceParam}`, { method: "POST" });
      if (token === state.compatPollToken && location.pathname === route && await applyCompatState(prepareJob)) {
        return;
      }
    } catch (_) {}
  } else if (!isPreparationActiveStatus(item.compatibilityStatus)) {
    updateCompatElements(
      {
        status: item.compatibilityStatus || "IDLE",
        message: item.compatibilityMessage || item.reason || "Waiting for preparation.",
        progressPercent: null,
        complete: item.compatibilityComplete || false,
      },
      false,
    );
    return;
  } else {
    updateCompatElements(
      {
        status: item.compatibilityStatus || "QUEUED",
        message: item.compatibilityMessage || "Preparing video for browser playback",
        progressPercent: null,
        complete: item.compatibilityComplete || false,
      },
      false,
    );
  }

  let attempts = 0;
  async function tick() {
    if (token !== state.compatPollToken || location.pathname !== route) return;
    attempts += 1;
    if (attempts > 600) return;

    let job;
    try {
      job = await api(`/api/compat/${id}`);
      if (token !== state.compatPollToken || location.pathname !== route) return;
      if (await applyCompatState(job)) {
        return;
      }
    } catch (_) {}

    const interval = getAdaptiveInterval(attempts, job || {});
    if (interval <= 0) return;
    state.compatPollTimer = setTimeout(tick, interval);
  }

  state.compatPollTimer = setTimeout(tick, 500);
}

async function startPreparation(id, forceCompat = false) {
  const currentItem = state.compatItem && state.compatItem.id === id
    ? state.compatItem
    : await api(`/api/item/${id}`);
  state.compatItem = currentItem;
  showCompatibilityWaitingStage({
    ...currentItem,
    compatibilityStatus: "QUEUED",
    compatibilityMessage: "Preparing video for browser playback",
    streamReady: false,
    compatibilityComplete: false,
  });
  pollCompat(id, currentItem, { startPreparation: true, forceCompat });
}

function forceDirectPlayback(item) {
  const video = document.getElementById("vPlayer");
  if (!video || !item.preparedMp4Url) return;
  
  destroyHls();
  if (state.plyr) {
    state.plyr.source = {
      type: "video",
      sources: [{ src: item.preparedMp4Url, type: "video/mp4" }]
    };
  } else {
    video.src = item.preparedMp4Url;
    video.load();
  }
}

function cancelCompatPolling() {
  state.compatPollToken += 1;
  if (state.compatPollTimer) {
    clearTimeout(state.compatPollTimer);
    state.compatPollTimer = null;
  }
}

function destroyPlyr() {
  if (state.plyr) {
    try {
      state.plyr.destroy();
    } catch (_) {}
  }
  state.plyr = null;
  state.plyrItemId = null;
}

function destroyHls() {
  if (state.hls) {
    try {
      state.hls.destroy();
    } catch (_) {}
  }
  state.hls = null;
  state.hlsItemId = null;
}

function destroyUppy() {
  if (state.uppy) {
    try {
      state.uppy.destroy();
    } catch (_) {}
  }
  state.uppy = null;
}

function queueUploadFiles(files) {
  const normalized = Array.from(files || []).filter(Boolean);
  if (normalized.length === 0) return;
  if (location.pathname === "/upload" && state.uppy) {
    addFilesToUppy(normalized);
    return;
  }
  state.pendingUploadFiles = normalized;
  if (location.pathname !== "/upload") {
    navigate("/upload");
  }
}

function addFilesToUppy(files) {
  if (!state.uppy || !files || files.length === 0) return;
  files.forEach((file) => {
    try {
      state.uppy.addFile({
        id: `${file.name}-${file.size}-${file.lastModified}-${Math.random().toString(36).slice(2)}`,
        name: file.name,
        type: file.type,
        data: file,
        source: "Local",
      });
    } catch (_) {}
  });
}

function describeUploadSelection(files) {
  if (!files || files.length === 0) {
    return gsStr("web_upload_selection_empty", "No files selected yet.");
  }
  if (files.length === 1) {
    return gsStr("web_upload_selection_single", "Ready to send %1$s", files[0].name);
  }
  return gsStr("web_upload_selection_multiple", "Ready to send %1$d files", files.length);
}

function getUploadDisplayFiles() {
  if (state.uppy) return state.uppy.getFiles();
  return state.pendingUploadFiles || [];
}

function destroyMusicPlayers() {
  state.musicPlayers.forEach((player) => {
    try {
      player.destroy();
    } catch (_) {}
  });
  state.musicPlayers = [];
}

function setNowPlaying(next) {
  state.nowPlaying = next;
  renderNowPlayingBar();
}

function clearNowPlaying(element) {
  if (state.nowPlaying?.element === element) {
    state.nowPlaying = null;
    renderNowPlayingBar();
  }
}

function renderNowPlayingBar() {
  const bar = document.getElementById("nowPlayingBar");
  if (!bar) return;
  if (!state.nowPlaying) {
    bar.className = "gs-now";
    bar.innerHTML = "";
    return;
  }

  bar.className = "gs-now is-visible";
  bar.innerHTML = `
    <div>
      <div class="gs-now-label">${gsStr("web_now_playing", "Now playing")}</div>
      <strong>${esc(state.nowPlaying.title)}</strong>
      <div class="gs-meta">${esc(state.nowPlaying.type)}</div>
    </div>
    <div class="gs-toolbar-actions">
      <button class="gs-btn gs-btn-sm" id="nowPlayingToggleBtn">${state.nowPlaying.element?.paused ? gsStr("common_resume", "Resume") : gsStr("common_pause", "Pause")}</button>
      ${state.nowPlaying.route ? `<a class="gs-btn gs-btn-sm" data-link href="${state.nowPlaying.route}">${gsStr("common_open", "Open")}</a>` : ""}
    </div>`;

  document.getElementById("nowPlayingToggleBtn")?.addEventListener("click", () => {
    const element = state.nowPlaying?.element;
    if (!element) return;
    if (element.paused) {
      element.play().catch(() => {});
    } else {
      element.pause();
    }
    renderNowPlayingBar();
  });
}

function skeletons(count) {
  return Array.from({ length: count }, () => `
    <article class="gs-card gs-skeleton">
      <div class="gs-skel gs-skel-img"></div>
      <div class="gs-card-body">
        <div class="gs-skel gs-skel-line" style="width:70%"></div>
        <div class="gs-skel gs-skel-line" style="width:44%"></div>
      </div>
    </article>
  `).join("");
}

function titleForPath(path) {
  switch (path) {
    case "/videos": return gsStr("web_cat_videos", "Videos");
    case "/photos": return gsStr("web_cat_photos", "Photos");
    case "/music": return gsStr("web_cat_music", "Music");
    case "/files": return gsStr("web_cat_files", "Files");
    default: return gsStr("web_nav_media", "Media");
  }
}

function fmtBytes(bytes) {
  if (!bytes) return `0 ${gsStr("common_unit_b", "B")}`;
  const units = [
    gsStr("common_unit_b", "B"),
    gsStr("common_unit_kb", "KB"),
    gsStr("common_unit_mb", "MB"),
    gsStr("common_unit_gb", "GB")
  ];
  let value = bytes;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value >= 100 || index === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[index]}`;
}

function fmtDur(ms) {
  const totalSeconds = Math.floor(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`
    : `${minutes}:${String(seconds).padStart(2, "0")}`;
}

function esc(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

boot();
async function renderUpload() {
  const bootstrap = state.bootstrap;
  const content = `
    <div class="gs-section">
      <div class="gs-section-head">
        <div>
          <h2>${gsStr("web_upload_title", "Send to Device")}</h2>
          <div class="gs-section-meta">${gsStr("web_upload_subtitle", "Upload files to the host device over the network.")}</div>
        </div>
      </div>
      
      <div class="gs-upload-zone" id="uploadZone">
        <div class="gs-upload-zone-inner">
          <div class="gs-upload-zone-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
          </div>
          <h3>${gsStr("web_upload_prompt_title", "Select files to send")}</h3>
          <p class="gs-desktop-only">${gsStr("web_upload_prompt_desktop", "Drag and drop here, or tap the button below")}</p>
          <p class="gs-mobile-only">${gsStr("web_upload_prompt_mobile", "Tap the button to select files from your library")}</p>
          <input id="nativeUploadInput" class="gs-upload-native-input" type="file" multiple>
          <div class="gs-upload-zone-actions">
            <button class="gs-btn gs-btn-accent gs-btn-block gs-upload-primary-btn" id="uppyBrowseBtn">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
              ${gsStr("web_upload_button_browse", "Browse Files")}
            </button>
            <button class="gs-btn gs-btn-block" id="uppyUploadBtn" disabled>${gsStr("web_upload_button_send", "Upload")}</button>
          </div>
          <div class="gs-upload-selection" id="uploadSelectionStatus">${gsStr("web_upload_selection_empty", "No files selected yet.")}</div>
          <div class="gs-upload-dashboard-wrap">
            <div id="uploadDashboard"></div>
          </div>
        </div>
      </div>

      <div class="gs-section" style="margin-top: 40px">
        <div class="gs-category-grid" style="grid-template-columns: repeat(auto-fit, minmax(300px, 1fr))">
          <div class="gs-category-card">
            <div class="gs-category-kicker">${gsStr("web_upload_target_kicker", "Target Device")}</div>
            <strong>${esc(bootstrap?.title || sessionTitle)}</strong>
            <div class="gs-category-meta" style="font-family: monospace; word-break: break-all; margin-top: 8px">
              ${esc(bootstrap?.sessionUrl || "Local connection")}
            </div>
            <div class="gs-category-meta" style="margin-top: 4px; font-size: 0.82rem">
              ${gsStr("web_upload_target_status", "Status: Connected and ready for transfers")}
            </div>
          </div>
          <div class="gs-category-card">
            <div class="gs-category-kicker">${gsStr("web_upload_how_kicker", "How it works")}</div>
            <strong>${gsStr("web_upload_how_title", "Secure Approval")}</strong>
            <div class="gs-category-meta">${gsStr("web_upload_how_body", "When you send a file, a notification will appear on the phone. The device owner must <strong>Accept</strong> for the transfer to begin.")}</div>
          </div>
        </div>
      </div>
    </div>
  `;

  shell(content);
  mountUploadDashboard();
}

function mountUploadDashboard() {
  const zone = document.getElementById("uploadZone");
  const dashboardTarget = document.getElementById("uploadDashboard");
  const browseBtn = document.getElementById("uppyBrowseBtn");
  const uploadBtn = document.getElementById("uppyUploadBtn");
  const nativeInput = document.getElementById("nativeUploadInput");
  const selectionStatus = document.getElementById("uploadSelectionStatus");
  if (!browseBtn || !uploadBtn || !nativeInput) return;

  const setSelectionStatus = (files) => {
    if (selectionStatus) {
      selectionStatus.textContent = describeUploadSelection(files);
    }
    if (dashboardTarget) {
      dashboardTarget.parentElement?.classList.toggle("has-files", Boolean(files && files.length > 0));
    }
  };

  const refreshFallbackToolbar = () => {
    uploadBtn.disabled = state.pendingUploadFiles.length === 0;
    setSelectionStatus(state.pendingUploadFiles);
  };

  if (!window.Uppy?.Uppy || !window.Uppy?.Dashboard || !dashboardTarget) {
    browseBtn.addEventListener("click", () => {
      nativeInput.value = "";
      nativeInput.click();
    });
    nativeInput.addEventListener("change", () => {
      state.pendingUploadFiles = Array.from(nativeInput.files || []);
      refreshFallbackToolbar();
    });
    uploadBtn.addEventListener("click", async () => {
      if (state.pendingUploadFiles.length === 0) return;
      uploadBtn.disabled = true;
      try {
        await handleFilesUpload(state.pendingUploadFiles);
        state.pendingUploadFiles = [];
        nativeInput.value = "";
      } finally {
        refreshFallbackToolbar();
      }
    });
    refreshFallbackToolbar();
    return;
  }

  const uppy = new window.Uppy.Uppy({
    autoProceed: false,
    allowMultipleUploadBatches: true,
  });
  state.uppy = uppy;

  uppy.use(window.Uppy.Dashboard, {
    inline: true,
    target: "#uploadDashboard",
    proudlyDisplayPoweredByUppy: false,
    hideUploadButton: true,
    hideRetryButton: true,
    hidePauseResumeButton: true,
    showProgressDetails: true,
    note: gsStr("web_upload_prompt_mobile", "Tap the button to select files from your library"),
    width: "100%",
    height: 420,
    doneButtonHandler: null,
    browserBackButtonClose: false,
  });

  const refreshUploadToolbar = () => {
    const files = uppy.getFiles();
    uploadBtn.disabled = files.length === 0;
    setSelectionStatus(files);
  };

  browseBtn.addEventListener("click", () => {
    nativeInput.value = "";
    nativeInput.click();
  });
  nativeInput.addEventListener("change", () => {
    const files = Array.from(nativeInput.files || []);
    if (files.length === 0) return;
    addFilesToUppy(files);
  });
  uploadBtn.addEventListener("click", () => {
    uppy.upload().catch(() => {});
  });

  uppy.addUploader((fileIDs) => {
    const selectedFiles = fileIDs
      .map((id) => uppy.getFile(id))
      .filter(Boolean);
    const files = selectedFiles
      .map((file) => file.data)
      .filter(Boolean);

    if (files.length === 0) return Promise.resolve();

    const startedAt = Date.now();
    selectedFiles.forEach((file) => {
      uppy.setFileState(file.id, {
        progress: {
          ...file.progress,
          uploadStarted: startedAt,
          uploadComplete: false,
          percentage: 0,
          bytesUploaded: 0,
          bytesTotal: file.size,
        },
      });
    });

    return handleFilesUpload(files, {
      onProgress: ({ percent, loaded, total }) => {
        selectedFiles.forEach((file) => {
          const ratio = total > 0 ? loaded / total : 0;
          uppy.setFileState(file.id, {
            progress: {
              ...file.progress,
              uploadStarted: startedAt,
              uploadComplete: false,
              percentage: percent,
              bytesUploaded: Math.min(file.size, Math.round(file.size * ratio)),
              bytesTotal: file.size,
            },
          });
        });
      },
      onSuccess: () => {
        selectedFiles.forEach((file) => {
          uppy.emit("upload-success", file, { status: 200, body: {} });
          uppy.setFileState(file.id, {
            progress: {
              ...file.progress,
              uploadStarted: startedAt,
              uploadComplete: true,
              percentage: 100,
              bytesUploaded: file.size,
              bytesTotal: file.size,
            },
          });
        });
        setTimeout(() => {
          fileIDs.forEach((id) => {
            try {
              uppy.removeFile(id);
            } catch (_) {}
          });
        }, 600);
      },
      onError: (error) => {
        selectedFiles.forEach((file) => {
          uppy.emit("upload-error", file, error);
        });
      },
    });
  });

  uppy.on("file-added", refreshUploadToolbar);
  uppy.on("file-removed", refreshUploadToolbar);
  uppy.on("complete", refreshUploadToolbar);
  uppy.on("upload-error", refreshUploadToolbar);
  refreshUploadToolbar();

  zone?.addEventListener("dragover", (e) => {
    e.preventDefault();
    zone.classList.add("is-active");
  });
  zone?.addEventListener("dragleave", () => zone.classList.remove("is-active"));
  zone?.addEventListener("drop", () => {
    zone.classList.remove("is-active");
    setSelectionStatus(getUploadDisplayFiles());
  });

  if (state.pendingUploadFiles.length > 0) {
    const queuedFiles = state.pendingUploadFiles;
    state.pendingUploadFiles = [];
    addFilesToUppy(queuedFiles);
  }
}

/**
 * Formats seconds into a human-readable string (H:MM:SS or M:SS).
 */
function formatTime(seconds) {
  if (isNaN(seconds) || seconds === Infinity) return "0:00";
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) {
    return `${h}:${m.toString().padStart(2, "0")}:${s.toString().padStart(2, "0")}`;
  }
  return `${m}:${s.toString().padStart(2, "0")}`;
}

/**
 * Sets up the scrubbing preview tooltip for the video player.
 */
function setupScrubbingPreviews(item, plyr) {
  if (!plyr || !plyr.elements || !plyr.elements.progress) return;
  const progress = plyr.elements.progress;
  const container = plyr.elements.container;

  let preview = container.querySelector(".gs-scrub-preview");
  if (!preview) {
    preview = document.createElement("div");
    preview.className = "gs-scrub-preview";
    preview.innerHTML = `
      <img src="" alt="">
      <div class="gs-scrub-preview-time">0:00</div>
    `;
    container.appendChild(preview);
  }

  const img = preview.querySelector("img");
  const timeLabel = preview.querySelector(".gs-scrub-preview-time");
  let lastFetchTime = 0;
  let currentTargetTimeMs = -1;

  const updatePreview = (e) => {
    const rect = progress.getBoundingClientRect();
    const percent = Math.max(0, Math.min(1, (e.pageX - rect.left) / rect.width));
    const duration = plyr.duration || 0;
    if (duration <= 0) return;

    const timeSec = percent * duration;
    const timeMs = Math.floor(timeSec * 1000);

    // Position the tooltip
    preview.style.left = `${percent * 100}%`;
    timeLabel.textContent = formatTime(timeSec);
    preview.classList.add("is-visible");

    // Throttled fetch (at most every 400ms) to avoid overloading the server/hardware
    const now = Date.now();
    if (now - lastFetchTime > 400 && timeMs !== currentTargetTimeMs) {
      lastFetchTime = now;
      currentTargetTimeMs = timeMs;
      // We use a temporary image to avoid flickering while loading
      const nextImg = new Image();
      nextImg.onload = () => {
        if (currentTargetTimeMs === timeMs) {
          img.src = nextImg.src;
          img.style.opacity = "1";
        }
      };
      nextImg.onerror = () => {
        // If the frame extraction fails, the server should have returned the poster
        // but if even that fails, we keep the previous frame or hide.
        if (currentTargetTimeMs === timeMs) {
           console.log("[DirectServe] Thumbnail fetch failed for timeMs=" + timeMs);
        }
      };
      nextImg.src = `/thumb/${item.id}?timeMs=${timeMs}`;
    }
  };

  const hidePreview = () => {
    preview.classList.remove("is-visible");
    currentTargetTimeMs = -1;
  };

  progress.addEventListener("mousemove", updatePreview);
  progress.addEventListener("mouseenter", updatePreview);
  progress.addEventListener("mouseleave", hidePreview);
  progress.addEventListener("touchstart", updatePreview, { passive: true });
  progress.addEventListener("touchend", hidePreview, { passive: true });
}
