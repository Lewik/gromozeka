package com.gromozeka.server

import com.gromozeka.application.service.MemoryToolApplicationService
import com.gromozeka.application.service.AiConversationMessageMapper
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.server.testsupport.app.ServerTestHarness
import com.gromozeka.server.testsupport.app.sanitizePathSegment
import com.gromozeka.shared.uuid.uuid7
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Timeout

class LongMemEvalMemorySmokeTest {

    @Test
    @Timeout(value = 90, unit = TimeUnit.MINUTES)
    fun runLongMemEvalMemorySmoke() = runBlocking {
        if (!java.lang.Boolean.getBoolean(ENABLE_PROPERTY)) {
            println(
                "Skipping LongMemEval memory smoke. Run with -D$ENABLE_PROPERTY=true " +
                    "and -D$DATA_FILE_PROPERTY=/path/to/longmemeval_oracle.json."
            )
            return@runBlocking
        }

        val dataFile = resolveDataFile()
        assertTrue(
            dataFile.exists(),
            "LongMemEval data file does not exist: $dataFile. " +
                "Download it from Hugging Face or point -D$DATA_FILE_PROPERTY to a local json file."
        )

        val entries = loadEntries(dataFile)
            .selectByProperties()
        assertTrue(entries.isNotEmpty(), "No LongMemEval entries selected from $dataFile")

        val subscriptionPath = resolveSubscriptionConfigPath()
        val subscriptionSession = ServerTestHarness.subscriptionSessionFromConfig(subscriptionPath)
        assertNotNull(subscriptionSession, "OpenAI subscription config not found or incomplete: $subscriptionPath")

        val modelName = System.getProperty(MODEL_NAME_PROPERTY)?.trim().orEmpty().ifBlank { DEFAULT_MODEL_NAME }
        val postgresSchema = "longmemeval_${uuid7().replace("-", "_")}"
        val runId = "${Clock.System.now()}-$postgresSchema".sanitizePathSegment()
        val settings = Settings(
            userProfile = ServerTestHarness.openAiSubscriptionProfile(modelName).copy(
                memorySettings = UserProfile.MemorySettings(
                    autoRemember = true,
                    autoRecall = true,
                    defaultNamespace = LONGMEMEVAL_NAMESPACE,
                )
            ),
        )

        ServerTestHarness(
            settings = settings,
            subscriptionSession = subscriptionSession,
            systemProperties = mapOf(
                "gromozeka.postgres.schema" to postgresSchema,
                "gromozeka.ai.openai-subscription.websocket-response-timeout-ms" to
                    System.getProperty(WEBSOCKET_RESPONSE_TIMEOUT_MS_PROPERTY, "1200000"),
                "gromozeka.ai.openai-subscription.websocket-transport-timeout-ms" to
                    System.getProperty(WEBSOCKET_TRANSPORT_TIMEOUT_MS_PROPERTY, "30000"),
                "gromozeka.memory.llm.maxAttempts" to "1",
                "gromozeka.memory.llm.timeoutMs" to
                    System.getProperty(MEMORY_LLM_STAGE_TIMEOUT_MS_PROPERTY, "1200000"),
                "gromozeka.memory.routing.failFast" to "true",
                "gromozeka.memory.routing.deterministicIds" to "true",
            ),
            additionalSources = listOf(MemoryRealModelE2eNoToolsConfig::class.java),
        ).use { harness ->
            val memoryTools = harness.context.getBean(MemoryToolApplicationService::class.java)
            val judgeRuntime = harness.context
                .getBean(AiRuntimeProvider::class.java)
                .getRuntime(ServerTestHarness.openAiSubscriptionRuntimeSelection(), resolveProjectRoot())
            val artifactDirectory = prepareArtifactDirectory(runId)
            val progressPath = artifactDirectory.resolve("progress.log")
            val resultsPath = artifactDirectory.resolve("results.jsonl")
            val summaryPath = artifactDirectory.resolve("summary.md")

            progressPath.writeText("")
            appendProgress(
                progressPath,
                "suite_start model=$modelName schema=$postgresSchema data=$dataFile cases=${entries.size} namespace=$LONGMEMEVAL_NAMESPACE"
            )

            val caseResults = entries.mapIndexed { index, entry ->
                val namespace = namespaceFor(entry)
                appendProgress(
                    progressPath,
                    "case_start index=${index + 1}/${entries.size} id=${entry.questionId} type=${entry.questionType} sessions=${entry.haystackSessions.size} namespace=${namespace.value}"
                )
                val startedAt = System.currentTimeMillis()
                val result = runCase(memoryTools, judgeRuntime, entry, namespace, progressPath)
                Files.writeString(
                    resultsPath,
                    json.encodeToString(result) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
                appendProgress(
                    progressPath,
                    "case_done index=${index + 1}/${entries.size} id=${entry.questionId} durationMs=${System.currentTimeMillis() - startedAt} retrieved=${result.retrievedCount} exactAnswerVisible=${result.exactAnswerTextVisible} supported=${result.answerSupportedByMemory}"
                )
                result
            }

            summaryPath.writeText(renderSummary(dataFile, modelName, postgresSchema, caseResults))
            println("LongMemEval memory smoke artifacts saved to: $artifactDirectory")

            val failedCases = caseResults.filterNot { it.answerSupportedByMemory }
            assertTrue(
                failedCases.isEmpty(),
                "Expected answer was not supported by memory_context for ${failedCases.size}/${caseResults.size} cases. " +
                    "Artifact: $summaryPath"
            )
        }
    }

    private suspend fun runCase(
        memoryTools: MemoryToolApplicationService,
        judgeRuntime: AiRuntime,
        entry: LongMemEvalEntry,
        namespace: MemoryNamespace,
        progressPath: Path,
    ): LongMemEvalSmokeCaseResult {
        val caseStartedAt = System.currentTimeMillis()
        val rememberedSessions = mutableListOf<LongMemEvalRememberedSession>()
        val rememberStartedAt = System.currentTimeMillis()
        val rememberResults = entry.haystackSessions.mapIndexed { index, session ->
            val sessionText = renderSession(session, entry.haystackDates.getOrNull(index))
            val sessionNumber = index + 1
            val haystackSessionId = entry.haystackSessionIds.getOrNull(index) ?: "session-$sessionNumber"
            val sourceRef = "longmemeval:${entry.questionId}:session:$sessionNumber"
            val sourceId = sourceIdForProvidedText(namespace, sourceRef, sessionText)
            rememberedSessions += LongMemEvalRememberedSession(
                haystackSessionId = haystackSessionId,
                sourceId = sourceId,
                hasAnswer = session.any { it.hasAnswer },
            )
            appendProgress(
                progressPath,
                "remember_session_start id=${entry.questionId} index=$sessionNumber/${entry.haystackSessions.size} chars=${sessionText.length} sourceId=$sourceId"
            )
            val startedAt = System.currentTimeMillis()
            val result = parseToolResult(
                memoryTools.rememberProvidedText(
                    conversationIdValue = null,
                    text = sessionText,
                    title = "LongMemEval ${entry.questionId} session $sessionNumber",
                    sourceRef = sourceRef,
                    forceWrite = true,
                    mode = "force",
                    namespaceValue = namespace.value,
                )
            )
            appendProgress(
                progressPath,
                "remember_session id=${entry.questionId} index=$sessionNumber/${entry.haystackSessions.size} durationMs=${System.currentTimeMillis() - startedAt} status=${result.status} decision=${result.decision.orEmpty()} counts=${result.countsSummary}"
            )
            result
        }
        val rememberDurationMs = System.currentTimeMillis() - rememberStartedAt
        val expectedEvidenceSourceIds = expectedEvidenceSourceIds(entry, rememberedSessions)

        appendProgress(progressPath, "enrich_start id=${entry.questionId}")
        val enrichStartedAt = System.currentTimeMillis()
        val enrichResult = parseToolResult(
            memoryTools.enrichProvidedContext(
                conversationIdValue = null,
                contextText = renderQuestion(entry),
                namespaceValue = namespace.value,
            )
        )
        val enrichDurationMs = System.currentTimeMillis() - enrichStartedAt
        assertEquals("completed", enrichResult.status, "memory_enrich_context failed for ${entry.questionId}")

        val memoryContext = enrichResult.memoryContext.orEmpty()
        val expectedAnswer = entry.answerText()
        val exactAnswerTextVisible = memoryContext.contains(expectedAnswer, ignoreCase = true)
        val selectedEvidenceSourceIds = enrichResult.selectedEvidenceSourceIds
        val selectedExpectedEvidenceSourceIds = expectedEvidenceSourceIds
            .filter { expectedSourceId -> expectedSourceId in selectedEvidenceSourceIds.toSet() }
        val evidenceSourceHit = if (expectedEvidenceSourceIds.isEmpty()) {
            null
        } else {
            selectedExpectedEvidenceSourceIds.isNotEmpty()
        }
        val allEvidenceSourcesHit = if (expectedEvidenceSourceIds.isEmpty()) {
            null
        } else {
            expectedEvidenceSourceIds.all { it in selectedEvidenceSourceIds.toSet() }
        }
        appendProgress(
            progressPath,
            "support_judge_start id=${entry.questionId} exactAnswerVisible=$exactAnswerTextVisible evidenceSourceHit=$evidenceSourceHit memoryContextChars=${memoryContext.length}"
        )
        val supportJudgeStartedAt = System.currentTimeMillis()
        val supportJudgement = judgeMemorySupport(
            runtime = judgeRuntime,
            entry = entry,
            expectedAnswer = expectedAnswer,
            memoryContext = memoryContext,
        )
        val supportJudgeDurationMs = System.currentTimeMillis() - supportJudgeStartedAt
        appendProgress(
            progressPath,
            "support_judge_done id=${entry.questionId} supported=${supportJudgement.supported} hypothesis=${supportJudgement.hypothesis.oneLineForArtifact(180)} reason=${supportJudgement.reason.oneLineForArtifact(240)}"
        )
        return LongMemEvalSmokeCaseResult(
            questionId = entry.questionId,
            questionType = entry.questionType,
            namespace = namespace.value,
            question = entry.question,
            expectedAnswer = expectedAnswer,
            retrievedCount = enrichResult.retrievedCount ?: 0,
            exactAnswerTextVisible = exactAnswerTextVisible,
            answerSupportedByMemory = supportJudgement.supported,
            expectedEvidenceSourceIds = expectedEvidenceSourceIds,
            selectedEvidenceSourceIds = selectedEvidenceSourceIds,
            selectedExpectedEvidenceSourceIds = selectedExpectedEvidenceSourceIds,
            evidenceSourceHit = evidenceSourceHit,
            allEvidenceSourcesHit = allEvidenceSourcesHit,
            supportHypothesis = supportJudgement.hypothesis,
            supportReason = supportJudgement.reason,
            rememberStatuses = rememberResults.map { it.status },
            rememberDecisions = rememberResults.map { it.decision.orEmpty() },
            selectedRefs = enrichResult.selectedRefs,
            memoryContextPreview = memoryContext.take(MEMORY_CONTEXT_REPORT_CHARS),
            rememberDurationMs = rememberDurationMs,
            enrichDurationMs = enrichDurationMs,
            supportJudgeDurationMs = supportJudgeDurationMs,
            durationMs = System.currentTimeMillis() - caseStartedAt,
        )
    }

    private suspend fun judgeMemorySupport(
        runtime: AiRuntime,
        entry: LongMemEvalEntry,
        expectedAnswer: String,
        memoryContext: String,
    ): LongMemEvalSupportJudgement {
        val conversationId = Conversation.Id("longmemeval-support-judge:${entry.questionId}")
        val response = runtime.call(
            AiRuntimeRequest(
                systemPrompts = listOf(
                    """
                    You are an objective evaluator for a long-term-memory benchmark.
                    Decide whether the retrieved memory context is sufficient to answer the question in the same core direction as the expected answer.
                    Mark supported=true when the context contains equivalent source-grounded facts, preferences, events, or rationale, even if wording differs.
                    The official expected answer can contain noisy illustrative examples or broad inferences not literally present in the source. Do not require every illustrative example when the memory supports the central answer.
                    Mark supported=false when the context is missing the central answer, contradicts it, or only supports a weaker unrelated subset.
                    Return only the configured JSON object.
                    """.trimIndent()
                ),
                messages = listOf(
                    Conversation.Message(
                        id = Conversation.Message.Id("longmemeval-support-judge-user:${uuid7()}"),
                        conversationId = conversationId,
                        role = Conversation.Message.Role.USER,
                        content = listOf(
                            Conversation.Message.ContentItem.UserMessage(
                                """
                                Question:
                                ${entry.question}

                                Question date:
                                ${entry.questionDate}

                                Expected answer:
                                $expectedAnswer

                                Retrieved memory context:
                                $memoryContext
                                """.trimIndent()
                            )
                        ),
                        createdAt = Clock.System.now(),
                    )
                ),
                options = AiRuntimeOptions(
                    maxOutputTokens = 600,
                    toolChoice = AiToolChoice.None,
                    responseFormat = SUPPORT_JUDGE_RESPONSE_FORMAT,
                    toolContext = mapOf(
                        "longMemEvalSupportJudge" to true,
                        "questionId" to entry.questionId,
                        "questionType" to entry.questionType,
                    ),
                ),
            )
        )

        val rawText = AiConversationMessageMapper.extractAssistantText(response)
        val root = json.parseToJsonElement(rawText).jsonObject
        return LongMemEvalSupportJudgement(
            supported = root["supported"]?.jsonPrimitive?.booleanOrNull ?: false,
            hypothesis = root.stringValue("hypothesis").orEmpty(),
            reason = root.stringValue("reason").orEmpty(),
        )
    }

    private fun renderSession(
        session: List<LongMemEvalTurn>,
        date: String?,
    ): String = buildString {
        appendLine("LongMemEval past chat session.")
        if (!date.isNullOrBlank()) appendLine("Session date: $date")
        appendLine("Transcript:")
        session.forEach { turn ->
            appendLine("${turn.role}: ${turn.content}")
        }
    }.trim()

    private fun renderQuestion(entry: LongMemEvalEntry): String = buildString {
        appendLine("LongMemEval recall target.")
        appendLine("Current date: ${entry.questionDate}")
        appendLine("Question: ${entry.question}")
    }.trim()

    private fun LongMemEvalEntry.answerText(): String =
        runCatching { answer.jsonPrimitive.contentOrNull ?: answer.toString() }
            .getOrElse { answer.toString() }

    private fun expectedEvidenceSourceIds(
        entry: LongMemEvalEntry,
        rememberedSessions: List<LongMemEvalRememberedSession>,
    ): List<String> {
        val byHaystackSessionId = rememberedSessions.associateBy { it.haystackSessionId }
        val fromAnswerSessionIds = entry.answerSessionIds
            .mapNotNull { answerSessionId -> byHaystackSessionId[answerSessionId]?.sourceId }
        if (fromAnswerSessionIds.isNotEmpty()) return fromAnswerSessionIds.distinct()
        return rememberedSessions
            .filter { it.hasAnswer }
            .map { it.sourceId }
            .distinct()
    }

    private fun namespaceFor(entry: LongMemEvalEntry): MemoryNamespace =
        MemoryNamespace("$LONGMEMEVAL_NAMESPACE:${entry.questionId}")

    private fun sourceIdForProvidedText(
        namespace: MemoryNamespace,
        sourceRef: String,
        text: String,
    ): String {
        val contentHash = text.sha256()
        val identityHash = listOf(namespace.value, sourceRef, contentHash)
            .joinToString("\n")
            .sha256()
            .take(32)
        return "external:provided-text:$identityHash"
    }

    private fun parseToolResult(text: String): MemoryToolJsonResult {
        val root = json.parseToJsonElement(text).jsonObject
        val counts = root["counts"] as? JsonObject
        return MemoryToolJsonResult(
            status = root.stringValue("status") ?: "missing",
            decision = root.stringValue("decision"),
            retrievedCount = root["retrieved_count"]?.jsonPrimitive?.intOrNull,
            memoryContext = root.stringValue("memory_context"),
            countsSummary = counts?.entries
                ?.joinToString(",") { (key, value) -> "$key=${value.jsonPrimitive.contentOrNull ?: value}" }
                .orEmpty(),
            selectedRefs = root["selected_refs"]
                ?.let { element ->
                    element.toString()
                        .take(SELECTED_REFS_REPORT_CHARS)
                }
                .orEmpty(),
            selectedEvidenceSourceIds = selectedEvidenceSourceIds(root),
        )
    }

    private fun selectedEvidenceSourceIds(root: JsonObject): List<String> =
        root["selected_refs"]
            ?.jsonArray
            ?.flatMap { element ->
                val ref = element.jsonObject
                val directSourceId = ref.stringValue("id")
                    ?.takeIf { ref.stringValue("type") == "SOURCE" }
                    ?.let { listOf(it) }
                    .orEmpty()
                val evidenceSourceIds = ref["evidence_source_ids"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()
                directSourceId + evidenceSourceIds
            }
            ?.distinct()
            .orEmpty()

    private fun loadEntries(dataFile: Path): List<LongMemEvalEntry> =
        json.decodeFromString(dataFile.readText())

    private fun List<LongMemEvalEntry>.selectByProperties(): List<LongMemEvalEntry> =
        filterByCaseProperty()
            .filterByTypeProperty()
            .sampleByProperty()
            .limitByProperty()

    private fun List<LongMemEvalEntry>.filterByCaseProperty(): List<LongMemEvalEntry> {
        val filter = System.getProperty(CASE_FILTER_PROPERTY)?.trim().orEmpty()
        if (filter.isBlank()) return this
        val tokens = filter.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return filter { entry ->
            tokens.any { token ->
                entry.questionId == token ||
                    entry.questionId.contains(token, ignoreCase = true) ||
                    entry.questionType.equals(token, ignoreCase = true)
            }
        }
    }

    private fun List<LongMemEvalEntry>.filterByTypeProperty(): List<LongMemEvalEntry> {
        val filter = System.getProperty(TYPE_FILTER_PROPERTY)?.trim().orEmpty()
        if (filter.isBlank()) return this
        val types = filter.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        return filter { entry -> types.any { type -> entry.questionType.equals(type, ignoreCase = true) } }
    }

    private fun List<LongMemEvalEntry>.sampleByProperty(): List<LongMemEvalEntry> {
        val sample = System.getProperty(SAMPLE_PROPERTY)?.trim().orEmpty()
        if (sample.isBlank()) return this
        val balancedPrefix = "balanced"
        require(sample == balancedPrefix || sample.startsWith("$balancedPrefix:")) {
            "Unsupported LongMemEval sample mode '$sample'. Supported: balanced or balanced:<per-type-count>."
        }
        val perType = sample.substringAfter(":", "1").toIntOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Invalid LongMemEval balanced sample count: $sample")
        return groupBy { it.questionType }
            .values
            .flatMap { entries -> entries.take(perType) }
            .sortedBy { indexOf(it) }
    }

    private fun List<LongMemEvalEntry>.limitByProperty(): List<LongMemEvalEntry> {
        val limit = System.getProperty(LIMIT_PROPERTY)?.trim().orEmpty()
        if (limit.isBlank() || limit.equals("all", ignoreCase = true)) return this
        val count = limit.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Invalid LongMemEval limit '$limit'. Use a positive integer or 'all'.")
        return take(count)
    }

    private fun prepareArtifactDirectory(runId: String): Path {
        val root = Path.of("build", "test-artifacts", "longmemeval")
            .resolve(this::class.simpleName.orEmpty().sanitizePathSegment())
        Files.createDirectories(root)
        val artifactDirectory = root.resolve(runId)
        Files.createDirectories(artifactDirectory)
        root.resolve("latest-run.txt").writeText(artifactDirectory.toAbsolutePath().normalize().toString())
        return artifactDirectory
    }

    private fun renderSummary(
        dataFile: Path,
        modelName: String,
        postgresSchema: String,
        results: List<LongMemEvalSmokeCaseResult>,
    ): String = buildString {
        appendLine("# LongMemEval Memory Smoke")
        appendLine()
        appendLine("- data: `$dataFile`")
        appendLine("- model: `$modelName`")
        appendLine("- postgres schema: `$postgresSchema`")
        appendLine("- namespace prefix: `$LONGMEMEVAL_NAMESPACE`")
        appendLine("- cases: ${results.size}")
        appendLine("- answer supported by memory: ${results.count { it.answerSupportedByMemory }}/${results.size}")
        appendLine("- exact answer text visible: ${results.count { it.exactAnswerTextVisible }}/${results.size}")
        val evidenceMeasuredResults = results.filter { it.evidenceSourceHit != null }
        appendLine("- evidence source hit: ${evidenceMeasuredResults.count { it.evidenceSourceHit == true }}/${evidenceMeasuredResults.size}")
        appendLine("- total duration: ${results.sumOf { it.durationMs }.durationSummary()}")
        appendLine("- remember duration: ${results.sumOf { it.rememberDurationMs }.durationSummary()}")
        appendLine("- enrich duration: ${results.sumOf { it.enrichDurationMs }.durationSummary()}")
        appendLine("- support judge duration: ${results.sumOf { it.supportJudgeDurationMs }.durationSummary()}")
        appendLine()
        results.forEach { result ->
            appendLine("## ${result.questionId} (${result.questionType})")
            appendLine()
            appendLine("- namespace: `${result.namespace}`")
            appendLine("- question: ${result.question}")
            appendLine("- expected answer: ${result.expectedAnswer}")
            appendLine("- retrieved count: ${result.retrievedCount}")
            appendLine("- exact answer text visible: ${result.exactAnswerTextVisible}")
            appendLine("- answer supported by memory: ${result.answerSupportedByMemory}")
            appendLine("- evidence source hit: ${result.evidenceSourceHit}")
            appendLine("- all evidence sources hit: ${result.allEvidenceSourcesHit}")
            appendLine("- expected evidence source ids: ${result.expectedEvidenceSourceIds.joinToString()}")
            appendLine("- selected expected evidence source ids: ${result.selectedExpectedEvidenceSourceIds.joinToString()}")
            appendLine("- support hypothesis: ${result.supportHypothesis}")
            appendLine("- support reason: ${result.supportReason}")
            appendLine("- remember statuses: ${result.rememberStatuses.joinToString()}")
            appendLine("- remember decisions: ${result.rememberDecisions.joinToString()}")
            appendLine("- durations: total=${result.durationMs.durationSummary()}, remember=${result.rememberDurationMs.durationSummary()}, enrich=${result.enrichDurationMs.durationSummary()}, judge=${result.supportJudgeDurationMs.durationSummary()}")
            appendLine()
            appendLine("Selected refs:")
            appendLine("```json")
            appendLine(result.selectedRefs)
            appendLine("```")
            appendLine()
            appendLine("Memory context preview:")
            appendLine("```text")
            appendLine(result.memoryContextPreview)
            appendLine("```")
            appendLine()
        }
    }

    private fun resolveDataFile(): Path {
        val override = System.getProperty(DATA_FILE_PROPERTY)?.trim().orEmpty()
        if (override.isNotBlank()) return Path.of(override).toAbsolutePath().normalize()
        return Path.of(resolveProjectRoot())
            .resolve(".sources")
            .resolve("longmemeval")
            .resolve("data")
            .resolve("longmemeval_oracle.json")
            .toAbsolutePath()
            .normalize()
    }

    private fun resolveSubscriptionConfigPath(): Path {
        val override = System.getProperty(SUBSCRIPTION_CONFIG_PROPERTY)?.trim().orEmpty()
        if (override.isNotBlank()) return Path.of(override).toAbsolutePath().normalize()
        return Path.of(resolveProjectRoot())
            .resolve("dev-data")
            .resolve("client")
            .resolve(".gromozeka")
            .resolve("openai-subscription.json")
            .toAbsolutePath()
            .normalize()
    }

    private fun resolveProjectRoot(): String {
        val cwd = Path.of("").toAbsolutePath().normalize()
        if (cwd.resolve("settings.gradle.kts").exists()) return cwd.absolutePathString()
        return cwd.parent
            ?.takeIf { it.resolve("settings.gradle.kts").exists() }
            ?.absolutePathString()
            ?: cwd.absolutePathString()
    }

    private fun appendProgress(path: Path, message: String) {
        Files.writeString(
            path,
            "${Clock.System.now()} $message\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun JsonObject.stringValue(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ENABLE_PROPERTY = "gromozeka.longmemeval"
        const val DATA_FILE_PROPERTY = "gromozeka.longmemeval.data"
        const val LIMIT_PROPERTY = "gromozeka.longmemeval.limit"
        const val CASE_FILTER_PROPERTY = "gromozeka.longmemeval.caseFilter"
        const val TYPE_FILTER_PROPERTY = "gromozeka.longmemeval.type"
        const val SAMPLE_PROPERTY = "gromozeka.longmemeval.sample"
        const val MODEL_NAME_PROPERTY = "gromozeka.longmemeval.modelName"
        const val SUBSCRIPTION_CONFIG_PROPERTY = "gromozeka.longmemeval.subscriptionConfig"
        const val WEBSOCKET_RESPONSE_TIMEOUT_MS_PROPERTY = "gromozeka.longmemeval.websocketResponseTimeoutMs"
        const val WEBSOCKET_TRANSPORT_TIMEOUT_MS_PROPERTY = "gromozeka.longmemeval.websocketTransportTimeoutMs"
        const val MEMORY_LLM_STAGE_TIMEOUT_MS_PROPERTY = "gromozeka.longmemeval.memoryLlmStageTimeoutMs"
        const val DEFAULT_MODEL_NAME = "gpt-5.5"
        const val LONGMEMEVAL_NAMESPACE = "benchmark:longmemeval"
        const val MEMORY_CONTEXT_REPORT_CHARS = 20_000
        const val SELECTED_REFS_REPORT_CHARS = 8_000

        val SUPPORT_JUDGE_RESPONSE_FORMAT = AiResponseFormat.JsonSchema(
            name = "longmemeval_memory_support_judge",
            schema = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                putJsonObject("properties") {
                    putJsonObject("supported") {
                        put("type", "boolean")
                        put("description", "Whether retrieved memory is sufficient to support the expected answer.")
                    }
                    putJsonObject("hypothesis") {
                        put("type", "string")
                        put("description", "Short answer that can be produced from the retrieved memory context.")
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "One short sentence explaining the judgement.")
                    }
                }
                putJsonArray("required") {
                    add("supported")
                    add("hypothesis")
                    add("reason")
                }
            },
        )

        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

@Serializable
private data class LongMemEvalEntry(
    @SerialName("question_id")
    val questionId: String,
    @SerialName("question_type")
    val questionType: String,
    val question: String,
    val answer: JsonElement,
    @SerialName("question_date")
    val questionDate: String,
    @SerialName("haystack_dates")
    val haystackDates: List<String>,
    @SerialName("haystack_session_ids")
    val haystackSessionIds: List<String> = emptyList(),
    @SerialName("answer_session_ids")
    val answerSessionIds: List<String> = emptyList(),
    @SerialName("haystack_sessions")
    val haystackSessions: List<List<LongMemEvalTurn>>,
)

@Serializable
private data class LongMemEvalTurn(
    val role: String,
    val content: String,
    @SerialName("has_answer")
    val hasAnswer: Boolean = false,
)

private data class LongMemEvalRememberedSession(
    val haystackSessionId: String,
    val sourceId: String,
    val hasAnswer: Boolean,
)

private data class MemoryToolJsonResult(
    val status: String,
    val decision: String?,
    val retrievedCount: Int?,
    val memoryContext: String?,
    val countsSummary: String,
    val selectedRefs: String,
    val selectedEvidenceSourceIds: List<String>,
)

private data class LongMemEvalSupportJudgement(
    val supported: Boolean,
    val hypothesis: String,
    val reason: String,
)

@Serializable
private data class LongMemEvalSmokeCaseResult(
    val questionId: String,
    val questionType: String,
    val namespace: String,
    val question: String,
    val expectedAnswer: String,
    val retrievedCount: Int,
    val exactAnswerTextVisible: Boolean,
    val answerSupportedByMemory: Boolean,
    val expectedEvidenceSourceIds: List<String>,
    val selectedEvidenceSourceIds: List<String>,
    val selectedExpectedEvidenceSourceIds: List<String>,
    val evidenceSourceHit: Boolean?,
    val allEvidenceSourcesHit: Boolean?,
    val supportHypothesis: String,
    val supportReason: String,
    val rememberStatuses: List<String>,
    val rememberDecisions: List<String>,
    val selectedRefs: String,
    val memoryContextPreview: String,
    val rememberDurationMs: Long,
    val enrichDurationMs: Long,
    val supportJudgeDurationMs: Long,
    val durationMs: Long,
)

private fun String.oneLineForArtifact(limit: Int): String =
    replace(Regex("\\s+"), " ")
        .trim()
        .let { if (it.length <= limit) it else it.take(limit - 3) + "..." }

private fun Long.durationSummary(): String =
    if (this < 1_000) {
        "${this}ms"
    } else {
        "${this / 1_000}.${(this % 1_000).toString().padStart(3, '0')}s"
    }
