const app = document.getElementById("app");
const sessionTitle = app.dataset.title || "GhostStream";
const sessionSubtitle = app.dataset.subtitle || "Local-only streaming";

const state = {
  bootstrap: null,
  query: "",
  selected: new Set(),
  selectMode: false,
};

const routes = {
  "/": renderHome,
  "/login": renderLogin,
  "/videos": () => renderLibrary("videos", "Videos", "🎬"),
  "/photos": () => renderLibrary("photos", "Photos", "📷"),
  "/music": () => renderLibrary("music", "Music", "🎵"),
  "/files": () => renderLibrary("files", "Files", "📄"),
};

window.addEventListener("popstate", () => boot());
document.addEventListener("click", (e) => {
  const a = e.target.closest("[data-link]");
  if (!a) return;
  e.preventDefault();
  navigate(a.getAttribute("href"));
});

async function boot() {
  const path = location.pathname;
  if (path === "/login") return renderLogin();

  try {
    state.bootstrap = await api("/api/bootstrap");
    if (path.startsWith("/player/video/")) return renderVideoPlayer(path.split("/").pop());
    if (path.startsWith("/photo/")) return renderPhotoViewer(path.split("/").pop());
    (routes[path] || renderHome)();
  } catch (err) {
    if (err.status === 401) return navigate("/login", true);
    renderError(err.message || "Unable to load GhostStream.");
  }
}

function navigate(path, replace) {
  history[replace ? "replaceState" : "pushState"]({}, "", path);
  boot();
}

async function api(url, opts = {}) {
  const r = await fetch(url, { credentials: "include", headers: { "Content-Type": "application/json", ...(opts.headers || {}) }, ...opts });
  if (!r.ok) {
    let p = {}; try { p = await r.json(); } catch (_) {}
    const e = new Error(p.message || `Request failed (${r.status})`);
    e.status = r.status;
    throw e;
  }
  return (r.headers.get("content-type") || "").includes("json") ? r.json() : r.text();
}

/* ── Shell ── */

function shell(content) {
  const b = state.bootstrap;
  const p = location.pathname;
  app.innerHTML = `
    <div class="gs-shell">
      <nav class="gs-nav">
        <a class="gs-logo" data-link href="/">
          <span class="gs-logo-icon">◈</span>
          <span>${esc(b?.title || sessionTitle)}</span>
        </a>
        <div class="gs-nav-links">
          <a class="gs-tab${p === '/' ? ' on' : ''}" data-link href="/">Home</a>
          <a class="gs-tab${p === '/videos' ? ' on' : ''}" data-link href="/videos">Videos</a>
          <a class="gs-tab${p === '/photos' ? ' on' : ''}" data-link href="/photos">Photos</a>
          <a class="gs-tab${p === '/music' ? ' on' : ''}" data-link href="/music">Music</a>
          <a class="gs-tab${p === '/files' ? ' on' : ''}" data-link href="/files">Files</a>
        </div>
        ${b?.authEnabled ? `<button class="gs-btn gs-btn-sm" id="logoutBtn">Log out</button>` : ""}
      </nav>
      <main class="gs-main">${content}</main>
    </div>`;
  document.getElementById("logoutBtn")?.addEventListener("click", async () => {
    await api("/auth/logout", { method: "POST" });
    navigate("/login", true);
  });
}

/* ── Home ── */

function renderHome() {
  const b = state.bootstrap;
  const cats = [
    { key: "videos", label: "Videos", count: b.categories.videos, icon: "🎬", href: "/videos" },
    { key: "photos", label: "Photos", count: b.categories.photos, icon: "📷", href: "/photos" },
    { key: "music",  label: "Music",  count: b.categories.music,  icon: "🎵", href: "/music" },
    { key: "files",  label: "Files",  count: b.categories.files,  icon: "📄", href: "/files" },
  ];
  const total = cats.reduce((s, c) => s + c.count, 0);
  shell(`
    <section class="gs-hero">
      <h1>Your local media hub</h1>
      <p>No internet. No app install. Just open and enjoy — ${total} items available.</p>
      <div class="gs-hero-actions">
        <button class="gs-btn gs-btn-accent" id="downloadAllBtn">⬇ Download Everything (${total})</button>
      </div>
    </section>

    <div class="gs-cat-row">
      ${cats.map(c => `
        <a class="gs-cat" data-link href="${c.href}">
          <span class="gs-cat-icon">${c.icon}</span>
          <span class="gs-cat-label">${c.label}</span>
          <span class="gs-cat-count">${c.count}</span>
        </a>
      `).join("")}
    </div>

    ${b.recent.length ? `
      <section class="gs-section">
        <h2>Recently added</h2>
        <div class="gs-grid">${b.recent.map(item => card(item)).join("")}</div>
      </section>
    ` : ""}
  `);
  document.getElementById("downloadAllBtn")?.addEventListener("click", () => downloadAllFromBootstrap());
}

/* ── Library ── */

async function renderLibrary(category, title, icon) {
  state.selected.clear();
  state.selectMode = false;
  shell(`
    <section class="gs-section">
      <div class="gs-toolbar">
        <h2>${icon} ${title}</h2>
        <div class="gs-toolbar-right">
          <input class="gs-search" id="libSearch" placeholder="Search…" value="${esc(state.query)}">
          <button class="gs-btn gs-btn-sm" id="selectBtn">☐ Select</button>
          <button class="gs-btn gs-btn-accent gs-btn-sm" id="dlAllBtn">⬇ Download All</button>
        </div>
      </div>
      <div class="gs-select-bar" id="selectBar" style="display:none">
        <span id="selectCount">0 selected</span>
        <button class="gs-btn gs-btn-sm" id="selectAllBtn">Select All</button>
        <button class="gs-btn gs-btn-sm" id="deselectAllBtn">Deselect All</button>
        <button class="gs-btn gs-btn-accent gs-btn-sm" id="dlSelectedBtn">⬇ Download Selected</button>
        <button class="gs-btn gs-btn-sm" id="cancelSelectBtn">Cancel</button>
      </div>
      <div class="gs-grid" id="grid">${skeletons(6)}</div>
    </section>
  `);

  // Wire up search
  const searchEl = document.getElementById("libSearch");
  searchEl?.addEventListener("input", (e) => {
    state.query = e.target.value;
    clearTimeout(window.__t);
    window.__t = setTimeout(() => renderLibrary(category, title, icon), 200);
  });

  // Fetch items
  let items = [];
  try {
    items = await api(`/api/items?category=${encodeURIComponent(category)}&q=${encodeURIComponent(state.query || "")}`);
    const grid = document.getElementById("grid");
    if (grid) {
      grid.innerHTML = items.length
        ? items.map(item => card(item, true)).join("")
        : `<div class="gs-empty">No items found.</div>`;
    }
    attachMusicPlayers();
  } catch (err) {
    const grid = document.getElementById("grid");
    if (grid) grid.innerHTML = `<div class="gs-empty">${esc(err.message)}</div>`;
  }

  // Download All
  document.getElementById("dlAllBtn")?.addEventListener("click", () => {
    downloadItems(items);
  });

  // Select mode
  document.getElementById("selectBtn")?.addEventListener("click", () => toggleSelectMode(true, items));
  document.getElementById("cancelSelectBtn")?.addEventListener("click", () => toggleSelectMode(false, items));
  document.getElementById("selectAllBtn")?.addEventListener("click", () => {
    items.forEach(i => state.selected.add(i.id));
    updateSelectUI(items);
  });
  document.getElementById("deselectAllBtn")?.addEventListener("click", () => {
    state.selected.clear();
    updateSelectUI(items);
  });
  document.getElementById("dlSelectedBtn")?.addEventListener("click", () => {
    const sel = items.filter(i => state.selected.has(i.id));
    if (sel.length) downloadItems(sel);
  });
}

function toggleSelectMode(on, items) {
  state.selectMode = on;
  if (!on) state.selected.clear();
  document.getElementById("selectBar").style.display = on ? "flex" : "none";
  document.querySelectorAll(".gs-card-select").forEach(el => el.style.display = on ? "flex" : "none");
  updateSelectUI(items);
}

function updateSelectUI(items) {
  const count = state.selected.size;
  const el = document.getElementById("selectCount");
  if (el) el.textContent = `${count} selected`;
  document.querySelectorAll(".gs-card-select").forEach(cb => {
    const id = cb.dataset.id;
    cb.classList.toggle("checked", state.selected.has(id));
  });
}

/* ── Download All (individual files) ── */

function downloadItems(items) {
  if (!items.length) return;
  // Stagger downloads slightly to avoid browser blocking
  items.forEach((item, i) => {
    setTimeout(() => {
      const a = document.createElement("a");
      a.href = item.downloadUrl;
      a.download = item.title || "";
      a.style.display = "none";
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
    }, i * 350);
  });
}

function downloadAllFromBootstrap() {
  const items = state.bootstrap?.recent || [];
  if (!items.length) return;
  // Fetch all items then download
  api("/api/items?category=all").then(all => downloadItems(all)).catch(() => downloadItems(items));
}

/* ── Video Player ── */

async function renderVideoPlayer(id) {
  const item = await api(`/api/item/${id}`);
  const ready = item.streamReady;
  shell(`
    <section class="gs-section">
      <div class="gs-player">
        <div class="gs-player-top">
          <div>
            <h2>${esc(item.title)}</h2>
            <span class="gs-meta">${fmtBytes(item.sizeBytes)}${item.durationMs ? ` · ${fmtDur(item.durationMs)}` : ""}</span>
          </div>
          <div class="gs-actions">
            <a class="gs-btn gs-btn-sm" data-link href="/videos">← Back</a>
            <a class="gs-btn gs-btn-accent gs-btn-sm" href="${item.downloadUrl}">⬇ Download</a>
          </div>
        </div>
        ${item.compatibility || item.subtitleUrl ? `
          <div class="gs-badges">
            ${item.compatibility ? `<span class="gs-badge">${esc(item.compatibility)}</span>` : ""}
            ${item.subtitleUrl ? `<span class="gs-badge">Subtitles</span>` : ""}
          </div>
        ` : ""}
        ${ready ? `
          <div class="gs-video-wrap">
            <video id="vPlayer" controls playsinline autoplay preload="auto" src="${item.streamUrl}">
              ${item.subtitleUrl ? `<track kind="subtitles" src="${item.subtitleUrl}" srclang="en" label="Subtitle" default>` : ""}
            </video>
            <div class="gs-video-err" id="vErr" style="display:none">
              <div style="font-size:2.4rem">⚠️</div>
              <p id="vErrMsg">This format may not be supported.</p>
              <div class="gs-actions">
                <button class="gs-btn gs-btn-accent gs-btn-sm" id="vRetry">Retry</button>
                <a class="gs-btn gs-btn-sm" href="${item.downloadUrl}">Download Original</a>
              </div>
            </div>
          </div>
        ` : `
          <div class="gs-compat-card">
            <span class="gs-badge">${esc(item.compatibilityStatus || "PENDING")}</span>
            <p id="compatMsg">${esc(item.compatibilityMessage || "Optimizing for browser playback…")}</p>
            <p class="gs-meta" id="compatProg">${item.compatibilityProgressPercent != null ? item.compatibilityProgressPercent + "%" : "Preparing…"}</p>
          </div>
        `}
      </div>
    </section>
  `);

  const v = document.getElementById("vPlayer");
  if (v) {
    v.addEventListener("error", () => {
      document.getElementById("vErr").style.display = "flex";
      const msg = v.error?.code === 4
        ? "This format isn't supported by your browser. Try downloading."
        : "Playback failed. Check connection and retry.";
      document.getElementById("vErrMsg").textContent = msg;
    });
    document.getElementById("vRetry")?.addEventListener("click", () => {
      document.getElementById("vErr").style.display = "none";
      v.load(); v.play().catch(() => {});
    });
  }
  if (!ready && item.playbackMode !== "DIRECT") pollCompat(id);
}

/* ── Photo Viewer ── */

async function renderPhotoViewer(id) {
  const item = await api(`/api/item/${id}`);
  let prev = null, next = null;
  try {
    const all = await api("/api/items?category=photos");
    const idx = all.findIndex(p => p.id === id);
    if (idx > 0) prev = all[idx - 1].id;
    if (idx >= 0 && idx < all.length - 1) next = all[idx + 1].id;
  } catch (_) {}

  shell(`
    <section class="gs-section">
      <div class="gs-player">
        <div class="gs-player-top">
          <div>
            <h2>${esc(item.title)}</h2>
            <span class="gs-meta">${fmtBytes(item.sizeBytes)}</span>
          </div>
          <div class="gs-actions">
            ${prev ? `<a class="gs-btn gs-btn-sm" data-link href="/photo/${prev}">←</a>` : ""}
            ${next ? `<a class="gs-btn gs-btn-sm" data-link href="/photo/${next}">→</a>` : ""}
            <a class="gs-btn gs-btn-sm" data-link href="/photos">Gallery</a>
            <a class="gs-btn gs-btn-accent gs-btn-sm" href="${item.downloadUrl}">⬇ Download</a>
          </div>
        </div>
        ${item.mimeType === "application/pdf"
          ? `<iframe class="gs-preview" src="${item.streamUrl}" title="${esc(item.title)}"></iframe>`
          : `<img class="gs-preview" src="${item.streamUrl}" alt="${esc(item.title)}">`}
      </div>
    </section>
  `);
}

/* ── Login ── */

function renderLogin(err = "") {
  app.innerHTML = `
    <div class="gs-center">
      <div class="gs-login">
        <div class="gs-logo" style="justify-content:center;margin-bottom:20px">
          <span class="gs-logo-icon" style="font-size:2rem">◈</span>
          <span style="font-size:1.4rem;font-weight:700">GhostStream</span>
        </div>
        <p class="gs-meta" style="text-align:center">Enter the PIN shown on the host phone.</p>
        <form id="loginForm">
          <input id="pinInput" class="gs-pin" inputmode="numeric" maxlength="6" placeholder="• • • •" autofocus>
          ${err ? `<p class="gs-err">${esc(err)}</p>` : ""}
          <button class="gs-btn gs-btn-accent" style="width:100%;margin-top:16px" type="submit">Continue</button>
        </form>
      </div>
    </div>`;
  document.getElementById("loginForm")?.addEventListener("submit", async (e) => {
    e.preventDefault();
    try {
      await api("/auth/login", { method: "POST", body: JSON.stringify({ pin: document.getElementById("pinInput").value.trim() }) });
      navigate("/", true);
    } catch (err) { renderLogin(err.message || "Wrong PIN."); }
  });
}

function renderError(msg) {
  app.innerHTML = `<div class="gs-center"><div class="gs-login"><h1>GhostStream</h1><p class="gs-err">${esc(msg)}</p></div></div>`;
}

/* ── Card Component ── */

function card(item, selectable = false) {
  const cat = item.category;
  const action =
    cat === "video" ? `<a class="gs-btn gs-btn-accent gs-btn-sm" data-link href="/player/video/${item.id}">▶ Play</a>` :
    cat === "photo" ? `<a class="gs-btn gs-btn-accent gs-btn-sm" data-link href="/photo/${item.id}">View</a>` :
    cat === "music" ? `<button class="gs-btn gs-btn-accent gs-btn-sm music-play-btn" data-url="${item.streamUrl}" data-title="${esc(item.title)}">▶ Play</button>` :
    item.title.toLowerCase().endsWith(".pdf") ? `<a class="gs-btn gs-btn-accent gs-btn-sm" data-link href="/photo/${item.id}">Preview</a>` :
    "";

  return `
    <article class="gs-card" data-id="${item.id}">
      ${selectable ? `<div class="gs-card-select" data-id="${item.id}" style="display:${state.selectMode ? 'flex' : 'none'}" onclick="toggleSelect('${item.id}')"></div>` : ""}
      ${item.thumbnailUrl
        ? `<img class="gs-card-img" loading="lazy" src="${item.thumbnailUrl}" alt="">`
        : `<div class="gs-card-img gs-card-placeholder"><span>${cat === "video" ? "🎬" : cat === "photo" ? "📷" : cat === "music" ? "🎵" : "📄"}</span></div>`}
      <div class="gs-card-body">
        <div class="gs-card-title">${esc(item.title)}</div>
        <div class="gs-meta">${fmtBytes(item.sizeBytes)}${item.durationMs ? ` · ${fmtDur(item.durationMs)}` : ""}</div>
        ${cat === "music" ? `
          <div class="gs-music-row">
            <audio preload="none" src="${item.streamUrl}"></audio>
            <div class="gs-music-bar"><span class="gs-music-fill"></span></div>
            <span class="gs-music-time gs-meta">0:00</span>
          </div>
        ` : ""}
        <div class="gs-card-actions">
          ${action}
          <a class="gs-btn gs-btn-sm" href="${item.downloadUrl}">⬇</a>
        </div>
      </div>
    </article>`;
}

// Global function for inline onclick
window.toggleSelect = function(id) {
  if (state.selected.has(id)) state.selected.delete(id);
  else state.selected.add(id);
  updateSelectUI([]);
};

function skeletons(n) {
  return Array.from({ length: n }, () => `
    <article class="gs-card gs-skeleton">
      <div class="gs-skel gs-skel-img"></div>
      <div class="gs-card-body">
        <div class="gs-skel gs-skel-line" style="width:70%"></div>
        <div class="gs-skel gs-skel-line" style="width:40%"></div>
      </div>
    </article>
  `).join("");
}

/* ── Music ── */

function attachMusicPlayers() {
  document.querySelectorAll(".music-play-btn").forEach(btn => {
    btn.addEventListener("click", () => {
      const card = btn.closest(".gs-card");
      const audio = card?.querySelector("audio");
      if (!audio) return;
      if (!audio.paused) { audio.pause(); btn.textContent = "▶ Play"; return; }
      document.querySelectorAll("audio").forEach(a => { if (a !== audio) a.pause(); });
      document.querySelectorAll(".music-play-btn").forEach(b => { b.textContent = "▶ Play"; });
      audio.play().catch(() => {});
      btn.textContent = "⏸ Pause";
      audio.ontimeupdate = () => {
        const fill = card.querySelector(".gs-music-fill");
        const time = card.querySelector(".gs-music-time");
        if (fill && audio.duration > 0) fill.style.width = `${(audio.currentTime / audio.duration) * 100}%`;
        if (time) time.textContent = audio.duration > 0 ? `${fmtDur(audio.currentTime * 1000)} / ${fmtDur(audio.duration * 1000)}` : fmtDur(audio.currentTime * 1000);
      };
      audio.onended = () => { btn.textContent = "▶ Play"; };
    });
  });
}

/* ── Compat Polling ── */

async function pollCompat(id) {
  try { await api(`/api/compat/${id}/prepare`, { method: "POST" }); } catch (_) {}
  let attempts = 0;
  async function tick() {
    if (++attempts > 20) return;
    try {
      const job = await api(`/api/compat/${id}`);
      const m = document.getElementById("compatMsg");
      const p = document.getElementById("compatProg");
      if (m) m.textContent = job.message;
      if (p) p.textContent = job.progressPercent != null ? `${job.progressPercent}%` : "Preparing…";
      if (job.ready) return renderVideoPlayer(id);
      if (job.status === "FAILED") return;
    } catch (_) {}
    setTimeout(tick, 1500);
  }
  setTimeout(tick, 800);
}

/* ── Utilities ── */

function fmtBytes(b) {
  if (!b) return "0 B";
  const u = ["B", "KB", "MB", "GB"];
  let v = b, i = 0;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v >= 100 || i === 0 ? v.toFixed(0) : v.toFixed(1)} ${u[i]}`;
}

function fmtDur(ms) {
  const s = Math.floor(ms / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return h > 0 ? `${h}:${String(m).padStart(2,"0")}:${String(sec).padStart(2,"0")}` : `${m}:${String(sec).padStart(2,"0")}`;
}

function esc(s) {
  return String(s ?? "").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;");
}

boot();
