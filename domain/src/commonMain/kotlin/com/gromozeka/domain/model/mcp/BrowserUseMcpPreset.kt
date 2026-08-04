package com.gromozeka.domain.model.mcp

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.shared.utils.sha256

object BrowserUseMcpPreset {
    const val SERVER_ID_PREFIX = "browser_"
    const val OPERATION_TIMEOUT_MS = 120_000L
    const val OUTPUT_MAX_BYTES = 52_428_800
    const val EXTENSION_TOKEN_ENV = "PLAYWRIGHT_MCP_EXTENSION_TOKEN"
    const val EXTENSION_ID_ENV = "PLAYWRIGHT_MCP_EXTENSION_ID"
    const val EXTENSION_ID = "jiadoiohindhpbaahcahcbeokjiojlml"
    const val BRIDGE_ARTIFACT_ID = "browser-bridge"
    const val BRIDGE_FILE_NAME = "gromozeka-browser-bridge.zip"

    val arguments: List<String> = listOf(
        "--extension",
        "--output-mode=stdout",
        "--output-max-size=$OUTPUT_MAX_BYTES",
    )

    fun config(
        workerId: ConversationRuntimeWorkerId,
        extensionToken: String? = null,
    ): McpServerConfig = McpServerConfig(
        id = serverId(workerId),
        displayName = "Browser · ${workerId.value}",
        workerId = workerId,
        transport = transport(extensionToken),
        timeoutMs = OPERATION_TIMEOUT_MS,
    )

    fun transport(extensionToken: String? = null): McpServerTransport.BundledStdio =
        McpServerTransport.BundledStdio(
            runtime = BundledMcpRuntime.BROWSER_USE,
            arguments = arguments,
            environment = buildMap {
                put(EXTENSION_ID_ENV, EXTENSION_ID)
                extensionToken
                    ?.let(::normalizeExtensionToken)
                    ?.takeIf(String::isNotBlank)
                    ?.let { put(EXTENSION_TOKEN_ENV, it) }
            },
            ephemeralWorkingDirectory = true,
        )

    fun normalizeExtensionToken(value: String): String =
        value.trim().removePrefix("$EXTENSION_TOKEN_ENV=").trim()

    fun isConnection(server: McpServer): Boolean {
        val transport = server.config.transport as? McpServerTransport.BundledStdio ?: return false
        return server.config.id.value.startsWith(SERVER_ID_PREFIX) &&
            transport.runtime == BundledMcpRuntime.BROWSER_USE
    }

    fun serverId(workerId: ConversationRuntimeWorkerId): McpServerId {
        val readableWorker = workerId.value
            .lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
            .joinToString("")
            .trim('_')
            .ifEmpty { "worker" }
            .take(40)
        return McpServerId(
            "$SERVER_ID_PREFIX${readableWorker}_${workerId.value.sha256().take(8)}"
        )
    }
}
