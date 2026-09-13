package com.gromozeka.server

import com.gromozeka.application.service.ConversationArtifactApplicationService
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.BinaryContent
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.MAX_TOOL_OUTPUT_DOWNLOAD_CHUNK_BYTES
import com.gromozeka.domain.service.ToolOutputArtifactChunk
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.remote.protocol.WorkerArtifactCodec
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import org.springframework.stereotype.Service

@Service
class WorkerArtifactGatewayHandler(
    private val artifacts: ConversationArtifactApplicationService,
    private val conversations: ConversationRepository,
    private val access: WorkerAccessService,
) : WorkerGatewayServerRequestHandler {
    override val operation = WorkerGatewayOperation.ARTIFACT_CONTENT

    override suspend fun execute(identity: ConversationRuntimeWorkerIdentity, request: WorkerGatewayMessage.Request): ByteArray {
        require(request.operation == operation)
        val read = WorkerArtifactCodec.decodeRequest(request.payload)
        require(read.offset >= 0 && read.limit in 1..MAX_TOOL_OUTPUT_DOWNLOAD_CHUNK_BYTES)
        val conversation = requireNotNull(conversations.findById(read.conversationId)) { "Conversation unavailable" }
        access.requireProjectAccess(identity.workerId, conversation.projectId)
        val artifact = requireNotNull(artifacts.find(read.artifactId)) { "Artifact unavailable" }
        require(artifact.conversationId == conversation.id && artifact.state == Artifact.State.COMMITTED) {
            "Artifact unavailable in this conversation"
        }
        val chunk = artifacts.readChunk(artifact, read.offset, read.limit)
        return WorkerArtifactCodec.encodeResponse(ToolOutputArtifactChunk(
            artifact.reference(), BinaryContent.fromBytes(chunk.first), read.offset, chunk.second, artifact.sha256,
        ))
    }
}
