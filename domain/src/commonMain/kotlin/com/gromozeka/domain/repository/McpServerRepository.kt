package com.gromozeka.domain.repository

import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerId

interface McpServerRepository {
    suspend fun find(id: McpServerId): McpServer?

    suspend fun list(): List<McpServer>

    suspend fun listByWorker(workerId: ConversationRuntimeWorkerId): List<McpServer>

    suspend fun create(server: McpServer): Boolean

    suspend fun replace(server: McpServer, expectedRevision: Long): Boolean

    suspend fun markRefreshAvailable(id: McpServerId, expectedRevision: Long): Boolean

    suspend fun delete(id: McpServerId, expectedRevision: Long): Boolean
}
