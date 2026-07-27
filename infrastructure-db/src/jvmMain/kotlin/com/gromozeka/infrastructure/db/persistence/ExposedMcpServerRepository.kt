package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.repository.McpServerRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.infrastructure.db.persistence.tables.McpServers
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedMcpServerRepository(
    private val json: Json,
) : McpServerRepository {
    override suspend fun find(id: McpServerId): McpServer? = dbQuery {
        McpServers.selectAll()
            .where { McpServers.id eq id.value }
            .singleOrNull()
            ?.toMcpServer()
    }

    override suspend fun list(): List<McpServer> = dbQuery {
        McpServers.selectAll()
            .orderBy(McpServers.id)
            .map { it.toMcpServer() }
    }

    override suspend fun listByWorker(workerId: ConversationRuntimeWorkerId): List<McpServer> = dbQuery {
        McpServers.selectAll()
            .where { McpServers.workerId eq workerId.value }
            .orderBy(McpServers.id)
            .map { it.toMcpServer() }
    }

    override suspend fun create(server: McpServer): Boolean = dbQuery {
        require(server.revision == 1L) { "New MCP server revision must be 1" }
        McpServers.insertIgnore {
            it[id] = server.config.id.value
            it[workerId] = server.config.workerId.value
            it[revision] = server.revision
            it[refreshAvailable] = server.refreshAvailable
            it[payloadJson] = json.encodeToString(server)
            it[createdAt] = server.createdAt.toKotlin()
            it[updatedAt] = server.updatedAt.toKotlin()
        }.insertedCount == 1
    }

    override suspend fun replace(
        server: McpServer,
        expectedRevision: Long,
    ): Boolean = dbQuery {
        require(server.revision == expectedRevision + 1) {
            "Replacement MCP server revision ${server.revision} does not follow $expectedRevision"
        }
        McpServers.update({
            (McpServers.id eq server.config.id.value) and
                (McpServers.revision eq expectedRevision)
        }) {
            it[workerId] = server.config.workerId.value
            it[revision] = server.revision
            it[refreshAvailable] = server.refreshAvailable
            it[payloadJson] = json.encodeToString(server)
            it[updatedAt] = server.updatedAt.toKotlin()
        } == 1
    }

    override suspend fun markRefreshAvailable(
        id: McpServerId,
        expectedRevision: Long,
    ): Boolean = dbQuery {
        val row = McpServers.selectAll()
            .where {
                (McpServers.id eq id.value) and
                    (McpServers.revision eq expectedRevision)
            }
            .singleOrNull()
            ?: return@dbQuery false
        val server = row.toMcpServer()
        if (server.refreshAvailable) {
            return@dbQuery true
        }
        val changed = server.copy(refreshAvailable = true)
        McpServers.update({
            (McpServers.id eq id.value) and
                (McpServers.revision eq expectedRevision)
        }) {
            it[refreshAvailable] = true
            it[payloadJson] = json.encodeToString(changed)
        } == 1
    }

    override suspend fun delete(
        id: McpServerId,
        expectedRevision: Long,
    ): Boolean = dbQuery {
        McpServers.deleteWhere {
            (McpServers.id eq id.value) and
                (McpServers.revision eq expectedRevision)
        } == 1
    }

    private fun ResultRow.toMcpServer(): McpServer =
        json.decodeFromString<McpServer>(this[McpServers.payloadJson]).also { server ->
            check(server.config.id.value == this[McpServers.id])
            check(server.config.workerId.value == this[McpServers.workerId])
            check(server.revision == this[McpServers.revision])
            check(server.refreshAvailable == this[McpServers.refreshAvailable])
        }
}
