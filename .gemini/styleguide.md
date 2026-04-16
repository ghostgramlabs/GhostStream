# DirectServe | Style & Architectural Guide

## Coding Conventions
- **Request Context**: Always propagate `remoteHost` or `ClientCapabilities` through to the decision engine.
- **State Management**: Use `StateFlow` to broadcast job updates; ensure UI components are passive observers.
- **Documentation**: All `requestPreparation` calls must include a comment citing the explicit user intent triggering it.

## Architectural Constraints (Media Pipeline)
- **Passive Lists**: The endpoints `/api/items` and `/api/bootstrap` must NEVER trigger preparation or analysis (`triggerPreparation = false`).
- **Pipeline Gating**: All files entering the `CompatibilityPipeline` must first be validated against supported transformation MIME types (`video/*`, `audio/*`).
- **Cache Authority**: The `TempPlaybackCache` is the fuente de verdad for "already-ready" optimized assets. Always check cache before spawning work.

## Critical "Do Not Break" Rules
- **No Eager Preparations**: Prefetching and background warmup are strictly forbidden. 
- **Capability Propagation**: Failure to propagate `remoteHost` IP to the analyzer will cause redundant transcoding (reverting to "Gold Standard" H.264).
- **HLS Readiness Validator**: Manifests must not be served until the `HlsReadinessValidator` confirms at least **4 seconds** of media duration is buffered.
- **Priority Ethics**: `JobPriority.LOW` must never be used for any automated task. It is manual batch only.

## Validation Expectations
- [ ] No `init` block or lifecycle hook triggers a preparation job.
- [ ] HLS manifests are served only after the 4-second duration threshold.
- [ ] Direct file serving (206) is used for all non-media categories.
- [ ] Seek events attempt to reuse the existing buffer/source before jumping the worker.
