package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.model.Conversation

internal enum class ActivityState {
    RUNNING,
    COMPLETE,
    FAILED,
    INTERRUPTED,
}

internal sealed interface ActivityKind {
    data class Tool(val name: String) : ActivityKind
    data object Reasoning : ActivityKind
}

internal sealed interface ChatActivity {
    val state: ActivityState
    val kind: ActivityKind
    val canExpand: Boolean

    data class Tool(
        val call: Conversation.Message.ContentItem.ToolCall,
        val result: Conversation.Message.ContentItem.ToolResult?,
    ) : ChatActivity {
        override val state: ActivityState
            get() = when {
                call.state == Conversation.Message.BlockState.INTERRUPTED ||
                    result?.state == Conversation.Message.BlockState.INTERRUPTED -> ActivityState.INTERRUPTED
                call.state == Conversation.Message.BlockState.STREAMING ||
                    result == null || result.state == Conversation.Message.BlockState.STREAMING -> ActivityState.RUNNING
                result.isError -> ActivityState.FAILED
                else -> ActivityState.COMPLETE
            }
        override val kind: ActivityKind = ActivityKind.Tool(call.call.name)
        override val canExpand: Boolean get() = result != null
    }

    data class Reasoning(val block: Conversation.Message.ContentItem.Thinking) : ChatActivity {
        override val state: ActivityState
            get() = when (block.state) {
                Conversation.Message.BlockState.STREAMING -> ActivityState.RUNNING
                Conversation.Message.BlockState.COMPLETE -> ActivityState.COMPLETE
                Conversation.Message.BlockState.INTERRUPTED -> ActivityState.INTERRUPTED
            }
        override val kind: ActivityKind = ActivityKind.Reasoning
        override val canExpand: Boolean get() = block.thinking.isNotBlank()
    }
}

internal data class ActivityReference(
    val message: Conversation.Message,
    val contentIndex: Int,
    val activity: ChatActivity,
    val isFirstInMessage: Boolean,
    val isLastInMessage: Boolean,
) {
    val key: String = "${message.id.value}:$contentIndex:content"

    fun toEntry(): MessageListEntry = MessageListEntry(
        message = message,
        segment = MessageSegment.Activity(contentIndex, activity),
        isFirstInMessage = isFirstInMessage,
        isLastInMessage = isLastInMessage,
    )
}

internal fun MessageListEntry.activityReferenceOrNull(): ActivityReference? {
    val activity = segment as? MessageSegment.Activity ?: return null
    return ActivityReference(message, activity.contentIndex, activity.activity, isFirstInMessage, isLastInMessage)
}

internal fun groupActivityEntries(entries: List<MessageListEntry>): List<MessageListEntry> {
    val activityOnlyMessageIds = entries.groupBy { it.message.id }
        .filterValues { messageEntries -> messageEntries.all { it.segment is MessageSegment.Activity } }
        .keys
    val result = mutableListOf<MessageListEntry>()
    val pending = mutableListOf<ActivityReference>()

    fun flushPending() {
        when (pending.size) {
            0 -> Unit
            1 -> result += pending.single().toEntry()
            else -> result += MessageListEntry(
                message = pending.first().message,
                segment = MessageSegment.ActivityGroup(pending.toList()),
                isFirstInMessage = pending.first().isFirstInMessage,
                isLastInMessage = pending.last().isLastInMessage,
            )
        }
        pending.clear()
    }

    for (entry in entries) {
        val reference = entry.activityReferenceOrNull()
        if (reference == null || reference.activity.state != ActivityState.COMPLETE) {
            flushPending()
            result += entry
            continue
        }
        val previous = pending.lastOrNull()
        if (previous != null && previous.message.id != reference.message.id) {
            val canJoin = previous.message.id in activityOnlyMessageIds &&
                reference.message.id in activityOnlyMessageIds &&
                previous.message.role == reference.message.role &&
                previous.message.author == reference.message.author
            if (!canJoin) flushPending()
        }
        pending += reference
    }
    flushPending()
    return result
}

internal fun activityExpansionKey(entryKey: String): String = "activity:$entryKey"

internal fun activityGroupExpansionKey(entryKey: String): String = "group:$entryKey"

internal data class ActivitySummary(val kind: ActivityKind, val count: Int)

internal fun summarizeActivities(activities: List<ChatActivity>): List<ActivitySummary> =
    activities.groupingBy(ChatActivity::kind).eachCount()
        .map { (kind, count) -> ActivitySummary(kind, count) }
        .sortedBy { it.kind != ActivityKind.Reasoning }
