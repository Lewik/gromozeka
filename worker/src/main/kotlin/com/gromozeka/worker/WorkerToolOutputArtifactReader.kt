package com.gromozeka.worker

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ToolOutputArtifactChunk
import com.gromozeka.domain.service.ToolOutputArtifactReader
import com.gromozeka.remote.protocol.WorkerArtifactCodec
import com.gromozeka.remote.protocol.WorkerArtifactReadRequest
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import org.springframework.stereotype.Service

@Service
class WorkerToolOutputArtifactReader(private val outbound: WorkerGatewayOutbound) : ToolOutputArtifactReader {
    override suspend fun read(
        conversationId: Conversation.Id,
        artifactId: Artifact.Id,
        offset: Long,
        limit: Int,
    ): ToolOutputArtifactChunk = WorkerArtifactCodec.decodeResponse(
        outbound.execute(
            WorkerGatewayOperation.ARTIFACT_CONTENT,
            WorkerArtifactCodec.encodeRequest(WorkerArtifactReadRequest(conversationId, artifactId, offset, limit)),
        )
    )
}
