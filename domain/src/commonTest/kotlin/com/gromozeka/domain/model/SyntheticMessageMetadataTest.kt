package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolCall
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class SyntheticMessageMetadataTest {
    @Test
    fun kindsUseStandardEnumSerializationAndTypedAccess() {
        for (kind in SyntheticMessageKind.entries) {
            assertEquals(JsonPrimitive(kind.name), Json.encodeToJsonElement(kind))
            val metadata = buildJsonObject { putSyntheticMessageMetadata(kind) }
            assertEquals(kind, message(metadata).syntheticKind)
            assertEquals(JsonPrimitive(true), metadata["synthetic"])
            assertEquals(kind, Json.decodeFromJsonElement<SyntheticMessageKind>(metadata.getValue("syntheticKind")))
        }
    }

    @Test
    fun runtimeDoesNotAcceptOldSpellingsUnknownKindsOrWrongTypes() {
        for (kind in listOf(JsonPrimitive("command_task_completion"), JsonPrimitive("UNKNOWN"), JsonPrimitive(1), JsonNull)) {
            assertFailsWith<SerializationException> { message(buildJsonObject { put("syntheticKind", kind) }).syntheticKind }
        }
        assertNull(message(buildJsonObject { put("provider", "OPENAI") }).syntheticKind)
    }

    @Test
    fun launchSourceRoundTripsWithoutInventingAnAuthor() {
        val source = BackgroundActivityMessageSource(ToolCall.Id("actual-launch"), AgentDefinition.Id("agent"))
        for (kind in listOf(SyntheticMessageKind.COMMAND_TASK_COMPLETION, SyntheticMessageKind.COMMAND_MONITOR_UPDATE)) {
            val original = message(buildJsonObject { putSyntheticMessageMetadata(kind, source) })
            val restored = Json.decodeFromJsonElement<Message>(Json.encodeToJsonElement(original))
            assertEquals(original, restored)
            assertEquals(source, restored.backgroundActivitySource)
            assertNull(restored.author)
        }
    }

    @Test
    fun invalidLaunchSourcesFailInsteadOfBecomingUnattributedMessages() {
        assertFailsWith<IllegalArgumentException> { BackgroundActivityMessageSource(ToolCall.Id(" "), AgentDefinition.Id("agent")) }
        val source = BackgroundActivityMessageSource(ToolCall.Id("launch"), AgentDefinition.Id("agent"))
        assertFailsWith<IllegalArgumentException> { buildJsonObject { putSyntheticMessageMetadata(SyntheticMessageKind.MEMORY, source) } }
        assertFailsWith<IllegalArgumentException> {
            message(buildJsonObject { putSyntheticMessageMetadata(SyntheticMessageKind.MEMORY); put("backgroundActivitySource", Json.encodeToJsonElement(source)) }).backgroundActivitySource
        }
        assertFailsWith<SerializationException> {
            message(buildJsonObject { putSyntheticMessageMetadata(SyntheticMessageKind.COMMAND_TASK_COMPLETION); put("backgroundActivitySource", buildJsonObject {}) }).backgroundActivitySource
        }
    }

    private fun message(metadata: JsonObject) = Message(Message.Id("message"), Conversation.Id("conversation"),
        role = Message.Role.ASSISTANT, content = emptyList(), providerMetadata = metadata, createdAt = Instant.fromEpochMilliseconds(0))
}
