const app = document.getElementById("app");
const sessionTitle = app.dataset.title || "GhostStream";
const sessionSubtitle = app.dataset.subtitle || "Local-only streaming";

const state = {
  bootstrap: null,
  query: "",
  nowPlaying: null,
};

const routes = {
  "/": renderHome,
  "/login": renderLogin,
  "/videos": () => renderLibrary("videos", "Videos"),
  "/photos": () => renderLibrary("photos", "Photos"),
  "/music": () => renderLibrary("music", "Music"),
  "/files": () => renderLibrary("files", "Files"),
};

window.addEventListener("popstate", () => boot());
document.addEventListener("click", (event) => {
  const anchor = event.target.closest("[data-link]");
  if (!anchor) return;
  event.preventDefault();
  navigate(anchor.getAttribute("href"));
});

async function boot() {
  const path = window.location.pathname;
  if (path === "/login") {
    renderLogin();
    return;
  }

  try {
    state.bootstrap = await api("/api/bootstrap");
    if (path.startsWith("/player/video/")) {
      return renderVideoPlayer(path.split("/").pop());
    }
    if (path.startsWith("/photo/")) {
      return renderPhotoViewer(path.split("/").pop());
    }
    const route = routes[path] || renderHome;
    route();
  } catch (error) {
    if (error.status === 401) {
      navigate("/login", true);
      return;
    }
    renderError(error.message || "Unable to load GhostStream right now.");
  }
}

function navigate(path, replace = false) {
  if (replace) {
    window.history.replaceState({}, "", path);
  } else {
    window.history.pushState({}, "", path);
  }
  boot();
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
  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    return response.json();
  }
  return response.text();
}

function shell(content, search = true) {
  const bootstrap = state.bootstrap;
  const searchBox = search
    ? `<input class="searchbar" id="globalSearch" placeholder="Search by filename" value="${escapeHtml(state.query)}">`
    : "";

  app.innerHTML = `
    <div class="shell">
      <header class="topbar">
        <div>
          <div class="brand-title">${escapeHtml(bootstrap?.title || sessionTitle)}</div>
          <div class="brand-subtitle">${escapeHtml(bootstrap?.subtitle || sessionSubtitle)}</div>
        </div>
        <nav class="nav-links">
          <a class="nav-link" data-link href="/">Home</a>
          <a class="nav-link" data-link href="/videos">Videos</a>
          <a class="nav-link" data-link href="/photos">Photos</a>
          <a class="nav-link" data-link href="/music">Music</a>
          <a class="nav-link" data-link href="/files">Files</a>
          ${bootstrap?.authEnabled ? `<button class="btn" id="logoutBtn">Log out</button>` : ""}
        </nav>
      </header>
      ${search ? `<div class="section">${searchBox}</div>` : ""}
      ${content}
    </div>
    <div id="nowPlayingDock" class="now-playing-dock${nowPlayingVisible() ? " visible" : ""}">
      ${nowPlayingMarkup()}
    </div>
  `;

  if (bootstrap?.authEnabled) {
    document.getElementById("logoutBtn")?.addEventListener("click", async () => {
      await api("/auth/logout", { method: "POST" });
      navigate("/login", true);
    });
  }

  const input = document.getElementById("globalSearch");
  if (input) {
    input.addEventListener("input", (event) => {
      state.query = event.target.value;
      const targetPath = window.location.pathname === "/" ? "/videos" : window.location.pathname;
      if (targetPath.startsWith("/player") || targetPath.startsWith("/photo")) return;
      if (targetPath !== window.location.pathname) {
        navigate(targetPath, true);
        return;
      }
      clearTimeout(window.__ghoststreamSearchTimer);
      window.__ghoststreamSearchTimer = setTimeout(() => boot(), 180);
    });
  }

  renderNowPlayingDock();
}

function renderHome() {
  const bootstrap = state.bootstrap;
  shell(`
    <section class="hero">
      <h1>Scan, open, play</h1>
      <p>No internet needed. Stream videos, view photos, play music, preview PDFs, or download original files from this local session.</p>
    </section>
    <section class="section">
      <div class="section-head">
        <h2 class="section-title">Browse</h2>
      </div>
      <div class="category-grid">
        ${categoryCard("Videos", bootstrap.categories.videos, "/videos", "Start with movies and clips")}
        ${categoryCard("Photos", bootstrap.categories.photos, "/photos", "Visual gallery for albums and moments")}
        ${categoryCard("Music", bootstrap.categories.music, "/music", "Play tracks right in the browser")}
        ${categoryCard("Files", bootstrap.categories.files, "/files", "Download documents and originals")}
      </div>
    </section>
    <section class="section">
      <div class="section-head">
        <h2 class="section-title">Recently added</h2>
      </div>
      <div class="card-grid">
        ${bootstrap.recent.length ? bootstrap.recent.map(cardMarkup).join("") : `<div class="empty">Nothing has been added yet.</div>`}
      </div>
    </section>
  `);
}

async function renderLibrary(category, title) {
  const items = await api(`/api/items?category=${encodeURIComponent(category)}&q=${encodeURIComponent(state.query || "")}`);
  shell(`
    <section class="section">
      <div class="section-head">
        <h2 class="section-title">${title}</h2>
        <input class="searchbar" id="globalSearch" placeholder="Search by filename" value="${escapeHtml(state.query)}">
      </div>
      <div class="card-grid">
        ${items.length ? items.map(cardMarkup).join("") : `<div class="empty">No matching items found.</div>`}
      </div>
    </section>
  `, false);
  document.getElementById("globalSearch")?.addEventListener("input", (event) => {
    state.query = event.target.value;
    clearTimeout(window.__ghoststreamSearchTimer);
    window.__ghoststreamSearchTimer = setTimeout(() => renderLibrary(category, title), 160);
  });
}

async function renderVideoPlayer(id) {
  const item = await api(`/api/item/${id}`);
  const streamNotice = item.streamReady && item.compatibilityStatus && item.compatibilityStatus !== "READY"
    ? `
      <div class="panel" style="margin-top:16px">
        <div class="badge">${escapeHtml(item.compatibilityStatus)}</div>
        <div style="height:10px"></div>
        <div>${escapeHtml(item.compatibilityMessage || "Playback has started while compatibility work finishes in the background.")}</div>
      </div>
    `
    : "";
  const mediaBlock = item.streamReady
    ? `<video controls playsinline preload="metadata" src="${item.streamUrl}" data-media-title="${escapeHtml(item.title)}" data-media-category="Video" data-media-href="/player/video/${item.id}">
         ${item.subtitleUrl ? `<track kind="subtitles" src="${item.subtitleUrl}" srclang="en" label="Subtitle">` : ""}
       </video>`
    : `
      <div class="panel">
        <div class="badge">${escapeHtml(item.compatibilityStatus || "PENDING")}</div>
        <div style="height:10px"></div>
        <div id="compatMessage">${escapeHtml(item.compatibilityMessage || item.reason || "Optimizing for browser playback...")}</div>
        <div id="compatProgress" class="muted" style="margin-top:8px">${item.compatibilityProgressPercent != null ? `${item.compatibilityProgressPercent}% complete` : "Preparing a browser-friendly playback path."}</div>
      </div>
    `;
  shell(`
    <section class="section">
      <div class="player-shell">
        <div class="section-head">
          <div>
            <h2 class="section-title">${escapeHtml(item.title)}</h2>
            <div class="muted">${escapeHtml(item.reason || "")}</div>
          </div>
          <div class="actions">
            <a class="btn" data-link href="/videos">Back to library</a>
            <a class="btn btn-primary" href="${item.downloadUrl}">Download Original</a>
          </div>
        </div>
        <div class="actions">
          ${item.compatibility ? `<div class="badge">${escapeHtml(item.compatibility)}</div>` : ""}
          ${item.subtitleUrl ? `<div class="badge">Subtitles available</div>` : ""}
        </div>
        <div style="height:16px"></div>
        ${mediaBlock}
        ${streamNotice}
      </div>
    </section>
  `, false);
  if (!item.streamReady && item.playbackMode !== "DIRECT") {
    startCompatibilityPolling(id);
  }
}

async function renderPhotoViewer(id) {
  const item = await api(`/api/item/${id}`);
  shell(`
    <section class="section">
      <div class="player-shell">
        <div class="section-head">
          <div>
            <h2 class="section-title">${escapeHtml(item.title)}</h2>
            <div class="muted">${formatBytes(item.sizeBytes)}</div>
          </div>
          <div class="actions">
            <a class="btn" data-link href="/photos">Back to gallery</a>
            <a class="btn btn-primary" href="${item.downloadUrl}">Download Original</a>
          </div>
        </div>
        ${item.mimeType === "application/pdf"
          ? `<iframe src="${item.streamUrl}" title="${escapeHtml(item.title)}"></iframe>`
          : `<img src="${item.streamUrl}" alt="${escapeHtml(item.title)}">`}
      </div>
    </section>
  `, false);
}

function renderLogin(errorMessage = "") {
  app.innerHTML = `
    <div class="login-wrap">
      <div class="login-card">
        <h1>Enter access PIN</h1>
        <p class="muted">This local session is protected. Enter the PIN shown on the host phone.</p>
        <form id="loginForm">
          <input id="pinInput" class="pin-input" inputmode="numeric" maxlength="6" placeholder="2468">
          ${errorMessage ? `<div class="error">${escapeHtml(errorMessage)}</div>` : ""}
          <div style="height:16px"></div>
          <button class="btn btn-primary" style="width:100%" type="submit">Continue</button>
        </form>
      </div>
    </div>
  `;
  document.getElementById("loginForm")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const pin = document.getElementById("pinInput").value.trim();
    try {
      await api("/auth/login", {
        method: "POST",
        body: JSON.stringify({ pin }),
      });
      navigate("/", true);
    } catch (error) {
      renderLogin(error.message || "That PIN didn't match. Please try again.");
    }
  });
}

function renderError(message) {
  app.innerHTML = `
    <div class="login-wrap">
      <div class="login-card">
        <h1>GhostStream</h1>
        <p class="error">${escapeHtml(message)}</p>
      </div>
    </div>
  `;
}

function categoryCard(title, count, href, description) {
  return `
    <a class="category-card" data-link href="${href}">
      <strong>${title}</strong>
      <div class="muted">${count} available</div>
      <div style="height:8px"></div>
      <div>${description}</div>
    </a>
  `;
}

function cardMarkup(item) {
  const primaryAction =
    item.category === "video" ? `<a class="btn btn-primary" data-link href="/player/video/${item.id}">Play</a>` :
    item.category === "photo" ? `<a class="btn btn-primary" data-link href="/photo/${item.id}">Open</a>` :
    item.category === "music" ? `<audio controls preload="none" src="${item.streamUrl}" data-media-title="${escapeHtml(item.title)}" data-media-category="Music" data-media-href="/music"></audio>` :
    item.title.toLowerCase().endsWith(".pdf") ? `<a class="btn btn-primary" data-link href="/photo/${item.id}">Preview</a>` :
    `<a class="btn btn-primary" href="${item.downloadUrl}">Download</a>`;

  return `
    <article class="media-card">
      ${item.thumbnailUrl ? `<img loading="lazy" src="${item.thumbnailUrl}" alt="${escapeHtml(item.title)}">` : `<div class="thumb-fallback">${escapeHtml(item.category)}</div>`}
      <div class="media-title">${escapeHtml(item.title)}</div>
      <div class="media-meta">
        <span>${formatBytes(item.sizeBytes)}</span>
        ${item.durationMs ? `<span>${formatDuration(item.durationMs)}</span>` : ""}
      </div>
      ${item.compatibilityLabel ? `<div class="badge">${escapeHtml(item.compatibilityLabel)}</div>` : ""}
      ${item.subtitleUrl ? `<div class="badge">Subtitles</div>` : ""}
      ${item.compatibilityStatus && item.compatibilityStatus !== "READY" ? `<div class="muted">${escapeHtml(item.compatibilityStatus)}</div>` : ""}
      <div class="actions">
        ${primaryAction}
        <a class="btn" href="${item.downloadUrl}">Download</a>
      </div>
    </article>
  `;
}

async function startCompatibilityPolling(id) {
  try {
    await api(`/api/compat/${id}/prepare`, { method: "POST" });
  } catch (_) {}

  const maxAttempts = 18;
  let attempts = 0;

  async function tick() {
    attempts += 1;
    try {
      const job = await api(`/api/compat/${id}`);
      const messageNode = document.getElementById("compatMessage");
      const progressNode = document.getElementById("compatProgress");
      if (messageNode) {
        messageNode.textContent = job.message;
      }
      if (progressNode) {
        progressNode.textContent = job.progressPercent != null
          ? `${job.progressPercent}% complete`
          : job.ready
            ? "Playback is ready."
            : "Waiting for compatibility processing.";
      }

      if (job.ready) {
        renderVideoPlayer(id);
        return;
      }
      if (job.status === "FAILED" || attempts >= maxAttempts) {
        return;
      }
      window.setTimeout(tick, 1500);
    } catch (_) {
      if (attempts < maxAttempts) {
        window.setTimeout(tick, 2000);
      }
    }
  }

  window.setTimeout(tick, 800);
}

function formatBytes(bytes) {
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

function formatDuration(durationMs) {
  const totalSeconds = Math.floor(durationMs / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`
    : `${minutes}:${String(seconds).padStart(2, "0")}`;
}

function escapeHtml(input) {
  return String(input ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function nowPlayingVisible() {
  return Boolean(state.nowPlaying);
}

function nowPlayingMarkup() {
  if (!state.nowPlaying) return "";
  const progress = state.nowPlaying.duration > 0
    ? Math.max(0, Math.min(100, (state.nowPlaying.currentTime / state.nowPlaying.duration) * 100))
    : 0;
  return `
    <div class="now-playing-row">
      <div>
        <div class="now-playing-title">Now Playing: ${escapeHtml(state.nowPlaying.title)}</div>
        <div class="now-playing-meta">${escapeHtml(state.nowPlaying.category)} · ${state.nowPlaying.paused ? "Paused" : "Live"}${state.nowPlaying.duration ? ` · ${formatDuration(state.nowPlaying.currentTime * 1000)} / ${formatDuration(state.nowPlaying.duration * 1000)}` : ""}</div>
      </div>
      <div class="actions">
        ${state.nowPlaying.href ? `<a class="btn" data-link href="${state.nowPlaying.href}">Open Player</a>` : ""}
        <button class="btn" id="clearNowPlaying">Dismiss</button>
      </div>
    </div>
    <div class="now-playing-progress"><span style="width:${progress}%"></span></div>
  `;
}

function renderNowPlayingDock() {
  const dock = document.getElementById("nowPlayingDock");
  if (!dock) return;
  dock.className = `now-playing-dock${nowPlayingVisible() ? " visible" : ""}`;
  dock.innerHTML = nowPlayingMarkup();
  document.getElementById("clearNowPlaying")?.addEventListener("click", () => {
    state.nowPlaying = null;
    renderNowPlayingDock();
  });
}

document.addEventListener("play", (event) => {
  const media = event.target;
  if (!(media instanceof HTMLMediaElement)) return;
  state.nowPlaying = {
    title: media.dataset.mediaTitle || "GhostStream media",
    category: media.dataset.mediaCategory || (media.tagName === "VIDEO" ? "Video" : "Audio"),
    href: media.dataset.mediaHref || window.location.pathname,
    currentTime: media.currentTime || 0,
    duration: Number.isFinite(media.duration) ? media.duration : 0,
    paused: false,
  };
  renderNowPlayingDock();
}, true);

document.addEventListener("pause", (event) => {
  const media = event.target;
  if (!(media instanceof HTMLMediaElement) || !state.nowPlaying) return;
  state.nowPlaying = {
    ...state.nowPlaying,
    currentTime: media.currentTime || state.nowPlaying.currentTime,
    duration: Number.isFinite(media.duration) ? media.duration : state.nowPlaying.duration,
    paused: true,
  };
  renderNowPlayingDock();
}, true);

document.addEventListener("timeupdate", (event) => {
  const media = event.target;
  if (!(media instanceof HTMLMediaElement) || !state.nowPlaying) return;
  state.nowPlaying = {
    ...state.nowPlaying,
    currentTime: media.currentTime || 0,
    duration: Number.isFinite(media.duration) ? media.duration : state.nowPlaying.duration,
    paused: media.paused,
  };
  renderNowPlayingDock();
}, true);

boot();
