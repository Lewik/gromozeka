package com.gromozeka.server

import com.gromozeka.application.service.MemoryToolApplicationService
import com.gromozeka.application.service.AiConversationMessageMapper
import com.gromozeka.application.service.memory.MemoryReadTraceEvent
import com.gromozeka.application.service.memory.MemoryWriteTraceEvent
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.server.testsupport.app.ServerTestHarness
import com.gromozeka.server.testsupport.app.sanitizePathSegment
import com.gromozeka.server.testsupport.llm.AiRuntimeCassetteSettings
import com.gromozeka.server.testsupport.llm.AiRuntimeCassetteUsageReporter
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

        val cassetteSettings = AiRuntimeCassetteSettings.fromSystemProperties()
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
                "gromozeka.memory.write.parallelism" to
                    System.getProperty(MEMORY_WRITE_PARALLELISM_PROPERTY, "2"),
                "gromozeka.memory.routing.failFast" to "true",
                "gromozeka.memory.routing.deterministicIds" to "true",
            ),
            additionalSources = listOf(MemoryRealModelE2eNoToolsConfig::class.java),
        ).use { harness ->
            val memoryTools = harness.context.getBean(MemoryToolApplicationService::class.java)
            val readTraceCollector = harness.context.getBean(MemoryE2eReadTraceCollector::class.java)
            val writeTraceCollector = harness.context.getBean(MemoryE2eWriteTraceCollector::class.java)
            val judgeRuntime = harness.context
                .getBean(AiRuntimeProvider::class.java)
                .getRuntime(ServerTestHarness.openAiSubscriptionRuntimeSelection(), resolveProjectRoot())
            val artifactDirectory = prepareArtifactDirectory(runId)
            val caseArtifactDirectory = artifactDirectory.resolve("cases")
            val progressPath = artifactDirectory.resolve("progress.log")
            val resultsPath = artifactDirectory.resolve("results.jsonl")
            val summaryPath = artifactDirectory.resolve("summary.md")

            Files.createDirectories(caseArtifactDirectory)
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
                val result = runCase(
                    memoryTools = memoryTools,
                    judgeRuntime = judgeRuntime,
                    readTraceCollector = readTraceCollector,
                    writeTraceCollector = writeTraceCollector,
                    entry = entry,
                    namespace = namespace,
                    progressPath = progressPath,
                    caseArtifactDirectory = caseArtifactDirectory,
                )
                Files.writeString(
                    resultsPath,
                    jsonLines.encodeToString(result) + "\n",
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
            AiRuntimeCassetteUsageReporter.writeReportIfEnabled(
                settings = cassetteSettings,
                artifactDirectory = artifactDirectory,
            )?.let { report ->
                println("LLM cassette usage report saved to: ${report.reportPath}")
                appendProgress(
                    progressPath,
                    "cassette_usage_report used=${report.usedCount} disk=${report.diskCount} unused=${report.unusedCount} " +
                        "missingUsed=${report.missingUsedCount} deleted=${report.deletedCount} path=${report.reportPath}"
                )
            }
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
        readTraceCollector: MemoryE2eReadTraceCollector,
        writeTraceCollector: MemoryE2eWriteTraceCollector,
        entry: LongMemEvalEntry,
        namespace: MemoryNamespace,
        progressPath: Path,
        caseArtifactDirectory: Path,
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
            val writeTrace = writeTraceCollector.takeBySourceId(sourceId)
            rememberedSessions += LongMemEvalRememberedSession(
                haystackSessionId = haystackSessionId,
                sourceId = sourceId,
                hasAnswer = session.any { it.hasAnswer },
                sourceRef = sourceRef,
                chars = sessionText.length,
                result = result,
                writeTrace = writeTrace,
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
        val readTrace = readTraceCollector.takeLatest(namespace)

        val memoryContext = enrichResult.memoryContext.orEmpty()
        val expectedAnswer = entry.answerText()
        val exactAnswerTextVisible = memoryContext.contains(expectedAnswer, ignoreCase = true)
        val selectedEvidenceSourceIds = (
            enrichResult.selectedEvidenceSourceIds +
                selectedProfileEvidenceSourceIds(enrichResult.selectedRefItems, rememberedSessions)
            )
            .distinct()
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
        val caseDossierPath = caseArtifactDirectory.resolve("${entry.questionId.sanitizePathSegment()}.md")
        caseDossierPath.writeText(
            renderCaseDossier(
                entry = entry,
                namespace = namespace,
                rememberedSessions = rememberedSessions,
                expectedEvidenceSourceIds = expectedEvidenceSourceIds,
                enrichResult = enrichResult,
                readTrace = readTrace,
                expectedAnswer = expectedAnswer,
                exactAnswerTextVisible = exactAnswerTextVisible,
                evidenceSourceHit = evidenceSourceHit,
                allEvidenceSourcesHit = allEvidenceSourcesHit,
                supportJudgement = supportJudgement,
                memoryContext = memoryContext,
            )
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
            caseDossierPath = caseDossierPath.toAbsolutePath().normalize().toString(),
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
                    For imported chat transcripts, source-grounded support may come from adjacent turns and the active topic, not only from one sentence that literally repeats the answer.
                    Accept a strong conversational entailment when the expected answer follows from the surrounding user/assistant turns and no other retrieved source points to a competing answer.
                    The official expected answer can contain noisy illustrative examples or broad inferences not literally present in the source. Do not require every illustrative example when the memory supports the central answer.
                    Mark supported=false when the context is missing the central answer, contradicts it, leaves several plausible competing answers, or only supports a weaker unrelated subset.
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
            selectedRefItems = selectedRefItems(root),
            selectedEvidenceSourceIds = selectedEvidenceSourceIds(root),
        )
    }

    private fun selectedRefItems(root: JsonObject): List<MemoryToolSelectedRef> =
        root["selected_refs"]
            ?.jsonArray
            ?.map { element ->
                val ref = element.jsonObject
                MemoryToolSelectedRef(
                    type = ref.stringValue("type").orEmpty(),
                    id = ref.stringValue("id").orEmpty(),
                    evidenceSourceIds = ref["evidence_source_ids"]
                        ?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        .orEmpty(),
                )
            }
            .orEmpty()

    private fun selectedEvidenceSourceIds(root: JsonObject): List<String> =
        selectedRefItems(root)
            .flatMap { ref ->
                val directSourceId = ref.id
                    .takeIf { ref.type == "SOURCE" }
                    ?.let { listOf(it) }
                    .orEmpty()
                directSourceId + ref.evidenceSourceIds
            }
            .distinct()

    private fun selectedProfileEvidenceSourceIds(
        selectedRefItems: List<MemoryToolSelectedRef>,
        rememberedSessions: List<LongMemEvalRememberedSession>,
    ): List<String> {
        val selectedProfileIds = selectedRefItems
            .filter { it.type == "PROFILE" }
            .map { it.id }
            .toSet()
        if (selectedProfileIds.isEmpty()) return emptyList()

        val memoryBatches = rememberedSessions.mapNotNull { it.writeTrace?.result?.memoryBatch }
        val claimsById = memoryBatches.flatMap { it.claims }.associateBy { it.id.value }
        val notesById = memoryBatches.flatMap { it.notes }.associateBy { it.id.value }
        val actionItemsById = memoryBatches.flatMap { it.actionItems }.associateBy { it.id.value }

        return memoryBatches
            .flatMap { it.profiles }
            .filter { it.id.value in selectedProfileIds }
            .flatMap { profile ->
                profile.profileJson.profileReferencedIds("facts").flatMap { claimId ->
                    claimsById[claimId]?.evidenceRefs?.map { it.sourceId.value }.orEmpty()
                } +
                    profile.profileJson.profileReferencedIds("notes").flatMap { noteId ->
                        notesById[noteId]?.evidenceRefs?.map { it.sourceId.value }.orEmpty()
                    } +
                    profile.profileJson.profileReferencedIds("actionItems").flatMap { actionItemId ->
                        actionItemsById[actionItemId]?.evidenceRefs?.map { it.sourceId.value }.orEmpty()
                    }
            }
            .distinct()
    }

    private fun JsonObject.profileReferencedIds(section: String): List<String> =
        this[section]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject.stringValue("id") }
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
        appendLine("status | ${if (results.all { it.answerSupportedByMemory }) "PASS" else "FAIL"}")
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
            appendLine("- case dossier: `${result.caseDossierPath}`")
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

    private fun renderCaseDossier(
        entry: LongMemEvalEntry,
        namespace: MemoryNamespace,
        rememberedSessions: List<LongMemEvalRememberedSession>,
        expectedEvidenceSourceIds: List<String>,
        enrichResult: MemoryToolJsonResult,
        readTrace: MemoryReadTraceEvent?,
        expectedAnswer: String,
        exactAnswerTextVisible: Boolean,
        evidenceSourceHit: Boolean?,
        allEvidenceSourcesHit: Boolean?,
        supportJudgement: LongMemEvalSupportJudgement,
        memoryContext: String,
    ): String = buildString {
        appendLine("# LongMemEval Case Dossier")
        appendLine()
        appendLine("questionId | ${entry.questionId}")
        appendLine("questionType | ${entry.questionType}")
        appendLine("namespace | ${namespace.value}")
        appendLine("questionDate | ${entry.questionDate}")
        appendLine("question | ${entry.question}")
        appendLine("expectedAnswer | $expectedAnswer")
        appendLine("supportedByMemory | ${supportJudgement.supported}")
        appendLine("exactAnswerTextVisible | $exactAnswerTextVisible")
        appendLine("evidenceSourceHit | $evidenceSourceHit")
        appendLine("allEvidenceSourcesHit | $allEvidenceSourcesHit")
        appendLine("expectedEvidenceSourceIds | ${expectedEvidenceSourceIds.joinToString().ifBlank { "none" }}")
        appendLine("selectedEvidenceSourceIds | ${enrichResult.selectedEvidenceSourceIds.joinToString().ifBlank { "none" }}")
        appendLine()
        appendLine("## Remember / Write Pipeline")
        rememberedSessions.forEachIndexed { index, remembered ->
            appendLine("### Session ${index + 1}: ${remembered.haystackSessionId}")
            appendLine()
            appendLine("sourceRef | ${remembered.sourceRef}")
            appendLine("sourceId | ${remembered.sourceId}")
            appendLine("chars | ${remembered.chars}")
            appendLine("hasAnswer | ${remembered.hasAnswer}")
            appendLine("toolStatus | ${remembered.result.status}")
            appendLine("toolDecision | ${remembered.result.decision.orEmpty().ifBlank { "none" }}")
            appendLine("toolCounts | ${remembered.result.countsSummary.ifBlank { "none" }}")
            appendLine()
            appendLine(renderWriteTraceForDossier(remembered.writeTrace))
            appendLine()
        }
        appendLine("## Enrich / Read Pipeline")
        appendLine()
        appendLine("toolStatus | ${enrichResult.status}")
        appendLine("retrievedCount | ${enrichResult.retrievedCount ?: 0}")
        appendLine("selectedRefs |")
        appendLine("```json")
        appendLine(enrichResult.selectedRefs.ifBlank { "[]" })
        appendLine("```")
        appendLine()
        appendLine(renderReadTraceForDossier(readTrace))
        appendLine()
        appendLine("## Memory Context")
        appendLine()
        appendLine("```text")
        appendLine(memoryContext.take(MEMORY_CONTEXT_REPORT_CHARS))
        appendLine("```")
        appendLine()
        appendLine("## Support Judge")
        appendLine()
        appendLine("supported | ${supportJudgement.supported}")
        appendLine("hypothesis | ${supportJudgement.hypothesis}")
        appendLine("reason | ${supportJudgement.reason}")
    }

    private fun renderWriteTraceForDossier(event: MemoryWriteTraceEvent?): String {
        if (event == null) return "Write trace | missing"

        val result = event.result
        return buildString {
            appendLine("Write trace | captured")
            appendLine("latencyMs | ${event.latencyMs ?: "unknown"}")
            appendLine("llmCalls | ${event.llmCalls.renderLlmCallsForDossier()}")
            appendLine("routeDecision | ${result.routeDecision.decision.name}")
            appendLine("memoryTypes | ${result.routeDecision.memoryTypes.joinToString { it.name }.ifBlank { "none" }}")
            appendLine("salience | ${result.routeDecision.salience}")
            appendLine("reason | ${result.routeDecision.reason}")
            appendLine("sourcePolicy | structured=${result.routeDecision.sourcePolicy.allowStructuredExtraction} recall=${result.routeDecision.sourcePolicy.allowRecall} evidence=${result.routeDecision.sourcePolicy.allowEvidenceHydration}")
            appendLine("retrievalPlan | ${result.retrievalPlan?.let { plan -> "need=${plan.needRetrieval} types=${plan.memoryTypes.joinToString { it.name }} entityQueries=${plan.entityQueries.joinToString("|").ifBlank { "none" }} textQueries=${plan.textQueries.joinToString("|").ifBlank { "none" }} predicates=${plan.predicateHints.joinToString("|").ifBlank { "none" }} budget=${plan.retrievalBudget}" } ?: "none"}")
            appendLine("retrievedHits | ${result.retrievedHits.size} ${result.retrievedHits.breakdownForDossier()}")
            appendLine("entityOps | ${result.entityOps.size}")
            appendLine("noteCandidates | ${result.noteCandidates.size}")
            appendLine("noteOps | raw=${result.rawNoteOps.size} final=${result.noteOps.size}")
            appendLine("claimCandidates | ${result.claimCandidates.size}")
            appendLine("claimOps | raw=${result.rawClaimOps.size} final=${result.claimOps.size}")
            appendLine("actionItemOps | raw=${result.rawActionItemOps.size} final=${result.actionItemOps.size}")
            appendLine("materialized | sources=${result.memoryBatch.sources.size} runs=${result.memoryBatch.runs.size} entities=${result.memoryBatch.entities.size} claims=${result.memoryBatch.claims.size} notes=${result.memoryBatch.notes.size} actionItems=${result.memoryBatch.actionItems.size} profiles=${result.memoryBatch.profiles.size} episodes=${result.memoryBatch.episodes.size}")
            appendLine()
            appendLine("Sources:")
            appendLine(result.memoryBatch.sources.take(8).joinToString("\n") { "- ${it.id.value}: ${it.contentText.oneLineForArtifact(260)}" }.ifBlank { "- none" })
            appendLine()
            appendLine("Retrieved hits:")
            appendLine(result.retrievedHits.take(24).joinToString("\n") { it.renderForDossier() }.ifBlank { "- none" })
            appendLine()
            appendLine("Entity ops:")
            appendLine(result.entityOps.take(24).joinToString("\n") { "- $it" }.ifBlank { "- none" })
            appendLine()
            appendLine("Claim candidates:")
            appendLine(result.claimCandidates.take(40).joinToString("\n") { "- ${it.predicate}: ${it.normalizedText}; reason=${it.reason.oneLineForArtifact(240)}" }.ifBlank { "- none" })
            appendLine()
            appendLine("Final claim ops:")
            appendLine(result.claimOps.take(40).joinToString("\n") { "- $it" }.ifBlank { "- none" })
            appendLine()
            appendLine("Materialized entities:")
            appendLine(result.memoryBatch.entities.take(40).joinToString("\n") { "- ${it.id.value}: ${it.entityType.name}:${it.canonicalName}" }.ifBlank { "- none" })
            appendLine()
            appendLine("Materialized claims:")
            appendLine(result.memoryBatch.claims.take(60).joinToString("\n") { claim ->
                "- ${claim.id.value}: ${claim.status.name}:${claim.predicate}: ${claim.normalizedText}; evidence=${claim.evidenceRefs.map { it.sourceId.value }.distinct().joinToString("|").ifBlank { "none" }}"
            }.ifBlank { "- none" })
            appendLine()
            appendLine("Materialized notes:")
            appendLine(result.memoryBatch.notes.take(30).joinToString("\n") { "- ${it.id.value}: ${it.noteType.name}:${it.title}; ${it.summary}" }.ifBlank { "- none" })
            appendLine()
            appendLine("Materialized runs:")
            appendLine(result.memoryBatch.runs.take(20).joinToString("\n") { "- ${it.id.value}: ${it.runType.name}:${it.status.name}; ${it.summary}" }.ifBlank { "- none" })
        }
    }

    private fun renderReadTraceForDossier(event: MemoryReadTraceEvent?): String {
        if (event == null) return "Read trace | missing"

        val result = event.result
        val trace = result.trace
        return buildString {
            appendLine("Read trace | captured")
            appendLine("latencyMs | ${event.latencyMs}")
            appendLine("llmCalls | ${event.llmCalls.renderLlmCallsForDossier()}")
            appendLine("needMemory | ${result.plan.needMemory}")
            appendLine("answerMode | ${result.plan.answerMode.name}")
            appendLine("coverageMode | ${result.plan.coverageMode.name}")
            appendLine("retrievedHits | ${result.retrievedHits.size} ${result.retrievedHits.breakdownForDossier()}")
            appendLine("selectedHits | ${trace.selectedHits.size}")
            appendLine("runtimePromptChars | ${result.runtimePrompt?.length ?: 0}")
            appendLine()
            appendLine("Search steps:")
            appendLine(
                trace.searchSteps.joinToString("\n") { step ->
                    "- ${step.stage} scope=${step.scope} requested=${step.requestedLimit} raw=${step.rawCount} candidates=${step.candidateCount} selected=${step.selectedCount} query=${step.query.oneLineForArtifact(240)}"
                }.ifBlank { "- none" }
            )
            appendLine()
            appendLine("Selector stages:")
            appendLine(
                trace.selectorTrace.stages.joinToString("\n") { stage ->
                    "- ${stage.mode.name}: input=${stage.inputRefs.size} selected=${stage.llmSelectedRefs.size} carried=${stage.llmCarriedRefs.size} safety=${stage.safetyAddedRefs.size} output=${stage.outputRefs.size}"
                }.ifBlank { "- none" }
            )
            appendLine()
            appendLine("Selector decisions:")
            appendLine(
                trace.selectorDecisions.take(60).joinToString("\n") { decision ->
                    "- ${if (decision.selected) "SELECT" else "REJECT"} ${decision.ref.type.name}:${decision.ref.id} rank=${decision.rank} summary=${decision.summary.oneLineForArtifact(240)} reason=${decision.reason.oneLineForArtifact(240)}"
                }.ifBlank { "- none" }
            )
            appendLine()
            appendLine("Selected hits:")
            appendLine(trace.selectedHits.take(40).joinToString("\n") { "- ${it.ref.type.name}:${it.ref.id} score=${it.score} predicate=${it.predicate ?: "-"} status=${it.status ?: "-"} text=${it.summary.oneLineForArtifact(360)}" }.ifBlank { "- none" })
            appendLine()
            appendLine("Source safety:")
            appendLine("suppressedSources=${trace.sourceSafety.suppressedSources.size} restoredTypedHits=${trace.sourceSafety.restoredTypedHits.size}")
            appendLine()
            appendLine("Injected prompt preview:")
            appendLine(trace.injectedPrompt?.preview?.take(MEMORY_CONTEXT_REPORT_CHARS) ?: "none")
        }
    }

    private fun List<MemoryRun.LlmCallTiming>.renderLlmCallsForDossier(): String =
        if (isEmpty()) {
            "none"
        } else {
            joinToString("; ") { call ->
                "${call.stageName}:${call.status.name}:${call.latencyMs}ms"
            }
        }

    private fun List<MemoryStore.SearchHit>.breakdownForDossier(): String =
        groupingBy {
            when (it) {
                is MemoryStore.SearchHit.SourceHit -> "source"
                is MemoryStore.SearchHit.EntityHit -> "entity"
                is MemoryStore.SearchHit.ClaimHit -> "claim"
                is MemoryStore.SearchHit.NoteHit -> "note"
                is MemoryStore.SearchHit.ActionItemHit -> "actionItem"
                is MemoryStore.SearchHit.ProfileHit -> "profile"
                is MemoryStore.SearchHit.EpisodeHit -> "episode"
                is MemoryStore.SearchHit.RunHit -> "run"
            }
        }.eachCount().entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "none" }

    private fun MemoryStore.SearchHit.renderForDossier(): String =
        when (this) {
            is MemoryStore.SearchHit.SourceHit -> "- SOURCE:${source.id.value}: ${source.contentText.oneLineForArtifact(360)}"
            is MemoryStore.SearchHit.EntityHit -> "- ENTITY:${entity.id.value}: ${entity.entityType.name}:${entity.canonicalName}"
            is MemoryStore.SearchHit.ClaimHit -> "- CLAIM:${claim.id.value}: ${claim.status.name}:${claim.predicate}: ${claim.normalizedText}"
            is MemoryStore.SearchHit.NoteHit -> "- NOTE:${note.id.value}: ${note.noteType.name}:${note.title}; ${note.summary}"
            is MemoryStore.SearchHit.ActionItemHit -> "- ACTION_ITEM:${actionItem.id.value}: ${actionItem.status.name}:${actionItem.title}"
            is MemoryStore.SearchHit.ProfileHit -> "- PROFILE:${profile.id.value}: ${profile.profileText.oneLineForArtifact(360)}"
            is MemoryStore.SearchHit.EpisodeHit -> "- EPISODE:${episode.id.value}: ${episode.lesson.oneLineForArtifact(360)}"
            is MemoryStore.SearchHit.RunHit -> "- RUN:${run.id.value}: ${run.runType.name}:${run.status.name}; ${run.summary}"
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
        const val MEMORY_WRITE_PARALLELISM_PROPERTY = "gromozeka.longmemeval.memoryWriteParallelism"
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
        val jsonLines = Json {
            ignoreUnknownKeys = true
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
    val sourceRef: String,
    val chars: Int,
    val result: MemoryToolJsonResult,
    val writeTrace: MemoryWriteTraceEvent?,
)

private data class MemoryToolJsonResult(
    val status: String,
    val decision: String?,
    val retrievedCount: Int?,
    val memoryContext: String?,
    val countsSummary: String,
    val selectedRefs: String,
    val selectedRefItems: List<MemoryToolSelectedRef>,
    val selectedEvidenceSourceIds: List<String>,
)

private data class MemoryToolSelectedRef(
    val type: String,
    val id: String,
    val evidenceSourceIds: List<String>,
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
    val caseDossierPath: String,
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
