package com.gromozeka.client

import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.remote.protocol.BrowserUseProbeResponse
import com.gromozeka.remote.protocol.CreateMcpServerRequest
import com.gromozeka.remote.protocol.DeleteMcpServerRequest
import com.gromozeka.remote.protocol.ListMcpServersRequest
import com.gromozeka.remote.protocol.McpServerResponse
import com.gromozeka.remote.protocol.McpServersResponse
import com.gromozeka.remote.protocol.RefreshMcpServerRequest
import com.gromozeka.remote.protocol.RemoteMcpServerView
import com.gromozeka.remote.protocol.SavedResponse
import com.gromozeka.remote.protocol.TestBrowserUseRequest
import com.gromozeka.remote.protocol.UpdateMcpServerRequest

class RemoteMcpServerService internal constructor(
    private val client: GromozekaWsClient,
) {
    suspend fun list(): List<RemoteMcpServerView> =
        client.requestTyped<ListMcpServersRequest, McpServersResponse>(ListMcpServersRequest).servers

    suspend fun create(config: McpServerConfig): RemoteMcpServerView =
        client.requestTyped<CreateMcpServerRequest, McpServerResponse>(
            CreateMcpServerRequest(config)
        ).server

    suspend fun update(
        config: McpServerConfig,
        expectedRevision: Long,
        removeEnvironmentVariables: Set<String> = emptySet(),
        removeHttpHeaders: Set<String> = emptySet(),
    ): RemoteMcpServerView =
        client.requestTyped<UpdateMcpServerRequest, McpServerResponse>(
            UpdateMcpServerRequest(
                config = config,
                expectedRevision = expectedRevision,
                removeEnvironmentVariables = removeEnvironmentVariables,
                removeHttpHeaders = removeHttpHeaders,
            )
        ).server

    suspend fun refresh(
        serverId: McpServerId,
        expectedRevision: Long,
    ): RemoteMcpServerView =
        client.requestTyped<RefreshMcpServerRequest, McpServerResponse>(
            RefreshMcpServerRequest(serverId, expectedRevision)
        ).server

    suspend fun testBrowserUse(serverId: McpServerId): BrowserUseProbeResponse =
        client.requestTyped<TestBrowserUseRequest, BrowserUseProbeResponse>(
            TestBrowserUseRequest(serverId)
        )

    suspend fun delete(
        serverId: McpServerId,
        expectedRevision: Long,
    ) {
        client.requestTyped<DeleteMcpServerRequest, SavedResponse>(
            DeleteMcpServerRequest(serverId, expectedRevision)
        )
    }
}
