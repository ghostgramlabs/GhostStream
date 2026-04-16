# DirectServe | AI Agent Instructions

## Project Overview
**DirectServe** is a professional-grade local network file and media sharing platform. It enables users to serve any file type (video, audio, images, PDFs, documents, archives, binaries) from an Android host to nearby devices via a browser, with a specialized "Smart Compatibility" engine for on-the-fly media optimization.

## Product Vision
- **Generic File Sharing**: Support reliable delivery of all file types.
- **Smart Media Optimization**: Only transcode/transform media when the target browser lacks native support.
- **Strictly Request-Driven**: Preserve host resources by avoiding all eager/background processing.
- **Privacy-First**: Local-only, P2P-style delivery without cloud intermediaries.

## Hard Architectural Rules (Strictly Request-Driven)
All AI interactions MUST strictly enforce these rules to prevent regressions in battery life and performance.

### 1. The Trigger Rule
Compatibility/Optimization work may **ONLY** start from explicit user intents:
- **Play**: Initiating a media session.
- **Seek**: Jumping to an unprepared timestamp (conditional).
- **Manual "Prepare"**: Explicitly requested from the host UI.
- **Retry**: Manual recovery after failure.

### 2. Forbidden Triggers (Zero Tolerance)
Work must **NEVER** start from:
- Dashboard/Library/List loading.
- "Info" or "Details" view card appearance.
- App startup or network discovery phases.
- Device lifecycle hooks or automated timers.
- **No background warmup, prefetch, or periodic maintenance jobs.**

### 3. Playback Preference Logic (Layered Compatibility)
DirectServe uses a tiered approach to deliver media with the least CPU impact:
1. **DIRECT_ORIGINAL**: Serve the raw file if the specific client supports the codec (e.g., HEVC on Safari).
2. **DIRECT_PREPARED**: Serve a finalized optimization from `TempPlaybackCache`.
3. **HLS_WHILE_PREPARING**: Stream via HLS/fMP4 while the worker finalizes.
4. **Full Transcode**: Last-resort fallback to H.264 "Gold Standard."

### 4. JobPriority Rules
- **CRITICAL**: Active Seek or playback continuity recovery.
- **HIGH**: Initial Play intent or Manual Retry.
- **LOW**: Manual batch preparation ONLY. 
- **STRICT RULE**: `LOW` priority must **NEVER** be auto-triggered. It is reserved exclusively for explicit manual batch actions.

### 5. Conversion Strategy
The engine must prefer surgical fixes over full re-encoding:
- `Direct Play` > `Remux (Container fix)` > `Transmux (Audio/Faststart fix)` > `Full Transcode`.

## HLS & Session Stability
- **HLS Finalization**: HLS is a transitionary state that "finalizes" into a `DIRECT_PREPARED` asset.
- **Stability Rule**: Do not force-switch a stable HLS session to Direct MP4 mid-playback. Maintain HLS for session continuity and prefer the finalized direct file only for the **next** playback start in a fresh player instance.
- **Readiness**: Never serve a manifest until at least **4 seconds** of media duration is verified.

## Seek Behavior (Source-First)
- **Source-First**: Always attempt to satisfy a seek request from the current playback source or local browser buffer first.
- **Minimalist**: New work or worker jumps should only be triggered when the existing source/buffer cannot satisfy the requested offset.

## Failure Handling Philosophy
- **Fallbacks**: If optimization fails, offer a standard file download.
- **Retry Rules**: Manual retry must clear the failure state and restart with `HIGH` priority.
- **No Infinite Loops**: Fatal errors must invalidate the choice for the current session to prevent repeated failing attempts.

## File Handling & Scope
- **Compatibility Pipeline**: Applies ONLY to `video/*` and browser-playable `audio/*`.
- **Standard Serving**: Images, PDFs, Documents, Archives, and Binaries are served via standard `HTTP 206` range requests and bypass the compatibility pipeline entirely.

## Definition of Done
- No new eager-prep triggers introduced.
- Capability-aware decisions are preserved across all routes.
- Branding correctly identifies project as **DirectServe**.
- Build passes `./gradlew assembleDebug`.
