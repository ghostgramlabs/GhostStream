# Graph Report - C:\Users\sudhi\.gemini\antigravity\scratch\GhostStream\app\src  (2026-04-19)

## Corpus Check
- 13 files · ~22,432 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 137 nodes · 124 edges · 14 communities detected
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]
- [[_COMMUNITY_Community 12|Community 12]]
- [[_COMMUNITY_Community 13|Community 13]]

## God Nodes (most connected - your core abstractions)
1. `MainViewModel` - 44 edges
2. `GhostStreamForegroundService` - 17 edges
3. `DebugLogRepository` - 10 edges
4. `SharingCoordinator` - 5 edges
5. `AppLanguages` - 4 edges
6. `LocaleManager` - 3 edges
7. `GhostStreamApplication` - 2 edges
8. `MainActivity` - 2 edges
9. `Routes` - 1 edges
10. `AppLanguage` - 1 edges

## Surprising Connections (you probably didn't know these)
- None detected - all connections are within the same source files.

## Communities

### Community 0 - "Community 0"
Cohesion: 0.05
Nodes (1): MainViewModel

### Community 1 - "Community 1"
Cohesion: 0.1
Nodes (1): GhostStreamForegroundService

### Community 2 - "Community 2"
Cohesion: 0.14
Nodes (13): AppEvent, MainUiState, NavigateHistory, NavigateHome, NavigateLibrary, NavigateNetworkSetup, NavigateSession, OpenExternalUrl (+5 more)

### Community 3 - "Community 3"
Cohesion: 0.15
Nodes (8): Failure, NeedsNetwork, NoContent, Ready, SharePreflightResult, ShareStartResult, SharingCoordinator, Started

### Community 4 - "Community 4"
Cohesion: 0.17
Nodes (2): MainActivity, Routes

### Community 5 - "Community 5"
Cohesion: 0.18
Nodes (1): DebugLogRepository

### Community 6 - "Community 6"
Cohesion: 0.33
Nodes (2): AppLanguage, AppLanguages

### Community 7 - "Community 7"
Cohesion: 0.5
Nodes (1): LocaleManager

### Community 8 - "Community 8"
Cohesion: 0.67
Nodes (1): GhostStreamApplication

### Community 9 - "Community 9"
Cohesion: 0.67
Nodes (0): 

### Community 10 - "Community 10"
Cohesion: 1.0
Nodes (0): 

### Community 11 - "Community 11"
Cohesion: 1.0
Nodes (1): AppContainer

### Community 12 - "Community 12"
Cohesion: 1.0
Nodes (0): 

### Community 13 - "Community 13"
Cohesion: 1.0
Nodes (0): 

## Knowledge Gaps
- **23 isolated node(s):** `Routes`, `AppLanguage`, `AppContainer`, `MainUiState`, `AppEvent` (+18 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Community 10`** (2 nodes): `LanguageSelectionScreen.kt`, `LanguageSelectionScreen()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 11`** (2 nodes): `AppContainer`, `AppContainer.kt`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 12`** (2 nodes): `Theme.kt`, `GhostStreamTheme()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 13`** (1 nodes): `Color.kt`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainViewModel` connect `Community 0` to `Community 9`?**
  _High betweenness centrality (0.112) - this node is a cross-community bridge._
- **What connects `Routes`, `AppLanguage`, `AppContainer` to the rest of the system?**
  _23 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.05 - nodes in this community are weakly interconnected._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.1 - nodes in this community are weakly interconnected._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.14 - nodes in this community are weakly interconnected._