package db.migration.postgres

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SearchToolResultFormatMigrationTest {
    private val migration = V102__search_tool_result_format()

    @Test
    fun `legacy search result becomes the current names-only format without changing other results`() {
        val legacy = """
            {"query":"calendar","catalog_revision":"revision","loaded_for_current_context":true,"count":1,
             "tools":[{"name":"calendar_create_event","description":"Create an event.","source":"calendar",
             "input_schema":{"type":"object","properties":{}}}]}
        """.trimIndent().replace("\n", "")
        val unrelated = Conversation.Message.ContentItem.ToolResult(
            toolUseId = Conversation.Message.ContentItem.ToolCall.Id("other-call"),
            toolName = "other_tool",
            result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text(legacy)),
        )
        val message = Conversation.Message(
            id = Conversation.Message.Id("result-message"),
            conversationId = Conversation.Id("conversation"),
            role = Conversation.Message.Role.USER,
            content = listOf(
                Conversation.Message.ContentItem.ToolResult(
                    toolUseId = Conversation.Message.ContentItem.ToolCall.Id("search-call"),
                    toolName = "search_tools",
                    result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text(legacy)),
                ),
                unrelated,
            ),
            createdAt = Instant.fromEpochMilliseconds(1),
        )
        val original = Json.encodeToJsonElement(message).jsonObject

        val migrated = migration.migrateDocument(original)
        val decoded = Json.decodeFromJsonElement<Conversation.Message>(migrated)
        val searchResult = decoded.content.first() as Conversation.Message.ContentItem.ToolResult

        assertEquals(
            """{"tools":["calendar_create_event"]}""",
            (searchResult.result.single() as Conversation.Message.ContentItem.ToolResult.Data.Text).content,
        )
        assertEquals(unrelated, decoded.content.last())
        assertEquals(migrated, migration.migrateDocument(migrated))
    }
}
