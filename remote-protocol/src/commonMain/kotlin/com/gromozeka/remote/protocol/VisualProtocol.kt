package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.visual.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("list_visuals")
data class ListVisualsRequest(val conversationId: Conversation.Id) : ClientRequest

@Serializable
@SerialName("create_visual")
data class CreateVisualRequest(val conversationId: Conversation.Id, val visual: VisualCreate) : ClientRequest

@Serializable
@SerialName("update_visual")
data class UpdateVisualRequest(val conversationId: Conversation.Id, val visualId: String, val update: VisualUpdate) : ClientRequest

@Serializable
@SerialName("close_visual")
data class CloseVisualRequest(val conversationId: Conversation.Id, val visualId: String) : ClientRequest

@Serializable
@SerialName("visual_action")
data class VisualActionRequest(val conversationId: Conversation.Id, val action: VisualAction) : ClientRequest

@Serializable
@SerialName("visuals")
data class VisualsResponse(val visuals: List<Visual>) : ServerResponse

@Serializable
@SerialName("visual")
data class VisualResponse(val visual: Visual) : ServerResponse

@Serializable
@SerialName("visual_action_result")
data class VisualActionResponse(val result: VisualActionResult) : ServerResponse

@Serializable
@SerialName("visuals")
data class VisualsStateQuery(val conversationId: Conversation.Id) : RemoteStateSyncQuery

@Serializable
@SerialName("visuals")
data class VisualsStatePayload(val visuals: List<Visual>) : RemoteStateSyncPayload

@Serializable
@SerialName("highlight_visual")
data class HighlightVisualDirective(val command: VisualHighlightCommand) : ClientPresentationDirective

/** Private Server/Worker protocol. Commands remain ordinary managed processes. */
@Serializable
data class VisualWorkerCommand(
    val operation: Operation,
    val visualId: String,
    val conversationId: Conversation.Id,
    val generation: String,
    val worker: ConversationRuntimeWorkerIdentity,
    val taskId: CommandTask.Id? = null,
    val workspace: WorkspaceExecutionContext? = null,
    val spec: VisualHandlerSpec? = null,
    val toolContext: Map<String, String> = emptyMap(),
    val input: String? = null,
    val secretEnvironment: Map<String, String> = emptyMap(),
) {
    @Serializable enum class Operation { START, INPUT, CANCEL }
    override fun toString(): String = "VisualWorkerCommand(operation=$operation, visualId=$visualId, generation=$generation, secrets=[REDACTED])"
}

@Serializable
data class VisualWorkerResult(val task: CommandTask? = null)

@Serializable
data class VisualWorkerOutput(
    val visualId: String,
    val conversationId: Conversation.Id,
    val generation: String,
    val task: CommandTask,
    val records: List<VisualOutputRecord>,
    val finished: Boolean = false,
)

@Serializable
data class VisualWorkerOutputResult(val active: Boolean)
