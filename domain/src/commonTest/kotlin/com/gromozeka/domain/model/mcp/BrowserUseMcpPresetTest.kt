package com.gromozeka.domain.model.mcp

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserUseMcpPresetTest {
    @Test
    fun `creates a stable worker-scoped Playwright extension configuration`() {
        val workerId = ConversationRuntimeWorkerId("Local Worker/1")
        val config = BrowserUseMcpPreset.config(workerId, "extension-token")
        val transport = config.transport as McpServerTransport.Stdio

        assertEquals(config.id, BrowserUseMcpPreset.serverId(workerId))
        assertTrue(config.id.value.startsWith("browser_local_worker_1_"))
        assertEquals("Browser · Local Worker/1", config.displayName)
        assertEquals(BrowserUseMcpPreset.OPERATION_TIMEOUT_MS, config.timeoutMs)
        assertEquals("npx", transport.command)
        assertEquals(BrowserUseMcpPreset.arguments, transport.arguments)
        assertEquals(
            mapOf(BrowserUseMcpPreset.EXTENSION_TOKEN_ENV to "extension-token"),
            transport.environment,
        )
        assertTrue(transport.ephemeralWorkingDirectory)
    }

    @Test
    fun `accepts the environment assignment copied by Browser Bridge`() {
        assertEquals(
            "extension-token",
            BrowserUseMcpPreset.normalizeExtensionToken(
                " ${BrowserUseMcpPreset.EXTENSION_TOKEN_ENV}=extension-token "
            ),
        )
        assertEquals(
            mapOf(BrowserUseMcpPreset.EXTENSION_TOKEN_ENV to "extension-token"),
            BrowserUseMcpPreset.transport(
                "${BrowserUseMcpPreset.EXTENSION_TOKEN_ENV}=extension-token"
            ).environment,
        )
    }
}
