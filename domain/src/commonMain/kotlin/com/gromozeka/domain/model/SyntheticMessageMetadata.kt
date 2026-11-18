package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable
enum class SyntheticMessageKind {
    COMMAND_TASK_COMPLETION,
    COMMAND_MONITOR_UPDATE,
    TOOL_ROUTING_ERROR,
    RUNTIME_EXECUTION_INCIDENT,
    SLOT_EVENT,
    MEMORY,
    MEMORY_FOCUSED_CONTEXT,
    COMPACTION_CONTINUATION,
}

/** The initiating call and its agent, not the author of a Runtime-generated message. */
@Serializable
data class BackgroundActivityMessageSource(
    val toolCallId: ToolCall.Id,
    val agentDefinitionId: AgentDefinition.Id,
) {
    init {
        require(toolCallId.value.isNotBlank()) { "Background activity source requires a tool call id" }
        require(agentDefinitionId.value.isNotBlank()) { "Background activity source requires an agent id" }
    }
}

val Message.syntheticKind: SyntheticMessageKind?
    get() = providerMetadata[SYNTHETIC_KIND]?.let { Json.decodeFromJsonElement<SyntheticMessageKind>(it) }

val Message.backgroundActivitySource: BackgroundActivityMessageSource?
    get() = providerMetadata[BACKGROUND_ACTIVITY_SOURCE]?.let {
        require(syntheticKind.isBackgroundActivity()) { "Only background activity messages can name a launch source" }
        Json.decodeFromJsonElement<BackgroundActivityMessageSource>(it)
    }

fun SyntheticMessageKind?.isBackgroundActivity(): Boolean =
    this == SyntheticMessageKind.COMMAND_TASK_COMPLETION || this == SyntheticMessageKind.COMMAND_MONITOR_UPDATE

fun JsonObjectBuilder.putSyntheticMessageMetadata(
    kind: SyntheticMessageKind,
    source: BackgroundActivityMessageSource? = null,
) {
    require(source == null || kind.isBackgroundActivity()) { "Only background activity messages can name a launch source" }
    put("synthetic", true)
    put(SYNTHETIC_KIND, Json.encodeToJsonElement(kind))
    source?.let { put(BACKGROUND_ACTIVITY_SOURCE, Json.encodeToJsonElement(it)) }
}

private const val SYNTHETIC_KIND = "syntheticKind"
private const val BACKGROUND_ACTIVITY_SOURCE = "backgroundActivitySource"
