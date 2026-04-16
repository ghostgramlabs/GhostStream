# DirectServe Media Pipeline Architecture

## Preparation Philosophy
DirectServe uses a **Strictly Request-Driven Architecture**. Optimization work is expensive and is performed as a targeted response to a specific playback intent or manual optimization command.

## The Playback Flow
1. **Decision (Client-Aware)**:
   - Server receives intent. Checks original file against client's `ClientCapabilities`.
   - If natively supported (e.g., Safari + HEVC), serve **DIRECT_ORIGINAL**.
2. **Cache Check**:
   - If not natively supported, check `TempPlaybackCache` for a finalized optimization.
   - If exists, serve **DIRECT_PREPARED**.
3. **Pipeline Initialization**:
   - Only if 1 & 2 fail, start a `CompatibilityJob`.
   - Return `202 Accepted` to the client while the first segments are generated.

## HLS Transition & Session Stability
For incompatible media, DirectServe uses fMP4/HLS as a bridging technology:
- **Phase 1 (Live/Transitory)**: STREAMING via HLS while the worker is active. 
- **Phase 2 (Finalized)**: Once the job hits 100%, the output is moved to the cache.
- **Stability Rule**: Do not force-switch a stable HLS session to Direct MP4 mid-playback. Maintain HLS for session continuity and prefer the finalized **DIRECT_PREPARED** file only for the next "Play" intent in a fresh session.

## Layered Compatibility Strategy
The engine follows a tiered preference to minimize CPU load:
1. **Direct Play**: No changes.
2. **Remux**: Repair container structure (e.g., moov-at-front).
3. **Transmux**: Partial fix (e.g., Transcode Audio to AAC, but keep Video Direct).
4. **Full Transcode**: Last-resort fallback to standard H.264.

## HLS Readiness
To prevent player stalls, manifests are never served until the `HlsReadinessValidator` confirms at least **4.0 seconds** of media duration is buffered.

## Seek Behavior (Source-First)
1. **Reuse**: Always attempt to satisfy a seek request from the current playback source or local browser buffer first.
2. **Jump**: Only restart or jump the compatibility worker if the seek target is outside the currently prepared range and cannot be satisfied by the active source.

## Failure & Retry Handling
- **Philosophy**: Fail fast, fallback gracefully, and avoid infinite failing loops.
- **Persistence**: If a job fails, the failure state is cached to prevent repeated automated attempts.
- **Recovery**: A "Manual Retry" button clears the failure state and restarts work at **HIGH** priority.
- **Fallback**: If optimization is impossible, the system provides a direct file download link.
