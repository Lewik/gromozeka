package com.gromozeka.domain.service

import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
data class WorkerControlRequest(
    val id: Id,
    val target: ConversationRuntimeWorkerIdentity,
    val command: Command,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "Worker control request id must not be blank" }
        }
    }

    @Serializable
    sealed interface Command {
        @Serializable
        @SerialName("apply_mcp_server")
        data class ApplyMcpServer(
            val kind: McpServerMutationKind,
            val config: McpServerConfig,
            val expectedRevision: Long? = null,
        ) : Command {
            init {
                require((kind == McpServerMutationKind.CREATE) == (expectedRevision == null)) {
                    "MCP create must not have an expected revision; update and refresh must have one"
                }
            }
        }

        @Serializable
        @SerialName("delete_mcp_server")
        data class DeleteMcpServer(
            val serverId: McpServerId,
            val expectedRevision: Long,
        ) : Command {
            init {
                require(expectedRevision > 0) { "MCP delete expected revision must be positive" }
            }
        }
    }
}

@Serializable
enum class McpServerMutationKind {
    CREATE,
    UPDATE,
    REFRESH,
}

@Serializable
data class WorkerControlResult(
    val requestId: WorkerControlRequest.Id,
    val status: Status,
    val mcpServer: McpServer? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    init {
        require(
            (status == Status.SUCCEEDED && mcpServer != null && errorCode == null && errorMessage == null) ||
                (status == Status.DELETED && mcpServer == null && errorCode == null && errorMessage == null) ||
                (status == Status.FAILED && mcpServer == null && !errorCode.isNullOrBlank() && !errorMessage.isNullOrBlank())
        ) {
            "Worker control result payload does not match status $status"
        }
    }

    @Serializable
    enum class Status {
        SUCCEEDED,
        DELETED,
        FAILED,
    }
}

interface WorkerControlClient {
    suspend fun execute(request: WorkerControlRequest): WorkerControlResult
}

fun interface WorkerControlHandler {
    suspend fun handle(request: WorkerControlRequest): WorkerControlResult
}
