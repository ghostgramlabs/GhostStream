# DirectServe: Play Store Listing Details

**DirectServe** is a lightweight, privacy-focused media server and file explorer that turns your Android device into a local cloud. It allows you to share, stream, and preview your media library directly in any web browser on your local network without using any data or third-party servers.

---

## 🚀 App Overview

### **Key Features**
- **Instant Local Streaming**: Stream high-quality videos and music from your phone to any computer, tablet, or smart TV browser on the same Wi-Fi.
- **Universal Format Support**: Plays standard formats and automatically handles advanced formats like **AVI**, **MKV**, **FLV**, **MOV**, **WMV**, and **WEBM** via built-in transcoding.
- **Secure by Design**: Protect your local session with a 6-digit PIN shown on your phone to keep your shared library private.
- **Browser Previews**: View photos, read PDFs, and browse your entire file library without downloading them first.
- **Zero Configuration**: No complex server setup. Just start the server and open the generated link in your browser.
- **Offline First**: Works entirely on your local Wi-Fi or Hotspot. No internet connection is required for sharing.

---

## 🔒 Permissions & Privacy

DirectServe requires the following permissions to provide a seamless local sharing experience. We value your privacy and never upload your files to external servers.

### **Network Permissions**
1.  **INTERNET**: Required to create the local web server and allow other devices on your Wi-Fi to connect to your shared library.
2.  **ACCESS_WIFI_STATE / ACCESS_NETWORK_STATE**: Used to detect your network connection and generate the correct local IP address/URL for your browser session.

### **Storage & Media Permissions**
1.  **READ_MEDIA_VIDEO / IMAGES / AUDIO**: Required on Android 13+ to allow you to select and stream your media files to the web interface.
2.  **READ_EXTERNAL_STORAGE** (Max SDK 32): Required on older Android versions to access and serve your files locally.

### **Service & Notification Permissions**
1.  **FOREGROUND_SERVICE (Data Sync)**: Necessary to keep the media server running even if you switch apps or lock your screen, ensuring your stream doesn't cut out.
2.  **POST_NOTIFICATIONS**: Used to show a "Server Running" persistent notification, allowing you to quickly monitor session status or stop the server.

---

## 🛠️ Data Safety Information

- **Data Collected**: None. DirectServe does not collect any personal data, usage statistics, or analytics.
- **Data Shared**: None. All data transfers happen strictly between your phone and the local devices you authorize via the PIN.
- **Encryption**: The local web interface is standard HTTP for local network compatibility. Access is restricted using a dynamic PIN generated on the host device.

---

## 📋 Technical Requirements
- **Minimum OS**: Android 8.0 (Oreo) or higher.
- **Connectivity**: Single local network (Wi-Fi or Hotspot).
- **Client**: Any modern web browser (Chrome, Safari, Firefox, or Edge).
