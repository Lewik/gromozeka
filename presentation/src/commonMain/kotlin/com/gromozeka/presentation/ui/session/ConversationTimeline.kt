package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.backgroundActivitySource
import com.gromozeka.domain.model.isBackgroundActivity
import com.gromozeka.domain.model.syntheticKind
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask

/** Transient UI state; it never becomes a conversation message. */
internal data class ModelRequestActivity(
    val generation: ActiveGenerationSnapshot,
    val owner: AgentDefinition.Id?,
    val rootUserMessageId: Conversation.Message.Id?,
    val indicator: ConversationIndicatorState,
    val description: String,
) {
    val key: String get() = "model-request:${generation.generationId}"
}

internal fun modelRequestActivity(
    generation: ActiveGenerationSnapshot?,
    runtime: ConversationRuntimeSnapshot?,
    indicator: ConversationIndicatorState,
    description: String,
): ModelRequestActivity? {
    if (generation == null || indicator.activity == ConversationActivity.UNKNOWN) return null
    // These are independently synchronized snapshots. Only a matching task proves ownership.
    val matches = (listOfNotNull(runtime?.activeTask) + runtime?.activeInsertions.orEmpty())
        .filter { it.id == generation.taskId && it.conversationId == generation.conversationId }
    check(matches.size <= 1) { "An active generation cannot belong to multiple runtime tasks" }
    val call = matches.singleOrNull()?.payload as? ConversationRuntimeTask.Payload.LlmCall
    return ModelRequestActivity(generation, call?.agentDefinitionId, call?.rootUserMessageId,
        indicator.copy(unread = false), description)
}

internal sealed interface ConversationTimelineItem {
    val key: String
    val messageId: Conversation.Message.Id?

    data class Message(val entry: MessageListEntry) : ConversationTimelineItem {
        override val key: String get() = entry.key
        override val messageId: Conversation.Message.Id get() = entry.message.id
    }

    data class ModelRequest(
        val activity: ModelRequestActivity,
        val groupKey: String? = null,
    ) : ConversationTimelineItem {
        override val key: String get() = activity.key
        override val messageId: Conversation.Message.Id? get() = null
    }

    data class ActivityDivider(
        val groupKey: String,
        val before: ActivityGroupSection,
    ) : ConversationTimelineItem {
        override val key: String get() = "activity-divider:$groupKey:${before.name.lowercase()}"
        override val messageId: Conversation.Message.Id? get() = null
    }
}

internal fun bindModelRequest(
    entries: List<MessageListEntry>,
    request: ModelRequestActivity?,
): List<ConversationTimelineItem> {
    val last = entries.lastOrNull()
    val references = when (val segment = last?.segment) {
        is MessageSegment.ActivityGroup -> segment.activities
        is MessageSegment.Activity -> listOf(checkNotNull(last.activityReferenceOrNull()))
        else -> emptyList()
    }
    val rootIndex = request?.rootUserMessageId?.let { root -> entries.indexOfLast { it.message.id == root } } ?: -1
    val belongsToVisibleTurn = rootIndex >= 0 && entries.drop(rootIndex + 1).none {
        it.message.role == Conversation.Message.Role.USER && it.message.content.any { content ->
            content is Conversation.Message.ContentItem.UserMessage
        }
    }
    val canAttach = request != null && request.owner != null && belongsToVisibleTurn && references.isNotEmpty() &&
        references.all { reference ->
            val message = reference.message
            val owner = if (message.syntheticKind.isBackgroundActivity()) message.backgroundActivitySource?.agentDefinitionId
                else (message.author as? Conversation.Message.Author.Agent)?.agentDefinitionId
            message.conversationId == request.generation.conversationId && owner == request.owner
        }
    val result = entries.mapIndexed { index, entry ->
        val value = if (canAttach && index == entries.lastIndex)
            entry.copy(segment = MessageSegment.ActivityGroup(references, request)) else entry
        ConversationTimelineItem.Message(value)
    }
    return if (request != null && !canAttach) result + ConversationTimelineItem.ModelRequest(request) else result
}

internal fun allocateActivityCaptionWidths(desired: List<Int>, available: Int, minimum: Int): List<Int> {
    require(desired.all { it >= 0 } && minimum >= 0)
    var remaining = available.coerceAtLeast(0)
    return desired.map { wanted ->
        val width = minOf(wanted, remaining).takeIf { it >= minimum } ?: 0
        remaining -= width
        width
    }
}
