const app = document.getElementById("app");
const sessionTitle = app.dataset.title || "DirectServe";
const sessionSubtitle = app.dataset.subtitle || "Local-only streaming";

const state = {
  bootstrap: null,
  query: "",
  selected: new Set(),
  selectMode: false,
  libraryItems: [],
  nowPlaying: null,
  plyr: null,
  plyrItemId: null,
  musicPlayers: [],
  searchTimer: null,
  compatPollToken: 0,
  compatPollTimer: null,
};

const routes = {
  "/": renderHome,
  "/login": () => renderLogin(),
  "/videos": () => renderLibrary("videos", "Videos"),
  "/photos": () => renderLibrary("photos", "Photos"),
  "/music": () => renderLibrary("music", "Music"),
  "/files": () => renderLibrary("files", "Files"),
};

window.addEventListener("popstate", () => boot());
document.addEventListener("click", (event) => {
  const link = event.target.closest("[data-link]");
  if (!link) return;
  event.preventDefault();
  navigate(link.getAttribute("href"));
});

async function boot() {
  cancelCompatPolling();
  destroyPlyr();
  destroyMusicPlayers();
  const path = location.pathname;
  if (path === "/login") {
    renderLogin();
    return;
  }

  try {
    state.bootstrap = await api("/api/bootstrap");
    applyBootstrapUi();
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
  const themeMode = bootstrap?.themeMode || "SYSTEM";
  document.documentElement.style.colorScheme =
    themeMode === "DARK" ? "dark" : themeMode === "LIGHT" ? "light" : "";
  document.body.classList.toggle("gs-theme-dark", themeMode === "DARK");
  document.body.classList.toggle("gs-theme-light", themeMode === "LIGHT");
  document.body.classList.toggle("gs-large-cards", Boolean(bootstrap?.largeCards));
  document.body.classList.toggle("gs-prominent-downloads", Boolean(bootstrap?.prominentDownloadButton));
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

function isAppleMobileReceiver() {
  const ua = navigator.userAgent || "";
  const appleHandheld = /iPhone|iPad|iPod/i.test(ua);
  const iPadDesktopMode = navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1;
  return Boolean(appleHandheld || iPadDesktopMode);
}

function shouldUseHlsPlayback(item) {
  return Boolean(item.hlsUrl && item.playbackMode !== "DIRECT" && isAppleMobileReceiver());
}

function shouldUseNativeVideoPlayer(item) {
  return isMobileBrowser() || item.playbackMode !== "DIRECT";
}

function shouldStartCompatibilityPlayback(item, job = null) {
  if (item.playbackMode === "DIRECT") return true;
  const complete = job ? Boolean(job.complete) : Boolean(item.compatibilityComplete);
  const ready = job
    ? Boolean(job.ready || job.status === "READY")
    : Boolean(item.streamReady || item.compatibilityStatus === "READY");
  return ready || complete;
}

function compatibilityHeadline(item, streamLive = item.streamReady) {
  if (item.compatibilityStatus === "FAILED") {
    return "Playback preparation failed";
  }
  if (!streamLive) {
    return "Preparing browser playback";
  }
  if (item.compatibilityComplete || item.compatibilityStatus === "READY") {
    return "Compatibility playback is ready";
  }
  return "Streaming while finishing conversion";
}

function compatibilityBody(item, streamLive = item.streamReady) {
  if (!streamLive && item.compatibilityMessage) {
    return item.compatibilityMessage;
  }
  if (streamLive) {
    return item.compatibilityMessage || "DirectServe is finishing the compatible stream in the background.";
  }
  return item.compatibilityMessage || "DirectServe is preparing a compatible stream for this browser.";
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
  destroyPlyr();
  destroyMusicPlayers();
  if (options.resetNowPlaying !== false) {
    state.nowPlaying = null;
  }
  const bootstrap = state.bootstrap;
  const path = location.pathname;
  const securityLabel = bootstrap?.authEnabled ? "PIN protected" : "Open on local network";
  const sessionLink = bootstrap?.sessionUrl
    ? `
      <div class="gs-inline-note">
        <span class="gs-inline-note-label">Link</span>
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
          <a class="gs-tab${path === "/" ? " on" : ""}" data-link href="/">Home</a>
          <a class="gs-tab${path === "/videos" ? " on" : ""}" data-link href="/videos">Videos</a>
          <a class="gs-tab${path === "/photos" ? " on" : ""}" data-link href="/photos">Photos</a>
          <a class="gs-tab${path === "/music" ? " on" : ""}" data-link href="/music">Music</a>
          <a class="gs-tab${path === "/files" ? " on" : ""}" data-link href="/files">Files</a>
        </div>
        <div class="gs-nav-meta">
          <span class="gs-status-pill">${securityLabel}</span>
          ${bootstrap?.authEnabled ? '<button class="gs-btn gs-btn-sm" id="logoutBtn">Log out</button>' : ""}
        </div>
      </nav>
      <div class="gs-status-strip">
        <div class="gs-inline-note">
          <span class="gs-inline-note-label">Session</span>
          <span>${esc(bootstrap?.subtitle || sessionSubtitle)}</span>
        </div>
        ${sessionLink}
      </div>
      <main class="gs-main">${content}</main>
      <div class="gs-now${state.nowPlaying ? " is-visible" : ""}" id="nowPlayingBar"></div>
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
    { key: "videos", label: "Videos", count: bootstrap.categories.videos, href: "/videos" },
    { key: "photos", label: "Photos", count: bootstrap.categories.photos, href: "/photos" },
    { key: "music", label: "Music", count: bootstrap.categories.music, href: "/music" },
    { key: "files", label: "Files", count: bootstrap.categories.files, href: "/files" },
  ];
  const total = categories.reduce((sum, category) => sum + category.count, 0);

  shell(`
    <section class="gs-hero">
      <div class="gs-hero-copy">
        <span class="gs-eyebrow">Private receiver view</span>
        <h1>Stream & share files offline</h1>
        <p>${esc(sessionSubtitle)}. Browse, play, preview, or download ${total} shared items on the same local network.</p>
      </div>
      <div class="gs-hero-side">
        <div class="gs-hero-summary">
          <div class="gs-hero-stat">
            <span class="gs-inline-note-label">Shared now</span>
            <strong>${total} items</strong>
            <span class="gs-meta">Ready on this local session</span>
          </div>
          <div class="gs-hero-stat">
            <span class="gs-inline-note-label">Access</span>
            <strong>${bootstrap?.authEnabled ? "PIN required" : "Instant open"}</strong>
            <span class="gs-meta">${bootstrap?.authEnabled ? "Enter the code from the host phone" : "Open in any browser on this network"}</span>
          </div>
        </div>
        <div class="gs-hero-actions">
          <a class="gs-btn gs-btn-accent" data-link href="/videos">Browse videos</a>
          <button class="gs-btn gs-btn-download" id="downloadAllBtn">Download all files</button>
        </div>
      </div>
    </section>

    <section class="gs-category-grid">
      ${categories.map((category) => `
        <a class="gs-category-card" data-link href="${category.href}">
          <span class="gs-category-kicker">${category.label}</span>
          <strong>${category.count}</strong>
          <span class="gs-category-meta">Open ${category.label.toLowerCase()}</span>
        </a>
      `).join("")}
    </section>

    ${bootstrap.recent.length ? `
      <section class="gs-section">
        <div class="gs-section-head">
          <h2>Recently added</h2>
          <span class="gs-section-meta">${bootstrap.recent.length} highlighted items</span>
        </div>
        <div class="gs-grid">${bootstrap.recent.map((item) => card(item)).join("")}</div>
      </section>
    ` : ""}
  `);

  document.getElementById("downloadAllBtn")?.addEventListener("click", async () => {
    const all = await api("/api/items?category=all");
    downloadItems(all);
  });
}

async function renderLibrary(category, title) {
  shell(`
    <section class="gs-section">
      <div class="gs-section-head">
        <h2>${esc(title)}</h2>
        <span class="gs-section-meta">Select files or download the whole shelf.</span>
      </div>
      <div class="gs-control-card">
        <div class="gs-toolbar">
          <input class="gs-search" id="libSearch" placeholder="Search by file name" value="${esc(state.query)}">
          <div class="gs-toolbar-actions">
            <button class="gs-btn" id="selectBtn">${state.selectMode ? "Selection on" : "Select files"}</button>
            <button class="gs-btn gs-btn-download" id="downloadAllBtn">Download all</button>
          </div>
        </div>
        <div class="gs-select-bar${state.selectMode ? " is-visible" : ""}" id="selectBar">
          <span id="selectCount">0 selected</span>
          <div class="gs-toolbar-actions">
            <button class="gs-btn gs-btn-sm" id="selectAllBtn">Select all</button>
            <button class="gs-btn gs-btn-sm" id="clearSelectBtn">Clear</button>
            <button class="gs-btn gs-btn-accent gs-btn-sm" id="downloadSelectedBtn">Download selected</button>
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
    : '<div class="gs-empty">No matching files were found in this section.</div>';

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
    selectCount.textContent = `${count} selected`;
  }
  document.querySelectorAll("[data-select-card]").forEach((cardElement) => {
    cardElement.classList.toggle("is-selected", state.selected.has(cardElement.dataset.selectCard));
  });
}

function downloadItems(items) {
  if (!items.length) return;
  items.forEach((item, index) => {
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
  const shouldMountReadyPlayer = shouldStartCompatibilityPlayback(item);
  const playbackItem = shouldMountReadyPlayer ? { ...item, streamReady: true } : { ...item, streamReady: false };
  shell(`
    <section class="gs-section">
      <div class="gs-player">
        <div class="gs-player-top">
          <div>
            <h2>${esc(item.title)}</h2>
            <div class="gs-meta">${fmtBytes(item.sizeBytes)}${item.durationMs ? ` | ${fmtDur(item.durationMs)}` : ""}</div>
          </div>
          <div class="gs-toolbar-actions">
            <a class="gs-btn gs-btn-sm" data-link href="/videos">Back to videos</a>
            <a class="gs-btn gs-btn-download" href="${item.downloadUrl}">Download original</a>
          </div>
        </div>
        <div class="gs-badges">
          ${playbackItem.compatibility ? `<span class="gs-badge">${esc(playbackItem.compatibility)}</span>` : ""}
          ${playbackItem.subtitleUrl ? '<span class="gs-badge">Subtitles available</span>' : ""}
          <span class="gs-badge">${esc(playbackItem.playbackMode)}</span>
        </div>
        <div id="playerStage">${renderVideoStage(playbackItem)}</div>
        ${playbackItem.playbackMode !== "DIRECT" ? `
          <div class="gs-compat-inline${playbackItem.streamReady ? " is-visible" : ""}" id="compatInline">
            <span class="gs-badge" data-compat-badge>${esc(playbackItem.compatibilityStatus || (playbackItem.streamReady ? "STREAM LIVE" : "PREPARING"))}</span>
            <div class="gs-compat-inline-copy">
              <strong data-compat-title>${compatibilityHeadline(playbackItem)}</strong>
              <p class="gs-meta" data-compat-message>${esc(compatibilityBody(playbackItem))}</p>
            </div>
            <span class="gs-meta" data-compat-progress>${playbackItem.compatibilityProgressPercent != null ? `${playbackItem.compatibilityProgressPercent}%` : "Preparing"}</span>
          </div>
        ` : ""}
      </div>
    </section>
  `);

  if (playbackItem.streamReady) {
    ensureCompatiblePlayerMounted(playbackItem);
  } else {
    hydrateVideoPlayer(playbackItem);
  }

  if (playbackItem.playbackMode !== "DIRECT" && !playbackItem.streamReady) {
    pollCompat(id, playbackItem);
  }
}

function renderVideoStage(item) {
  return item.streamReady
    ? videoMarkup(item)
    : `
      <div class="gs-compat-card" id="compatStageCard">
        <span class="gs-badge" data-compat-badge>${esc(item.compatibilityStatus || "PREPARING")}</span>
        <h3 data-compat-title>${compatibilityHeadline(item, false)}</h3>
        <p data-compat-message>${esc(compatibilityBody(item, false))}</p>
        <p class="gs-meta" data-compat-progress>${item.compatibilityProgressPercent != null ? `${item.compatibilityProgressPercent}%` : "Preparing"}</p>
      </div>
    `;
}

function videoMarkup(item) {
  const nativeClass = shouldUseNativeVideoPlayer(item) ? " gs-native-video" : "";
  const preload = shouldUseNativeVideoPlayer(item) ? "metadata" : "auto";
  const sourceUrl = shouldUseHlsPlayback(item) ? item.hlsUrl : item.streamUrl;
  return `
    <div class="gs-video-wrap">
      <video id="vPlayer" class="gs-plyr-target${nativeClass}" controls playsinline webkit-playsinline="true" x-webkit-airplay="allow" preload="${preload}" src="${sourceUrl}">
        ${item.subtitleUrl ? `<track kind="subtitles" src="${item.subtitleUrl}" srclang="en" label="Subtitle" default>` : ""}
      </video>
      <div class="gs-video-error" id="vError">
        <strong>Playback needs attention</strong>
        <p id="vErrorText">This browser could not start the video.</p>
        <div class="gs-toolbar-actions">
          <button class="gs-btn gs-btn-accent gs-btn-sm" id="retryVideoBtn">Retry</button>
          <a class="gs-btn gs-btn-download gs-btn-sm" href="${item.downloadUrl}">Download original</a>
        </div>
      </div>
    </div>
  `;
}

function hydrateVideoPlayer(item, options = {}) {
  const video = document.getElementById("vPlayer");
  if (!video || video.dataset.bound === "true") return;
  destroyPlyr();
  video.dataset.bound = "true";
  const useNativePlayer = shouldUseNativeVideoPlayer(item);
  const errorCard = document.getElementById("vError");
  const errorText = document.getElementById("vErrorText");
  let autoRetryUsed = false;

  if (!useNativePlayer && typeof window.Plyr === "function") {
    state.plyr = new window.Plyr(video, {
      iconUrl: "/plyr.svg",
    });
    state.plyrItemId = item.id;
  }

  const clearVideoError = () => {
    if (errorCard) errorCard.classList.remove("is-visible");
  };

  video.addEventListener("loadedmetadata", clearVideoError);
  video.addEventListener("canplay", clearVideoError);
  video.addEventListener("playing", clearVideoError);
  video.addEventListener("error", () => {
    if (errorCard) errorCard.classList.add("is-visible");
    if (errorText) {
      errorText.textContent = item.playbackMode === "DIRECT"
        ? "This browser could not start the original stream. Try downloading the original file."
        : (shouldUseHlsPlayback(item)
            ? "DirectServe is opening the HLS playback path for this device. Retry in a moment."
            : "The compatibility stream is still warming up. Retry in a moment.");
    }
    if (item.playbackMode !== "DIRECT" && !autoRetryUsed) {
      autoRetryUsed = true;
      setTimeout(() => {
        if (location.pathname !== `/player/video/${item.id}`) return;
        clearVideoError();
        video.load();
      }, 1800);
    }
  });
  document.getElementById("retryVideoBtn")?.addEventListener("click", () => {
    clearVideoError();
    if (state.plyr && state.plyrItemId === item.id) {
      state.plyr.restart();
      state.plyr.play().catch(() => {});
    } else {
      video.load();
      video.play().catch(() => {});
    }
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
}

function updateCompatElements(job, streamLive) {
  document.querySelectorAll("[data-compat-message]").forEach((element) => {
    element.textContent = compatibilityBody({
      compatibilityMessage: job.message,
      compatibilityStatus: job.status,
      compatibilityComplete: job.complete,
      streamReady: streamLive,
    }, streamLive);
  });
  document.querySelectorAll("[data-compat-progress]").forEach((element) => {
    element.textContent = job.progressPercent != null ? `${job.progressPercent}%` : (streamLive ? "Streaming" : "Preparing");
  });
  document.querySelectorAll("[data-compat-badge]").forEach((element) => {
    element.textContent = job.status || (streamLive ? "STREAM LIVE" : "PREPARING");
  });
  document.querySelectorAll("[data-compat-title]").forEach((element) => {
    element.textContent = compatibilityHeadline({
      compatibilityStatus: job.status,
      compatibilityComplete: job.complete,
      streamReady: streamLive,
    }, streamLive);
  });
  const inline = document.getElementById("compatInline");
  if (inline) {
    inline.classList.toggle("is-visible", streamLive);
  }
}

function ensureCompatiblePlayerMounted(item) {
  if (document.getElementById("vPlayer")) {
    hydrateVideoPlayer(item);
    return;
  }
  const stage = document.getElementById("playerStage");
  if (!stage) return;
  stage.innerHTML = videoMarkup(item);
  hydrateVideoPlayer(item, { autoplay: true });
}

async function renderPhotoViewer(id) {
  const item = await api(`/api/item/${id}`);
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
            <a class="gs-btn gs-btn-download" href="${item.downloadUrl}">Download original</a>
          </div>
        </div>
        ${item.mimeType === "application/pdf"
          ? `<iframe class="gs-preview" src="${item.streamUrl}" title="${esc(item.title)}"></iframe>`
          : `<img class="gs-preview" src="${item.streamUrl}" alt="${esc(item.title)}">`}
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
        <span class="gs-eyebrow">PIN protected session</span>
        <h1>Enter access PIN</h1>
        <p class="gs-meta">Use the PIN shown on the host phone to unlock this local session.</p>
        <form id="loginForm">
          <input id="pinInput" class="gs-pin" inputmode="numeric" maxlength="6" placeholder="Enter PIN" autofocus>
          ${errorMessage ? `<p class="gs-error-text">${esc(errorMessage)}</p>` : ""}
          <button class="gs-btn gs-btn-accent gs-btn-block" type="submit">Continue</button>
        </form>
      </div>
    </div>`;

  document.getElementById("loginForm")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
      await api("/auth/login", {
        method: "POST",
        body: JSON.stringify({ pin: document.getElementById("pinInput").value.trim() }),
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
  const actionBtnClass = state.bootstrap?.prominentDownloadButton
    ? "gs-btn gs-btn-sm"
    : "gs-btn gs-btn-accent gs-btn-sm";
  const downloadBtnClass = state.bootstrap?.prominentDownloadButton
    ? "gs-btn gs-btn-download gs-btn-sm"
    : "gs-btn gs-btn-sm";
  const action = item.category === "video"
    ? `<a class="${actionBtnClass}" data-link href="/player/video/${item.id}">Play</a>`
    : item.category === "photo"
      ? `<a class="${actionBtnClass}" data-link href="/photo/${item.id}">View</a>`
      : item.category === "music"
        ? `<button class="${actionBtnClass} music-play-btn" data-audio-item-id="${item.id}" data-title="${esc(item.title)}">Play</button>`
        : item.title.toLowerCase().endsWith(".pdf")
          ? `<a class="${actionBtnClass}" data-link href="/photo/${item.id}">Preview</a>`
          : "";

  return `
    <article class="gs-card${selectable && state.selectMode ? " gs-card-selectable" : ""}${state.selected.has(item.id) ? " is-selected" : ""}" data-select-card="${selectable ? item.id : ""}">
      ${selectable ? `<button class="gs-card-toggle${state.selectMode ? " is-visible" : ""}" data-select-toggle="${item.id}">${state.selected.has(item.id) ? "Selected" : "Select"}</button>` : ""}
      ${item.thumbnailUrl
        ? `<img class="gs-card-img" loading="lazy" src="${item.thumbnailUrl}" alt="">`
        : `<div class="gs-card-img gs-card-placeholder"><span>${esc(item.category.toUpperCase())}</span></div>`}
      <div class="gs-card-body">
        <div class="gs-card-topline">
          <span class="gs-card-type">${esc(item.category)}</span>
          ${item.compatibilityLabel ? `<span class="gs-card-tag">${esc(item.compatibilityLabel)}</span>` : ""}
        </div>
        <div class="gs-card-title">${esc(item.title)}</div>
        <div class="gs-meta">${fmtBytes(item.sizeBytes)}${item.durationMs ? ` | ${fmtDur(item.durationMs)}` : ""}</div>
        ${item.compatibilityLabel ? `<div class="gs-card-caption">Browser playback is prepared when needed.</div>` : ""}
        ${item.category === "music" ? `
          <div class="gs-music-row">
            <audio class="gs-audio-player" preload="none" src="${item.streamUrl}">
              Your browser does not support audio playback.
            </audio>
          </div>
        ` : ""}
        <div class="gs-card-actions">
          ${action}
          <a class="${downloadBtnClass}" href="${item.downloadUrl}">Download</a>
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
        element.textContent = "Play";
      });
      if (button) button.textContent = "Pause";
      setNowPlaying({
        type: "Music",
        title,
        element: mediaElement,
        route: location.pathname,
      });
    });

    mediaElement.addEventListener("pause", () => {
      if (mediaElement.ended) return;
      if (button) button.textContent = "Play";
      clearNowPlaying(mediaElement);
    });

    mediaElement.addEventListener("ended", () => {
      if (button) button.textContent = "Play";
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

async function pollCompat(id, item) {
  cancelCompatPolling();
  const route = `/player/video/${id}`;
  const token = ++state.compatPollToken;
  try {
    await api(`/api/compat/${id}/prepare`, { method: "POST" });
  } catch (_) {}

  let attempts = 0;
  async function tick() {
    if (token !== state.compatPollToken || location.pathname !== route) return;
    attempts += 1;
    if (attempts > 600) return;

    try {
      const job = await api(`/api/compat/${id}`);
      if (token !== state.compatPollToken || location.pathname !== route) return;
      const canStartPlayback = shouldStartCompatibilityPlayback(item, job);
      const nextItem = {
        ...item,
        streamReady: canStartPlayback,
        compatibilityStatus: job.status,
        compatibilityMessage: job.message,
        compatibilityProgressPercent: job.progressPercent,
        compatibilityComplete: job.complete,
      };

      updateCompatElements(job, canStartPlayback);
      if (canStartPlayback) {
        ensureCompatiblePlayerMounted(nextItem);
      }
      if (job.complete) {
        cancelCompatPolling();
        return;
      }
      if (job.status === "FAILED") {
        updateCompatElements(job, false);
        return;
      }
    } catch (_) {}

    state.compatPollTimer = setTimeout(tick, 1200);
  }

  state.compatPollTimer = setTimeout(tick, 700);
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
      <div class="gs-now-label">Now playing</div>
      <strong>${esc(state.nowPlaying.title)}</strong>
      <div class="gs-meta">${esc(state.nowPlaying.type)}</div>
    </div>
    <div class="gs-toolbar-actions">
      <button class="gs-btn gs-btn-sm" id="nowPlayingToggleBtn">${state.nowPlaying.element?.paused ? "Resume" : "Pause"}</button>
      ${state.nowPlaying.route ? `<a class="gs-btn gs-btn-sm" data-link href="${state.nowPlaying.route}">Open</a>` : ""}
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
    case "/videos": return "Videos";
    case "/photos": return "Photos";
    case "/music": return "Music";
    case "/files": return "Files";
    default: return "Library";
  }
}

function fmtBytes(bytes) {
  if (!bytes) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
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
