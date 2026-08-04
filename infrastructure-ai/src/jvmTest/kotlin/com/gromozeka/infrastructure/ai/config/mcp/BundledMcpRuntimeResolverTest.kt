package com.gromozeka.infrastructure.ai.config.mcp

import com.gromozeka.domain.model.mcp.BundledMcpRuntime
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledMcpRuntimeResolverTest {
    @Test
    fun `resolves Browser MCP from the development repository`() {
        val projectRoot = checkNotNull(System.getProperty("gromozeka.project.root"))
        val resolved = BundledMcpRuntimeResolver.resolve(BundledMcpRuntime.BROWSER_USE)

        assertTrue(resolved.command.endsWith("gromozeka-browser-mcp"))
        assertEquals(
            Path.of(projectRoot, "browser-mcp").absolutePathString(),
            resolved.environment["GROMOZEKA_BROWSER_MCP_HOME"],
        )
    }
}
