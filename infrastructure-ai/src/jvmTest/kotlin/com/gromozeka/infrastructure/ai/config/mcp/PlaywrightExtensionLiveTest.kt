package com.gromozeka.infrastructure.ai.config.mcp

import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.BrowserUseMcpPreset
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.tool.AiToolResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class PlaywrightExtensionLiveTest {
    @Test
    fun controlsApprovedChromeTabAndReturnsScreenshot() = runBlocking {
        if (System.getenv(ENABLED_ENV) != "true") return@runBlocking

        val client = DefaultMcpClientFactory().connect(
            McpServerConfig(
                id = McpServerId("playwright_extension_live_test"),
                displayName = "Playwright Extension Live Test",
                workerId = ConversationRuntimeWorkerId("local-live-test"),
                transport = BrowserUseMcpPreset.transport(
                    System.getenv(BrowserUseMcpPreset.EXTENSION_TOKEN_ENV)
                ),
                timeoutMs = 120_000,
            )
        )

        try {
            val tabs = client.callToolResult("browser_tabs", mapOf("action" to "list"))
            assertTrue(tabs.isNotEmpty())

            val screenshot = client.callToolResult("browser_take_screenshot", emptyMap())
            assertTrue(screenshot.any { it is AiToolResult.Binary && it.mediaType == "image/png" })
        } finally {
            client.close()
        }
    }

    private companion object {
        const val ENABLED_ENV = "GROMOZEKA_PLAYWRIGHT_EXTENSION_E2E"
    }
}
