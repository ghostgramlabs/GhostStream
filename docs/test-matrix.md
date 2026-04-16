# DirectServe | Regression & Test Matrix

## Delivery Matrix
| Category | File Type | Pipeline Path | Delivery Mode |
| :--- | :--- | :--- | :--- |
| Video | .mp4 (Web Optimized) | Bypass | Direct (206) |
| Video | .mkv (Incompatible) | Remux/Transcode | HLS -> Direct |
| Audio | .mp3 / .aac | Bypass | Direct (206) |
| Document | .pdf / .docx | Bypass | Direct Download |
| Binary | .apk / .exe | Bypass | Direct Download |
| Archive | .zip / .rar | Bypass | Direct Download |

## Core Validation Matrix

### 1. Request-Driven Triggers (Architecture Compliance)
- [ ] **Discovery/Bootstrap**: Confirm no CPU spike or job spawning.
- [ ] **Library Scroll**: Confirm 100+ items can be scrolled without triggering jobs.
- [ ] **Info Card**: Confirm opening "Item Details" remains passive.
- [ ] **Manual Override**: Verify "Prepare for Browser" button spawns a **LOW** priority job.

### 2. Media Strategy & Stability
- [ ] **Layered Choice**: Verify MKV with AAC audio only remuxes video (Copy), not transcode.
- [ ] **HLS Readiness**: Verify `202 Accepted` is returned for exactly 4 seconds of initial buffer.
- [ ] **Session Stability**: Verify that completing a transcode mid-play does NOT drop the current HLS session.
- [ ] **Cache Persistence**: Verify that the second play of a previously optimized file uses **DIRECT_PREPARED**.

### 3. Seek Behavior
- [ ] **Internal Seek**: Seek within buffer; verify no worker restart.
- [ ] **External Seek**: Seek beyond buffer; verify worker jumps to new offset.
- [ ] **Source Reuse**: Verify the browser attempts to reuse the existing source before restarting.

### 4. Client-Awareness (Cap Probing)
- [ ] **Safari (HEVC)**: Confirm HEVC MKV plays via `DIRECT_ORIGINAL` or Remux.
- [ ] **Chrome (No HEVC)**: Confirm the same file triggers a full Transcode to H.264.

### 5. Failure Recovery
- [ ] **Kill Process**: Terminate worker mid-prep; verify state moves to "FAILED".
- [ ] **Retry**: Click "Manual Retry" in UI; verify job restarts at **HIGH** priority.
- [ ] **Persistence**: Confirm failed jobs don't auto-retry on every list load.

### 6. Generic File Integrity
- [ ] **Range Requests**: Verify large files (>2GB) support resume/range requests.
- [ ] **Concurrency**: Verify 3+ clients can download different files simultaneously.
