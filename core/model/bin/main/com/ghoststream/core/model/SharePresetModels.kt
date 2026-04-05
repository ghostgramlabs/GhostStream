package com.ghoststream.core.model

data class SharePreset(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val lastUsedAtEpochMs: Long? = null,
    val itemUris: List<String> = emptyList(),
    val folderUris: List<String> = emptyList(),
    val itemCount: Int = 0,
    val totalBytes: Long = 0L,
)

enum class DiagnosticLevel {
    GOOD,
    INFO,
    WARNING,
    ACTION,
}

data class DiagnosticCheck(
    val id: String,
    val title: String,
    val detail: String,
    val level: DiagnosticLevel,
)

data class ConnectionDiagnostics(
    val summary: String,
    val checks: List<DiagnosticCheck>,
) {
    val actionCount: Int
        get() = checks.count { it.level == DiagnosticLevel.ACTION }

    val warningCount: Int
        get() = checks.count { it.level == DiagnosticLevel.WARNING }
}

fun buildConnectionDiagnostics(
    libraryState: LibraryState,
    sessionState: SessionState,
    nearbyDiscoveryState: NearbyDiscoveryState,
): ConnectionDiagnostics {
    val compatibilityCount = libraryState.items.count { item ->
        item.category == MediaCategory.VIDEO && item.playbackDecision.mode != PlaybackMode.DIRECT
    }
    val accessUrl = sessionState.resolvedAccessUrl()
    val checks = buildList {
        add(
            if (libraryState.summary.totalItems > 0) {
                DiagnosticCheck(
                    id = "content",
                    title = "Content ready",
                    detail = "${libraryState.summary.totalItems} items are selected for sharing.",
                    level = DiagnosticLevel.GOOD,
                )
            } else {
                DiagnosticCheck(
                    id = "content",
                    title = "Add content",
                    detail = "Select files or a folder before starting your local session.",
                    level = DiagnosticLevel.ACTION,
                )
            },
        )

        add(
            if (sessionState.networkAvailability.isReady && sessionState.networkAvailability.localAddress != null) {
                DiagnosticCheck(
                    id = "network",
                    title = "Local network ready",
                    detail = sessionState.networkAvailability.helperText,
                    level = DiagnosticLevel.GOOD,
                )
            } else {
                DiagnosticCheck(
                    id = "network",
                    title = "Network needed",
                    detail = sessionState.networkAvailability.helperText,
                    level = DiagnosticLevel.ACTION,
                )
            },
        )

        add(
            when {
                sessionState.isSharing && accessUrl != null -> DiagnosticCheck(
                    id = "browser",
                    title = "Browser access live",
                    detail = "QR, copy link, and browser access are ready for nearby devices.",
                    level = DiagnosticLevel.GOOD,
                )

                sessionState.isSharing -> DiagnosticCheck(
                    id = "browser",
                    title = "Browser link preparing",
                    detail = "DirectServe is still preparing the local browser link.",
                    level = DiagnosticLevel.WARNING,
                )

                else -> DiagnosticCheck(
                    id = "browser",
                    title = "Browser path available",
                    detail = "When you start sharing, DirectServe will still use the same QR and browser flow.",
                    level = DiagnosticLevel.INFO,
                )
            },
        )

        add(
            when {
                compatibilityCount == 0 -> DiagnosticCheck(
                    id = "compatibility",
                    title = "Playback mostly direct",
                    detail = "Your current selection is ready for fast browser playback.",
                    level = DiagnosticLevel.GOOD,
                )

                compatibilityCount == 1 -> DiagnosticCheck(
                    id = "compatibility",
                    title = "One video may need prep",
                    detail = "Use Prepare Now in the library if you want playback ready before guests join.",
                    level = DiagnosticLevel.WARNING,
                )

                else -> DiagnosticCheck(
                    id = "compatibility",
                    title = "$compatibilityCount videos may need prep",
                    detail = "Pre-warming larger videos can make playback feel smoother on phones and TVs.",
                    level = DiagnosticLevel.WARNING,
                )
            },
        )

        add(
            when {
                nearbyDiscoveryState.lastError != null -> DiagnosticCheck(
                    id = "nearby",
                    title = "Nearby discovery optional",
                    detail = "Discovery is having trouble, but QR and the browser link still work normally.",
                    level = DiagnosticLevel.INFO,
                )

                nearbyDiscoveryState.devices.isNotEmpty() -> DiagnosticCheck(
                    id = "nearby",
                    title = "Nearby app discovery live",
                    detail = "${nearbyDiscoveryState.devices.size} DirectServe device(s) found on this network.",
                    level = DiagnosticLevel.GOOD,
                )

                else -> DiagnosticCheck(
                    id = "nearby",
                    title = "Nearby app discovery scanning",
                    detail = "Optional: nearby DirectServe devices will appear here when found.",
                    level = DiagnosticLevel.INFO,
                )
            },
        )
    }

    val summary = when {
        checks.any { it.level == DiagnosticLevel.ACTION } -> "Needs attention before sharing"
        checks.any { it.level == DiagnosticLevel.WARNING } -> "Ready, with a few watchouts"
        else -> "Ready for nearby sharing"
    }
    return ConnectionDiagnostics(
        summary = summary,
        checks = checks,
    )
}
