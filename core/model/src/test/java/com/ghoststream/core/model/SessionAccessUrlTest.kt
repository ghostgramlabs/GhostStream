package com.ghoststream.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionAccessUrlTest {
    @Test
    fun `buildFriendlyDisplayUrl appends local suffix when hostname is plain`() {
        val url = buildFriendlyDisplayUrl(
            hostname = "ghoststream-phone",
            port = 43183,
        )

        assertEquals("http://ghoststream-phone.local:43183", url)
    }

    @Test
    fun `buildFriendlyDisplayUrl keeps dotted hostname`() {
        val url = buildFriendlyDisplayUrl(
            hostname = "ghoststream-phone.local",
            port = 43183,
        )

        assertEquals("http://ghoststream-phone.local:43183", url)
    }

    @Test
    fun `buildFriendlyDisplayUrl rejects localhost style names`() {
        assertNull(buildFriendlyDisplayUrl(hostname = "localhost", port = 43183))
        assertNull(buildFriendlyDisplayUrl(hostname = "127.0.0.1", port = 43183))
    }
}
