package com.gromozeka.infrastructure.ai.claude

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.ClaudeCodeSessionStateRepository
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class ClaudeCodeLatencyProbeTest {
    @Test
    fun validatesCapturedSyntheticResponsesWhenEnabled() {
        val root = System.getenv("GROMOZEKA_CLAUDE_PROBE_REPLAY_DIR") ?: return
        var validated = 0
        for (directory in requireNotNull(File(root).listFiles()).filter { it.name.startsWith("process-") }) {
            val system = directory.resolve("system-prompt.txt").readText()
            val schema = Json.parseToJsonElement(system.substringAfter("<json_schema>\n").substringBefore("\n</json_schema>"))
            val contract = ClaudeCodeResponseContract(schema, listOf(probeTool()), true)
            directory.resolve("protocol.jsonl").useLines { lines ->
                lines.forEach { line ->
                    val record = Json.parseToJsonElement(line).jsonObject
                    if (record["direction"]?.jsonPrimitive?.content != "stdout") return@forEach
                    val event = Json.parseToJsonElement(record.getValue("data").jsonPrimitive.content).jsonObject
                    if (event["type"]?.jsonPrimitive?.content != "result" || event["is_error"]?.jsonPrimitive?.booleanOrNull == true) return@forEach
                    assertTrue(contract.validationErrors(event.getValue("result").jsonPrimitive.content).isEmpty())
                    validated++
                }
            }
        }
        assertTrue(validated > 0)
        println("CLAUDE_PROBE_REPLAY validatedResponses=$validated")
    }

    @Test
    fun compareSyntheticCliAndProviderLatencyWhenEnabled() = runBlocking {
        if (System.getenv("GROMOZEKA_CLAUDE_SYNTHETIC_PROBE") != "true") return@runBlocking
        val logger = LoggerFactory.getLogger("com.gromozeka.infrastructure.ai.claude") as Logger
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        val executable = requireNotNull(System.getenv("GROMOZEKA_CLAUDE_CODE_EXECUTABLE"))
        val model = System.getenv("GROMOZEKA_CLAUDE_CODE_MODEL") ?: "claude-opus-5"
        val effort = AiReasoningEffort.valueOf((System.getenv("GROMOZEKA_CLAUDE_PROBE_EFFORT") ?: "medium").uppercase())
        val turns = (System.getenv("GROMOZEKA_CLAUDE_PROBE_TURNS") ?: "3").toInt().also { require(it in 1..10) }
        val referenceLines = (System.getenv("GROMOZEKA_CLAUDE_PROBE_REFERENCE_LINES") ?: "0").toInt().also { require(it in 0..1_000) }
        val dialogue = System.getenv("GROMOZEKA_CLAUDE_PROBE_PROFILE") == "dialogue"
        val systemPrompt = SYSTEM
        val processExecutor = ProcessClaudeCodeCliExecutor(executable)
        val executor = processExecutor
        val workspace = File(requireNotNull(System.getenv("GROMOZEKA_CLAUDE_DIAGNOSTIC_DIR")))
        val selected = (System.getenv("GROMOZEKA_CLAUDE_PROBE_CASES")
            ?: "direct-text,runtime-text,runtime-schema,runtime-tools").split(',').toSet()
        try {
            for (scenario in listOf("direct-text", "runtime-text", "runtime-schema", "runtime-tools", "runtime-inline", "runtime-tool-loop", "runtime-inline-tool-loop", "runtime-shared-auxiliary")) {
                if (scenario !in selected) continue
                val inline = scenario in setOf("runtime-inline", "runtime-inline-tool-loop")
                val toolLoop = scenario in setOf("runtime-tool-loop", "runtime-inline-tool-loop")
                val conversationId = UUID.randomUUID().toString()
                val runtime = ClaudeCodeCliRuntime(
                    executor, connectionId = "latency-probe", modelConfigurationId = model,
                    modelName = model, workspaceDirectory = workspace,
                    sessionStateRepository = ProbeSessions(), sessionLocks = ConcurrentHashMap(),
                )
                val messages = mutableListOf<Conversation.Message>()
                var sessionId: String? = null
                repeat(turns) { index ->
                    val label = "$scenario-${index + 1}"
                    val probeKey = if (inline) "образец-$index" else "sample-$index"
                    val question = if (toolLoop) {
                        "Call the external action lookup_probe with key=$probeKey. You must request it even if you know the answer. " +
                            if (inline) "After receiving the result: ${dialogueQuestion(index).replace("Не используй инструменты. ", "")}" else
                                "After receiving the result, respond in one short sentence ending with PROBE_OK."
                    } else if (dialogue) {
                        dialogueQuestion(index)
                    } else {
                        "In 120 to 150 words, explain why retrying a request after a network timeout can duplicate an action. " +
                            "Mention request IDs, deduplication and uncertain outcomes. No tools are needed. End with PROBE_OK."
                    }
                    val prompt = if (index == 0 && referenceLines > 0) {
                        buildString {
                            appendLine("Synthetic reference for this conversation, seed=$conversationId:")
                            repeat(referenceLines) { number ->
                                appendLine("Section $number: Requests have stable identifiers. A transport timeout is not proof that execution failed. " +
                                    "The receiver persists outcomes, suppresses duplicate actions, and acknowledges durable results. Marker=REFERENCE_$number.")
                            }
                            appendLine(question)
                        }
                    } else question
                    val startedAt = System.nanoTime()
                    val responseText = withTimeout(300_000) {
                        if (scenario == "direct-text") {
                            val result = executor.execute(ClaudeCodeCommand(
                                diagnosticId = label, connectionId = "latency-probe", cacheKey = conversationId,
                                modelName = model, workspaceDirectory = workspace, systemPrompt = systemPrompt,
                                userPrompt = prompt, effort = effort,
                                reasoningMode = AiReasoningMode.ADAPTIVE, resumeSessionId = sessionId,
                                noSessionPersistence = false,
                            ))
                            sessionId = result.sessionId
                            println("CLAUDE_PROBE_RESULT label=$label raw=${result.raw}")
                            result.result
                        } else {
                            messages += message(Conversation.Message.Role.USER, listOf(Conversation.Message.ContentItem.UserMessage(prompt)))
                            val request = AiRuntimeRequest(
                                systemPrompts = if (inline) listOf(systemPrompt, INLINE_INSTRUCTION) else listOf(systemPrompt),
                                messages = messages.toList(),
                                tools = if (inline || toolLoop || scenario == "runtime-tools") listOf(probeTool()) else emptyList(),
                                options = AiRuntimeOptions(
                                    responseFormat = when (scenario) {
                                        "runtime-schema" -> answerSchema
                                        "runtime-inline", "runtime-inline-tool-loop" -> inlineSchema
                                        else -> AiResponseFormat.Text
                                    },
                                    assistantResponseFormat = if (inline)
                                        AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA
                                    else AiModelConfiguration.AssistantResponseFormat.TEXT,
                                    reasoning = AiReasoningConfig(mode = AiReasoningMode.ADAPTIVE, effort = effort),
                                    toolContext = mapOf("conversationId" to conversationId, "threadId" to conversationId,
                                        "projectId" to "latency-probe", "aiCallDiagnosticId" to label),
                                    usagePurpose = "synthetic-latency-probe",
                                ),
                            )
                            var response = runtime.call(request)
                            messages += response.messages.map { message(Conversation.Message.Role.ASSISTANT, it.content) }
                            if (toolLoop) {
                                assertEquals(1, response.toolCalls.size)
                                val call = response.toolCalls.single()
                                assertEquals("lookup_probe", call.call.name)
                                assertEquals(buildJsonObject { put("key", probeKey) }, call.call.input)
                                messages += message(Conversation.Message.Role.USER, listOf(Conversation.Message.ContentItem.ToolResult(
                                    toolUseId = call.id, toolName = call.call.name,
                                    result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("synthetic-status=ready")),
                                )))
                                response = runtime.call(request.copy(messages = messages.toList(), options = request.options.copy(
                                    toolContext = request.options.toolContext + ("aiCallDiagnosticId" to "$label-result"),
                                )))
                                messages += response.messages.map { message(Conversation.Message.Role.ASSISTANT, it.content) }
                                assertEquals(true, response.providerMetadata["resumed"])
                            }
                            assertTrue(response.toolCalls.isEmpty())
                            if (inline) {
                                val answer = response.messages.flatMap { it.content }
                                    .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>().single().structured
                                assertEquals(2, answer.suggestedReplies.size)
                            }
                            if (index > 0 && scenario != "runtime-shared-auxiliary") assertEquals(true, response.providerMetadata["resumed"])
                            if (scenario == "runtime-shared-auxiliary" && index < turns - 1) {
                                runtime.call(request.copy(
                                    systemPrompts = listOf("Generate optional suggested replies. Return exactly SUGGESTION_OK."),
                                    messages = listOf(message(Conversation.Message.Role.USER,
                                        listOf(Conversation.Message.ContentItem.UserMessage("Return SUGGESTION_OK.")))),
                                    options = request.options.copy(usagePurpose = "SUGGESTED_REPLIES",
                                        toolContext = request.options.toolContext + ("aiCallDiagnosticId" to "$label-auxiliary")),
                                ))
                            }
                            println("CLAUDE_PROBE_USAGE label=$label usage=${response.usage}")
                            response.messages.flatMap { it.content }.filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
                                .joinToString { it.structured.fullText }
                        }
                    }
                    assertTrue(responseText.contains("PROBE_OK"), "$label returned unexpected synthetic response")
                    val visibleText = if (scenario == "runtime-schema") {
                        Json.parseToJsonElement(responseText).jsonObject.getValue("answer").jsonPrimitive.content
                    } else responseText
                    val sentences = Regex("(?m)^\\d+\\.\\s").findAll(visibleText).count()
                    println("CLAUDE_PROBE_SAMPLE label=$label model=$model effort=$effort totalMs=${(System.nanoTime() - startedAt) / 1_000_000} chars=${responseText.length} numberedSentences=$sentences")
                    if ((dialogue && scenario != "runtime-tool-loop") || (inline && toolLoop)) {
                        assertEquals(listOf(10, 15, 20, 12, 18)[index % 5], sentences, "$label returned $sentences numbered sentences")
                    }
                }
            }
        } finally {
            processExecutor.shutdown()
            logger.level = previousLevel
        }
    }

    private fun dialogueQuestion(index: Int): String {
        val topics = listOf(
            10 to "Объясни, почему у океана климат мягче, чем внутри материка.",
            15 to "Теперь продолжим: как в описанной тобой картине участвуют морской бриз и суточное изменение температуры?",
            20 to "А если рядом с этим побережьем находятся горы? Продолжи объяснение, связав его с предыдущими двумя ответами.",
            12 to "Сравни в нашем примере два города по разные стороны этих гор и объясни различия в осадках.",
            18 to "Подведи итог нашего разговора: опиши путешествие от моря через горы в глубину материка и изменения погоды по пути.",
        )
        val (sentences, topic) = topics[index % topics.size]
        return "$topic Ответь по-русски ровно $sentences полными предложениями, каждое на отдельной строке с номером вида 1. " +
            "Не используй инструменты. После последнего предложения добавь отдельную строку PROBE_OK."
    }

    private fun message(role: Conversation.Message.Role, content: List<Conversation.Message.ContentItem>) =
        Conversation.Message(Conversation.Message.Id(UUID.randomUUID().toString()), Conversation.Id("probe"), role = role,
            content = content, createdAt = Clock.System.now())

    private fun probeTool() = object : AiToolCallback {
        override val definition = AiToolDefinition("lookup_probe", "Look up a synthetic status only when explicitly asked.",
            """{"type":"object","properties":{"key":{"type":"string"}},"required":["key"],"additionalProperties":false}""")
        override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.WORKSPACE)
        override fun call(toolInput: String, context: ToolExecutionContext?): String = error("Probe tools must not execute")
    }

    private class ProbeSessions : ClaudeCodeSessionStateRepository {
        private val states = mutableMapOf<ClaudeCodeSessionState.Key, ClaudeCodeSessionState>()
        override suspend fun find(key: ClaudeCodeSessionState.Key) = states[key]
        override suspend fun save(state: ClaudeCodeSessionState) = state.also { states[it.key] = it }
        override suspend fun delete(key: ClaudeCodeSessionState.Key) { states.remove(key) }
    }

    private companion object {
        const val SYSTEM = "You are participating in a synthetic latency measurement. Answer only the supplied question."
        const val INLINE_INSTRUCTION = "Return the assistant answer as the configured structured response. " +
            "fullText: complete visible answer. ttsText: short speakable main answer, or empty string. " +
            "voiceTone: short English voice style hint, or empty string when ttsText is empty. " +
            "attentionRequested: true only when the user should act now. " +
            "suggestedReplies: include two concise replies the user might naturally send next, in this same response."
        val inlineSchema = AiResponseFormat.JsonSchema("gromozeka_assistant_response", buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                for (name in listOf("fullText", "ttsText", "voiceTone")) putJsonObject(name) { put("type", "string") }
                putJsonObject("attentionRequested") { put("type", "boolean") }
                putJsonObject("suggestedReplies") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
            }
            putJsonArray("required") {
                for (name in listOf("fullText", "ttsText", "voiceTone", "attentionRequested", "suggestedReplies")) add(name)
            }
        })
        val answerSchema = AiResponseFormat.JsonSchema("answer", Json.parseToJsonElement(
            """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}"""
        ).jsonObject)
    }
}
