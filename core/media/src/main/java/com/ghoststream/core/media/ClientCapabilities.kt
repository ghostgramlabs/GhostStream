package com.ghoststream.core.media

import kotlinx.serialization.Serializable

/**
 * Represents the playback capabilities of a connected browser client.
 * Collected via JS probing (MediaCapabilities API) and sent to the server.
 */
@Serializable
data class ClientCapabilities(
    val browserFamily: String? = null,
    val browserVersion: String? = null,
    val os: String? = null,
    
    // Video Codec Support
    val supportsAvc: Boolean = true, // Almost universal
    val supportsHevc: Boolean = false,
    val supportsVp9: Boolean = false,
    val supportsAv1: Boolean = false,
    
    // Audio Codec Support
    val supportsAac: Boolean = true, // Almost universal
    val supportsMp3: Boolean = true,
    val supportsOpus: Boolean = false,
    val supportsAc3: Boolean = false,
    val supportsEac3: Boolean = false,
    
    // Container/Platform Support
    val supportsMse: Boolean = true,
    val supportsHlsNatively: Boolean = false, // e.g., Safari
    
    // Advanced
    val supportsHdr: Boolean = false,
    val isPowerEfficient: Boolean = true,
) {
    companion object {
        /** Safe defaults for an unknown/legacy client. */
        val DEFAULT = ClientCapabilities(
            supportsAvc = true,
            supportsAac = true,
            supportsMp3 = true,
            supportsMse = true
        )
    }
}
