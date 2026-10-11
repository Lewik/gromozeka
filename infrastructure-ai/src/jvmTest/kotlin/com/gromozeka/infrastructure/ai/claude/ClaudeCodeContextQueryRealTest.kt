package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.ClaudeCodeSessionStateRepository
import com.gromozeka.domain.tool.ToolAccessPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.*
import kotlin.time.Clock

/** Opt-in protocol smoke only: no Server, database, real conversation, or application configuration. */
class ClaudeCodeContextQueryRealTest {
    @Test fun isolatedToolFreeProtocol() = runBlocking<Unit> {
        assumeTrue("Opt-in live protocol smoke", System.getenv("GROMOZEKA_RUN_CONTEXT_QUERY_CLAUDE_SMOKE") == "true")
        val model = requireNotNull(System.getenv("GROMOZEKA_CONTEXT_QUERY_SMOKE_MODEL")) { "Explicit smoke model required" }
        val process = ProcessClaudeCodeCliExecutor()
        val commands = mutableListOf<ClaudeCodeCommand>()
        val executor = object : ClaudeCodeCliExecutor {
            override suspend fun execute(command: ClaudeCodeCommand): ClaudeCodeCliResponse {
                commands += command
                return process.execute(command)
            }
        }
        val forbiddenSessions = object : ClaudeCodeSessionStateRepository {
            override suspend fun find(key: ClaudeCodeSessionState.Key): ClaudeCodeSessionState? = error("Native session read forbidden")
            override suspend fun save(state: ClaudeCodeSessionState): ClaudeCodeSessionState = error("Native session write forbidden")
            override suspend fun delete(key: ClaudeCodeSessionState.Key) { error("Native session delete forbidden") }
        }
        try {
            val runtime = ClaudeCodeCliRuntime(executor = executor, connectionId = "synthetic-query-smoke",
                modelConfigurationId = "synthetic-query-smoke", modelName = model, workspaceDirectory = null,
                sessionStateRepository = forbiddenSessions, sessionLocks = java.util.concurrent.ConcurrentHashMap())
            val message = Conversation.Message(Conversation.Message.Id("synthetic-input"), Conversation.Id("synthetic-target"),
                role = Conversation.Message.Role.USER, content = listOf(Conversation.Message.ContentItem.UserMessage(
                    "This is a synthetic read-only protocol check. Reply briefly; tools and actions are not available.")), createdAt = Clock.System.now())
            val response = withTimeout(90_000) { runtime.call(AiRuntimeRequest(listOf("Return a final answer to the supplied question."),
                listOf(message), options = AiRuntimeOptions(usagePurpose = AI_CONTEXT_QUERY_PURPOSE,
                    toolChoice = AiToolChoice.None, toolAccess = ToolAccessPolicy.AllowOnly(),
                    assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    toolContext = mapOf("conversationId" to "synthetic-target", "threadId" to "synthetic-main-branch", "projectId" to "synthetic-project")))) }
            val command = commands.single()
            assertNull(command.cacheKey)
            assertNull(command.resumeSessionId)
            assertTrue(command.noSessionPersistence)
            assertTrue(command.disableCompaction)
            assertTrue(command.nativeTools.isEmpty())
            assertEquals(AiStepOutcome.COMPLETE, response.outcome)
            assertTrue(response.messages.flatMap { it.content }.none {
                it is Conversation.Message.ContentItem.ToolCall || it is Conversation.Message.ContentItem.ToolResult })
            assertTrue(response.messages.flatMap { it.content }.filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
                .any { it.structured.fullText.isNotBlank() })
            // Measurements only, not an assertion of cache reuse or answer intelligence.
            println("CONTEXT_QUERY_PROTOCOL_OK commands=${commands.size} input=${response.usage?.promptTokens} cache_read=${response.usage?.cacheReadTokens} cache_write=${response.usage?.cacheCreationTokens}")
        } finally { process.shutdown() }
    }
}
