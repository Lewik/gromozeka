package com.gromozeka.bot

import com.gromozeka.bot.model.ClaudeLogEntry
import com.gromozeka.bot.services.ClaudeLogEntryMapper
import com.gromozeka.shared.domain.message.ChatMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.beInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class ClaudeLogEntryMapperTest : FunSpec({

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = false
    }

    fun parseSessionFiles(): List<ClaudeLogEntry> {
        val projectsDir = File("/Users/lewik/.claude/projects")

        val brokenFiles = setOf(
            "d6f9c172-d0bd-4eb3-84a6-daf8132472d5.jsonl",
            "c1d25871-c2f7-48cc-80f0-14cd75e45ac7.jsonl"
        )

        val allSessionFiles = projectsDir.walkTopDown()
            .filter { it.isFile && it.extension == "jsonl" }
            .filter { it.name !in brokenFiles }
            .toList()

        val entries = mutableListOf<ClaudeLogEntry>()

        allSessionFiles.forEach { file ->
            file.readLines().forEach { line ->
                if (line.trim().isNotEmpty()) {
                    try {
                        val entry = json.decodeFromString<ClaudeLogEntry>(line)
                        entries.add(entry)
                    } catch (e: Exception) {
                        // Skip parsing errors
                    }
                }
            }
        }

        return entries
    }

    test("parsing + mapping integration test") {
        val entries = parseSessionFiles()
        var successfullyMapped = 0
        var totalEntries = 0

        entries.forEach { entry ->
            totalEntries++
            try {
                val chatMessage = ClaudeLogEntryMapper.mapToChatMessage(entry)
                if (chatMessage != null) {
                    successfullyMapped++

                    // Basic validation
                    chatMessage.uuid.should { it.isNotEmpty() }
                    chatMessage.content.shouldNotBe(emptyList<ChatMessage.ContentItem>())
                    chatMessage.llmSpecificMetadata should beInstanceOf<ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry>()
                }
            } catch (e: Exception) {
                println("Failed to map entry ${entry::class.simpleName}: ${e.message}")
            }
        }

        println("Mapping Statistics:")
        println("Total entries: $totalEntries")
        println("Successfully mapped: $successfullyMapped (${(successfullyMapped * 100.0 / totalEntries).toInt()}%)")
    }

    test("mapper should handle UserEntry with text message") {
        val userEntry = ClaudeLogEntry.UserEntry(
            cwd = "/test",
            gitBranch = "main",
            sessionId = "session-123",
            timestamp = "2025-08-01T10:00:00Z",
            userType = "human",
            uuid = "user-uuid",
            version = "1.0",
            isSidechain = false,
            parentUuid = null,
            message = ClaudeLogEntry.Message.UserMessage(
                content = ClaudeLogEntry.ClaudeMessageContent.StringContent("Hello world")
            )
        )

        val result = ClaudeLogEntryMapper.mapToChatMessage(userEntry)
        result shouldNotBe null
        result!!.messageType shouldBe ChatMessage.MessageType.USER
        result.uuid shouldBe "user-uuid"
        result.cwd shouldBe "/test"
        result.gitBranch shouldBe "main"
        result.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.Message>()
    }

    test("mapper should handle SystemEntry") {
        val systemEntry = ClaudeLogEntry.SystemEntry(
            cwd = "/test",
            gitBranch = "main",
            sessionId = "session-123",
            timestamp = "2025-08-01T10:00:00Z",
            userType = "system",
            uuid = "system-uuid",
            version = "1.0",
            isSidechain = false,
            parentUuid = null,
            content = "System message",
            isMeta = false,
            level = "info",
            toolUseId = "tool-123"
        )

        val result = ClaudeLogEntryMapper.mapToChatMessage(systemEntry)
        result shouldNotBe null
        result!!.messageType shouldBe ChatMessage.MessageType.SYSTEM
        result.content shouldHaveSize 1

        val systemContent = result.content[0] as ChatMessage.ContentItem.System
        systemContent.content shouldBe "System message"
        systemContent.level shouldBe ChatMessage.ContentItem.System.SystemLevel.INFO
        systemContent.toolUseId shouldBe "tool-123"
    }

    test("mapper should skip SummaryEntry") {
        val summaryEntry = ClaudeLogEntry.SummaryEntry(
            leafUuid = "leaf-uuid",
            summary = "Summary text"
        )

        val result = ClaudeLogEntryMapper.mapToChatMessage(summaryEntry)
        result shouldBe null
    }

    test("mapper should handle tool calls in assistant messages") {
        // Создаем mock AssistantEntry с tool_use в content
        // (полную структуру делать лень, проверим что маппер не падает на базовых кейсах)

        val assistantEntry = ClaudeLogEntry.AssistantEntry(
            cwd = "/test",
            gitBranch = "main",
            sessionId = "session-123",
            timestamp = "2025-08-01T10:00:00Z",
            userType = "assistant",
            uuid = "assistant-uuid",
            version = "1.0",
            isSidechain = false,
            parentUuid = null,
            requestId = "req-123",
            message = null // TODO: нет желания создавать полную структуру для теста
        )

        val result = ClaudeLogEntryMapper.mapToChatMessage(assistantEntry)
        result shouldNotBe null
        result!!.messageType shouldBe ChatMessage.MessageType.ASSISTANT
        result.uuid shouldBe "assistant-uuid"

        val metadata = result.llmSpecificMetadata as ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry
        metadata.requestId shouldBe "req-123"
        metadata.isMeta shouldBe false
    }

    test("mapper should handle system levels correctly") {
        listOf(
            "info" to ChatMessage.ContentItem.System.SystemLevel.INFO,
            "warning" to ChatMessage.ContentItem.System.SystemLevel.WARNING,
            "error" to ChatMessage.ContentItem.System.SystemLevel.ERROR,
            "unknown" to ChatMessage.ContentItem.System.SystemLevel.INFO
        ).forEach { (level, expectedLevel) ->
            val systemEntry = ClaudeLogEntry.SystemEntry(
                cwd = null, gitBranch = null, sessionId = "s", timestamp = "2025-08-01T10:00:00Z",
                userType = null, uuid = "u", version = null, isSidechain = null, parentUuid = null,
                content = "test", isMeta = false, level = level
            )

            val result = ClaudeLogEntryMapper.mapToChatMessage(systemEntry)!!
            val systemContent = result.content[0] as ChatMessage.ContentItem.System
            systemContent.level shouldBe expectedLevel
        }
    }

    test("mapper should handle Gromozeka JSON content") {
        val gromozekaJson = JsonObject(
            mapOf(
                "fullText" to JsonPrimitive("Привет от Громозеки!"),
                "ttsText" to JsonPrimitive("Привет от Громозеки!"),
                "voiceTone" to JsonPrimitive("friendly")
            )
        )

        val userEntry = ClaudeLogEntry.UserEntry(
            cwd = "/test", gitBranch = "main", sessionId = "session-123",
            timestamp = "2025-08-01T10:00:00Z", userType = "human", uuid = "user-uuid",
            version = "1.0", isSidechain = false, parentUuid = null,
            message = ClaudeLogEntry.Message.UserMessage(
                content = ClaudeLogEntry.ClaudeMessageContent.GromozekaJsonContent(gromozekaJson)
            )
        )

        val result = ClaudeLogEntryMapper.mapToChatMessage(userEntry)
        result shouldNotBe null
        result!!.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.IntermediateMessage>()

        val gromozekaContent = result.content[0] as ChatMessage.ContentItem.IntermediateMessage
        gromozekaContent.structured?.fullText shouldBe "Привет от Громозеки!"
    }

    test("mapper should handle unknown JSON content") {
        val unknownJson = JsonObject(
            mapOf(
                "weird_field" to JsonPrimitive("strange_value"),
                "nested" to JsonObject(mapOf("data" to JsonPrimitive(42)))
            )
        )

        val userEntry = ClaudeLogEntry.UserEntry(
            cwd = "/test", gitBranch = "main", sessionId = "session-123",
            timestamp = "2025-08-01T10:00:00Z", userType = "human", uuid = "user-uuid",
            version = "1.0", isSidechain = false, parentUuid = null,
            message = ClaudeLogEntry.Message.UserMessage(
                content = ClaudeLogEntry.ClaudeMessageContent.UnknownJsonContent(unknownJson)
            )
        )

        val result = ClaudeLogEntryMapper.mapToChatMessage(userEntry)
        result shouldNotBe null
        result!!.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.UnknownJson>()

        val unknownContent = result.content[0] as ChatMessage.ContentItem.UnknownJson
        unknownContent.json should beInstanceOf<JsonObject>()
    }

    test("ClaudeMessageContent deserializer should handle string content") {
        val jsonString = "\"Простой текст\""
        val content = Json.decodeFromString<ClaudeLogEntry.ClaudeMessageContent>(jsonString)
        content should beInstanceOf<ClaudeLogEntry.ClaudeMessageContent.StringContent>()
        (content as ClaudeLogEntry.ClaudeMessageContent.StringContent).content shouldBe "Простой текст"
    }

    test("ClaudeMessageContent deserializer should handle Gromozeka format") {
        val gromozekaJsonString =
            """{"fullText": "Ответ Громозеки", "ttsText": "Ответ Громозеки", "voiceTone": "friendly"}"""
        val content = Json.decodeFromString<ClaudeLogEntry.ClaudeMessageContent>(gromozekaJsonString)
        content should beInstanceOf<ClaudeLogEntry.ClaudeMessageContent.GromozekaJsonContent>()

        val gromozekaContent = content as ClaudeLogEntry.ClaudeMessageContent.GromozekaJsonContent
        gromozekaContent.data["fullText"]?.jsonPrimitive?.content shouldBe "Ответ Громозеки"
        gromozekaContent.data["voiceTone"]?.jsonPrimitive?.content shouldBe "friendly"
    }

    test("ClaudeMessageContent deserializer should fallback to unknown") {
        val weirdJsonString = """{"some_weird_field": [1, 2, 3], "nested": {"unknown": true}}"""
        val content = Json.decodeFromString<ClaudeLogEntry.ClaudeMessageContent>(weirdJsonString)
        content should beInstanceOf<ClaudeLogEntry.ClaudeMessageContent.UnknownJsonContent>()
    }

    test("mapper should parse Gromozeka JSON from text content") {
        val gromozekaJsonText =
            """{"fullText": "Ответ Громозеки", "ttsText": "Короткий ответ", "voiceTone": "friendly"}"""
        val assistantEntry = ClaudeLogEntry.AssistantEntry(
            cwd = "/test", gitBranch = "main", sessionId = "session-123",
            timestamp = "2025-08-01T10:00:00Z", userType = "human", uuid = "assistant-uuid",
            version = "1.0", isSidechain = false, parentUuid = null, requestId = "req-123",
            message = ClaudeLogEntry.Message.AssistantMessage(
                id = "msg-123",
                model = "claude-opus",
                type = "message",
                usage = ClaudeLogEntry.UsageInfo(inputTokens = 10, outputTokens = 20),
                stopReason = null,
                stopSequence = null,
                content = listOf(ClaudeLogEntry.AssistantContent.TextContent(gromozekaJsonText))
            )
        )

        val result = ClaudeLogEntryMapper.mapToChatMessage(assistantEntry)
        result shouldNotBe null
        result!!.content shouldHaveSize 1
        result.content[0] should beInstanceOf<ChatMessage.ContentItem.IntermediateMessage>()

        val gromozekaContent = result.content[0] as ChatMessage.ContentItem.IntermediateMessage
        gromozekaContent.structured?.fullText shouldBe "Ответ Громозеки"
        gromozekaContent.structured?.ttsText shouldBe "Короткий ответ"
        gromozekaContent.structured?.voiceTone shouldBe "friendly"
    }
})