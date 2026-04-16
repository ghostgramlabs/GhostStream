# DirectServe Technical & Functional Manual

**DirectServe** is a privacy-first, high-performance media and file sharing application. It allows users to host a temporary, localized media server on their Android device, enabling browsers on other devices (Phones, PCs, TVs) to stream videos, photos, music, and download documents without installing any apps.

---

## 🏗️ Architecture & Tech Stack

DirectServe is built with a **Clean Architecture** approach using a multi-module Gradle project structure. This ensures separation of concerns between core logic (Ktor, Media) and UI features (Compose).

### 🛠️ Core Tech Stack
| Layer | Technologies |
| :--- | :--- |
| **Language** | Kotlin (100%) |
| **UI Framework** | Jetpack Compose |
| **Server Engine** | Ktor (CIO Engine) |
| **Web Frontend** | Vanilla JavaScript, CSS Grid/Flexbox |
| **Media Engine** | MediaExtractor, MediaCodec, Fragments of MP4 |
| **Web Player** | Plyr.js, Hls.js |
| **State Management** | Kotlin Coroutines & Flow |

### 📂 Module Structure
- `:app`: Entry point, Application class, and the Persistent Foreground Service.
- `:core:network`: The Ktor-powered server core and Network Inspection logic.
- `:core:media`: Intelligent media analyzer and the Compatibility Pipeline.
- `:core:session`: Coordination of active sharing, security, and NSD discovery.
- `:core:resources`: Localization hub for 26 supported languages.
- `:feature:*`: Modular UI screens (Library, Home, Settings, Session).
- `:webassets`: The static assets (JS, CSS, HTML) served to browser clients.

---

## 🚀 Core Functionality

### 1. File & Media Sharing (Sender -> Receiver)
The primary mode of operation. The Android device acts as a **Dynamic Content Server**.
- **Discovery**: Uses **mDNS/NSD (Network Service Discovery)** to broadcast the service.
- **Bootstrapping**: When a browser hits the local URL, the server sends a JSON payload (`/api/bootstrap`) containing localized strings and session info.
- **Adaptive Playback**: If a video is in an incompatible format for that specific browser, the app automatically prepares a browser-ready stream on-the-fly.

### 2. Reverse Sharing (Receiver -> Sender)
Allows browser users to upload files back to the Android device.
- **Request Flow**: A receiver asks to upload. The Android device owner receives a notification to Accept or Decline.

### 3. Localization Pipeline
DirectServe features a unique **Dual-Registry Localization** system verifying linguistic parity between the app and the website.

---

## 🔍 Media Compatibility Pipeline (The "Brain")

This is the most complex part of DirectServe. It decides how a media file is served:
1. **DIRECT_ORIGINAL**: Raw file served (H.264/AAC in MP4/WebM) if browser-ready.
2. **DIRECT_PREPARED**: Serves a finalized browser-ready version from the `TempPlaybackCache`.
3. **REMUX/TRANSMUX**: Fixes container or audio tracks while keeping video direct.
4. **TRANSCODE**: Tracks are incompatible (e.g., HEVC on legacy Chrome). Uses a **Growing MP4 / HLS Fragment** system to stream while re-encoding.

### 🍎 Apple/Safari HLS Implementation
DirectServe detects Apple devices via User-Agent and serves an **HLS (m3u8) Playlist**. Segments are generated in real-time by the `CompatibilityWorker`.

---

## 🔐 Security & Auth Model
- **PIN System**: Uses a 6-digit PIN for session authorization.
- **Cookie Auth**: Once the PIN is verified, the server issues a session cookie.
- **IP Blocking**: Owners can block specific IPs from the foreground service UI.
- **Network Guards**: The server starts only on private local networks (Wi-Fi, Hotspot).

### 👻 Ghost Mode (Privacy)
When "Ghost Mode" is enabled:
- No file selection is saved between sessions.
- All transcoding caches and thumbnails are wiped immediately upon stopping.
- Browser authentication tokens are invalidated server-side.

---

## 🛠️ Maintenance & Extension

### Modifying the Web UI
The web UI is located in `webassets/src/main/assets/web/`.
- `app.js`: Main logic (Routing, UI Rendering).
- `app.css`: The "Glassmorphism" design system.

---

**DirectServe version 1.0 - Documentation Finalized.**
