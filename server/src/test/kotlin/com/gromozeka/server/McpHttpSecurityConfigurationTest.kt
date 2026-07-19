package com.gromozeka.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class McpHttpSecurityConfigurationTest {
    @Test
    fun `keeps SDK defaults when hosts are not configured`() {
        val configuration = resolveMcpHttpSecurityConfiguration(null)

        assertNull(configuration.allowedHosts)
        assertNull(configuration.allowedOrigins)
    }

    @Test
    fun `normalizes configured hosts and restricts browser origins to the same hosts`() {
        val configuration = resolveMcpHttpSecurityConfiguration(
            " localhost,127.0.0.1,example.tailnet.ts.net,localhost ",
        )

        assertEquals(
            listOf("localhost", "127.0.0.1", "example.tailnet.ts.net"),
            configuration.allowedHosts,
        )
        assertEquals(
            listOf(
                "http://localhost",
                "http://127.0.0.1",
                "http://example.tailnet.ts.net",
            ),
            configuration.allowedOrigins,
        )
    }

    @Test
    fun `rejects an explicitly empty host list`() {
        assertFailsWith<IllegalArgumentException> {
            resolveMcpHttpSecurityConfiguration(" , ")
        }
    }
}
