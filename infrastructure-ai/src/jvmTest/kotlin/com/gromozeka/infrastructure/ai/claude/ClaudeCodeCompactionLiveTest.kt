package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.infrastructure.ai.runtime.TestAiAdmission

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.ClaudeCodeSessionStateRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*
import kotlin.time.Clock

class ClaudeCodeCompactionLiveTest {
    @Test
    fun compactsForksAndRecoversAnUnavailableNativeSession() = verifyRecovery(nativeCompaction = false)

    @Test
    fun nativeAutoCompactionPreservesTheReplayTail() = verifyRecovery(nativeCompaction = true)

    @Test
    fun messageSquashHelperCompletesWithoutPersistingANativeSession() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("GROMOZEKA_CLAUDE_COMPACTION_LIVE") == "true")
        val process = ProcessClaudeCodeCliExecutor(TestAiAdmission(), System.getenv("GROMOZEKA_CLAUDE_EXECUTABLE") ?: "claude")
        val sessions = Sessions()
        val runtime = ClaudeCodeCliRuntime(
            executor = process,
            connectionId = "squash-live-${UUID.randomUUID()}",
            modelConfigurationId = "haiku",
            modelName = "haiku",
            workspaceDirectory = null,
            sessionStateRepository = sessions,
            sessionLocks = ConcurrentHashMap(),
        )
        val conversationId = Conversation.Id(UUID.randomUUID().toString())
        try {
            withTimeout(120_000) {
                repeat(2) {
                    val response = runtime.call(AiRuntimeRequest(
                        systemPrompts = emptyList(),
                        messages = listOf(Conversation.Message(
                            id = Conversation.Message.Id(UUID.randomUUID().toString()),
                            conversationId = conversationId,
                            role = Conversation.Message.Role.USER,
                            content = listOf(Conversation.Message.ContentItem.UserMessage(
                                "Summarize this synthetic selection in one short sentence.\n" +
                                    "<selection>The fixture stores an orange marker. Keep the marker unchanged.</selection>",
                            )),
                            createdAt = Clock.System.now(),
                        )),
                        options = AiRuntimeOptions(
                            usagePurpose = "MESSAGE_SQUASH",
                            toolChoice = AiToolChoice.None,
                            reasoning = AiReasoningConfig(mode = AiReasoningMode.DISABLED),
                            toolContext = mapOf("conversationId" to conversationId.value, "threadId" to "helper", "projectId" to "synthetic"),
                        ),
                    ))
                    assertEquals(AiStepOutcome.COMPLETE, response.outcome)
                    assertTrue(response.text().isNotBlank())
                    assertTrue(response.messages.flatMap { it.content }.none { it is Conversation.Message.ContentItem.ToolCall })
                    assertTrue(sessions.states.isEmpty())
                }
            }
        } finally {
            process.close()
        }
    }

    private fun verifyRecovery(nativeCompaction: Boolean) = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("GROMOZEKA_CLAUDE_COMPACTION_LIVE") == "true")
        val executable = System.getenv("GROMOZEKA_CLAUDE_EXECUTABLE") ?: "claude"
        val launcher = if (nativeCompaction) java.io.File.createTempFile("gromozeka-compact-probe-", ".sh").apply {
            writeText("#!/bin/sh\nexport CLAUDE_CODE_AUTO_COMPACT_WINDOW=100000\nexport CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=5\nexec '" + executable.replace("'", "'\"'\"'") + "' \"\$@\"\n")
            check(setExecutable(true))
        } else null
        val process = ProcessClaudeCodeCliExecutor(TestAiAdmission(), launcher?.absolutePath ?: executable)
        val sessions = Sessions()
        val commands = mutableListOf<ClaudeCodeCommand>()
        val executor = object : ClaudeCodeCliExecutor {
            override suspend fun execute(command: ClaudeCodeCommand): ClaudeCodeCliResponse = process.execute(command)
            override suspend fun <T> withSession(command: ClaudeCodeCommand, block: suspend (ClaudeCodeCliExecutor) -> T): T =
                process.withSession(command) { delegate ->
                    block(object : ClaudeCodeCliExecutor {
                        override suspend fun execute(command: ClaudeCodeCommand): ClaudeCodeCliResponse {
                            commands += command
                            return delegate.execute(command)
                        }
                    })
                }
        }
        val runtime = ClaudeCodeCliRuntime(
            executor = executor,
            connectionId = "compaction-live-${UUID.randomUUID()}",
            modelConfigurationId = "haiku",
            modelName = "haiku",
            workspaceDirectory = null,
            sessionStateRepository = sessions,
            sessionLocks = ConcurrentHashMap(),
        )
        val conversationId = Conversation.Id(UUID.randomUUID().toString())
        val messages = mutableListOf<Conversation.Message>()
        val marker = "AURORA_${UUID.randomUUID().toString().take(8)}"
        val tailMarker = "TAIL_${UUID.randomUUID().toString().take(8)}"
        var thread = "original"
        fun currentRequest() = AiRuntimeRequest(
            systemPrompts = listOf("You are a synthetic integration test. Remember the exact marker supplied by the user. Answer briefly. No tools."),
            messages = messages.toList(),
            options = AiRuntimeOptions(reasoning = AiReasoningConfig(mode = AiReasoningMode.DISABLED),
                toolContext = mapOf("conversationId" to conversationId.value, "threadId" to thread, "projectId" to "synthetic")),
        )
        fun persist(response: AiRuntimeResponse) {
            for (generated in response.messages) {
                var saved = Conversation.Message(
                    id = Conversation.Message.Id(UUID.randomUUID().toString()), conversationId = conversationId,
                    role = Conversation.Message.Role.ASSISTANT, content = generated.content, createdAt = Clock.System.now(),
                    providerMetadata = kotlinx.serialization.json.JsonObject((response.providerMetadata + generated.metadata).mapValues { (_, value) ->
                        value as? kotlinx.serialization.json.JsonElement ?: kotlinx.serialization.json.JsonPrimitive(value?.toString())
                    }),
                )
                val checkpoint = saved.content.singleOrNull() as? Conversation.Message.ContentItem.ContextCompactionResult
                if (checkpoint != null) {
                    val source = Conversation.Thread.Id(thread)
                    thread = "cow-${UUID.randomUUID()}"
                    saved = saved.copy(content = listOf(checkpoint.copy(historyTransition =
                        Conversation.Message.ContentItem.ContextCompactionResult.HistoryTransition(source, Conversation.Thread.Id(thread)))))
                    val boundary = messages.indexOfLast { it.id == checkpoint.sourceMessageIds.last() }
                    check(boundary >= 0)
                    messages.add(boundary + 1, saved)
                } else messages += saved
            }
        }
        suspend fun ask(text: String, compactBeforeGeneration: Boolean = false): AiRuntimeResponse {
            messages += Conversation.Message(id = Conversation.Message.Id(UUID.randomUUID().toString()), conversationId = conversationId,
                role = Conversation.Message.Role.USER, content = listOf(Conversation.Message.ContentItem.UserMessage(text)), createdAt = Clock.System.now())
            if (compactBeforeGeneration) {
                val prepared = currentRequest()
                assertTrue(runtime.inspectContext(prepared).nativeCompactionAvailable)
                val compacted = runtime.compact(prepared.copy(options = prepared.options.copy(usagePurpose = "CONTEXT_COMPACTION")))
                assertEquals(1, compacted.messages.size)
                assertEquals(AiStepOutcome.CONTINUE, compacted.outcome)
                assertFalse((compacted.messages.single().content.single() as Conversation.Message.ContentItem.ContextCompactionResult)
                    .sourceMessageIds.contains(messages.last().id), "Unsent user input must remain outside native coverage")
                persist(compacted)
            }
            val response = runtime.call(currentRequest())
            persist(response)
            println("COMPACTION_PROBE native=$nativeCompaction thread=$thread resumed=${response.providerMetadata["resumed"]} context=${response.contextUsage?.inputTokens}")
            return response
        }
        try {
            withTimeout(240_000) {
                ask("The project marker is $marker. Remember it and reply OK.\n" + (1..250).joinToString("\n") { "Inert reference $it: alpha beta gamma delta epsilon zeta." })
                ask("Keep the project marker. Reply OK.")
                val compacted = ask("The separate task marker is $tailMarker. Remember both markers without replacing the original project marker. Now reply only with the original project marker.", compactBeforeGeneration = !nativeCompaction)
                assertTrue(messages.flatMap { it.content }.any { it is Conversation.Message.ContentItem.ContextCompactionResult })
                assertEquals(if (nativeCompaction) 0 else 1, commands.count { it.userPrompt == "/compact" })
                assertTrue(compacted.text().contains(marker))

                thread = "fork"
                val forked = ask("Return both exact markers: the original project marker and the separate task marker.")
                assertEquals(false, forked.providerMetadata["resumed"])
                assertTrue(forked.text().contains(marker))
                assertTrue(forked.text().contains(tailMarker))
                assertFalse(commands.last().userPrompt.contains("Inert reference 249"))

                sessions.states.replaceAll { key, state ->
                    if (key.threadId.value == thread) state.copy(claudeSessionId = UUID.randomUUID().toString()) else state
                }
                val recovered = ask("Again, return both exact markers: the original project marker and the separate task marker.")
                assertEquals(false, recovered.providerMetadata["resumed"])
                assertTrue(recovered.text().contains(marker))
                assertTrue(recovered.text().contains(tailMarker))
            }
        } finally {
            process.close()
            launcher?.delete()
        }
    }

    private fun AiRuntimeResponse.text(): String = messages.flatMap { it.content }
        .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
        .joinToString("\n") { it.structured.fullText.orEmpty() }

    private class Sessions : ClaudeCodeSessionStateRepository {
        val states = mutableMapOf<ClaudeCodeSessionState.Key, ClaudeCodeSessionState>()
        override suspend fun find(key: ClaudeCodeSessionState.Key): ClaudeCodeSessionState? = states[key]
        override suspend fun save(state: ClaudeCodeSessionState): ClaudeCodeSessionState = state.also { states[it.key] = it }
        override suspend fun delete(key: ClaudeCodeSessionState.Key) { states.remove(key) }
    }
}
