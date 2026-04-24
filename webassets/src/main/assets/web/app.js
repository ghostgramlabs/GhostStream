const app = document.getElementById("app");
const sessionTitle = app.dataset.title || "DirectServe";
const sessionSubtitle = app.dataset.subtitle || "Local-only streaming";
const LIBRARY_BATCH_SIZE = 60;
const LIBRARY_SHOW_ALL_CONFIRM_THRESHOLD = 500;
const LIBRARY_BULK_FETCH_SIZE = 100;
const THUMBNAIL_MAX_CONCURRENT = 4;
const THUMBNAIL_PLACEHOLDER_SRC = "data:image/gif;base64,R0lGODlhAQABAAAAACwAAAAAAQABAAA=";

const state = {
  bootstrap: null,
  debugTracing: false,
  query: "",
  selected: new Set(),
  selectMode: false,
  libraryItems: [],
  libraryCategory: "media",
  libraryTitle: "",
  libraryTotalCount: 0,
  libraryHasMore: false,
  libraryLoadingMore: false,
  libraryShowingAll: false,
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
  compatDirectFallbackTimer: null,
  compatMountToken: 0,
  compatPlaybackFailures: {},
  compatProgressMemory: {},
  compatItem: null,
  pendingCompatSeekOffset: {},
  playerSourceLocks: {},
  lastReportedCapabilities: null,
  ratioLocked: {}, // Track locked ratios per itemId to prevent placeholder overwrite
  folderId: null,
  folderName: null,
  thumbObserver: null,
  thumbQueue: [],
  thumbActiveCount: 0,
  libraryCache: new Map(),
  liveSocket: null,
  liveHls: null,
  livePeer: null,
  liveRemoteStream: null,
  livePendingRemoteIce: [],
  liveViewerId: null,
  liveAnswerPollTimer: null,
  liveIcePollTimer: null,
  liveStatePollTimer: null,
  liveStatsTimer: null,
  liveStatsToken: 0,
  liveFitMode: "contain",
  quickTextTimer: null,
};

function libraryCacheKey(category, query, folderId) {
  return `${category}|${query || ""}|${folderId || ""}`;
}

function saveLibraryScroll() {
  if (!state.libraryCategory || !state.libraryItems.length) return;
  const key = libraryCacheKey(state.libraryCategory, state.query, state.folderId);
  state.libraryCache.set(key, {
    items: state.libraryItems.slice(),
    totalCount: state.libraryTotalCount,
    hasMore: state.libraryHasMore,
    scrollY: window.scrollY || window.pageYOffset || 0,
  });
}

const routes = {
  "/": () => {
    const e = state.bootstrap?.enabledCategories;
    if (e && !e.videos && !e.photos && !e.music && e.files) {
      renderLibrary("files", titleForPath("/files"));
    } else {
      renderLibrary("media", titleForPath("/"));
    }
  },
  "/login": () => renderLogin(),
  "/videos": () => {
    if (!isCategoryEnabled("videos")) { navigate("/", true); return; }
    renderLibrary("videos", titleForPath("/videos"));
  },
  "/photos": () => {
    if (!isCategoryEnabled("photos")) { navigate("/", true); return; }
    renderLibrary("photos", titleForPath("/photos"));
  },
  "/music": () => {
    if (!isCategoryEnabled("music")) { navigate("/", true); return; }
    renderLibrary("music", titleForPath("/music"));
  },
  "/files": () => {
    if (!isCategoryEnabled("files")) { navigate("/", true); return; }
    renderLibrary("files", titleForPath("/files"));
  },
  "/live": renderLiveScreen,
  "/quick-text": renderQuickText,
  "/folders": () => renderFolders(titleForPath("/folders")),
  "/upload": renderUpload,
};

function isCategoryEnabled(cat) {
  const e = state.bootstrap?.enabledCategories;
  if (!e) return true;
  return e[cat] !== false;
}

function isBrowserPreviewable(mimeType) {
  if (!mimeType) return false;
  return mimeType === "application/pdf" || mimeType.startsWith("text/");
}

function fileTypeLabel(mimeType, title) {
  const ext = title ? title.split(".").pop().toLowerCase() : "";
  if (!mimeType || mimeType === "application/octet-stream") {
    return ext && ext.length <= 5 ? ext.toUpperCase() : "FILE";
  }
  if (mimeType === "application/pdf") return "PDF";
  if (mimeType === "application/vnd.android.package-archive") return "APK";
  if (mimeType === "application/epub+zip") return "EPUB";
  if (mimeType === "application/zip" || mimeType.startsWith("application/x-zip")) return "ZIP";
  if (mimeType.includes("rar")) return "RAR";
  if (mimeType.includes("7z")) return "7Z";
  if (mimeType === "application/json") return "JSON";
  if (mimeType === "application/xml" || mimeType === "text/xml") return "XML";
  if (mimeType === "application/msword") return "DOC";
  if (mimeType.startsWith("application/vnd.openxmlformats-officedocument.spreadsheet")) return "XLSX";
  if (mimeType.startsWith("application/vnd.openxmlformats-officedocument.presentation")) return "PPTX";
  if (mimeType.startsWith("application/vnd.openxmlformats-officedocument")) return "DOCX";
  if (mimeType.startsWith("application/vnd.ms-excel")) return "XLS";
  if (mimeType.startsWith("application/vnd.ms-powerpoint")) return "PPT";
  if (mimeType.startsWith("application/vnd.oasis.opendocument")) return "ODT";
  if (mimeType.startsWith("font/")) return "FONT";
  if (mimeType.startsWith("text/")) {
    if (ext === "md") return "MD";
    if (ext === "csv") return "CSV";
    if (ext === "html" || ext === "htm") return "HTML";
    return "TXT";
  }
  return ext && ext.length <= 5 ? ext.toUpperCase() : "FILE";
}

const CATEGORY_ICONS = {
  videos: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg>`,
  photos: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>`,
  music: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>`,
  files: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg>`,
  media: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="7" width="20" height="15" rx="2" ry="2"/><polyline points="17 2 12 7 7 2"/></svg>`,
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
      throw new Error(gsStr("web_upload_denied", "Upload transfer was denied by the device owner."));
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
          reject(new Error(gsStr("web_upload_failed_status", "Upload failed (%1$d)", currentUploadXhr.status)));
        }
      };
      currentUploadXhr.onerror = () => reject(new Error(gsStr("web_upload_network_error", "Network error - please check your connection.")));
      currentUploadXhr.onabort = () => reject(new Error(gsStr("web_upload_cancelled", "Transfer cancelled.")));
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
  destroyLiveScreen();
  destroyQuickTextPolling();
  resetThumbnailLoader();
  const path = location.pathname;

  try {
    if (!state.bootstrap) {
      state.bootstrap = await api("/api/bootstrap");
      applyBootstrapUi();
      await reportClientCapabilities();
    }
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
    await (routes[path] || routes["/"])();
  } catch (error) {
    if (error.status === 401) {
      state.bootstrap = null;
      navigate("/login", true);
      return;
    }
    renderError(error.message || gsStr("web_error_load_failed", "Unable to load DirectServe."));
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
  if (isMediaPath(location.pathname)) saveLibraryScroll();
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
  return path === "/" || path === "/videos" || path === "/photos" || path === "/music" || path === "/folders" || path === "/files";
}

function isImmersiveMediaPath(path) {
  return path.startsWith("/player/video/") || path.startsWith("/photo/") || path.startsWith("/pdf/");
}

function shouldShowFloatingSendBar(path) {
  return path !== "/upload" && !isImmersiveMediaPath(path);
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
  const supportsMatroska = canPlayMimeType(video, "video/x-matroska") ||
    canPlayMimeType(video, 'video/x-matroska; codecs="avc1.64001F, mp4a.40.2"');
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
    supportsMatroska,
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

// Raw HLS-path check: preconditions only, no prepared-MP4 gate. Callable from
// inside shouldUseDirectCompatMp4 without recursing through the eligibility
// helpers (which themselves guard against prepared MP4).
function hasUsableHlsPath(item) {
  if (!item || !item.hlsUrl || !item.streamReady) return false;
  if (isAppleDevice() || isTvBrowser()) {
    return buildClientCapabilities().supportsHlsNatively === true;
  }
  if (isDesktopChromiumBrowser()) {
    return Boolean(
      window.Hls &&
        typeof window.Hls.isSupported === "function" &&
        window.Hls.isSupported(),
    );
  }
  return false;
}

/**
 * Returns true when the prepared compat MP4 is ready for direct <video src> playback.
 * This is the primary path for REMUX/TRANSCODE videos once the job reaches READY.
 * No hls.js and no MSE are involved — the browser plays the file natively, bypassing
 * the TFHD base-data-offset restriction that caused bufferAppendError via MSE/hls.js.
 */
function shouldUseDirectCompatMp4(item) {
  if (!item.preparedMp4Url || item.playbackMode === "DIRECT") return false;
  // Once the prepared file is finalized, prepared MP4 is the cheapest path
  // (progressive Range, no MSE overhead).
  if (item.compatibilityComplete) return true;
  // While the prepared .tmp is still growing, far seeks land beyond the
  // writer head and the browser snaps back. HLS handles seek-beyond-buffer
  // gracefully (managed segment refetch + worker CRITICAL re-seek), so when
  // an HLS path is also available for this client, prefer it over the
  // in-progress prepared MP4. Once the file finalizes, future opens take the
  // prepared path again on the next selection pass.
  if (hasUsableHlsPath(item)) return false;
  return true;
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
  const hasPreparedSource = Boolean(job?.preparedMp4Url || effectiveItem.preparedMp4Url);
  const hasHlsSource = Boolean(effectiveItem.hlsUrl) && (
    shouldUseNativeHlsPlayback(effectiveItem) ||
    shouldUseManagedHlsPlayback(effectiveItem)
  );
  if (hasPreparedSource || shouldUseDirectCompatMp4(effectiveItem)) return true;
  if (hasHlsSource) return true;
  if (effectiveStatus === "PLAYABLE_NOW" && (hasPreparedSource || hasHlsSource)) return true;
  if ((effectiveItem.compatibilityComplete || effectiveStatus === "READY") && hasPreparedSource) return true;
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
    return gsStr("web_player_needs_prepare", "This video needs preparation for browser playback.");
  }
  if (!streamLive) {
    return gsStr("web_player_opening", "Preparing for web playback...");
  }
  if (item.compatibilityComplete || item.compatibilityStatus === "READY") {
    return gsStr("web_player_ready", "Ready");
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
    return gsStr("web_player_needs_prepare_desc", "Prepare this video when you want to watch it in the browser.");
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
  const showFloatingSendBar = shouldShowFloatingSendBar(path);
  const immersive = isImmersiveMediaPath(path);
  document.body.classList.toggle("gs-is-immersive", immersive);

  // Sync header height for sticky search offsets
  if (!immersive) {
    setTimeout(() => {
      const header = document.querySelector(".gs-header");
      if (header) {
        const height = header.offsetHeight;
        document.documentElement.style.setProperty("--gs-header-height", `${height}px`);
      }
    }, 0);
  }

  const mediaSubnav = mediaActive
    ? `
      <div class="gs-media-subnav">
        <a class="gs-media-tab${path === "/" ? " on" : ""}" data-link href="/">${gsStr("web_nav_media", "Media")}</a>
        ${isCategoryEnabled("videos") ? `<a class="gs-media-tab${path === "/videos" ? " on" : ""}" data-link href="/videos">${gsStr("web_cat_videos", "Videos")}</a>` : ""}
        ${isCategoryEnabled("photos") ? `<a class="gs-media-tab${path === "/photos" ? " on" : ""}" data-link href="/photos">${gsStr("web_cat_photos", "Photos")}</a>` : ""}
        ${isCategoryEnabled("music") ? `<a class="gs-media-tab${path === "/music" ? " on" : ""}" data-link href="/music">${gsStr("web_cat_music", "Music")}</a>` : ""}
        ${bootstrap?.categories?.folders > 0 ? `<a class="gs-media-tab${path === "/folders" ? " on" : ""}" data-link href="/folders">${gsStr("web_folders_title", "Folders")}</a>` : ""}
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

  const headerHtml = immersive
    ? ""
    : `
      <header class="gs-header">
        <nav class="gs-nav">
          <a class="gs-logo" data-link href="/">
            <div class="gs-logo-mark"></div>
            <span>${esc(bootstrap?.title || sessionTitle)}</span>
          </a>
          <div class="gs-nav-links">
            <a class="gs-tab${mediaActive ? " on" : ""}" data-link href="/">${gsStr("web_nav_media", "Media")}</a>
            ${isCategoryEnabled("files") ? `<a class="gs-tab${path === "/files" ? " on" : ""}" data-link href="/files">${gsStr("web_nav_files", "Files")}</a>` : ""}
            <a class="gs-tab${path === "/live" ? " on" : ""}" data-link href="/live">${gsStr("web_live_title", "")}</a>
            <a class="gs-tab${path === "/quick-text" ? " on" : ""}" data-link href="/quick-text">${gsStr("web_quick_text_title", "")}</a>
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
      </header>
    `;

  app.innerHTML = `
    <div class="gs-shell">
      ${headerHtml}
      <main class="gs-main">${content}</main>
      ${showFloatingSendBar ? `
        <a class="gs-send-bar${state.nowPlaying ? " is-raised" : ""}" id="floatingSendBar" data-link href="/upload">
          <span class="gs-send-bar-icon" aria-hidden="true">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
          </span>
          <span class="gs-send-bar-copy">
            <strong>${gsStr("web_send_floating_title", "Send to device")}</strong>
            <span>${gsStr("web_send_floating_subtitle", "Upload photos, videos, and files")}</span>
          </span>
        </a>
      ` : ""}
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
  const e = bootstrap?.enabledCategories || { videos: true, photos: true, music: true, files: true };

  const allCategories = [
    { key: "videos", label: gsStr("web_cat_videos", "Videos"), count: bootstrap.categories.videos, href: "/videos" },
    { key: "photos", label: gsStr("web_cat_photos", "Photos"), count: bootstrap.categories.photos, href: "/photos" },
    { key: "music", label: gsStr("web_cat_music", "Music"), count: bootstrap.categories.music, href: "/music" },
    { key: "files", label: gsStr("web_cat_files", "Files"), count: bootstrap.categories.files, href: "/files" },
  ];
  const categories = allCategories.filter(c => e[c.key]);
  const mediaCategories = categories.filter(c => c.key !== "files");
  const total = categories.reduce((sum, c) => sum + c.count, 0);

  const hasAnyContent = total > 0;
  const filesEnabled = e.files;

  shell(`
    <section class="gs-hero">
      <div class="gs-hero-copy">
        <span class="gs-eyebrow">${gsStr("web_hero_eyebrow", "DirectServe session")}</span>
        <h1>${gsStr("web_hero_title", "Media")}</h1>
        <p>${gsStr("web_hero_desc1", "Watch videos, open photos, and play music from this share.")}
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
          ${filesEnabled ? `<a class="gs-btn gs-btn-accent" data-link href="/files">${gsStr("web_nav_files", "Files")}</a>` : ""}
          <a class="gs-btn" data-link href="/upload">${gsStr("web_nav_send", "Send")}</a>
        </div>
      </div>
    </section>

    ${categories.length > 0 ? `
      <section class="gs-category-grid">
        ${categories.map((category) => `
          <a class="gs-category-card gs-category-card--icon" data-link href="${category.href}">
            <span class="gs-category-icon">${CATEGORY_ICONS[category.key] || CATEGORY_ICONS.media}</span>
            <span class="gs-category-kicker">${category.label}</span>
            <strong>${category.count}</strong>
            <span class="gs-category-meta">${gsStr("web_media_open_category", "Open %1$s", category.label)}</span>
          </a>
        `).join("")}
      </section>
    ` : `<section class="gs-section"><div class="gs-empty">${gsStr("web_media_empty", "No media is shared right now.")}</div></section>`}

    ${bootstrap.recent.length ? `
      <section class="gs-section">
        <div class="gs-section-head">
          <h2>${gsStr("web_recent_title", "Recently added")}</h2>
          <span class="gs-section-meta">${bootstrap.recent.length} ${gsStr("web_recent_meta", "highlighted items")}</span>
        </div>
        <div class="gs-grid">${bootstrap.recent.map((item) => card(item)).join("")}</div>
      </section>
    ` : (hasAnyContent ? "" : `<section class="gs-section"><div class="gs-empty">${gsStr("web_media_empty", "No media is shared right now.")}</div></section>`)}
  `);
  initDeferredThumbnails(app);
}

async function renderLibrary(category, title) {
  state.libraryCategory = category;
  state.libraryTitle = title;
  
  // Extract folderId if present
  const params = new URLSearchParams(location.search);
  const folderId = params.get("folderId");
  const folderName = params.get("folderName");
  state.folderId = folderId;
  state.folderName = folderName;

  const allowDownloads = !state.bootstrap?.preventDownload;
  shell(`
    <section class="gs-section">
      ${folderId ? `
        <nav class="gs-breadcrumb">
          <a class="gs-breadcrumb-item" data-link href="/folders">${gsStr("web_folders_title", "Folders")}</a>
          <span class="gs-breadcrumb-sep">/</span>
          <span class="gs-breadcrumb-item on">${esc(folderName || gsStr("web_all_media"))}</span>
        </nav>
      ` : ""}
      <div class="gs-section-head">
        <h2>${esc(title)}</h2>
        <span class="gs-section-meta">${allowDownloads ? gsStr("web_library_desc_download") : gsStr("web_library_desc_browse")}</span>
      </div>
      <div class="gs-control-card sticky-search">
        <div class="gs-toolbar">
          <input class="gs-search" id="libSearch" placeholder="${gsStr("web_search_placeholder")}" value="${esc(state.query)}">
          <div class="gs-toolbar-actions">
            <button class="gs-btn" id="selectBtn">${state.selectMode ? gsStr("web_status_selection_on") : gsStr("web_btn_select_files")}</button>
            ${allowDownloads ? `
              <button class="gs-btn gs-btn-download" id="downloadAllBtn">${gsStr("web_btn_download_all", "Download all")}</button>
              <button class="gs-btn gs-btn-download" id="downloadAllZipBtn" title="${gsStr("web_action_download_zip", "Download as ZIP")}">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
              </button>
            ` : ""}
          </div>
        </div>
        <div class="gs-select-bar${state.selectMode ? " is-visible" : ""}" id="selectBar">
          <span id="selectCount">${gsStr("web_selection_count", "0 selected", 0)}</span>
          <div class="gs-toolbar-actions">
            <button class="gs-btn gs-btn-sm" id="selectAllBtn">${gsStr("web_btn_select_all")}</button>
            <button class="gs-btn gs-btn-sm" id="clearSelectBtn">${gsStr("web_btn_clear_selection")}</button>
            ${allowDownloads ? `
              <button class="gs-btn gs-btn-accent gs-btn-sm" id="downloadSelectedBtn">${gsStr("web_btn_download_selected", "Download selected")}</button>
              <button class="gs-btn gs-btn-accent gs-btn-sm" id="downloadSelectedZipBtn" title="${gsStr("web_action_download_zip", "Download as ZIP")}">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
              </button>
            ` : ""}
          </div>
        </div>
      </div>
      <div class="gs-grid" id="grid">${skeletons(6)}</div>
      <div class="gs-library-footer" id="libraryFooter"></div>
    </section>
  `);

  document.getElementById("libSearch")?.addEventListener("input", (event) => {
    state.query = event.target.value;
    clearTimeout(state.searchTimer);
    state.searchTimer = setTimeout(() => {
      state.libraryCache.delete(libraryCacheKey(category, state.query, folderId));
      renderLibrary(category, title);
    }, 180);
  });

  const cacheKey = libraryCacheKey(category, state.query, folderId);
  const cached = state.libraryCache.get(cacheKey);
  if (cached && cached.items.length) {
    state.libraryItems = cached.items.slice();
    state.libraryTotalCount = cached.totalCount;
    state.libraryHasMore = cached.hasMore;
    state.libraryLoadingMore = false;
    renderLibraryGrid();
    bindLibraryControls();
    requestAnimationFrame(() => window.scrollTo(0, cached.scrollY || 0));
    return;
  }

  const page = await fetchLibraryPage(category, state.query, 0, LIBRARY_BATCH_SIZE, folderId);
  state.libraryItems = page.items;
  state.libraryTotalCount = page.totalCount;
  state.libraryHasMore = page.hasMore;
  state.libraryLoadingMore = false;
  renderLibraryGrid();
  bindLibraryControls();
}

async function renderFolders(title) {
  state.libraryCategory = "folders";
  state.libraryTitle = title;
  shell(`
    <section class="gs-section">
      <div class="gs-section-head">
        <h2>${esc(title)}</h2>
        <span class="gs-section-meta">${gsStr("web_library_desc_browse")}</span>
      </div>
      <div class="gs-control-card sticky-search">
        <div class="gs-toolbar">
          <input class="gs-search" id="folderSearch" placeholder="${gsStr("web_search_placeholder")}" value="${esc(state.query)}">
        </div>
      </div>
      <div class="gs-grid" id="grid">${skeletons(6)}</div>
    </section>
  `);

  document.getElementById("folderSearch")?.addEventListener("input", (event) => {
    state.query = event.target.value;
    clearTimeout(state.searchTimer);
    state.searchTimer = setTimeout(() => renderFolders(title), 180);
  });

  const folders = await api(`/api/folders?q=${encodeURIComponent(state.query)}`);
  const grid = document.getElementById("grid");
  if (!grid) return;

  if (!folders.length) {
    grid.innerHTML = `<div class="gs-empty">${gsStr("web_library_empty")}</div>`;
    return;
  }

  grid.innerHTML = folders.map(folder => {
    const isSelected = state.selected.has(folder.id);
    const item = { id: folder.id, title: folder.displayName, category: "folder" };
    return `
      <div class="gs-folder-card${state.selectMode ? " gs-card-selectable" : ""}${isSelected ? " is-selected" : ""}" data-select-card="${folder.id}">
        ${state.selectMode ? `<button class="gs-card-toggle is-visible" data-select-toggle="${folder.id}">${isSelected ? "Selected" : "Select"}</button>` : ""}
        <a class="gs-folder-link" data-link href="/?folderId=${encodeURIComponent(folder.id)}&folderName=${encodeURIComponent(folder.displayName)}">
          <div class="gs-folder-icon">
            <svg fill="currentColor" viewBox="0 0 24 24"><path d="M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></svg>
          </div>
          <div class="gs-folder-info">
            <h3>${esc(folder.displayName)}</h3>
            <span class="gs-meta">${gsStr("web_folder_items", "%1$d items", folder.fileCount)}</span>
          </div>
        </a>
      </div>
    `;
  }).join("");
  
  if (state.selectMode) {
    bindSelectableCards();
  }
}

function resetThumbnailLoader() {
  if (state.thumbObserver) {
    state.thumbObserver.disconnect();
    state.thumbObserver = null;
  }
  state.thumbQueue = [];
  state.thumbActiveCount = 0;
}

function initDeferredThumbnails(root = document) {
  const thumbnails = Array.from(root.querySelectorAll("img.gs-card-img[data-thumb-src]"));
  if (!thumbnails.length) return;

  if ("IntersectionObserver" in window) {
    if (!state.thumbObserver) {
      state.thumbObserver = new IntersectionObserver((entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          state.thumbObserver?.unobserve(entry.target);
          queueThumbnailLoad(entry.target);
        });
      }, { rootMargin: "300px 0px" });
    }
    thumbnails.forEach((thumbnail) => state.thumbObserver.observe(thumbnail));
    return;
  }

  thumbnails.forEach(queueThumbnailLoad);
}

function queueThumbnailLoad(img) {
  if (!(img instanceof HTMLImageElement)) return;
  if (!img.dataset.thumbSrc) return;
  if (img.dataset.thumbState === "queued" || img.dataset.thumbState === "loading" || img.dataset.thumbState === "done") return;
  img.dataset.thumbState = "queued";
  state.thumbQueue.push(img);
  drainThumbnailQueue();
}

function drainThumbnailQueue() {
  while (state.thumbActiveCount < THUMBNAIL_MAX_CONCURRENT && state.thumbQueue.length) {
    const next = state.thumbQueue.shift();
    if (!(next instanceof HTMLImageElement) || !next.isConnected || !next.dataset.thumbSrc) {
      continue;
    }
    startThumbnailLoad(next);
  }
}

function startThumbnailLoad(img) {
  state.thumbActiveCount += 1;
  img.dataset.thumbState = "loading";

  const finalize = (stateName) => {
    img.dataset.thumbState = stateName;
    state.thumbActiveCount = Math.max(0, state.thumbActiveCount - 1);
    drainThumbnailQueue();
  };

  const handleLoad = () => {
    img.removeEventListener("load", handleLoad);
    img.removeEventListener("error", handleError);
    finalize("done");
  };

  const handleError = () => {
    img.removeEventListener("load", handleLoad);
    img.removeEventListener("error", handleError);
    finalize("error");
  };

  img.addEventListener("load", handleLoad);
  img.addEventListener("error", handleError);
  img.src = img.dataset.thumbSrc;
}

async function fetchLibraryPage(category, query, offset, limit, folderId) {
  const qStr = query ? `&q=${encodeURIComponent(query)}` : "";
  const fStr = folderId ? `&folderId=${encodeURIComponent(folderId)}` : "";
  const response = await api(
    `/api/items?category=${encodeURIComponent(category)}${qStr}${fStr}&offset=${offset}&limit=${limit}`,
  );
  return {
    items: Array.isArray(response?.items) ? response.items : [],
    totalCount: Number.isFinite(response?.totalCount) ? response.totalCount : 0,
    hasMore: Boolean(response?.hasMore),
  };
}

async function fetchAllLibraryItems(category, query, folderId) {
  const items = [];
  let offset = 0;
  let hasMore = true;
  while (hasMore) {
    const page = await fetchLibraryPage(category, query, offset, LIBRARY_BULK_FETCH_SIZE, folderId);
    items.push(...page.items);
    offset += page.items.length;
    hasMore = page.hasMore && page.items.length > 0;
  }
  return items;
}

async function loadMoreLibraryItems() {
  if (state.libraryLoadingMore || !state.libraryHasMore) return;
  state.libraryLoadingMore = true;
  renderLibraryGrid();
  try {
    const page = await fetchLibraryPage(
      state.libraryCategory,
      state.query,
      state.libraryItems.length,
      LIBRARY_BATCH_SIZE,
      state.folderId
    );
    state.libraryItems = state.libraryItems.concat(page.items);
    state.libraryTotalCount = page.totalCount;
    state.libraryHasMore = page.hasMore;
  } finally {
    state.libraryLoadingMore = false;
    renderLibraryGrid();
  }
}

async function loadAllLibraryItems() {
  if (state.libraryLoadingMore || !state.libraryHasMore) return;
  const remaining = Math.max(0, (state.libraryTotalCount || 0) - state.libraryItems.length);
  if (remaining >= LIBRARY_SHOW_ALL_CONFIRM_THRESHOLD) {
    const message = gsStr(
      "web_show_all_confirm",
      "Show all %1$d items? Large libraries may take a moment to render.",
      remaining,
    );
    if (!window.confirm(message)) return;
  }
  state.libraryLoadingMore = true;
  state.libraryShowingAll = true;
  renderLibraryGrid();
  try {
    while (state.libraryHasMore) {
      const page = await fetchLibraryPage(
        state.libraryCategory,
        state.query,
        state.libraryItems.length,
        LIBRARY_BATCH_SIZE,
        state.folderId,
      );
      if (!page.items.length) break;
      state.libraryItems = state.libraryItems.concat(page.items);
      state.libraryTotalCount = page.totalCount;
      state.libraryHasMore = page.hasMore;
    }
  } finally {
    state.libraryLoadingMore = false;
    state.libraryShowingAll = false;
    renderLibraryGrid();
  }
}

function bindLibraryControls() {
  document.getElementById("selectBtn")?.addEventListener("click", () => {
    state.selectMode = !state.selectMode;
    if (!state.selectMode) {
      state.selected.clear();
    }
    renderLibrary(state.libraryCategory, state.libraryTitle);
  });

  document.getElementById("downloadAllBtn")?.addEventListener("click", async () => {
    const items = state.libraryHasMore
      ? await fetchAllLibraryItems(state.libraryCategory, state.query, state.folderId)
      : state.libraryItems;
    downloadItems(items);
  });

  document.getElementById("selectAllBtn")?.addEventListener("click", () => {
    // Select only what is currently loaded to avoid massive background fetches.
    // The user can load more if they need more.
    state.libraryItems.forEach((item) => state.selected.add(item.id));
    updateSelectionUi();
  });

  document.getElementById("clearSelectBtn")?.addEventListener("click", () => {
    state.selected.clear();
    updateSelectionUi();
  });

  document.getElementById("downloadSelectedBtn")?.addEventListener("click", () => {
    // Only download items that we have metadata for (currently loaded in libraryItems).
    const selectedItems = state.libraryItems.filter((item) => state.selected.has(item.id));
    if (selectedItems.length === 0 && state.selected.size > 0) {
      alert(gsStr("web_error_selection_not_loaded", "Selected items are no longer in view. Please re-select or load more."));
      return;
    }
    downloadItems(selectedItems);
  });

  document.getElementById("downloadSelectedZipBtn")?.addEventListener("click", () => {
    if (state.selected.size === 0) return;
    // ZIP download only needs IDs, which we already have in state.selected.
    // This avoids the massive "fetch all" bottleneck.
    downloadZip(Array.from(state.selected).map(id => ({ id })));
  });

  document.getElementById("downloadAllZipBtn")?.addEventListener("click", () => {
    // Highly efficient bulk ZIP using server-side filtering
    downloadZip([], {
      category: state.libraryCategory,
      query: state.query,
      folderId: state.folderId
    });
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

function renderLibraryGrid() {
  const grid = document.getElementById("grid");
  const footer = document.getElementById("libraryFooter");
  if (!grid || !footer) return;

  if (!state.libraryItems.length) {
    grid.innerHTML = `<div class="gs-empty">${gsStr("web_library_empty")}</div>`;
    footer.innerHTML = "";
    return;
  }

  grid.innerHTML = state.libraryItems.map((item) => card(item, true)).join("");
  const showingCount = gsStr(
    "web_library_showing_count",
    "Showing %1$d of %2$d",
    state.libraryItems.length,
    state.libraryTotalCount,
  );
  const loadMoreLabel = state.libraryLoadingMore && !state.libraryShowingAll
    ? gsStr("web_player_status_opening", "Preparing")
    : gsStr("web_btn_load_more", "Load more");
  const showAllLabel = state.libraryShowingAll
    ? gsStr("web_btn_showing_all", "Loading all…")
    : gsStr("web_btn_show_all", "Show all");
  footer.innerHTML = state.libraryHasMore
    ? `
      <span class="gs-meta">${showingCount}</span>
      <div class="gs-toolbar-actions">
        <button class="gs-btn" id="loadMoreBtn" ${state.libraryLoadingMore ? "disabled" : ""}>${loadMoreLabel}</button>
        <button class="gs-btn" id="showAllBtn" ${state.libraryLoadingMore ? "disabled" : ""}>${showAllLabel}</button>
      </div>
    `
    : `<span class="gs-meta">${showingCount}</span>`;

  attachMusicPlayers();
  initDeferredThumbnails(grid);
  bindSelectableCards();
  updateSelectionUi();
  document.getElementById("loadMoreBtn")?.addEventListener("click", loadMoreLibraryItems);
  document.getElementById("showAllBtn")?.addEventListener("click", loadAllLibraryItems);
}

function downloadItems(items) {
  if (!items.length) return;
  if (state.bootstrap?.preventDownload) {
    alert(gsStr("web_error_downloads_disabled", "Downloads are disabled by the device owner."));
    return;
  }

  if (items.length > 1) {
    showToast(gsStr("web_download_started_hint", "Batch download started. Large files may take a moment to appear."));
  }

  // Restore "Usual Download": Sequential individual downloads.
  // We use a small delay between triggers to encourage the browser to allow them.
  items.filter((item) => item.downloadUrl).forEach((item, index) => {
    setTimeout(() => {
      const anchor = document.createElement("a");
      anchor.href = item.downloadUrl;
      anchor.download = item.title || "";
      anchor.style.display = "none";
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
    }, index * 400); // 400ms is safer for sequential triggers
  });
}

function downloadZip(items, filters = null) {
  if (state.bootstrap?.preventDownload) {
    alert(gsStr("web_error_downloads_disabled", "Downloads are disabled by the device owner."));
    return;
  }

  showToast(gsStr("web_download_started_hint", "Batch download started. Large files may take a moment to appear."));

  const anchor = document.createElement("a");
  let url = "/api/download/zip?";
  
  if (filters) {
    const params = new URLSearchParams();
    if (filters.category) params.set("category", filters.category);
    if (filters.query) params.set("query", filters.query);
    if (filters.folderId) params.set("folderId", filters.folderId);
    url += params.toString();
  } else {
    const ids = items.map(item => item.id).join(",");
    url += `ids=${encodeURIComponent(ids)}`;
  }

  anchor.href = url;
  anchor.style.display = "none";
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
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
            <button class="gs-btn gs-btn-sm" type="button" id="fullscreenBtn">${gsStr("web_btn_fullscreen", "Full screen")}</button>
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

function parseRatioString(ratio) {
  if (!ratio || typeof ratio !== "string" || !ratio.includes(":")) return null;
  const [widthText, heightText] = ratio.split(":");
  const width = Number(widthText);
  const height = Number(heightText);
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return null;
  }
  return { width, height };
}

function preferredVideoRatio(item) {
  if (item?.width && item?.height) {
    return `${item.width}:${item.height}`;
  }
  return state.ratioLocked[item?.id] || "16:9";
}

function applyAspectRatioLayout(video, item, ratio = preferredVideoRatio(item)) {
  if (!video || !item?.id) return;
  const parsed = parseRatioString(ratio);
  if (!parsed) return;
  const { width, height } = parsed;
  const ratioCss = `${width} / ${height}`;
  const wrap = video.closest(".gs-video-wrap");
  if (wrap) {
    wrap.style.aspectRatio = ratioCss;
    wrap.classList.toggle("is-portrait", height > width);
  }
  video.style.aspectRatio = ratioCss;
  if (state.plyr && state.plyrItemId === item.id) {
    state.plyr.ratio = `${width}:${height}`;
  }
}

function isIosDevice() {
  const ua = navigator.userAgent || "";
  if (/iPad|iPhone|iPod/.test(ua)) return true;
  // iPadOS 13+ reports as Mac but has touch
  return ua.includes("Mac") && "ontouchend" in document;
}

function toggleVideoFullscreen(video) {
  if (!video) return;
  const wrap = video.closest(".gs-video-wrap");
  // iOS Safari only supports fullscreen on <video>, not on div containers.
  // Prefer webkitEnterFullscreen before Plyr / requestFullscreen on iOS.
  if (isIosDevice()) {
    const mediaEl = state.plyr?.media || video;
    if (mediaEl.webkitDisplayingFullscreen && typeof mediaEl.webkitExitFullscreen === "function") {
      try { mediaEl.webkitExitFullscreen(); return; } catch (_) {}
    }
    if (typeof mediaEl.webkitEnterFullscreen === "function") {
      try { mediaEl.webkitEnterFullscreen(); return; } catch (_) {}
    }
  }
  if (state.plyr?.fullscreen) {
    state.plyr.fullscreen.toggle();
    return;
  }
  if (document.fullscreenElement && document.exitFullscreen) {
    document.exitFullscreen().catch?.(() => {});
    return;
  }
  if (typeof video.webkitEnterFullscreen === "function") {
    try {
      video.webkitEnterFullscreen();
      return;
    } catch (_) {}
  }
  const fullscreenTarget = wrap || video;
  if (fullscreenTarget.requestFullscreen) {
    fullscreenTarget.requestFullscreen().catch?.(() => {});
    return;
  }
  if (fullscreenTarget.webkitRequestFullscreen) {
    fullscreenTarget.webkitRequestFullscreen();
  }
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
  const initialRatio = preferredVideoRatio(item);
  const parsedRatio = parseRatioString(initialRatio);
  const portraitClass = parsedRatio && parsedRatio.height > parsedRatio.width ? " is-portrait" : "";
  const aspectRatioStyle = parsedRatio ? ` style="aspect-ratio:${parsedRatio.width} / ${parsedRatio.height};"` : "";

  return `
    <div class="gs-video-wrap${portraitClass}"${aspectRatioStyle}>
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
  const pendingSeekOffset = state.pendingCompatSeekOffset[item.id];
  
  // Aspect Ratio Reset: clear previous ratio to prevent portrait/landscape leakage
  const playerStructural = video.closest(".gs-player");
  const wrapStructural = video.closest(".gs-video-wrap");
  if (playerStructural) playerStructural.style.aspectRatio = "";
  if (wrapStructural) wrapStructural.style.aspectRatio = "";
  video.style.aspectRatio = "";
  applyAspectRatioLayout(video, item);

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

  if (Number.isFinite(pendingSeekOffset) && pendingSeekOffset >= 0) {
    video.addEventListener("loadedmetadata", () => {
      const targetTime = state.pendingCompatSeekOffset[item.id];
      if (!Number.isFinite(targetTime)) return;
      delete state.pendingCompatSeekOffset[item.id];
      try {
        video.currentTime = Math.max(0, Math.min(targetTime, video.duration || targetTime));
        debugTrace("video_seek_resume", `id=${item.id} offset=${targetTime.toFixed(1)}s source=${selectedSource.kind}`);
      } catch (_) {}
      if (options.autoplay) {
        video.play().catch(() => {});
      }
    }, { once: true });
  }

  if (!useNativePlayer && typeof window.Plyr === "function") {
    const plyrOptions = {
      iconUrl: "/plyr.svg",
      fullscreen: { enabled: true, iosNative: true },
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
      applyAspectRatioLayout(video, item, state.ratioLocked[item.id]);
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

      // Force start at segment 0. Our growing playlists are served as
      // EXT-X-PLAYLIST-TYPE:EVENT while transcoding, and hls.js treats EVENT
      // playlists as live-edge by default — so it would jump to the latest
      // segment (e.g. segment 10) and MSE would fail to append it because the
      // base decode time is way past the current video.currentTime of 0.
      startPosition: 0,

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

    const switchToPreparedMp4 = (currentItem) => {
      if (!currentItem?.preparedMp4Url) return false;
      if (state.compatDirectFallbackTimer) {
        clearTimeout(state.compatDirectFallbackTimer);
        state.compatDirectFallbackTimer = null;
      }
      destroyHls();
      state.compatItem = currentItem;
      state.playerSourceLocks[item.id] = {
        kind: "prepared_mp4",
        url: currentItem.preparedMp4Url,
        mimeType: "video/mp4",
      };
      video.dataset.sourceType = "prepared_mp4";
      video.dataset.sourceUrl = currentItem.preparedMp4Url;
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
      return true;
    };

    const waitForPreparedMp4Fallback = (seedItem) => {
      if (state.compatDirectFallbackTimer) {
        clearTimeout(state.compatDirectFallbackTimer);
        state.compatDirectFallbackTimer = null;
      }
      const startedAt = Date.now();
      const maxWaitMs = 2 * 60 * 1000;
      debugTrace("hls_error_direct_mp4_fallback_wait", `id=${item.id}`);
      showHlsError(gsStr("web_player_finalizing_desc", "Almost ready. Completing the browser-compatible stream."));

      const tick = async () => {
        if (location.pathname !== `/player/video/${item.id}`) return;
        try {
          const job = await api(`/api/compat/${item.id}`);
          const complete = Boolean(job.complete || job.compatibilityComplete || job.status === "READY");
          const nextItem = {
            ...seedItem,
            streamReady: Boolean(job.ready),
            effectivePlaybackMode: job.effectivePlaybackMode || seedItem.effectivePlaybackMode,
            compatibilityStatus: job.status || seedItem.compatibilityStatus,
            compatibilityMessage: job.message || seedItem.compatibilityMessage,
            compatibilityProgressPercent: job.progressPercent ?? seedItem.compatibilityProgressPercent,
            compatibilityComplete: complete,
            preparedMp4Url: job.preparedMp4Url || seedItem.preparedMp4Url || null,
            hlsUrl: job.hlsUrl || seedItem.hlsUrl || null,
            width: job.width || seedItem.width,
            height: job.height || seedItem.height,
            totalDurationMs: job.totalDurationMs || seedItem.totalDurationMs || seedItem.durationMs,
          };
          state.compatItem = nextItem;
          if (nextItem.preparedMp4Url && nextItem.compatibilityComplete === true) {
            debugTrace("hls_error_direct_mp4_fallback_ready", `id=${item.id} url=${nextItem.preparedMp4Url}`);
            clearVideoError();
            switchToPreparedMp4(nextItem);
            return;
          }
          if (job.status === "FAILED" || job.status === "STALLED") {
            showHlsError(
              state.bootstrap?.preventDownload
                ? "This browser could not decode the video stream."
                : gsStr("web_error_video_decode", "This browser could not decode the video stream. Try downloading the original file."),
            );
            return;
          }
        } catch (_) {}

        if (Date.now() - startedAt < maxWaitMs) {
          state.compatDirectFallbackTimer = setTimeout(tick, 1000);
          return;
        }
        showHlsError("This video is still opening. Try again in a moment.");
      };

      state.compatDirectFallbackTimer = setTimeout(tick, 1000);
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
            // If the server has already finalized the prepared MP4, switch to direct
            // playback immediately: no HLS/MSE involved, so no TFHD or codec issues.
            // We use state.compatItem to ensure we have the latest state from polling.
            const currentItem = state.compatItem || item;
            if (currentItem.preparedMp4Url && currentItem.compatibilityComplete === true) {
              debugTrace("prepared_asset_reused", `id=${item.id} asset=${currentItem.preparedMp4Url.slice(currentItem.preparedMp4Url.lastIndexOf('/') + 1)}`);
              debugTrace("hls_error_direct_mp4_fallback", `id=${item.id} url=${currentItem.preparedMp4Url}`);
              switchToPreparedMp4(currentItem);
              return;
            }
            if (currentItem.preparedMp4Url) {
              debugTrace(
                "hls_error_direct_mp4_fallback_deferred",
                `id=${item.id} status=${currentItem.compatibilityStatus || ""} complete=${currentItem.compatibilityComplete}`,
              );
              waitForPreparedMp4Fallback(currentItem);
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

  const fullscreenBtn = document.getElementById("fullscreenBtn");
  fullscreenBtn?.addEventListener("click", () => toggleVideoFullscreen(video));
  video.addEventListener("dblclick", () => toggleVideoFullscreen(video));

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
        toggleVideoFullscreen(video);
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
        applyAspectRatioLayout(video, item, state.ratioLocked[item.id]);
        return;
    }

    if (metadataRatio) {
        state.ratioLocked[item.id] = metadataRatio;
        debugTrace("ratio_locked_authoritative", `id=${item.id} ratio=${metadataRatio}`);
        applyAspectRatioLayout(video, item, metadataRatio);
        return;
    }

    if (w && h && !isPlaceholder && !isDefaultUnstretched) {
        const ratioStr = `${w}:${h}`;
        state.ratioLocked[item.id] = ratioStr;
        debugTrace("ratio_locked_real", `id=${item.id} ratio=${ratioStr} source=${src}`);
        applyAspectRatioLayout(video, item, ratioStr);
    }
  });
  video.addEventListener("canplay", () => {
    // Throttle: Chrome fires canplay on every buffer extension during a growing
    // fragmented MP4, producing 60+ events/sec.  Limit trace to once per 2s.
    const now = Date.now();
    if (now - (state._lastCanplayTrace || 0) < 2000) return;
    state._lastCanplayTrace = now;
    debugTrace("video_canplay", `id=${item.id} readyState=${video.readyState}`);
  });
  video.addEventListener("playing", () => {
    debugTrace("video_playing", `id=${item.id} currentTime=${video.currentTime.toFixed(2)}`);
  });

  video.addEventListener("seeked", async () => {
    // DIRECT playback is satisfied by normal byte-range requests.
    // Compatibility playback may need an explicit restart when the seek target
    // is beyond the currently prepared region.
    if (item.playbackMode === "DIRECT") return;

    const sourceType = video.dataset.sourceType || selectedSource.kind;
    const usingManagedHls = Boolean(state.hls && state.hlsItemId === item.id);
    const usingGrowingPreparedMp4 = sourceType === "prepared_mp4" && !item.compatibilityComplete;
    if (!usingManagedHls && !usingGrowingPreparedMp4) return;

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

      // GROWING MP4 GUARD: During an in-progress transmux/remux served as a
      // growing prepared_mp4, the file is written linearly.  Sending a
      // server-side seek request would restart the entire transcode from the
      // new offset, discarding all work done so far, and causing the video
      // to oscillate between positions.  Instead, clamp to the end of the
      // current buffer so the browser stays at the latest available point
      // and the worker continues writing forward.
      if (usingGrowingPreparedMp4 && !usingManagedHls) {
        const bufEnd = video.buffered.length > 0
          ? video.buffered.end(video.buffered.length - 1)
          : 0;
        if (currentTime > bufEnd + 1) {
          debugTrace("video_seek_clamped", `id=${item.id} requested=${currentTime.toFixed(1)}s bufferEnd=${bufEnd.toFixed(1)}s`);
          video.currentTime = Math.max(0, bufEnd - 0.5);
        }
        return;
      }

      if (currentTime > preparedSeconds + 2 && !item.compatibilityComplete) {
         showCompatibilityWaitingStage({ 
            ...item, 
            streamReady: false,
            compatibilityMessage: gsStr("web_player_preparing_segment", "Preparing this segment for you...") 
         });
      }

      try {
        const seekJob = await api(`/api/compat/${item.id}/seek?offsetMs=${Math.floor(currentTime * 1000)}`, {
          method: "POST",
        });
        const nextItem = {
          ...item,
          streamReady: Boolean(seekJob.ready),
          effectivePlaybackMode: seekJob.effectivePlaybackMode || item.effectivePlaybackMode,
          compatibilityStatus: seekJob.status || item.compatibilityStatus || "QUEUED",
          compatibilityMessage: seekJob.message || item.compatibilityMessage,
          compatibilityProgressPercent: seekJob.progressPercent ?? item.compatibilityProgressPercent,
          compatibilityComplete: Boolean(seekJob.compatibilityComplete),
          preparedMp4Url: seekJob.preparedMp4Url || item.preparedMp4Url || null,
          hlsUrl: seekJob.hlsUrl || item.hlsUrl || null,
          width: seekJob.width || item.width,
          height: seekJob.height || item.height,
          totalDurationMs: seekJob.totalDurationMs || item.totalDurationMs || item.durationMs,
        };
        state.compatItem = nextItem;

        if (usingManagedHls && nextItem.hlsUrl) {
          // Reload the manifest. The new fMP4 segments have tfdt.baseMediaDecodeTime
          // offset by startOffsetMs, so hls.js will naturally land at the seek position
          // once the first segment is appended — no manual currentTime assignment needed.
          state.hls.loadSource(nextItem.hlsUrl);
          video.play().catch(() => {});
          return;
        }

        state.pendingCompatSeekOffset[item.id] = currentTime;
        const waitingItem = {
          ...nextItem,
          streamReady: false,
          compatibilityComplete: false,
          compatibilityMessage: gsStr("web_player_preparing_segment", "Preparing this segment for you..."),
        };
        showCompatibilityWaitingStage(waitingItem);
        pollCompat(item.id, waitingItem, { startPreparation: false });
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
      // Guard: an auto-TRANSCODE of a huge/high-res file (e.g. 8K HEVC 150Mbps)
      // can OOM-kill the host's foreground service. If the file is beyond what
      // the host can safely re-encode, skip auto-fallback and let the user
      // retry manually or download the original.
      const tooBigForAutoTranscode =
        (typeof item.sizeBytes === "number" && item.sizeBytes > 2 * 1024 * 1024 * 1024) ||
        (typeof item.width === "number" && item.width > 3840);
      if (tooBigForAutoTranscode) {
        debugTrace(
          "video_error_direct_compat_fallback_skipped",
          `id=${item.id} sizeBytes=${item.sizeBytes || ""} width=${item.width || ""} reason=too_large_for_safe_transcode`,
        );
      } else {
        debugTrace("video_error_direct_compat_fallback", `id=${item.id} failures=${failureCount} triggering compat preparation`);
        // Clear the source lock so the poll loop picks up the new compat
        // source (e.g. prepared_mp4 or HLS) instead of re-locking the
        // failing direct URL that just errored.
        delete state.playerSourceLocks[item.id];
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
    const all = state.libraryItems && state.libraryItems.length > 0 ? state.libraryItems : await api("/api/items?category=photos&limit=100");
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
            ? `<img class="gs-preview gs-photo-preview" src="${item.streamUrl}" alt="${esc(item.title)}">`
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

function showToast(message, duration = 4000) {
  let container = document.querySelector(".gs-toast-container");
  if (!container) {
    container = document.createElement("div");
    container.className = "gs-toast-container";
    document.body.appendChild(container);
  }

  const toast = document.createElement("div");
  toast.className = "gs-toast";
  toast.textContent = message;
  container.appendChild(toast);

  const leave = () => {
    toast.classList.add("is-leaving");
    toast.addEventListener("animationend", () => toast.remove());
  };

  setTimeout(leave, duration);
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
        : isBrowserPreviewable(item.mimeType)
          ? `<a class="${actionBtnClass}" href="${item.streamUrl}" target="_blank" rel="noopener noreferrer">${gsStr("web_action_view", "View")}</a>`
          : "";
  const showSlowStartHint = item.category === "video" && item.playbackMode !== "DIRECT";

  return `
    <article class="gs-card gs-card--${item.category}${selectable && state.selectMode ? " gs-card-selectable" : ""}${state.selected.has(item.id) ? " is-selected" : ""}" data-select-card="${selectable ? item.id : ""}">
      ${selectable ? `<button class="gs-card-toggle${state.selectMode ? " is-visible" : ""}" data-select-toggle="${item.id}">${state.selected.has(item.id) ? "Selected" : "Select"}</button>` : ""}
      ${item.thumbnailUrl
        ? `<img class="gs-card-img" loading="lazy" decoding="async" fetchpriority="low" src="${THUMBNAIL_PLACEHOLDER_SRC}" data-thumb-src="${item.thumbnailUrl}" data-thumb-state="idle" alt="">`
        : `<div class="gs-card-img gs-card-placeholder"><span>${item.category === "file" ? fileTypeLabel(item.mimeType, item.title) : esc(item.category.toUpperCase())}</span></div>`}
      <div class="gs-card-body">
        <div class="gs-card-topline">
          <span class="gs-card-type">${item.category === "file" ? fileTypeLabel(item.mimeType, item.title) : esc(item.category)}</span>
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
    if (attempts < 10) return 500;
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
  if (state.compatDirectFallbackTimer) {
    clearTimeout(state.compatDirectFallbackTimer);
    state.compatDirectFallbackTimer = null;
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
  const floatingSendBar = document.getElementById("floatingSendBar");
  if (!bar) return;
  if (!state.nowPlaying) {
    bar.className = "gs-now";
    bar.innerHTML = "";
    floatingSendBar?.classList.remove("is-raised");
    return;
  }

  bar.className = "gs-now is-visible";
  floatingSendBar?.classList.add("is-raised");
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

function destroyLiveScreen() {
  state.liveStatsToken += 1;
  if (state.liveAnswerPollTimer) {
    clearInterval(state.liveAnswerPollTimer);
    state.liveAnswerPollTimer = null;
  }
  if (state.liveIcePollTimer) {
    clearInterval(state.liveIcePollTimer);
    state.liveIcePollTimer = null;
  }
  if (state.liveStatePollTimer) {
    clearInterval(state.liveStatePollTimer);
    state.liveStatePollTimer = null;
  }
  if (state.liveStatsTimer) {
    clearInterval(state.liveStatsTimer);
    state.liveStatsTimer = null;
  }
  if (state.liveHls) {
    try { state.liveHls.destroy(); } catch (_) {}
    state.liveHls = null;
  }
  if (state.liveViewerId) {
    fetch(`/api/live/webrtc/disconnect/${encodeURIComponent(state.liveViewerId)}`, {
      method: "POST",
      credentials: "include",
      keepalive: true,
    }).catch(() => {});
  }
  if (state.livePeer) {
    try { state.livePeer.close(); } catch (_) {}
    state.livePeer = null;
  }
  state.liveRemoteStream = null;
  state.livePendingRemoteIce = [];
  state.liveViewerId = null;
}

function destroyQuickTextPolling() {
  if (state.quickTextTimer) {
    clearInterval(state.quickTextTimer);
    state.quickTextTimer = null;
  }
}

async function renderLiveScreen() {
  const liveState = await api("/api/live/state");
  shell(`
    <section class="gs-section" style="max-width:1200px;margin:0 auto;">
      <div class="gs-section-head">
        <div>
          <h2>${gsStr("web_live_title", "")}</h2>
          <div class="gs-section-meta" id="liveStatusText"></div>
        </div>
        <div class="gs-toolbar-actions">
          <button class="gs-btn gs-btn-sm" id="liveFullscreenBtn">${gsStr("web_live_fullscreen", "")}</button>
          <button class="gs-btn gs-btn-sm" id="liveRefreshBtn">${gsStr("web_live_refresh", "")}</button>
        </div>
      </div>
      <div style="background:#050816;border-radius:28px;padding:18px;">
        <video id="liveVideo" autoplay playsinline muted style="width:100%;max-height:72vh;background:#050816;border-radius:20px;object-fit:contain;"></video>
      </div>
      <div class="gs-section-meta" id="liveAudioText" style="margin-top:12px;"></div>
      <div class="gs-toolbar-actions" style="margin-top:16px;">
        <button class="gs-btn gs-btn-sm" id="liveFitBtn">${gsStr("web_live_fit", "")}</button>
        <button class="gs-btn gs-btn-sm" id="liveFillBtn">${gsStr("web_live_fill", "")}</button>
        <button class="gs-btn gs-btn-sm" id="liveAudioBtn">${gsStr("web_live_mute", "")}</button>
      </div>
    </section>
  `);
  mountLiveScreen(liveState);
}

function mountLiveScreen(initialState) {
  const video = document.getElementById("liveVideo");
  const statusText = document.getElementById("liveStatusText");
  const audioText = document.getElementById("liveAudioText");
  const audioBtn = document.getElementById("liveAudioBtn");
  if (!video || !statusText || !audioText || !audioBtn) return;

  const applyStatus = (label, detail) => {
    statusText.textContent = detail ? `${label} | ${detail}` : label;
  };

  const applyLiveState = (sessionState) => {
    if (sessionState?.status === "LIVE") applyStatus(gsStr("web_live_status_connecting", ""), sessionState.viewerCount > 0 ? gsStr("web_live_status_live", "") : gsStr("web_live_status_waiting_viewer", ""));
    else if (sessionState?.status === "STARTING") applyStatus(gsStr("web_live_status_starting", ""));
    else if (sessionState?.status === "ERROR") applyStatus(gsStr("web_live_stream_ended", ""), sessionState.lastError || "");
    else applyStatus(gsStr("web_live_waiting", ""));

    const audioState = sessionState?.audioStatus || "";
    if (audioState === "AUDIO_LIVE") {
      audioText.textContent = gsStr("web_live_audio_live", "");
    } else if (audioState === "AUDIO_SILENT") {
      audioText.textContent = gsStr("web_live_audio_blocked_or_silent", "");
    } else if (audioState === "AUDIO_INITIALIZING" || audioState === "AUDIO_SUPPORTED") {
      audioText.textContent = gsStr("web_live_audio_limit", "");
    } else if (audioState === "AUDIO_FAILED") {
      audioText.textContent = gsStr("web_live_audio_blocked_or_silent", "");
    } else {
      audioText.textContent = gsStr("web_live_audio_unavailable", "");
    }
  };

  applyLiveState(initialState);
  document.getElementById("liveRefreshBtn")?.addEventListener("click", () => navigate("/live", true));
  document.getElementById("liveFullscreenBtn")?.addEventListener("click", () => video.requestFullscreen?.().catch?.(() => {}));
  document.getElementById("liveFitBtn")?.addEventListener("click", () => { video.style.objectFit = "contain"; });
  document.getElementById("liveFillBtn")?.addEventListener("click", () => { video.style.objectFit = "cover"; });
  const applyLiveMuteButton = () => {
    audioBtn.textContent = video.muted ? gsStr("web_live_unmute", "") : gsStr("web_live_mute", "");
  };
  const enableLiveAudioFromGesture = async () => {
    video.muted = false;
    video.defaultMuted = false;
    video.removeAttribute("muted");
    video.volume = 1;
    applyLiveMuteButton();
    try {
      await video.play();
      debugTrace("live_audio_unmuted", `user_gesture=true elementMuted=${video.muted} volume=${video.volume}`);
      video.dispatchEvent(new Event("directserve-live-audio-unmuted"));
    } catch (error) {
      video.muted = true;
      video.defaultMuted = true;
      video.setAttribute("muted", "");
      applyLiveMuteButton();
      debugTrace("live_audio_unmute_failed", error?.message || String(error || ""));
      try { await video.play(); } catch (_) {}
    }
  };
  audioBtn.addEventListener("click", () => {
    if (video.muted) {
      enableLiveAudioFromGesture();
    } else {
      video.muted = true;
      video.defaultMuted = true;
      video.setAttribute("muted", "");
      applyLiveMuteButton();
      debugTrace("live_audio_muted", "user_gesture=true");
    }
  });
  applyLiveMuteButton();

  if (!("RTCPeerConnection" in window)) {
    applyStatus(gsStr("web_live_unsupported", ""));
    return;
  }

  startLiveWebRtc(video, applyStatus, applyLiveState, audioText, audioBtn);
}

async function startLiveMuxedHls(video, applyStatus, applyLiveState, audioText, audioBtn) {
  applyStatus(gsStr("web_live_status_connecting", ""));
  const sourceUrl = "/api/live/hls/master.m3u8";

  state.liveStatePollTimer = window.setInterval(async () => {
    if (location.pathname !== "/live") return;
    try {
      applyLiveState(await api("/api/live/state"));
    } catch (_) {}
  }, 2000);

  video.muted = false;
  audioBtn.textContent = gsStr("web_live_mute", "");
  video.addEventListener("playing", () => applyStatus(gsStr("web_live_status_live", "")));
  video.addEventListener("waiting", () => applyStatus(gsStr("web_live_status_reconnecting", "")));
  video.addEventListener("error", () => applyStatus(gsStr("web_live_status_reconnecting", "")));

  if (window.Hls?.isSupported?.()) {
    const hls = new Hls({
      lowLatencyMode: true,
      liveSyncDurationCount: 2,
      liveMaxLatencyDurationCount: 5,
      maxLiveSyncPlaybackRate: 1.15,
      backBufferLength: 15,
    });
    state.liveHls = hls;
    hls.on(Hls.Events.ERROR, (_, data) => {
      if (data?.fatal) {
        applyStatus(gsStr("web_live_status_reconnecting", ""));
        try { hls.startLoad(); } catch (_) {}
      }
    });
    hls.loadSource(sourceUrl);
    hls.attachMedia(video);
  } else {
    video.src = sourceUrl;
  }

  try {
    await video.play();
  } catch (_) {
    video.muted = true;
    audioBtn.textContent = gsStr("web_live_unmute", "");
    try { await video.play(); } catch (_) {}
  }
}

async function startLiveWebRtc(video, applyStatus, applyLiveState, audioText, audioBtn) {
  applyStatus(gsStr("web_live_status_connecting", ""));
  const session = await api("/api/live/webrtc/session", {
    method: "POST",
    body: JSON.stringify({ pin: null }),
  });

  if (!session.accepted || !session.viewerId) {
    if (session.status === "BUSY") applyStatus(gsStr("web_live_busy", ""));
    else if (session.status === "PIN_REQUIRED") applyStatus(gsStr("web_live_pin_required", ""));
    else applyStatus(gsStr("web_live_stream_ended", ""));
    return;
  }

  const viewerId = session.viewerId;
  state.liveViewerId = viewerId;

  const peer = new RTCPeerConnection({ iceServers: [] });
  state.livePeer = peer;
  const remoteStream = new MediaStream();
  state.liveRemoteStream = remoteStream;
  state.livePendingRemoteIce = [];
  const videoTransceiver = peer.addTransceiver("video", { direction: "recvonly" });
  const audioTransceiver = peer.addTransceiver("audio", { direction: "recvonly" });
  preferLiveCodecs(videoTransceiver, "video");
  preferLiveCodecs(audioTransceiver, "audio");
  video.autoplay = true;
  video.playsInline = true;
  video.muted = true;
  audioBtn.textContent = gsStr("web_live_unmute", "");
  video.srcObject = remoteStream;
  const ensureLivePlayback = () => {
    video.play().catch((error) => {
      debugTrace("live_video_play_failed", `muted=${video.muted} message=${error?.message || String(error || "")}`);
      if (!video.muted) {
        video.muted = true;
        audioBtn.textContent = gsStr("web_live_unmute", "");
      }
      video.play().catch(() => {});
    });
  };
  const traceLiveVideoState = (event) => {
    debugTrace(
      event,
      `tracks=${remoteStream.getTracks().map((track) => `${track.kind}:${track.readyState}:${track.muted ? "muted" : "unmuted"}`).join(",")} ready=${video.readyState} size=${video.videoWidth}x${video.videoHeight} paused=${video.paused} elementMuted=${video.muted}`,
    );
  };
  video.addEventListener("directserve-live-audio-unmuted", () => traceLiveVideoState("live_audio_unmuted_state"));
  const addRemoteTrack = (track) => {
    if (!track || remoteStream.getTracks().some((existing) => existing.id === track.id)) return false;
    remoteStream.addTrack(track);
    track.addEventListener?.("unmute", () => traceLiveVideoState(`live_track_unmute_${track.kind}`));
    track.addEventListener?.("mute", () => traceLiveVideoState(`live_track_mute_${track.kind}`));
    track.addEventListener?.("ended", () => traceLiveVideoState(`live_track_ended_${track.kind}`));
    return true;
  };
  const attachReceiverTracks = (reason) => {
    let added = 0;
    peer.getReceivers().forEach((receiver) => {
      if (addRemoteTrack(receiver.track)) added += 1;
    });
    debugTrace(
      "live_receiver_tracks_attached",
      `reason=${reason} added=${added} total=${remoteStream.getTracks().length} receivers=${peer.getReceivers().map((receiver) => receiver.track?.kind || "none").join(",")}`,
    );
    if (added > 0) {
      video.srcObject = remoteStream;
      ensureLivePlayback();
    }
  };
  ["loadedmetadata", "resize", "playing", "waiting", "stalled", "error"].forEach((eventName) => {
    video.addEventListener(eventName, () => traceLiveVideoState(`live_video_${eventName}`));
  });

  peer.ontrack = (event) => {
    console.info("[DirectServe] live remote track", event.track?.kind || "unknown");
    let added = 0;
    if (event.streams?.length) {
      event.streams.forEach((stream) => {
        stream.getTracks().forEach((track) => {
          if (addRemoteTrack(track)) added += 1;
        });
      });
    } else if (event.track) {
      if (addRemoteTrack(event.track)) added += 1;
    }
    debugTrace(
      "live_track_received",
      `kind=${event.track?.kind || "unknown"} id=${event.track?.id || ""} streams=${event.streams?.length || 0} added=${added} total=${remoteStream.getTracks().length}`,
    );
    if (event.track?.kind === "audio") {
      debugTrace("live_audio_track_received", "remote audio track received");
      audioText.textContent = gsStr("web_live_audio_live", "");
    }
    ensureLivePlayback();
  };

  peer.onconnectionstatechange = () => {
    const connectionState = peer.connectionState;
    debugTrace("live_peer_connection_state", connectionState);
    if (connectionState === "connected") {
      applyStatus(gsStr("web_live_status_live", ""));
    } else if (connectionState === "connecting") {
      applyStatus(gsStr("web_live_status_connecting", ""));
    } else if (connectionState === "disconnected") {
      applyStatus(gsStr("web_live_status_reconnecting", ""));
    } else if (connectionState === "failed") {
      applyStatus(gsStr("web_live_status_reconnecting", ""));
    } else if (connectionState === "closed") {
      applyStatus(gsStr("web_live_stream_ended", ""));
    }
  };
  peer.oniceconnectionstatechange = () => debugTrace("live_ice_connection_state", peer.iceConnectionState);
  peer.onicegatheringstatechange = () => debugTrace("live_ice_gathering_state", peer.iceGatheringState);

  peer.onicecandidate = (event) => {
    if (!event.candidate || !state.liveViewerId) return;
    api(`/api/live/webrtc/ice/browser/${encodeURIComponent(state.liveViewerId)}`, {
      method: "POST",
      body: JSON.stringify({
        sdpMid: event.candidate.sdpMid,
        sdpMLineIndex: event.candidate.sdpMLineIndex,
        candidate: event.candidate.candidate,
      }),
    }).catch(() => {});
  };
  const flushLiveRemoteIce = () => {
    if (!state.livePeer?.remoteDescription || state.livePendingRemoteIce.length === 0) return;
    const queued = state.livePendingRemoteIce.splice(0, state.livePendingRemoteIce.length);
    queued.forEach((candidate) => {
      state.livePeer?.addIceCandidate(candidate).catch(() => {});
    });
  };
  const createAndSendBrowserOffer = async () => {
    const browserOffer = await peer.createOffer();
    await peer.setLocalDescription(browserOffer);
    await waitForLiveIceGathering(peer, 2500);
    const localOffer = peer.localDescription || browserOffer;
    debugTrace(
      "live_browser_offer_ready",
      `ice=${peer.iceGatheringState} hasCandidates=${(localOffer.sdp || "").includes("a=candidate:")}`,
    );
    await api(`/api/live/webrtc/offer/${encodeURIComponent(viewerId)}`, {
      method: "POST",
      body: JSON.stringify({ sdp: localOffer.sdp || "" }),
    });
    debugTrace("live_browser_offer_sent", `transceivers=${peer.getTransceivers?.().map((transceiver) => `${transceiver.receiver?.track?.kind || "none"}:${transceiver.direction}`).join(",") || ""}`);
  };
  createAndSendBrowserOffer().catch((error) => {
    debugTrace("live_browser_offer_failed", error?.message || String(error || ""));
    applyStatus(gsStr("web_live_status_reconnecting", ""));
  });

  state.liveAnswerPollTimer = window.setInterval(async () => {
    if (!state.liveViewerId || !state.livePeer || state.livePeer.remoteDescription) return;
    try {
      const response = await fetch(`/api/live/webrtc/answer/${encodeURIComponent(state.liveViewerId)}`, {
        credentials: "include",
      });
      if (response.status === 202) return;
      if (!response.ok) return;
      const androidAnswer = await response.json();
      if (!androidAnswer?.sdp) return;
      await state.livePeer.setRemoteDescription({ type: "answer", sdp: androidAnswer.sdp });
      flushLiveRemoteIce();
      debugTrace(
        "live_android_answer_set",
        `hasCandidates=${androidAnswer.sdp.includes("a=candidate:")} receivers=${state.livePeer.getReceivers().map((receiver) => receiver.track?.kind || "none").join(",")}`,
      );
      attachReceiverTracks("answer_set");
      setTimeout(() => attachReceiverTracks("answer_set_delayed"), 500);
      debugTrace(
        "live_browser_answer_received",
        `receivers=${state.livePeer.getReceivers().map((receiver) => receiver.track?.kind || "none").join(",")}`,
      );
      ensureLivePlayback();
      if (!androidAnswer.audioEnabled) {
        audioText.textContent = gsStr("web_live_audio_unavailable", "");
      }
    } catch (_) {}
  }, 500);

  state.liveIcePollTimer = window.setInterval(async () => {
    if (!state.liveViewerId || !state.livePeer) return;
    try {
      const candidates = await api(`/api/live/webrtc/ice/android/${encodeURIComponent(state.liveViewerId)}`);
      (candidates || []).forEach((candidate) => {
        if (!state.livePeer.remoteDescription) {
          state.livePendingRemoteIce.push(candidate);
          return;
        }
        state.livePeer.addIceCandidate(candidate).catch(() => {});
      });
      flushLiveRemoteIce();
    } catch (_) {}
  }, 1000);

  state.liveStatePollTimer = window.setInterval(async () => {
    if (location.pathname !== "/live") return;
    try {
      applyLiveState(await api("/api/live/state"));
    } catch (_) {}
  }, 2000);
  startLiveStatsMonitor(peer, video);

  window.addEventListener("pagehide", () => {
    if (state.liveViewerId) {
      fetch(`/api/live/webrtc/disconnect/${encodeURIComponent(state.liveViewerId)}`, {
        method: "POST",
        credentials: "include",
        keepalive: true,
      }).catch(() => {});
    }
  }, { once: true });

  audioBtn.textContent = gsStr("web_live_unmute", "");
  ensureLivePlayback();
}

function preferLiveCodecs(transceiver, kind) {
  if (!transceiver?.setCodecPreferences || !window.RTCRtpReceiver?.getCapabilities) return;
  const capabilities = window.RTCRtpReceiver.getCapabilities(kind);
  const codecs = capabilities?.codecs || [];
  if (!codecs.length) return;
  const rank = (codec) => {
    const name = `${codec.mimeType || ""} ${codec.sdpFmtpLine || ""}`.toUpperCase();
    if (kind === "video") {
      if (name.includes("H264")) return 0;
      if (name.includes("VP8")) return 1;
      if (name.includes("VP9")) return 2;
      if (name.includes("AV1")) return 3;
      if (name.includes("RTX")) return 20;
      if (name.includes("RED")) return 21;
      if (name.includes("ULPFEC")) return 22;
      return 10;
    }
    if (name.includes("OPUS")) return 0;
    return 10;
  };
  const preferred = kind === "audio"
    ? codecs.filter((codec) => `${codec.mimeType || ""}`.toLowerCase() === "audio/opus")
    : codecs;
  const ordered = preferred.slice().sort((a, b) => rank(a) - rank(b));
  if (!ordered.length) return;
  try {
    transceiver.setCodecPreferences(ordered);
    debugTrace("live_codec_preferences", `${kind}=${ordered.slice(0, 4).map((codec) => codec.mimeType || "").join(",")}`);
  } catch (error) {
    debugTrace("live_codec_preferences_failed", `${kind}=${error?.message || String(error || "")}`);
  }
}

function startLiveStatsMonitor(peer, video) {
  if (state.liveStatsTimer) clearInterval(state.liveStatsTimer);
  const token = ++state.liveStatsToken;
  let last = null;
  const timer = window.setInterval(async () => {
    if (token !== state.liveStatsToken || location.pathname !== "/live" || !peer || peer.connectionState === "closed") {
      clearInterval(timer);
      if (state.liveStatsTimer === timer) state.liveStatsTimer = null;
      return;
    }
    try {
      const report = await peer.getStats();
      const now = Date.now();
      const stats = {
        audioBytes: 0,
        audioPackets: 0,
        audioLost: 0,
        audioJitter: "",
        audioLevel: "",
        videoBytes: 0,
        videoFrames: 0,
        videoDropped: 0,
        fps: "",
        freezes: "",
      };
      report.forEach((entry) => {
        if (entry.type !== "inbound-rtp" || entry.isRemote) return;
        const kind = entry.kind || entry.mediaType || "";
        if (kind === "audio") {
          stats.audioBytes += entry.bytesReceived || 0;
          stats.audioPackets += entry.packetsReceived || 0;
          stats.audioLost += entry.packetsLost || 0;
          if (entry.jitter != null) stats.audioJitter = Number(entry.jitter).toFixed(4);
          if (entry.audioLevel != null) stats.audioLevel = Number(entry.audioLevel).toFixed(4);
        } else if (kind === "video") {
          stats.videoBytes += entry.bytesReceived || 0;
          stats.videoFrames += entry.framesDecoded || 0;
          stats.videoDropped += entry.framesDropped || 0;
          if (entry.framesPerSecond != null) stats.fps = Number(entry.framesPerSecond).toFixed(1);
          if (entry.freezeCount != null) stats.freezes = entry.freezeCount;
        }
      });
      const deltaSeconds = last ? Math.max(0.001, (now - last.now) / 1000) : 0;
      const audioKbps = last ? Math.round(((stats.audioBytes - last.audioBytes) * 8) / 1000 / deltaSeconds) : 0;
      const videoKbps = last ? Math.round(((stats.videoBytes - last.videoBytes) * 8) / 1000 / deltaSeconds) : 0;
      debugTrace(
        "live_webrtc_stats",
        `audioKbps=${audioKbps} audioPackets=${stats.audioPackets} audioLost=${stats.audioLost} audioLevel=${stats.audioLevel} audioJitter=${stats.audioJitter} ` +
          `videoKbps=${videoKbps} fps=${stats.fps} frames=${stats.videoFrames} dropped=${stats.videoDropped} freezes=${stats.freezes} ` +
          `ready=${video.readyState} elementMuted=${video.muted} volume=${video.volume}`,
      );
      last = {
        now,
        audioBytes: stats.audioBytes,
        videoBytes: stats.videoBytes,
      };
    } catch (error) {
      debugTrace("live_webrtc_stats_failed", error?.message || String(error || ""));
    }
  }, 3000);
  state.liveStatsTimer = timer;
}

function waitForLiveIceGathering(peer, timeoutMs) {
  if (!peer || peer.iceGatheringState === "complete") return Promise.resolve();
  return new Promise((resolve) => {
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      peer.removeEventListener?.("icegatheringstatechange", onStateChange);
      resolve();
    };
    const onStateChange = () => {
      if (peer.iceGatheringState === "complete") finish();
    };
    const timer = setTimeout(finish, timeoutMs);
    peer.addEventListener?.("icegatheringstatechange", onStateChange);
  });
}

async function renderQuickText() {
  const payload = await api("/api/quick-text/messages");
  shell(`
    <section class="gs-section" style="max-width:900px;margin:0 auto;">
      <div class="gs-section-head">
        <div>
          <h2>${gsStr("web_quick_text_title", "")}</h2>
          <div class="gs-section-meta">${gsStr("web_quick_text_history", "")}</div>
        </div>
        <button class="gs-btn gs-btn-sm" id="quickTextClearBtn">${gsStr("web_quick_text_clear_all", "")}</button>
      </div>
      <div class="gs-category-card" style="margin-bottom:20px;">
        <textarea id="quickTextInput" class="gs-search" rows="4" placeholder="${esc(gsStr("web_quick_text_placeholder", ""))}"></textarea>
        <div class="gs-toolbar-actions" style="margin-top:12px;">
          <label for="quickTextTarget">${gsStr("web_quick_text_target", "")}</label>
          <select id="quickTextTarget" class="gs-search" style="max-width:260px;"></select>
          <button class="gs-btn gs-btn-accent" id="quickTextSendBtn">${gsStr("web_quick_text_send", "")}</button>
        </div>
      </div>
      <div id="quickTextHistory"></div>
    </section>
  `);
  mountQuickText(payload);
}

function mountQuickText(initialPayload) {
  const input = document.getElementById("quickTextInput");
  const target = document.getElementById("quickTextTarget");
  const historyEl = document.getElementById("quickTextHistory");
  const sendBtn = document.getElementById("quickTextSendBtn");
  const clearBtn = document.getElementById("quickTextClearBtn");
  if (!input || !target || !historyEl || !sendBtn || !clearBtn) return;

  const renderPayload = (payload) => {
    const devices = payload.devices || [];
    target.innerHTML = devices
      .filter((device) => !device.isHostPhone)
      .map((device) => `<option value="${esc(device.id)}">${esc(device.name)}</option>`)
      .join("") + `<option value="__broadcast__">${esc(gsStr("web_quick_text_broadcast", ""))}</option>`;

    const messages = payload.messages || [];
    historyEl.innerHTML = messages.length === 0
      ? `<div class="gs-category-card">${esc(gsStr("web_quick_text_empty", ""))}</div>`
      : messages.map((message) => `
          <article class="gs-category-card" style="margin-bottom:12px;">
            <div style="white-space:pre-wrap;">${esc(message.text)}</div>
            <div class="gs-category-meta" style="margin-top:8px;">
              ${esc(message.senderName)} | ${esc(message.targetName || gsStr("web_quick_text_broadcast", ""))} | ${esc(new Date(message.timestampMs).toLocaleString())}
            </div>
            <div class="gs-toolbar-actions" style="margin-top:10px;">
              <button class="gs-btn gs-btn-sm" data-copy="${esc(message.text)}">${gsStr("web_quick_text_copy", "")}</button>
              ${/^https?:\/\//i.test(message.text || "") ? `<button class="gs-btn gs-btn-sm" data-open="${esc(message.text)}">${gsStr("web_quick_text_open_link", "")}</button>` : ""}
              <button class="gs-btn gs-btn-sm" data-delete="${esc(message.id)}">${gsStr("common_delete", "Delete")}</button>
            </div>
          </article>
        `).join("");

    historyEl.querySelectorAll("[data-copy]").forEach((button) => {
      button.addEventListener("click", async () => {
        try { await navigator.clipboard.writeText(button.getAttribute("data-copy") || ""); } catch (_) {}
      });
    });
    historyEl.querySelectorAll("[data-open]").forEach((button) => {
      button.addEventListener("click", () => {
        const url = button.getAttribute("data-open");
        if (url) window.open(url, "_blank", "noopener");
      });
    });
    historyEl.querySelectorAll("[data-delete]").forEach((button) => {
      button.addEventListener("click", async () => {
        await api(`/api/quick-text/delete/${button.getAttribute("data-delete")}`, { method: "POST" });
        renderPayload(await api("/api/quick-text/messages"));
      });
    });
  };

  sendBtn.addEventListener("click", async () => {
    const text = input.value.trim();
    if (!text) return;
    const selected = target.value;
    const selectedOption = target.options[target.selectedIndex];
    await api("/api/quick-text/messages", {
      method: "POST",
      body: JSON.stringify({
        text,
        targetType: selected === "__broadcast__" ? "BROADCAST" : "DEVICE",
        targetId: selected === "__broadcast__" ? null : selected,
        targetName: selected === "__broadcast__" ? gsStr("web_quick_text_broadcast", "") : selectedOption?.text || "",
      }),
    });
    input.value = "";
    renderPayload(await api("/api/quick-text/messages"));
  });

  clearBtn.addEventListener("click", async () => {
    await api("/api/quick-text/clear", { method: "POST" });
    renderPayload(await api("/api/quick-text/messages"));
  });

  renderPayload(initialPayload);
  destroyQuickTextPolling();
  state.quickTextTimer = window.setInterval(async () => {
    if (location.pathname !== "/quick-text") return;
    try {
      renderPayload(await api("/api/quick-text/messages"));
    } catch (_) {}
  }, 2500);
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
          <input id="photoVideoInput" class="gs-upload-native-input" type="file" accept="image/*,video/*" multiple>
          <input id="nativeUploadInput" class="gs-upload-native-input" type="file" multiple>
          <div class="gs-upload-zone-actions">
            <label class="gs-btn gs-btn-accent gs-btn-block gs-upload-primary-btn" for="photoVideoInput" id="uppyPhotoVideoBtn">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
              ${gsStr("web_upload_button_photos", "Photos & Videos")}
            </label>
            <label class="gs-btn gs-btn-block" for="nativeUploadInput" id="uppyBrowseBtn">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
              ${gsStr("web_upload_button_any_file", "Any File")}
            </label>
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
  const uploadBtn = document.getElementById("uppyUploadBtn");
  const nativeInput = document.getElementById("nativeUploadInput");
  const photoVideoInput = document.getElementById("photoVideoInput");
  const selectionStatus = document.getElementById("uploadSelectionStatus");
  if (!uploadBtn || !nativeInput) return;
  const fileInputs = [nativeInput, photoVideoInput].filter(Boolean);

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
    fileInputs.forEach((input) => {
      input.addEventListener("change", () => {
        const picked = Array.from(input.files || []);
        if (picked.length === 0) return;
        state.pendingUploadFiles = picked;
        input.value = "";
        refreshFallbackToolbar();
      });
    });
    uploadBtn.addEventListener("click", async () => {
      if (state.pendingUploadFiles.length === 0) return;
      uploadBtn.disabled = true;
      try {
        await handleFilesUpload(state.pendingUploadFiles);
        state.pendingUploadFiles = [];
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

  fileInputs.forEach((input) => {
    input.addEventListener("change", () => {
      const files = Array.from(input.files || []);
      input.value = "";
      if (files.length === 0) return;
      addFilesToUppy(files);
    });
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
  const media = plyr.media || container.querySelector("video");

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
  let pointerActive = false;

  const getClientX = (event) => {
    if (!event) return null;
    if (typeof event.clientX === "number") return event.clientX;
    const touch = event.touches?.[0] || event.changedTouches?.[0];
    return typeof touch?.clientX === "number" ? touch.clientX : null;
  };

  const shouldFetchPreviewFrame = () => {
    if (pointerActive) return true;
    if (!media) return true;
    return media.paused || media.ended || media.readyState < 2;
  };

  const updatePreview = (event) => {
    const clientX = getClientX(event);
    if (!Number.isFinite(clientX)) return;
    const rect = progress.getBoundingClientRect();
    if (!rect.width) return;
    const percent = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
    const duration = plyr.duration || 0;
    if (duration <= 0) return;

    const timeSec = percent * duration;
    const timeMs = Math.floor(timeSec) * 1000;

    // Position the tooltip
    preview.style.left = `${percent * 100}%`;
    timeLabel.textContent = formatTime(timeSec);
    preview.classList.add("is-visible");

    if (!shouldFetchPreviewFrame()) return;

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
    pointerActive = false;
  };

  const handlePointerDown = (event) => {
    pointerActive = true;
    if (window.PointerEvent && typeof event.pointerId === "number" && progress.setPointerCapture) {
      try {
        progress.setPointerCapture(event.pointerId);
      } catch (_) {}
    }
    updatePreview(event);
  };

  const handlePointerMove = (event) => {
    updatePreview(event);
  };

  const handlePointerUp = (event) => {
    updatePreview(event);
    pointerActive = false;
  };

  if (window.PointerEvent) {
    progress.addEventListener("pointerdown", handlePointerDown);
    progress.addEventListener("pointermove", handlePointerMove);
    progress.addEventListener("pointerup", handlePointerUp);
    progress.addEventListener("pointerleave", hidePreview);
    progress.addEventListener("pointercancel", hidePreview);
    progress.addEventListener("lostpointercapture", hidePreview);
  } else {
    progress.addEventListener("mousedown", handlePointerDown);
    progress.addEventListener("mousemove", handlePointerMove);
    progress.addEventListener("mouseup", handlePointerUp);
    progress.addEventListener("mouseenter", handlePointerMove);
    progress.addEventListener("mouseleave", hidePreview);
    progress.addEventListener("touchstart", handlePointerDown, { passive: true });
    progress.addEventListener("touchmove", handlePointerMove, { passive: true });
    progress.addEventListener("touchend", handlePointerUp, { passive: true });
    progress.addEventListener("touchcancel", hidePreview, { passive: true });
  }

  media?.addEventListener("playing", () => {
    hidePreview();
  });
}
