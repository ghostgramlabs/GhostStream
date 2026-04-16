# DirectServe — Playback Reliability Improvement Plan

This document outlines the architectural fixes implemented to ensure 100% playback reliability across all browser environments (Chrome, Safari, Firefox, Smart TVs).

## 1. Problem: Growing MP4 Incompatibility
Serving range requests against a file that's still being written (Growing MP4) causes "416 Range Not Satisfiable" or manifest stalls in many browsers.

### The Fix: Mandatory HLS for "In-Progress" Assets
All transformable media that is not yet 100% complete MUST be served via **HLS (m3u8)**. Direct MP4 serving is gated until the job is finalized in the `TempPlaybackCache`.

## 2. Problem: Premature HLS Attachment
Players often attempt to load the manifest before the transcoder has written the first segments, leading to "EXTM3U" parsing errors.

### The Fix: HlsReadinessValidator
The server now enforces a **4.0-second** minimum buffered duration threshold before returning anything other than a `202 Accepted` to the client. This ensures the player starts with a stable buffer.

## 3. Problem: MSE tfhd.base-data-offset resets
Media3's InAppMuxer writes absolute offsets in TFHD boxes, which the web's Media Source Extensions (MSE) strictly forbid.

### The Fix: MseTfhdPatcher
All HLS segments (`.m4s`) are intercepted and patched on-the-fly to use `default-base-is-moof`, ensuring compliance with ISO/IEC 23009-1 and browser MSE requirements.

## 4. Problem: Redundant Transcoding
Lack of capability awareness leads to transcoding HEVC videos for Safari clients that could play them natively.

### The Fix: Client-Aware Decisioning
The `SmartPlaybackDecisionEngine` now receives `ClientCapabilities` probed by the frontend.
- **Safari**: Plays HEVC directly.
- **Chrome**: Triggers a transcode to H.264.
- **Direct Play Preference**: Always preferred for fresh playback starts once the file is cached.

## 5. Problem: Seek Stalls
Seeking into unprepared regions can break the HLS manifest continuity.

### The Fix: Source-First Seeking
The system attempts to satisfy seeks from the current source first. If a jump is required, the `CompatibilityWorker` is restarted with the new offset, and the manifest is updated to reflect the new timeline origin.
