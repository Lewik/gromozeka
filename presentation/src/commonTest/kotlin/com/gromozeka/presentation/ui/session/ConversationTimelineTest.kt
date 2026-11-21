package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant

class ConversationTimelineTest {
    private val conversation = Conversation.Id("conversation")
    private val owner = AgentDefinition.Id("agent")
    private val now = Instant.fromEpochSeconds(100)
    private val indicator = ConversationIndicatorState(ConversationActivity.WORKING, ConversationPressure.NORMAL, true, false, emptySet())
    private val generation = ActiveGenerationSnapshot("generation", conversation, ConversationRuntimeTask.Id("llm"),
        "test-provider", "test-model", 1, ActiveGenerationSnapshot.Phase.WAITING_FOR_MODEL,
        now, now, 2, 2, 1, 3)
    private val root = Conversation.Message(Conversation.Message.Id("root"), conversation, role = Conversation.Message.Role.USER,
        content = listOf(Conversation.Message.ContentItem.UserMessage("Continue")), createdAt = now)

    @Test fun modelOwnershipRequiresTheExactRuntimeTask() {
        val request = modelRequestActivity(generation, runtime(), indicator, "Calling model")!!
        assertEquals(owner, request.owner)
        assertEquals(root.id, request.rootUserMessageId)
        assertFalse(request.indicator.unread)
        val stale = modelRequestActivity(generation.copy(taskId = ConversationRuntimeTask.Id("different")), runtime(), indicator, "Calling model")!!
        assertNull(stale.owner)
        assertNull(stale.rootUserMessageId)
        assertNull(modelRequestActivity(null, runtime(), indicator, "Calling model"))
        assertNull(modelRequestActivity(generation, runtime(), indicator.copy(activity = ConversationActivity.UNKNOWN), "Calling model"))
    }

    @Test fun ambiguousInternalTaskOwnershipFailsExplicitly() {
        val runtime = runtime()
        assertFailsWith<IllegalStateException> {
            modelRequestActivity(generation, runtime.copy(activeInsertions = listOf(runtime.activeTask!!)), indicator, "Calling model")
        }
    }

    @Test fun requestJoinsOnlyTheVerifiedOwnersCurrentActivityGroup() {
        val entries = entries()
        val request = modelRequestActivity(generation, runtime(), indicator, "Calling model")!!
        val timeline = bindModelRequest(entries, request)
        assertEquals(entries.size, timeline.size)
        val group = assertIs<MessageSegment.ActivityGroup>(assertIs<ConversationTimelineItem.Message>(timeline.last()).entry.segment)
        assertSame(request, group.modelRequest)
        assertEquals(entries.last().message, assertIs<ConversationTimelineItem.Message>(timeline.last()).entry.message)
        assertEquals(entries.map { it.message.id }, timeline.map { it.messageId })
        assertEquals(entries.last().sourceKey, timeline.last().key)
        val cleared = bindModelRequest(entries, null)
        assertNull(assertIs<MessageSegment.ActivityGroup>(assertIs<ConversationTimelineItem.Message>(cleared.last()).entry.segment).modelRequest)
    }

    @Test fun aSingleActivityCanHostTheTransientRequestWithoutInventingAMessage() {
        val entries = entries(callCount = 1)
        val request = modelRequestActivity(generation, runtime(), indicator, "Calling model")!!
        val timeline = bindModelRequest(entries, request)
        val group = assertIs<MessageSegment.ActivityGroup>(assertIs<ConversationTimelineItem.Message>(timeline.last()).entry.segment)
        assertEquals(1, group.activities.size)
        assertSame(request, group.modelRequest)
        assertEquals(entries.last().message.id, timeline.last().messageId)
    }

    @Test fun firstRequestHasAnIndependentUiIdentityRatherThanAFakeAssistantMessage() {
        val request = modelRequestActivity(generation, runtime(), indicator, "Calling model")!!
        val only = assertIs<ConversationTimelineItem.ModelRequest>(bindModelRequest(emptyList(), request).single())
        assertNull(only.messageId)
        assertEquals("model-request:generation", only.key)
        assertEquals(emptyList(), bindModelRequest(emptyList(), null))
    }

    @Test fun missingHistoryAnotherOwnerAndNewInputNeverStealTheRequest() {
        val request = modelRequestActivity(generation, runtime(), indicator, "Calling model")!!
        for (input in listOf(entries().drop(1), entries(agent = AgentDefinition.Id("other")),
            listOf(entries().first(), entries().first().copy(message = root.copy(id = Conversation.Message.Id("new-user")))) + entries().drop(1))) {
            val timeline = bindModelRequest(input, request)
            assertIs<ConversationTimelineItem.ModelRequest>(timeline.last())
            assertEquals(input.map { it.message.id }, timeline.filterIsInstance<ConversationTimelineItem.Message>().map { it.messageId })
            val group = assertIs<MessageSegment.ActivityGroup>(timeline.filterIsInstance<ConversationTimelineItem.Message>().last().entry.segment)
            assertNull(group.modelRequest)
        }
    }

    @Test fun captionsShrinkFromTheRightWhileIconsKeepTheirSlots() {
        assertEquals(listOf(120, 100, 60), allocateActivityCaptionWidths(listOf(120, 100, 60), 280, 20))
        assertEquals(listOf(120, 100, 0), allocateActivityCaptionWidths(listOf(120, 100, 60), 220, 20))
        assertEquals(listOf(120, 30, 0), allocateActivityCaptionWidths(listOf(120, 100, 60), 150, 20))
        assertEquals(listOf(120, 0, 0), allocateActivityCaptionWidths(listOf(120, 100, 60), 135, 20))
        assertEquals(listOf(0, 0, 0), allocateActivityCaptionWidths(listOf(120, 100, 60), 0, 20))
        assertEquals(listOf(0), allocateActivityCaptionWidths(listOf(120), -1, 20))
        assertFailsWith<IllegalArgumentException> { allocateActivityCaptionWidths(listOf(-1), 100, 20) }
    }

    private fun runtime(): ConversationRuntimeSnapshot {
        val task = ConversationRuntimeTask(ConversationRuntimeTask.Id("llm"), conversation,
            parentTaskId = ConversationRuntimeTask.Id("parent"),
            payload = ConversationRuntimeTask.Payload.LlmCall(root.id, owner, 1),
            placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = "llm",
            requirements = ConversationRuntimeTaskRequirements(setOf(ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                ConversationRuntimeCapability.MEMORY_PIPELINE), ConversationRuntimeTaskTarget.Server), createdAt = now)
        return ConversationRuntimeSnapshot(1, conversation, null, activeTask = task, pendingTasks = emptyList())
    }

    private fun entries(agent: AgentDefinition.Id = owner, callCount: Int = 2): List<MessageListEntry> {
        val calls = (1..callCount).map { id -> Conversation.Message.ContentItem.ToolCall(
            Conversation.Message.ContentItem.ToolCall.Id("call-$id"),
            Conversation.Message.ContentItem.ToolCall.Data("grz_read_file", buildJsonObject {})) }
        val message = Conversation.Message(Conversation.Message.Id("activity"), conversation, role = Conversation.Message.Role.ASSISTANT,
            content = calls, createdAt = now, author = Conversation.Message.Author.Agent(agent, "Agent"))
        return groupActivityEntries(listOf(MessageListEntry(root, MessageSegment.Content(0, root.content.single()), true, true)) +
            calls.mapIndexed { index, call -> MessageListEntry(message, MessageSegment.Activity(index, ChatActivity.Tool(call, null)),
                index == 0, index == calls.lastIndex) })
    }
}
