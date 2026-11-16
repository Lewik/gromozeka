package db.migration.postgres

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.projectedMessages
import kotlinx.serialization.json.*
import kotlin.test.*

class TypedServiceMessagesMigrationTest {
    @Test fun `legacy SYSTEM text decodes and lowers as runtime data rather than an unsupported user block`() {
        val original = obj("""{"id":"incident","conversationId":"conversation","role":"SYSTEM","createdAt":"2026-11-16T00:00:00Z",
            "content":[{"type":"Message","text":"Original execution failure","state":"COMPLETE"}]}""")
        val decoded = Json.decodeFromJsonElement(Conversation.Message.serializer(), migrateServiceMessage(original))
        val notification = assertIs<Conversation.Message.ContentItem.RuntimeNotification>(decoded.content.single())
        assertEquals("Original execution failure", notification.content)
        val projected = com.gromozeka.domain.model.ai.AiRuntimeRequest(emptyList(), listOf(decoded)).projectedMessages().single()
        assertEquals(Conversation.Message.Role.USER, projected.role)
        val text = assertIs<Conversation.Message.ContentItem.UserMessage>(projected.content.single()).text
        assertContains(text, "Original execution failure")
        assertContains(text, "runtime_notification")
        val user = migrateServiceMessage(JsonObject(original + ("role" to JsonPrimitive("USER"))))
        assertEquals(original["content"], user["content"])
    }

    @Test fun `notification conversion preserves escaped NUL and does not reinterpret nested provider data`() {
        val document = obj("""{
            "id":"event", "conversationId":"conversation", "role":"SYSTEM", "createdAt":"2026-11-16T00:00:00Z",
            "content":[{"type":"com.gromozeka.domain.model.Conversation.Message.ContentItem.System","level":"ERROR","content":"before\u0000after"}],
            "providerMetadata":{"native":{"role":"SYSTEM","type":"com.gromozeka.domain.model.Conversation.Message.ContentItem.System"}}
        }""")
        val migrated = migrateServiceMessage(document)
        val decoded = Json.decodeFromJsonElement(Conversation.Message.serializer(), migrated)
        assertEquals(Conversation.Message.Role.RUNTIME_NOTIFICATION, decoded.role)
        assertEquals("before\u0000after", assertIs<Conversation.Message.ContentItem.RuntimeNotification>(decoded.content.single()).content)
        assertEquals(document["providerMetadata"], migrated["providerMetadata"])
        assertEquals(migrated, migrateServiceMessage(migrated))
    }

    @Test fun `old user text stays user text without guessed peer classification`() {
        val document = obj("""{"role":"USER","content":[{"type":"Message","text":"From another agent: please work"}],
            "instructions":[{"type":"agent","tabId":"old-tab"},{"type":"user"}]}""")
        val migrated = migrateServiceMessage(document)
        assertEquals(JsonPrimitive("USER"), migrated["role"])
        assertEquals(document["content"], migrated["content"])
        assertEquals(obj("""{"type":"user"}"""), migrated.getValue("instructions").jsonArray.single())
    }

    @Test fun `canonical original content is converted but tool argument lookalikes are untouched`() {
        val oldContent = arr("""[{"type":"com.gromozeka.domain.model.Conversation.Message.ContentItem.System","level":"INFO","content":"notice"}]""")
        val tool = obj("""{"type":"ToolCall","call":{"input":{"role":"SYSTEM","content":$oldContent}}}""")
        val document = buildJsonObject {
            put("role", "ASSISTANT"); put("content", JsonArray(listOf(tool)))
            put("providerMetadata", buildJsonObject { put("gromozekaCollaborationOriginalContent", oldContent) })
        }
        val migrated = migrateServiceMessage(document)
        assertEquals(tool, migrated.getValue("content").jsonArray.single())
        assertEquals(JsonPrimitive("runtime_notification"), migrated.getValue("providerMetadata").jsonObject
            .getValue("gromozekaCollaborationOriginalContent").jsonArray.single().jsonObject["type"])
    }

    @Test fun `queued canonical inputs and history edits are converted without touching unrelated payloads`() {
        val message = obj("""{"role":"SYSTEM","content":[{"type":"com.gromozeka.domain.model.Conversation.Message.ContentItem.System","level":"INFO","content":"notice"}]}""")
        val task = buildJsonObject { put("payload", buildJsonObject { put("type", "agent_invocation"); put("userMessage", message) }) }
        val toolTask = buildJsonObject { put("payload", buildJsonObject { put("type", "tool_execution"); put("userMessage", message) }) }
        val editTask = buildJsonObject { put("payload", buildJsonObject { put("type", "history_mutation"); put("mutation", buildJsonObject {
            put("mutationType", "edit"); put("newContent", message.getValue("content"))
        }) }) }
        val original = buildJsonObject {
            put("activeTask", task); put("activeInsertions", JsonArray(listOf(task))); put("continuationTask", toolTask)
            put("pendingTasks", JsonArray(listOf(task, editTask)))
            put("incidents", JsonArray(listOf(buildJsonObject { put("task", task); put("message", "original failure") })))
            put("proactiveCompaction", buildJsonObject { put("checkpoint", message) })
        }
        val migrated = migrateServiceScheduling(original)
        assertEquals(JsonPrimitive("RUNTIME_NOTIFICATION"), migrated.getValue("activeTask").jsonObject.getValue("payload").jsonObject.getValue("userMessage").jsonObject["role"])
        assertEquals(toolTask, migrated["continuationTask"])
        val incident = migrated.getValue("incidents").jsonArray.single().jsonObject
        assertEquals(migrated.getValue("activeTask"), incident["task"])
        assertEquals(JsonPrimitive("original failure"), incident["message"])
        assertEquals(JsonPrimitive("RUNTIME_NOTIFICATION"), migrated.getValue("proactiveCompaction").jsonObject.getValue("checkpoint").jsonObject["role"])
        assertEquals(JsonPrimitive("runtime_notification"), migrated.getValue("pendingTasks").jsonArray.last().jsonObject
            .getValue("payload").jsonObject.getValue("mutation").jsonObject.getValue("newContent").jsonArray.single().jsonObject["type"])
        assertEquals(migrated, migrateServiceScheduling(migrated))
    }

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun arr(text: String) = Json.parseToJsonElement(text).jsonArray
}
