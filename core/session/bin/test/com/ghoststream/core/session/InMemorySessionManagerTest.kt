package com.ghoststream.core.session

import com.ghoststream.core.model.NetworkAvailability
import com.ghoststream.core.model.NetworkType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemorySessionManagerTest {
    @Test
    fun `auth token is invalid after clear`() = runTest {
        val manager = InMemorySessionManager()
        manager.startSession(
            port = 8080,
            sessionUrl = "http://192.168.1.2:8080",
            hostname = null,
            items = emptyList(),
            folders = emptyList(),
            networkAvailability = NetworkAvailability(
                type = NetworkType.WIFI,
                localAddress = "192.168.1.2",
                isReady = true,
                helperText = "Ready",
            ),
            authEnabled = true,
            pin = "2468",
        )

        val token = manager.generateToken("192.168.1.8")
        assertTrue(manager.validateToken(token))

        manager.clearBrowserAuth()
        assertFalse(manager.validateToken(token))
    }
}

