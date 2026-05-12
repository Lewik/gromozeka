package com.gromozeka.server.testsupport.llm

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.infrastructure.ai.runtime.AiRuntimeBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiRuntimeCassetteProxyTest {

    @Test
    fun defaultDirectoryUsesTestResourcesInMemoryE2eMode() {
        withSystemProperties(
            "gromozeka.llm.cassette.dir" to null,
            "GROMOZEKA_HOME" to "/tmp/prod-home",
            "GROMOZEKA_MODE" to null,
            "gromozeka.memory.e2e" to "true",
        ) {
            val settings = AiRuntimeCassetteSettings.fromSystemProperties()

            assertEquals(projectRoot().resolve("server/src/test/resources/llm-cassettes"), settings.rootDirectory)
            assertEquals(AiRuntimeCassetteMode.REPLAY_ONLY, settings.mode)
        }
    }

    @Test
    fun defaultDirectoryUsesGromozekaHomeOutsideTests() {
        withSystemProperties(
            "gromozeka.llm.cassette.dir" to null,
            "GROMOZEKA_HOME" to "/tmp/prod-home",
            "GROMOZEKA_MODE" to "prod",
            "gromozeka.memory.e2e" to null,
        ) {
            val settings = AiRuntimeCassetteSettings.fromSystemProperties()

            assertEquals(Path.of("/tmp/prod-home", "llm-cassettes"), settings.rootDirectory)
            assertEquals(AiRuntimeCassetteMode.OFF, settings.mode)
        }
    }

    @Test
    fun recordMissingWritesCassetteAndReplaysByCanonicalFullRequest() = runBlocking {
        val root = Files.createTempDirectory("llm-cassettes-test-")
        try {
            val backend = CountingBackend()
            val provider = CassetteAiRuntimeProvider(
                backends = listOf(backend),
                settings = AiRuntimeCassetteSettings(
                    mode = AiRuntimeCassetteMode.RECORD_MISSING,
                    rootDirectory = root,
                ),
            )

            val firstRuntime = provider.getRuntime(
                provider = AIProvider.OPEN_AI,
                modelName = "fake/model:1",
                projectPath = "/tmp/gromozeka-e2e-111/projects/case-a",
            )
            val firstResponse = firstRuntime.call(requestWithDynamicIds("111", "Tell me the stored fact."))

            val secondRuntime = provider.getRuntime(
                provider = AIProvider.OPEN_AI,
                modelName = "fake/model:1",
                projectPath = "/tmp/gromozeka-e2e-222/projects/case-a",
            )
            val secondResponse = secondRuntime.call(requestWithDynamicIds("222", "Tell me the stored fact."))

            assertEquals("answer-1", firstResponse.text())
            assertEquals("answer-1", secondResponse.text())
            assertEquals("memory:222", secondResponse.providerMetadata["conversationKey"])
            assertEquals(1, backend.callCount)
            assertEquals(
                1,
                root.resolve("OPEN_AI")
                    .resolve("fake_model_1")
                    .resolve("call")
                    .listDirectoryEntries("*.json")
                    .size,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun canonicalRequestIgnoresRuntimeToolTargetAndRunIds() {
        val root = Files.createTempDirectory("llm-cassettes-test-")
        try {
            val store = AiRuntimeCassetteStore(root)
            val firstKey = store.keyFor(
                provider = "OPEN_AI",
                modelName = "fake/model:1",
                projectPath = "/tmp/gromozeka-e2e-111/projects/case-a",
                operation = AiRuntimeCassetteOperation.CALL,
                request = requestWithDynamicIds("111", "Tell me the stored fact."),
            )
            val secondKey = store.keyFor(
                provider = "OPEN_AI",
                modelName = "fake/model:1",
                projectPath = "/tmp/gromozeka-e2e-222/projects/case-a",
                operation = AiRuntimeCassetteOperation.CALL,
                request = requestWithDynamicIds("222", "Tell me the stored fact."),
            )

            assertEquals(firstKey.canonicalRequest, secondKey.canonicalRequest)
            assertEquals(firstKey.hash, secondKey.hash)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun refreshKeepsCassetteBodyStableWhenOnlyRuntimeFieldsChange() = runBlocking {
        val root = Files.createTempDirectory("llm-cassettes-test-")
        try {
            val firstBackend = DynamicMetadataBackend()
            val recordProvider = CassetteAiRuntimeProvider(
                backends = listOf(firstBackend),
                settings = AiRuntimeCassetteSettings(
                    mode = AiRuntimeCassetteMode.RECORD_MISSING,
                    rootDirectory = root,
                ),
            )

            recordProvider
                .getRuntime(AIProvider.OPEN_AI, "fake/model:1", "/tmp/gromozeka-e2e-111/projects/case-a")
                .call(requestWithDynamicIds("111", "Tell me the stored fact."))

            val cassettePath = root.resolve("OPEN_AI")
                .resolve("fake_model_1")
                .resolve("call")
                .listDirectoryEntries("*.json")
                .single()
            val firstBody = Files.readString(cassettePath)
            require("\\\"valid_from\\\":\\\"1970-01-01T00:00:00Z\\\"" in firstBody)
            require("\\\"explicit_date\\\":\\\"2026-01-10T00:00:00Z\\\"" in firstBody)

            val secondBackend = DynamicMetadataBackend()
            val refreshProvider = CassetteAiRuntimeProvider(
                backends = listOf(secondBackend),
                settings = AiRuntimeCassetteSettings(
                    mode = AiRuntimeCassetteMode.REFRESH,
                    rootDirectory = root,
                ),
            )

            refreshProvider
                .getRuntime(AIProvider.OPEN_AI, "fake/model:1", "/tmp/gromozeka-e2e-222/projects/case-a")
                .call(requestWithDynamicIds("222", "Tell me the stored fact."))

            assertEquals(firstBody, Files.readString(cassettePath))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun replayOnlyFailsOnMiss() = runBlocking {
        val root = Files.createTempDirectory("llm-cassettes-test-")
        try {
            val provider = CassetteAiRuntimeProvider(
                backends = listOf(CountingBackend()),
                settings = AiRuntimeCassetteSettings(
                    mode = AiRuntimeCassetteMode.REPLAY_ONLY,
                    rootDirectory = root,
                ),
            )
            val runtime = provider.getRuntime(AIProvider.OPEN_AI, "fake-model", projectPath = null)

            val failure = assertFailsWith<IllegalStateException> {
                runtime.call(requestWithDynamicIds("333", "No cassette exists."))
            }

            require("LLM cassette miss" in failure.message.orEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun usageReportListsAndDeletesUnusedCassettes() = runBlocking {
        val root = Files.createTempDirectory("llm-cassettes-test-")
        val artifacts = Files.createTempDirectory("llm-cassettes-artifacts-")
        AiRuntimeCassetteUsageRegistry.reset()
        try {
            val provider = CassetteAiRuntimeProvider(
                backends = listOf(CountingBackend()),
                settings = AiRuntimeCassetteSettings(
                    mode = AiRuntimeCassetteMode.RECORD_MISSING,
                    rootDirectory = root,
                ),
            )
            val runtime = provider.getRuntime(AIProvider.OPEN_AI, "fake/model:1", projectPath = null)

            runtime.call(requestWithDynamicIds("444", "Tell me the stored fact."))
            val cassetteDirectory = root.resolve("OPEN_AI")
                .resolve("fake_model_1")
                .resolve("call")
            val unusedCassette = cassetteDirectory.resolve("unused-cassette.json")
            Files.writeString(unusedCassette, "{}")

            val report = AiRuntimeCassetteUsageReporter.writeReport(
                rootDirectory = root,
                artifactDirectory = artifacts,
                deleteUnused = false,
            )
            val reportText = Files.readString(report.reportPath)

            assertEquals(1, report.usedCount)
            assertEquals(2, report.diskCount)
            assertEquals(1, report.unusedCount)
            assertEquals(0, report.deletedCount)
            require("unused-cassette.json" in reportText)
            require("rm --" in reportText)
            require(Files.exists(unusedCassette))

            val deleteReport = AiRuntimeCassetteUsageReporter.writeReport(
                rootDirectory = root,
                artifactDirectory = artifacts,
                deleteUnused = true,
            )

            assertEquals(1, deleteReport.deletedCount)
            require(!Files.exists(unusedCassette))
        } finally {
            AiRuntimeCassetteUsageRegistry.reset()
            root.toFile().deleteRecursively()
            artifacts.toFile().deleteRecursively()
        }
    }

    private fun requestWithDynamicIds(
        dynamicPart: String,
        text: String,
    ): AiRuntimeRequest {
        return AiRuntimeRequest(
            systemPrompts = listOf(
                "Project path: /tmp/gromozeka-e2e-$dynamicPart/projects/case-a\n" +
                    "Current time: 2026-05-07T21:38:${dynamicPart.takeLast(2).padStart(2, '0')}.123456Z\n" +
                    "candidate validFrom=2026-05-07T21:38:${dynamicPart.takeLast(2).padStart(2, '0')}.123456Z " +
                    "validTo=null\n" +
                    "{\"valid_from\":\"2026-05-07T21:38:${dynamicPart.takeLast(2).padStart(2, '0')}.123456Z\"}"
            ),
            messages = listOf(
                Conversation.Message(
                    id = Conversation.Message.Id("019e0460-a81e-782c-b9cf-$dynamicPart".padEnd(36, '3')),
                    conversationId = Conversation.Id("019e0460-a81e-782c-b9cf-$dynamicPart".padEnd(36, '4')),
                    role = Conversation.Message.Role.ASSISTANT,
                    content = listOf(
                        Conversation.Message.ContentItem.ToolCall(
                            id = Conversation.Message.ContentItem.ToolCall.Id("call-$dynamicPart"),
                            call = Conversation.Message.ContentItem.ToolCall.Data(
                                name = "remember_previous_message",
                                input = JsonObject(
                                    mapOf(
                                        "target_message_id" to JsonPrimitive(
                                            "019e0460-a81e-782c-b9cf-$dynamicPart".padEnd(36, '5')
                                        ),
                                        "targetMessageId" to JsonPrimitive(
                                            "019e0460-a81e-782c-b9cf-$dynamicPart".padEnd(36, '6')
                                        ),
                                    )
                                ),
                            ),
                        )
                    ),
                    createdAt = Instant.parse("2026-05-07T21:38:${dynamicPart.takeLast(2).padStart(2, '0')}Z"),
                ),
                Conversation.Message(
                    id = Conversation.Message.Id("019e0460-a81e-782c-b9cf-$dynamicPart".padEnd(36, '7')),
                    conversationId = Conversation.Id("019e0460-a81e-782c-b9cf-$dynamicPart".padEnd(36, '8')),
                    role = Conversation.Message.Role.SYSTEM,
                    content = listOf(
                        Conversation.Message.ContentItem.ToolResult(
                            toolUseId = Conversation.Message.ContentItem.ToolCall.Id("call-$dynamicPart"),
                            toolName = "remember_previous_message",
                            result = listOf(
                                Conversation.Message.ContentItem.ToolResult.Data.Text(
                                    "memory write completed by hot-path:run:019e0460-a81e-782c-b9cf-$dynamicPart".padEnd(72, '9')
                                )
                            ),
                        )
                    ),
                    createdAt = Instant.parse("2026-05-07T21:38:${dynamicPart.takeLast(2).padStart(2, '0')}Z"),
                ),
                Conversation.Message(
                    id = Conversation.Message.Id("019e0460-a81e-782c-b9cf-$dynamicPart".padEnd(36, '0')),
                    conversationId = Conversation.Id("019e0460-a81e-782c-b9cf-$dynamicPart".padEnd(36, '1')),
                    role = Conversation.Message.Role.USER,
                    content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
                    createdAt = Instant.parse("2026-05-07T21:38:${dynamicPart.takeLast(2).padStart(2, '0')}Z"),
                )
            ),
            options = AiRuntimeOptions(
                toolContext = mapOf(
                    "conversationId" to "memory:$dynamicPart",
                    "promptCacheKey" to "019e0460-a81e-782c-b9cf-$dynamicPart".padEnd(36, '2'),
                )
            ),
        )
    }

    private class CountingBackend : AiRuntimeBackend {
        var callCount: Int = 0

        override fun supports(provider: AIProvider): Boolean = provider == AIProvider.OPEN_AI

        override fun createRuntime(
            provider: AIProvider,
            modelName: String,
            projectPath: String?,
        ): AiRuntime = object : AiRuntime {
            override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                callCount += 1
                return response("answer-$callCount")
            }

            override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> {
                callCount += 1
                return listOf(response("stream-answer-$callCount")).asFlow()
            }
        }
    }

    private class DynamicMetadataBackend : AiRuntimeBackend {
        var callCount: Int = 0

        override fun supports(provider: AIProvider): Boolean = provider == AIProvider.OPEN_AI

        override fun createRuntime(
            provider: AIProvider,
            modelName: String,
            projectPath: String?,
        ): AiRuntime = object : AiRuntime {
            override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                callCount += 1
                val runtimeDate = Clock.System.now().toString().take(10)
                val runtimeSecond = if (projectPath.orEmpty().contains("111")) "11" else "22"
                return responseWithDynamicMetadata(
                    text = """{"answer":"stable-answer","valid_from":"${runtimeDate}T00:00:${runtimeSecond}Z","explicit_date":"2026-01-10T00:00:00Z"}""",
                    dynamicPart = "${projectPath.orEmpty()}-$callCount",
                )
            }

            override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> {
                callCount += 1
                return listOf(
                    responseWithDynamicMetadata(
                        text = "stable-stream-answer",
                        dynamicPart = "${projectPath.orEmpty()}-$callCount",
                    )
                ).asFlow()
            }
        }
    }

    private companion object {
        fun response(text: String): AiRuntimeResponse {
            return AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                structured = Conversation.Message.StructuredText(fullText = text)
                            )
                        )
                    )
                ),
                usage = AiUsage(promptTokens = 1, completionTokens = 1),
                finishReason = "stop",
                providerMetadata = mapOf(
                    "conversationKey" to text,
                    "responseId" to text,
                ),
            )
        }

        fun responseWithDynamicMetadata(
            text: String,
            dynamicPart: String,
        ): AiRuntimeResponse {
            return AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                structured = Conversation.Message.StructuredText(fullText = text)
                            )
                        ),
                        metadata = mapOf(
                            "messageId" to "msg_$dynamicPart",
                            "phase" to "final",
                        ),
                    )
                ),
                usage = AiUsage(promptTokens = 1, completionTokens = 1),
                finishReason = "stop",
                providerMetadata = mapOf(
                    "conversationKey" to "memory:$dynamicPart",
                    "responseId" to "resp_$dynamicPart",
                ),
            )
        }

        fun AiRuntimeResponse.text(): String {
            return messages
                .flatMap { it.content }
                .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
                .joinToString("\n") { it.structured.fullText }
        }

        fun withSystemProperties(
            vararg values: Pair<String, String?>,
            block: () -> Unit,
        ) {
            val previous = values.associate { (key, _) -> key to System.getProperty(key) }
            try {
                values.forEach { (key, value) ->
                    if (value == null) {
                        System.clearProperty(key)
                    } else {
                        System.setProperty(key, value)
                    }
                }
                block()
            } finally {
                previous.forEach { (key, value) ->
                    if (value == null) {
                        System.clearProperty(key)
                    } else {
                        System.setProperty(key, value)
                    }
                }
            }
        }

        private fun projectRoot(): Path {
            val cwd = Path.of("").toAbsolutePath().normalize()
            if (cwd.resolve("settings.gradle.kts").toFile().exists()) return cwd
            return cwd.parent
                ?.takeIf { it.resolve("settings.gradle.kts").toFile().exists() }
                ?: cwd
        }
    }
}
