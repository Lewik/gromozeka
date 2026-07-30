package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.model.WorkspaceMount
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

@Serializable
sealed interface WorkerWorkspaceRequest {
    @Serializable
    @SerialName("create_and_mount_filesystem")
    data class CreateAndMountFilesystem(
        val projectId: Project.Id,
        val name: String,
        val rootPath: String,
    ) : WorkerWorkspaceRequest

    @Serializable
    @SerialName("attach_filesystem")
    data class AttachFilesystem(
        val projectId: Project.Id,
        val workspaceId: Workspace.Id,
        val rootPath: String,
    ) : WorkerWorkspaceRequest

    @Serializable
    @SerialName("find_project_mounts")
    data class FindProjectMounts(
        val projectId: Project.Id,
    ) : WorkerWorkspaceRequest
}

@Serializable
sealed interface WorkerWorkspaceResponse {
    @Serializable
    @SerialName("execution_context")
    data class ExecutionContextResult(
        val context: WorkspaceExecutionContext,
    ) : WorkerWorkspaceResponse

    @Serializable
    @SerialName("mounts")
    data class MountsResult(
        val mounts: List<WorkspaceMount>,
    ) : WorkerWorkspaceResponse
}

@OptIn(ExperimentalSerializationApi::class)
object WorkerWorkspaceGatewayCodec {
    private val cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encodeRequest(request: WorkerWorkspaceRequest): ByteArray =
        cbor.encodeToByteArray(WorkerWorkspaceRequest.serializer(), request)

    fun decodeRequest(payload: ByteArray): WorkerWorkspaceRequest =
        cbor.decodeFromByteArray(WorkerWorkspaceRequest.serializer(), payload)

    fun encodeResponse(response: WorkerWorkspaceResponse): ByteArray =
        cbor.encodeToByteArray(WorkerWorkspaceResponse.serializer(), response)

    fun decodeResponse(payload: ByteArray): WorkerWorkspaceResponse =
        cbor.decodeFromByteArray(WorkerWorkspaceResponse.serializer(), payload)
}
