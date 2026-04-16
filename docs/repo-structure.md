# DirectServe | Repository Structure

## Module Responsibility Map

### `:app` (Android Application)
- **Coordinator**: Orchestrates server lifecycle and background services.
- **UI**: Jetpack Compose implementation of the library, sharing session, and settings.
- **State**: `MainViewModel` manages UI state and user-initiated manual optimizations.

### `:core:media` (The Compatibility Engine)
- **Analyzer**: `AndroidMediaAnalyzer` extracts metadata and assesses container health.
- **Decision Engine**: `SmartPlaybackDecisionEngine` performs capability-aware mode selection.
- **Pipeline**: `CompatibilityPipeline` coordinates jobs, priority, and preemption.
- **Workers**: Media3-based implementations for Remux, Transmux, and Transcode.
- **Cache**: `TempPlaybackCache` manages optimized assets on disk.

### `:core:network` (The Communication Layer)
- **Ktor Server**: REST API and byte-range/HLS streaming.
- **CapStore**: Persistent registry of client-side browser capabilities.
- **Discovery**: NSD/MDNS manager for local network peer discovery.

### `:webassets` (The Frontend)
- **Player**: Embedded player shell with HLS.js integration.
- **App JS**: Capability probing and player hydration logic.

## Data & Control Flow

### 1. The Passive Path (Browsing / Metadata)
- `Browser` requests `/api/items`.
- `Server` fetches from `StorageRepository`.
- `Pipeline` is **Bypassed** (No work spawned).

### 2. The Active Path (Playback Intent)
- `Browser` requests `/api/compat/{id}/prepare`.
- `Server` uses `SmartPlaybackDecisionEngine` to assess compatibility.
- `Pipeline` spawns `CompatibilityJob` at **HIGH** priority if needed.
- `Server` returns 202 until the **4-second duration threshold** is met.

### 3. The Optimization Path (Manual Batch)
- `App UI` requests `bulkPrepare`.
- `Pipeline` spawns `CompatibilityJobs` at **LOW** priority.
- Jobs are finalized to the `TempPlaybackCache` for future reuse.

## File Categorization Flow
DirectServe distinguishes between "Transformable Media" and "Generic Files":
- **Video/Audio**: Enter the Decision Engine; may trigger Pipeline.
- **Generic (PDF, ZIP, APK, Docs)**: Dispatched directly to the file serving layer (206/200).
