# DirectServe: Codebase Analysis & Play Store ASO Strategy

Based on a thorough audit of the Android codebase, manifest, services, view models, and web assets, this document presents the confirmed features, marketing strategy, and Play Store listing content for DirectServe.

---

## 1. Confirmed Feature List

### Local Media Server & File Sharing
*   **Evidence:** `core/network/server/KtorGhostStreamServer.kt`, `webassets/src/main/assets/web/app.js` (routes for `/videos`, `/photos`, `/music`, `/files`)
*   **User Value:** Access the phone's media and files from any local web browser (PC, tablet, smart TV) without installing an app on the receiving end.
*   **Marketing Readiness:** **Yes**

### Smart Compatibility Engine (Transcoding)
*   **Evidence:** `core/media/` package, `CompatibilityJob`, `MainViewModel.kt`, HLS streaming logic.
*   **User Value:** Automatically converts incompatible video/audio formats on-the-fly for smooth browser playback without requiring manual conversion. Strictly request-driven to save battery.
*   **Marketing Readiness:** **Yes**

### Live Screen Mirroring (WebRTC)
*   **Evidence:** `MainViewModel.kt` (`requestLiveScreenStart`), WebRTC libraries in `build.gradle.kts`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` in `AndroidManifest.xml`.
*   **User Value:** Mirror the phone's display to a local browser with ultra-low latency. Supports optional PIN protection and regenerating pins mid-session.
*   **Marketing Readiness:** **Yes**

### Device Audio / Microphone Capture for Live Screen
*   **Evidence:** `FOREGROUND_SERVICE_MICROPHONE` in Manifest, `android.hardware.microphone` feature (optional), audio capture logic during screen mirror.
*   **User Value:** Allows viewers of the screen mirror to hear the device's internal audio or the user's voice narration.
*   **Marketing Readiness:** **Yes**

### DLNA / UPnP Smart TV Streaming
*   **Evidence:** `core/network/server/DlnaService.kt`, `core/network/server/DlnaAnnouncer.kt`, `strings.xml` (TV, VLC, Kodi instructions).
*   **User Value:** Discovers and streams directly to smart TVs, game consoles, and media players (VLC, Kodi) on the same Wi-Fi network.
*   **Marketing Readiness:** **Yes**

### Two-Way File Transfers (Uploads to Phone)
*   **Evidence:** `web_upload_prompt_title` ("Drop media here..."), `KtorGhostStreamServer.kt` upload route handling.
*   **User Value:** Not just a server—users can drag and drop files from their computer's browser directly onto the phone.
*   **Marketing Readiness:** **Yes**

### Quick Text / Clipboard Sharing
*   **Evidence:** `webassets/src/main/assets/web/app.js` (Quick Text tab), `MainViewModel.kt` handling text messages.
*   **User Value:** Instantly send links, notes, or snippets of text from a PC browser to the phone's notifications/clipboard.
*   **Marketing Readiness:** **Yes**

### Local-Only & PIN Secured
*   **Evidence:** `settings_privacy_promise_desc` ("Your files stay on this phone"), PIN generation logic in `SessionManager`.
*   **User Value:** Works entirely offline (Wi-Fi/Hotspot) with zero cloud uploading. Sessions can be locked down with a generated PIN.
*   **Marketing Readiness:** **Yes**

---

## 2. Do-Not-Claim List

*   **Cloud Storage or Backup:** DirectServe operates purely locally. It does not back up files to external servers.
*   **Remote/Internet Access:** The server is bound to the local network (LAN). It cannot be accessed over the broader internet.
*   **Unlimited Speed:** Speed is bound by the quality of the local Wi-Fi router or hotspot, not the app itself.
*   **Instant Playback for All Files:** The Smart Compatibility engine is request-driven (no background eager-transcoding), meaning some heavy/unsupported files may take a moment to buffer via HLS.
*   **"Most Secure":** It uses standard HTTP on the local network secured by a PIN, not end-to-end encrypted HTTPS (which requires trusted certificates not feasible for local IPs).

---

## 3. Target Users and Use Cases

**Target Users:**
*   Professionals and students needing to transfer files between mobile and PC without USB cables.
*   Presenters and educators wanting to cast their mobile screen to a classroom or conference room monitor via a browser.
*   Home users looking to stream movies or photos from their phone to a Smart TV or tablet.
*   Travelers in hotels wanting to watch downloaded phone content on a TV using local Wi-Fi/Hotspot.

**Use Cases:**
*   Moving large video recordings from phone to a desktop editing rig using the local network.
*   Showing a live demo of a mobile app on a desktop monitor using Live Screen mirroring.
*   Sending a quick URL from a laptop to a phone using the Quick Text feature.
*   Streaming a local MKV file to a Roku or LG WebOS TV using DLNA.

---

## 4. ASO Keyword Research

*   **Primary Keywords:** local file sharing, screen mirroring, DLNA server, stream to tv, wifi file transfer.
*   **Secondary Keywords:** share to browser, upnp media server, local network share, send files to pc, no internet transfer.
*   **Long-tail Keywords:** share files without cable, mirror screen to browser, local media server for android, dlna streaming app, send text to phone clipboard.
*   **Keywords to Avoid (Misleading):** cloud drive, remote access, internet backup, global file share, encrypted cloud.

---

## 5. Play Store Listing

**App Title (Max 30):** DirectServe: File & Media Cast
**Short Description (Max 80):** Share files, stream media, and mirror your screen locally without the internet.

**Full Description:**
DirectServe turns your Android device into a powerful, local network companion. Whether you need to transfer large files, stream media to a smart TV, or mirror your screen to a computer, DirectServe handles it all securely on your local Wi-Fi or Hotspot—no internet, cables, or cloud accounts required.

Simply start a session on your phone and open the provided link in any web browser (PC, Mac, tablet, or Smart TV). There’s no need to install software on the receiving end.

**Key Features:**
*   **Share to Any Browser:** Browse, preview, and download your phone's photos, videos, music, and documents directly from a clean web interface.
*   **Live Screen Mirroring:** Cast your phone's display to any local browser with ultra-low latency. Great for presentations or demonstrating apps, complete with optional audio/microphone support.
*   **DLNA & Smart TV Ready:** Built-in DLNA support allows your phone to be discovered by smart TVs, game consoles, VLC, and Kodi for seamless big-screen streaming.
*   **Smart Media Streaming:** DirectServe automatically optimizes incompatible video formats on-the-fly, ensuring smooth playback in the browser.
*   **Two-Way File Transfers:** Drag and drop files into the web browser to send them instantly back to your phone.
*   **Quick Text:** Instantly send links, notes, or text snippets from your computer directly to your phone's notifications.
*   **Privacy First:** Everything stays on your local network. No cloud uploads, and sessions can be secured with a PIN.

**What's New (Release Notes v1.0.19):**
*   **Live Screen Stability:** Improved WebRTC stability for seamless screen mirroring across more Android versions.
*   **Smarter Battery Management:** Clearer guidance for background activity permissions to prevent interrupted streams.
*   **Expanded Device Support:** Installable on a wider range of devices, including tablets without built-in microphones.
*   **Global Readiness:** Fully localized support for 26 languages.

**Promotional Tagline:** Your phone's ultimate local network companion.

---

## 6. Screenshot Text (Headline / Subtitle)

1.  **Share to Any Device** / Open your files instantly in any web browser.
2.  **No Internet Required** / Share securely on your local Wi-Fi or Hotspot.
3.  **Live Screen Mirroring** / Cast your display to a browser in real-time.
4.  **Smart TV Ready** / Stream directly to TVs, VLC, and Kodi via DLNA.
5.  **Two-Way Transfers** / Drag and drop files from your PC back to your phone.
6.  **Stream Media Seamlessly** / Play videos and music without downloading first.
7.  **Secure & Private** / Protect your local session with PIN authorization.
8.  **Quick Text Sharing** / Send links and notes instantly to your phone.

---

## 7. Permission Explanation (Policy-Safe)

DirectServe operates completely offline and requires the following permissions to function on your local network:

*   **Foreground Services (Data Sync, Media Projection, Microphone):** Required to keep the local server running and file transfers active while the app is in the background. Media Projection and Microphone are used exclusively for the Live Screen mirroring feature to capture video and optional audio.
*   **Network (Wi-Fi State/Internet):** Used to bind the local web server to your device's local IP address. No internet connection is actually consumed.
*   **Storage / Media:** Required to allow you to select and share your files, photos, and videos with the web interface.
*   **Notifications:** Used to show an ongoing "Session Active" notification, allowing you to easily stop the server at any time.
