package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.projectedMessages
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class SyntheticAsyncCompletionMessageTest {
    private val sourceCall = ContentItem.ToolCall.Id("original-call")
    private val completionCall = syntheticToolCallId("async-tool:operation:completion")
    private val metadata = buildJsonObject {
        putSyntheticMessageMetadata(SyntheticMessageKind.ASYNC_TOOL_COMPLETION,
            BackgroundActivityMessageSource(sourceCall, AgentDefinition.Id("agent")))
        put("asyncToolOperationId", "operation")
    }
    private val call = Message(
        id = Message.Id("completion-call"), conversationId = Conversation.Id("conversation"),
        role = Message.Role.ASSISTANT, author = Message.Author.Runtime(),
        content = listOf(ContentItem.ToolCall(completionCall,
            ContentItem.ToolCall.Data("tool", buildJsonObject { put("value", 3) }))),
        instructions = listOf(Message.Instruction.RuntimeObservation("Runtime completion for operation; source original-call; not another execution.")),
        providerMetadata = metadata, createdAt = Instant.fromEpochSeconds(1),
    )
    private val result = call.copy(
        id = Message.Id("completion-result"), role = Message.Role.USER, instructions = emptyList(),
        content = listOf(ContentItem.ToolResult(completionCall, "tool", listOf(
            ContentItem.ToolResult.Data.Text("{\"answer\":42}"),
            ContentItem.ToolResult.Data.Base64Data("AAE=", Message.MediaType.parse("application/octet-stream"), "output.bin"),
        ))),
    )

    @Test fun ordinaryToolPairRemainsCanonicalAndProviderFacing() {
        val canonical = listOf(call, result)
        assertEquals(canonical, AiRuntimeRequest(emptyList(), canonical).projectedMessages())
        assertEquals(canonical, AiRuntimeRequest(emptyList(), canonical).projectedMessages().let {
            AiRuntimeRequest(emptyList(), it).projectedMessages()
        })
        assertIs<ContentItem.ToolCall>(canonical[0].content.single())
        assertIs<ContentItem.ToolResult>(canonical[1].content.single())
        assertNotEquals(sourceCall, completionCall)
        assertEquals(sourceCall, call.backgroundActivitySource?.toolCallId)
    }

    @Test fun sourceReceiptAndCompletionUseTwoIndependentPairs() {
        val original = call.copy(id = Message.Id("source"), author = null, instructions = emptyList(),
            providerMetadata = JsonObject(emptyMap()),
            content = listOf(ContentItem.ToolCall(sourceCall, ContentItem.ToolCall.Data("tool", JsonObject(emptyMap())))))
        val receipt = original.copy(id = Message.Id("receipt"), role = Message.Role.USER,
            content = listOf(ContentItem.ToolResult(sourceCall, "tool", listOf(ContentItem.ToolResult.Data.Text("ACCEPTED")))))
        val history = listOf(original, receipt, call, result)
        assertEquals(history, AiRuntimeRequest(emptyList(), history).projectedMessages())
        assertEquals(listOf(sourceCall, completionCall), history.flatMap { it.content }.filterIsInstance<ContentItem.ToolCall>().map { it.id })
        assertEquals(listOf(sourceCall, completionCall), history.flatMap { it.content }.filterIsInstance<ContentItem.ToolResult>().map { it.toolUseId })
    }

    @Test fun syntheticCompletionCannotMintRuntimeProvenanceThroughMetadataAlone() {
        for (author in listOf(null, Message.Author.User(User.Id("user"), "User"))) {
            for (index in 0..1) {
                val messages = listOf(call, result).mapIndexed { i, message -> if (index == i) message.copy(author = author) else message }
                assertFailsWith<IllegalStateException> { AiRuntimeRequest(emptyList(), messages).projectedMessages() }
            }
        }
    }

    @Test fun collisionsDoNotMergeCompletionWithTheSourceOrAnotherPair() {
        val native = call.copy(id = Message.Id("native"), author = null, instructions = emptyList(), providerMetadata = JsonObject(emptyMap()))
        assertFailsWith<IllegalStateException> { AiRuntimeRequest(emptyList(), listOf(native, call, result)).projectedMessages() }
        assertFailsWith<IllegalStateException> { AiRuntimeRequest(emptyList(), listOf(call, result, call.copy(id = Message.Id("other")))).projectedMessages() }
    }

    @Test fun newAsyncPairsRejectInvalidIdsRatherThanApplyingLegacyNormalization() {
        val invalid = ContentItem.ToolCall.Id("not-a-portable-async-id")
        val badCall = call.copy(content = listOf(assertIs<ContentItem.ToolCall>(call.content.single()).copy(id = invalid)))
        val badResult = result.copy(content = listOf(assertIs<ContentItem.ToolResult>(result.content.single()).copy(toolUseId = invalid)))
        assertFailsWith<IllegalStateException> { AiRuntimeRequest(emptyList(), listOf(badCall, badResult)).projectedMessages() }
    }

    @Test fun bothStoredCompletionHalvesAreProtectedFromHistoryEditing() {
        assertFailsWith<IllegalArgumentException> { requireEditableConversationMessage(call) }
        assertFailsWith<IllegalArgumentException> { requireEditableConversationMessage(result) }
        requireEditableConversationMessage(call.copy(author = null, providerMetadata = JsonObject(emptyMap())))
    }

    @Test fun runtimeObservationIsVisibleWithoutChangingTheToolResult() {
        val observation = assertIs<Message.Instruction.RuntimeObservation>(call.instructions.single()).text
        assertEquals(observation, call.assistantTextWithRuntimeObservations(""))
        assertEquals("$observation\nExisting assistant text", call.assistantTextWithRuntimeObservations("Existing assistant text"))
        assertFailsWith<IllegalStateException> { call.copy(author = null).assistantTextWithRuntimeObservations("") }
        assertEquals("Normal assistant text", call.copy(author = null, instructions = emptyList()).assistantTextWithRuntimeObservations("Normal assistant text"))
        assertEquals(result.content, AiRuntimeRequest(emptyList(), listOf(call, result)).projectedMessages()[1].content)
    }

    @Test fun savedPairRoundTripsWithItsNativeTypedOutputAndProvenance() {
        for (message in listOf(call, result)) {
            val decoded = Json.decodeFromString(Message.serializer(), Json.encodeToString(Message.serializer(), message))
            assertEquals(message, decoded)
        }
    }
}
