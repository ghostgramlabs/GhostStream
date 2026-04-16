# DirectServe | Claude Code Project Guidance

## Architecture Summary
DirectServe is a **File Sharing + Media Optimization** platform. It uses a **Request-Driven** philosophy to minimize battery usage on the Android host.

### The Golden Rule
> "If the user hasn't explicitly asked to play it or prepare it, the server must not touch it."

## Key Decision Points
- **MIME Awareness**: The compatibility pipeline starts only for transformable media (`video/*`, `audio/*`). Generic files (ZIP, APK, PDF, documents) use standard Ktor file streaming.
- **Capability Scoping**: Decisions in `SmartPlaybackDecisionEngine` are scoped to the `remoteHost` requesting the file, not global.
- **HLS Readiness**: Return `202 Accepted` until the `HlsReadinessValidator` confirms at least **4 seconds** of buffered duration.

## JobPriority & Conversion
- **CRITICAL**: Seeks / Active session recovery.
- **HIGH**: Play intent / Manual Retry.
- **LOW**: Manual optimization ONLY. (Manual batch).
- **Strategy**: Prefer `Direct Play` > `Remux` > `Transmux` > `Full Transcode`.

## Implementation Guardrails
- **No Background Workers**: Do not implement any "automated" cleanup or warmup tasks without explicit manual triggers.
- **HLS Stability**: A running HLS session is "stable." Do not attempt to force-switch to Direct MP4 mid-play; wait for the next "Play" intent.
- **Seek Behavior**: Attempt source reuse first. Only jump the worker if current source/buffer cannot satisfy the offset.
- **Product Promise**: Position DirectServe as "Direct play first, smart compatibility fallback when needed."

## Task Commands
- **Assemble**: `./gradlew assembleDebug`
- **Audit**: Run `./gradlew test` in `:core:media` to verify decision logic.
