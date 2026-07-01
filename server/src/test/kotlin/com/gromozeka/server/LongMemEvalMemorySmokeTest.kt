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
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

class LongMemEvalMemorySmokeTest {

    @Test
    fun parsesBalancedSamplePages() {
        assertEquals(LongMemEvalBalancedSample(perType = 1, page = 1), LongMemEvalBalancedSample.parse("balanced"))
        assertEquals(LongMemEvalBalancedSample(perType = 4, page = 1), LongMemEvalBalancedSample.parse("balanced:4"))
        assertEquals(LongMemEvalBalancedSample(perType = 4, page = 2), LongMemEvalBalancedSample.parse("balanced:4@2"))
        assertEquals(null, LongMemEvalBalancedSample.parse("random:4"))
    }

    @Test
    fun rejectsUnknownCaseFilterTokens() {
        val error = assertFailsWith<IllegalArgumentException> {
            longMemEvalSelectionFixture().selectByProperties(
                LongMemEvalSelectionProperties(
                    caseFilterTokens = listOf("known-case", "missing-case"),
                    typeFilterTokens = emptySet(),
                    sample = "",
                    offset = "",
                    limit = "",
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("missing-case"))
    }

    @Test
    fun acceptsQuestionTypeCaseFilterTokens() {
        val selected = longMemEvalSelectionFixture().selectByProperties(
            LongMemEvalSelectionProperties(
                caseFilterTokens = listOf("known-type"),
                typeFilterTokens = emptySet(),
                sample = "",
                offset = "",
                limit = "",
            )
        )

        assertEquals(listOf("known-case"), selected.map { it.questionId })
    }

    @Test
    fun runLongMemEvalMemorySmoke() = runBlocking {
        if (!java.lang.Boolean.getBoolean(ENABLE_PROPERTY)) {
            println(
                "Skipping LongMemEval memory smoke. Run with -D$ENABLE_PROPERTY=true " +
                    "and -D$DATA_FILE_PROPERTY=/path/to/longmemeval_oracle.json."
            )
            return@runBlocking
        }

        acquireLongMemEvalRunLock().use {
            runLongMemEvalMemorySmokeLocked()
        }
    }

    private suspend fun runLongMemEvalMemorySmokeLocked() {
        val dataFile = resolveDataFile()
        assertTrue(
            dataFile.exists(),
            "LongMemEval data file does not exist: $dataFile. " +
                "Download it from Hugging Face or point -D$DATA_FILE_PROPERTY to a local json file."
        )

        val selectionProperties = LongMemEvalSelectionProperties.fromSystemProperties()
        val entries = loadEntries(dataFile)
            .selectByProperties(selectionProperties)
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
                    systemProperty(WEBSOCKET_RESPONSE_TIMEOUT_MS_PROPERTY, DIRECT_WEBSOCKET_RESPONSE_TIMEOUT_MS_PROPERTY, "1200000"),
                "gromozeka.ai.openai-subscription.websocket-transport-timeout-ms" to
                    systemProperty(WEBSOCKET_TRANSPORT_TIMEOUT_MS_PROPERTY, DIRECT_WEBSOCKET_TRANSPORT_TIMEOUT_MS_PROPERTY, "30000"),
                "gromozeka.memory.llm.maxAttempts" to
                    systemProperty(MEMORY_LLM_MAX_ATTEMPTS_PROPERTY, DIRECT_MEMORY_LLM_MAX_ATTEMPTS_PROPERTY, "3"),
                "gromozeka.memory.llm.timeoutMs" to
                    systemProperty(MEMORY_LLM_STAGE_TIMEOUT_MS_PROPERTY, DIRECT_MEMORY_LLM_STAGE_TIMEOUT_MS_PROPERTY, "1200000"),
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
            val llmCallProgressCollector = harness.context.getBean(MemoryE2eLlmCallProgressCollector::class.java)
            val judgeRuntime = harness.context
                .getBean(AiRuntimeProvider::class.java)
                .getRuntime(ServerTestHarness.openAiSubscriptionRuntimeSelection(), resolveProjectRoot())
            val artifactDirectory = prepareArtifactDirectory(runId)
            val caseArtifactDirectory = artifactDirectory.resolve("cases")
            val progressPath = artifactDirectory.resolve("progress.log")
            val resultsPath = artifactDirectory.resolve("results.jsonl")
            val officialHypothesesPath = artifactDirectory.resolve("official-hypotheses.jsonl")
            val summaryPath = artifactDirectory.resolve("summary.md")

            Files.createDirectories(caseArtifactDirectory)
            progressPath.writeText("")
            llmCallProgressCollector.bind(progressPath)
            appendProgress(
                progressPath,
                "suite_start model=$modelName schema=$postgresSchema data=$dataFile cases=${entries.size} namespace=$LONGMEMEVAL_NAMESPACE selection=${selectionProperties.renderForProgress()}"
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
                Files.writeString(
                    officialHypothesesPath,
                    jsonLines.encodeToString(
                        LongMemEvalOfficialHypothesis(
                            questionId = result.questionId,
                            hypothesis = result.answerHypothesis,
                        )
                    ) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
                appendProgress(
                    progressPath,
                    "case_done index=${index + 1}/${entries.size} id=${entry.questionId} durationMs=${System.currentTimeMillis() - startedAt} retrieved=${result.retrievedCount} exactAnswerVisible=${result.exactAnswerTextVisible} supported=${result.answerSupportedByMemory} answer=${result.answerHypothesis.oneLineForArtifact(180)}"
                )
                result
            }

            summaryPath.writeText(renderSummary(dataFile, modelName, postgresSchema, officialHypothesesPath, caseResults))
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

            val failedRememberCases = caseResults.filter { result ->
                result.rememberStatuses.any { status -> status != "completed" }
            }
            assertTrue(
                failedRememberCases.isEmpty(),
                "Expected all LongMemEval memory ingests to complete, but ${failedRememberCases.size}/${caseResults.size} cases had failed sessions. " +
                    failedRememberCases.joinToString(separator = "; ") { result ->
                        "${result.questionId} statuses=${result.rememberStatuses.joinToString()} reasons=${result.rememberReasons.filter { it.isNotBlank() }}"
                    } +
                    ". Artifact: $summaryPath"
            )
            val failedCases = caseResults.filterNot { it.memorySmokePassed }
            assertTrue(
                failedCases.isEmpty(),
                "Expected answer was not supported by memory_context and gold evidence was not fully selected for ${failedCases.size}/${caseResults.size} cases. " +
                    "Artifact: $summaryPath"
            )
        }
    }

    private fun acquireLongMemEvalRunLock(): LongMemEvalRunLock {
        val root = Path.of("build", "test-artifacts", "longmemeval")
        Files.createDirectories(root)
        val channel = FileChannel.open(root.resolve("run.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (lock == null) {
            channel.close()
            error("Another LongMemEval memory smoke run is already active. Stop it before starting a new one.")
        }
        channel.truncate(0)
        channel.write(
            ByteBuffer.wrap(
                "pid=${ProcessHandle.current().pid()} startedAt=${Clock.System.now()}\n".encodeToByteArray()
            )
        )
        return LongMemEvalRunLock(channel = channel, lock = lock)
    }

    private class LongMemEvalRunLock(
        private val channel: FileChannel,
        private val lock: FileLock,
    ) : AutoCloseable {
        override fun close() {
            lock.release()
            channel.close()
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
                "remember_session id=${entry.questionId} index=$sessionNumber/${entry.haystackSessions.size} durationMs=${System.currentTimeMillis() - startedAt} status=${result.status} decision=${result.decision.orEmpty()} reason=${result.reason.orEmpty().oneLineForArtifact(240)} counts=${result.countsSummary}"
            )
            appendProgress(
                progressPath,
                "remember_session_llm_calls id=${entry.questionId} index=$sessionNumber/${entry.haystackSessions.size} " +
                    writeTrace?.llmCalls.orEmpty().renderLlmCallsForProgress()
            )
            result
        }
        val rememberDurationMs = System.currentTimeMillis() - rememberStartedAt
        val expectedEvidenceSourceIds = expectedEvidenceSourceIds(entry, rememberedSessions)

        appendProgress(progressPath, "answer_from_memory_start id=${entry.questionId}")
        val answerStartedAt = System.currentTimeMillis()
        val enrichResult = parseToolResult(
            memoryTools.answerProvidedQuestion(
                conversationIdValue = null,
                questionText = renderQuestion(entry),
                mode = "longmemeval",
                namespaceValue = namespace.value,
            )
        )
        val answerDurationMs = System.currentTimeMillis() - answerStartedAt
        if (enrichResult.status != "completed") {
            appendProgress(
                progressPath,
                "answer_from_memory_failed id=${entry.questionId} durationMs=$answerDurationMs status=${enrichResult.status} " +
                    "retrieved=${enrichResult.retrievedCount ?: 0} sufficiency=${enrichResult.sufficiency.orEmpty()} " +
                    "reason=${enrichResult.reason.orEmpty().oneLineForArtifact(240)} answer=${enrichResult.answer.orEmpty().oneLineForArtifact(180)}"
            )
        }
        assertEquals(
            "completed",
            enrichResult.status,
            "memory_answer_question failed for ${entry.questionId}: ${enrichResult.reason.orEmpty()}"
        )
        val readTrace = readTraceCollector.takeLatest(namespace)
        val enrichDurationMs = readTrace?.latencyMs ?: 0L

        val memoryContext = enrichResult.memoryContext.orEmpty()
        appendProgress(
            progressPath,
            "answer_from_memory_done id=${entry.questionId} durationMs=$answerDurationMs status=${enrichResult.status} " +
                "retrieved=${enrichResult.retrievedCount ?: 0} sufficiency=${enrichResult.sufficiency.orEmpty()} " +
                "memoryContextChars=${memoryContext.length} answer=${enrichResult.answer.orEmpty().oneLineForArtifact(180)} " +
                readTrace?.llmCalls.orEmpty().renderLlmCallsForProgress()
        )
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
        val answerHypothesis = enrichResult.answer.orEmpty()
        val answerHypothesisReasoning = enrichResult.reasoning.orEmpty()
        validateBenchmarkEvalText(stageName = "memory_answer_question", questionId = entry.questionId, fieldName = "answer", value = answerHypothesis)
        validateBenchmarkEvalText(stageName = "memory_answer_question", questionId = entry.questionId, fieldName = "reasoning", value = answerHypothesisReasoning)
        appendProgress(
            progressPath,
            "answer_judge_start id=${entry.questionId} exactAnswerVisible=$exactAnswerTextVisible evidenceSourceHit=$evidenceSourceHit"
        )
        val answerJudgeStartedAt = System.currentTimeMillis()
        val answerJudgement = callBenchmarkEvalStage(
            stageName = "answer_judge",
            questionId = entry.questionId,
            progressPath = progressPath,
        ) { attempt ->
            judgeAnswerHypothesis(
                runtime = judgeRuntime,
                entry = entry,
                expectedAnswer = expectedAnswer,
                goldEvidence = entry.renderGoldEvidenceForJudge(),
                answerHypothesis = answerHypothesis,
                attempt = attempt,
            )
        }
        val answerJudgeDurationMs = System.currentTimeMillis() - answerJudgeStartedAt
        appendProgress(
            progressPath,
            "answer_judge_done id=${entry.questionId} supported=${answerJudgement.supported} reason=${answerJudgement.reason.oneLineForArtifact(240)}"
        )
        val memoryLlmCalls = rememberedSessions.flatMap { remembered ->
            remembered.writeTrace?.llmCalls.orEmpty().map { call ->
                call.toLongMemEvalMemoryLlmCallResult(scope = "remember:${remembered.haystackSessionId}")
            }
        } + readTrace?.llmCalls.orEmpty().map { call ->
            call.toLongMemEvalMemoryLlmCallResult(scope = "read")
        }
        val memorySmokePassReason = if (answerJudgement.supported) {
            "answer_judge"
        } else {
            "unsupported_answer"
        }
        val memorySmokePassed = answerJudgement.supported
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
                memorySmokePassed = memorySmokePassed,
                memorySmokePassReason = memorySmokePassReason,
                answerHypothesis = answerHypothesis,
                answerHypothesisReasoning = answerHypothesisReasoning,
                answerJudgement = answerJudgement,
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
            answerSupportedByMemory = answerJudgement.supported,
            memorySmokePassed = memorySmokePassed,
            memorySmokePassReason = memorySmokePassReason,
            expectedEvidenceSourceIds = expectedEvidenceSourceIds,
            selectedEvidenceSourceIds = selectedEvidenceSourceIds,
            selectedExpectedEvidenceSourceIds = selectedExpectedEvidenceSourceIds,
            evidenceSourceHit = evidenceSourceHit,
            allEvidenceSourcesHit = allEvidenceSourcesHit,
            answerHypothesis = answerHypothesis,
            answerHypothesisReasoning = answerHypothesisReasoning,
            answerJudgeReason = answerJudgement.reason,
            memoryLlmCalls = memoryLlmCalls,
            rememberStatuses = rememberResults.map { it.status },
            rememberDecisions = rememberResults.map { it.decision.orEmpty() },
            rememberReasons = rememberResults.map { it.reason.orEmpty() },
            selectedRefs = enrichResult.selectedRefs,
            memoryContextPreview = memoryContext.take(MEMORY_CONTEXT_REPORT_CHARS),
            caseDossierPath = caseDossierPath.toAbsolutePath().normalize().toString(),
            rememberDurationMs = rememberDurationMs,
            enrichDurationMs = enrichDurationMs,
            answerDurationMs = answerDurationMs,
            answerJudgeDurationMs = answerJudgeDurationMs,
            durationMs = System.currentTimeMillis() - caseStartedAt,
        )
    }

    private suspend fun <T> callBenchmarkEvalStage(
        stageName: String,
        questionId: String,
        progressPath: Path,
        block: suspend (attempt: Int) -> T,
    ): T {
        val timeoutMs = benchmarkEvalLlmTimeoutMs()
        val maxAttempts = benchmarkEvalLlmMaxAttempts()
        var attempt = 1
        var retryDelayMs = 1_000L

        while (true) {
            val startedAt = System.currentTimeMillis()
            appendProgress(
                progressPath,
                "${stageName}_llm_attempt_start id=$questionId attempt=$attempt timeoutMs=$timeoutMs"
            )

            try {
                val result = withTimeout(timeoutMs) { block(attempt) }
                appendProgress(
                    progressPath,
                    "${stageName}_llm_attempt_done id=$questionId attempt=$attempt durationMs=${System.currentTimeMillis() - startedAt}"
                )
                return result
            } catch (error: Throwable) {
                if (error is CancellationException && error !is TimeoutCancellationException) {
                    throw error
                }

                val retryable = error.isRetryableBenchmarkEvalFailure()
                appendProgress(
                    progressPath,
                    "${stageName}_llm_attempt_failed id=$questionId attempt=$attempt durationMs=${System.currentTimeMillis() - startedAt} retryable=$retryable error=${error.shortErrorForProgress()}"
                )
                if (!retryable || attempt >= maxAttempts) {
                    throw error
                }

                delay(retryDelayMs)
                attempt += 1
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(4_000L)
            }
        }
    }

    private suspend fun judgeAnswerHypothesis(
        runtime: AiRuntime,
        entry: LongMemEvalEntry,
        expectedAnswer: String,
        goldEvidence: String,
        answerHypothesis: String,
        attempt: Int,
    ): LongMemEvalAnswerJudgement {
        val conversationId = Conversation.Id("longmemeval-answer-judge:${entry.questionId}")
        val retryPrompt = if (attempt == 1) {
            null
        } else {
            "Retry attempt $attempt: the previous evaluator response was invalid. Return only compact JSON. Keep reason under 25 words."
        }
        val response = runtime.call(
            AiRuntimeRequest(
                systemPrompts = listOfNotNull(
                    """
                    You are an objective evaluator for a long-term-memory benchmark.
                    Decide whether the candidate answer correctly answers the question in the same core direction as the noisy expected-answer label.
                    The expected-answer label can contain noisy illustrative examples, broad inferences, or details not present in the gold evidence.
                    Use the gold evidence only to decide which expected-answer details are actually evidence-backed.
                    Mark supported=true when the candidate answer is equivalent to the evidence-backed central expected answer or contains all evidence-backed central facts needed to answer.
                    Mark supported=false when the candidate answer misses an evidence-backed central answer, contradicts it, leaves several plausible competing answers, or only answers a weaker unrelated subset.
                    Do not require expected-answer details that are absent from the gold evidence.
                    For count/list questions, independently enumerate the evidence-backed items from the gold evidence. If the expected numeric count is evidence-backed, mark supported=false when the candidate gives a different count or misses a counted evidence-backed item.
                    For count/list questions, judge evidence-backed item equivalence by category fit and explicit user attribution rather than exact verb wording. A different lifecycle or status verb can still support the answer when the gold evidence clearly places the item inside the requested category.
                    For imported-source events with an explicit month/day but no explicit year, past-tense wording, and a same-year normalization that would put the event after the source or question date, treat the inferred year as uncertain. For relative-window aggregate/count questions, do not reject otherwise matching explicit numeric operands solely as future or outside-window.
                    For replaced/fixed/upgraded functional-slot counts, count paired gold evidence that an old item was removed, discarded, donated, given away, or got rid of and a new item or upgrade took over the same ordinary function. The new item's source, such as gift, purchase, or existing ownership, is not an exclusion reason by itself.
                    For indirect replacement/upgrade evidence, count one functional slot when gold evidence connects a newly acquired, gifted, bought, adopted, or started-using item with removal, donation, give-away, or discard of an older same-role item, even when the source does not use the exact word "replace".
                    Treat successor or substitute items as same-role when they serve the same ordinary user function or routine, even if their exact subtype differs. A same-source/session pattern of "newer or more capable item introduced for a routine" plus "older same-domain item removed from inventory" is replacement/upgrade evidence unless evidence explicitly says the items are unrelated.
                    For acquisition-style broad category counts, first-person evidence of a concrete personal copy or item is enough to count that item unless the question asks for transaction details such as price, store, payment, download source, or exact date.
                    For acquisition-style count/list questions, first-person evidence that the user got, received, acquired, bought, downloaded, adopted, or was given a concrete item from another person or source is acquisition evidence. Relative timing such as "last month" resolves from the source/session date unless contradicted by more specific evidence.
                    For relative month-window questions over imported chat sources, a first-person source-local cue such as "last month" can satisfy the requested month window when the source/session date is close to the question date and no explicit date places the event outside the window. Do not reject it solely because the exact day inside that relative month is unknown.
                    For broad category counts, do not require exact subtype words or exact title when ordinary language makes the evidence-backed personal item a member, copy, or instance of the requested category.
                    For broad counts of works or content items, count evidence-backed physical or digital carriers, copies, files, or personal items of that work as the work itself when user attribution is explicit.
                    For count/list questions, judge broad category labels as category-fit tests rather than exact-word qualifiers. Do not mark an answer unsupported only because the evidence names a member, carrier, copy, or concrete instance instead of repeating the category label.
                    For broad category counts, do not require an exact subtype word in evidence unless the evidence gives an explicit conflicting subtype/category or the question asks for exact subtype/transaction details.
                    For location-scoped item count/list questions, judge concrete objects as category-fitting when gold evidence explicitly places them in that location or their ordinary function fits that location. Do not narrow a location item category to an arbitrary subtype unless the question says so.
                    For qualified recall questions, the candidate's direct primary answer must satisfy every explicit qualifier supported by the gold evidence. Qualifiers include item ingredients, components, materials, features, owner, source, role, date, venue, relation, and other descriptive modifiers. Mark supported=false when it answers only a weaker partial match while another evidence-backed item satisfies the full qualified description. Mark supported=false when the candidate gives a conflicting or weaker primary answer and mentions the evidence-backed fully-qualified answer only as a caveat, aside, alternative, or correction.
                    For concrete who/from-whom/source/giver expected-answer labels, mark supported=false when the candidate answers that memory is insufficient or unknown without providing the evidence-backed expected giver/source. If the gold evidence contains the expected giver/source for the only target-period received/acquired personal item, noisy or mismatched object wording in the question does not make an insufficiency answer supported; the candidate may caveat the object mismatch, but it must still provide the giver/source.
                    When the expected-answer label says the information is not enough, not mentioned, unknown, or similar, mark a concrete candidate answer supported=true only if the gold evidence explicitly contains the asked fully-qualified fact. Do not overturn an insufficiency label by stitching together adjacent facts whose modifiers differ.
                    For preference or personalization questions, supported=true when the candidate uses the relevant user-specific preference or fact in the same core direction.
                    For temporal questions, do not penalize off-by-one errors for counts of days, weeks, months, or similar durations.
                    For remaining-amount questions such as "how many/much do I need to earn/save/add/pay/lose to reach/redeem/qualify", the evidence-backed central answer is the remaining delta between current value and required target. Mark supported=false when the candidate's primary answer gives only the target total or only the current value instead of the remaining delta, even if that total/current value appears in gold evidence.
                    For knowledge-update questions, the candidate may mention previous information if it also clearly gives the updated required answer.
                    Return only the configured JSON object.
                    """.trimIndent(),
                    retryPrompt,
                ),
                messages = listOf(
                    Conversation.Message(
                        id = Conversation.Message.Id("longmemeval-answer-judge-user:${uuid7()}"),
                        conversationId = conversationId,
                        role = Conversation.Message.Role.USER,
                        content = listOf(
                            Conversation.Message.ContentItem.UserMessage(
                                """
                                Question:
                                ${entry.question}

                                Question date:
                                ${entry.questionDate}

                                Noisy expected-answer label:
                                $expectedAnswer

                                Gold evidence:
                                ```text
                                $goldEvidence
                                ```

                                Candidate answer:
                                $answerHypothesis
                                """.trimIndent()
                            )
                        ),
                        createdAt = Clock.System.now(),
                    )
                ),
                options = AiRuntimeOptions(
                    maxOutputTokens = 300,
                    toolChoice = AiToolChoice.None,
                    responseFormat = ANSWER_JUDGE_RESPONSE_FORMAT,
                    toolContext = mapOf(
                        "longMemEvalAnswerJudge" to true,
                        "questionId" to entry.questionId,
                        "questionType" to entry.questionType,
                    ),
                ),
            )
        )

        val rawText = AiConversationMessageMapper.extractAssistantText(response)
        val root = json.parseToJsonElement(rawText).jsonObject
        val reason = root.stringValue("reason").orEmpty()
        validateBenchmarkEvalText(stageName = "answer_judge", questionId = entry.questionId, fieldName = "reason", value = reason)
        return LongMemEvalAnswerJudgement(
            supported = root["supported"]?.jsonPrimitive?.booleanOrNull ?: false,
            reason = reason,
        )
    }

    private fun LongMemEvalEntry.renderGoldEvidenceForJudge(): String =
        goldEvidenceSessionsForJudge()
            .map { session ->
                renderSession(session.turns, haystackDates.getOrNull(session.haystackIndex))
            }
            .joinToString("\n\n---\n\n")
            .truncateForJudgeEvidence(MAX_JUDGE_GOLD_EVIDENCE_CHARS)

    private fun LongMemEvalEntry.goldEvidenceSessionsForJudge(): List<LongMemEvalGoldEvidenceSession> {
        val answerMarkedSessions = haystackSessions.mapIndexedNotNull { index, session ->
            val answerTurns = session.filter { it.hasAnswer }
            if (answerTurns.isEmpty()) null else LongMemEvalGoldEvidenceSession(index, answerTurns)
        }
        if (answerMarkedSessions.isNotEmpty()) return answerMarkedSessions

        val byHaystackSessionId = haystackSessionIds.withIndex().associate { it.value to it.index }
        val sessionsFromAnswerIds = answerSessionIds.mapNotNull { answerSessionId ->
            byHaystackSessionId[answerSessionId]?.let { index ->
                LongMemEvalGoldEvidenceSession(index, haystackSessions[index])
            }
        }
        if (sessionsFromAnswerIds.isNotEmpty()) return sessionsFromAnswerIds.distinctBy { it.haystackIndex }

        return haystackSessions.mapIndexed { index, session ->
            LongMemEvalGoldEvidenceSession(index, session)
        }
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
        val fromAnswerMarkedSessions = rememberedSessions
            .filter { it.hasAnswer }
            .map { it.sourceId }
            .distinct()
        if (fromAnswerMarkedSessions.isNotEmpty()) return fromAnswerMarkedSessions

        val byHaystackSessionId = rememberedSessions.associateBy { it.haystackSessionId }
        val fromAnswerSessionIds = entry.answerSessionIds
            .mapNotNull { answerSessionId -> byHaystackSessionId[answerSessionId]?.sourceId }
        if (fromAnswerSessionIds.isNotEmpty()) return fromAnswerSessionIds.distinct()
        return emptyList()
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
            reason = root.stringValue("reason") ?: root.stringValue("message"),
            retrievedCount = root["retrieved_count"]?.jsonPrimitive?.intOrNull,
            memoryContext = root.stringValue("memory_context"),
            answer = root.stringValue("answer"),
            reasoning = root.stringValue("reasoning"),
            sufficiency = root.stringValue("sufficiency"),
            countedItems = root.stringArrayValue("counted_items"),
            excludedRefs = root.stringArrayValue("excluded_refs"),
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

    private fun longMemEvalSelectionFixture(): List<LongMemEvalEntry> =
        listOf(
            LongMemEvalEntry(
                questionId = "known-case",
                questionType = "known-type",
                question = "Question?",
                answer = JsonObject(emptyMap()),
                questionDate = "2026-01-01",
                haystackDates = emptyList(),
                haystackSessions = listOf(listOf(LongMemEvalTurn(role = "user", content = "Content."))),
            )
        )

    private fun List<LongMemEvalEntry>.selectByProperties(properties: LongMemEvalSelectionProperties): List<LongMemEvalEntry> =
        filterByCaseProperty(properties.caseFilterTokens)
            .filterByTypeProperty(properties.typeFilterTokens)
            .sampleByProperty(properties.sample)
            .offsetByProperty(properties.offset)
            .limitByProperty(properties.limit)

    private fun List<LongMemEvalEntry>.filterByCaseProperty(tokens: List<String>): List<LongMemEvalEntry> {
        if (tokens.isEmpty()) return this
        val unknownTokens = tokens.filterNot { token ->
            any { entry ->
                entry.questionId == token ||
                    entry.questionType.equals(token, ignoreCase = true)
            }
        }
        require(unknownTokens.isEmpty()) {
            "Unknown LongMemEval caseFilter token(s): ${unknownTokens.joinToString(", ")}"
        }
        return filter { entry ->
            tokens.any { token ->
                entry.questionId == token ||
                    entry.questionType.equals(token, ignoreCase = true)
            }
        }
    }

    private fun List<LongMemEvalEntry>.filterByTypeProperty(types: Set<String>): List<LongMemEvalEntry> {
        if (types.isEmpty()) return this
        return filter { entry -> types.any { type -> entry.questionType.equals(type, ignoreCase = true) } }
    }

    private fun List<LongMemEvalEntry>.sampleByProperty(sample: String): List<LongMemEvalEntry> {
        if (sample.isBlank()) return this
        val balancedSample = LongMemEvalBalancedSample.parse(sample)
            ?: throw IllegalArgumentException(
                "Unsupported LongMemEval sample mode '$sample'. Supported: balanced, balanced:<per-type-count>, or balanced:<per-type-count>@<page>."
            )
        val originalIndex = withIndex().associate { (index, entry) -> entry.questionId to index }
        return groupBy { it.questionType }
            .values
            .flatMap { entries ->
                entries
                    .drop(balancedSample.offsetPerType)
                    .take(balancedSample.perType)
            }
            .sortedBy { entry -> originalIndex.getValue(entry.questionId) }
    }

    private fun List<LongMemEvalEntry>.limitByProperty(limit: String): List<LongMemEvalEntry> {
        if (limit.isBlank() || limit.equals("all", ignoreCase = true)) return this
        val count = limit.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Invalid LongMemEval limit '$limit'. Use a positive integer or 'all'.")
        return take(count)
    }

    private fun List<LongMemEvalEntry>.offsetByProperty(offset: String): List<LongMemEvalEntry> {
        if (offset.isBlank()) return this
        val count = offset.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("Invalid LongMemEval offset '$offset'. Use a non-negative integer.")
        return drop(count)
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
        officialHypothesesPath: Path,
        results: List<LongMemEvalSmokeCaseResult>,
    ): String = buildString {
        val allRememberSessions = results.sumOf { it.rememberStatuses.size }
        val completedRememberSessions = results.sumOf { result ->
            result.rememberStatuses.count { status -> status == "completed" }
        }
        appendLine("# LongMemEval Memory Smoke")
        appendLine()
        appendLine("status | ${if (results.all { it.memorySmokePassed } && completedRememberSessions == allRememberSessions) "PASS" else "FAIL"}")
        appendLine()
        appendLine("- data: `$dataFile`")
        appendLine("- model: `$modelName`")
        appendLine("- postgres schema: `$postgresSchema`")
        appendLine("- namespace prefix: `$LONGMEMEVAL_NAMESPACE`")
        appendLine("- official hypotheses: `$officialHypothesesPath`")
        appendLine("- cases: ${results.size}")
        appendLine("- memory writes completed: $completedRememberSessions/$allRememberSessions")
        appendLine("- memory smoke pass: ${results.count { it.memorySmokePassed }}/${results.size}")
        appendLine("- answer supported by memory: ${results.count { it.answerSupportedByMemory }}/${results.size}")
        appendLine("- exact answer text visible: ${results.count { it.exactAnswerTextVisible }}/${results.size}")
        val evidenceMeasuredResults = results.filter { it.evidenceSourceHit != null }
        appendLine("- evidence source hit: ${evidenceMeasuredResults.count { it.evidenceSourceHit == true }}/${evidenceMeasuredResults.size}")
        appendLine("- total duration: ${results.sumOf { it.durationMs }.durationSummary()}")
        appendLine("- remember duration: ${results.sumOf { it.rememberDurationMs }.durationSummary()}")
        appendLine("- enrich duration: ${results.sumOf { it.enrichDurationMs }.durationSummary()}")
        appendLine("- answer duration: ${results.sumOf { it.answerDurationMs }.durationSummary()}")
        appendLine("- answer judge duration: ${results.sumOf { it.answerJudgeDurationMs }.durationSummary()}")
        appendLine()
        appendSlowestStages(results)
        appendLine()
        appendSlowestMemoryLlmCalls(results)
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
            appendLine("- memory smoke pass: ${result.memorySmokePassed} (${result.memorySmokePassReason})")
            appendLine("- evidence source hit: ${result.evidenceSourceHit}")
            appendLine("- all evidence sources hit: ${result.allEvidenceSourcesHit}")
            appendLine("- expected evidence source ids: ${result.expectedEvidenceSourceIds.joinToString()}")
            appendLine("- selected expected evidence source ids: ${result.selectedExpectedEvidenceSourceIds.joinToString()}")
            appendLine("- answer hypothesis: ${result.answerHypothesis}")
            appendLine("- answer hypothesis reasoning: ${result.answerHypothesisReasoning}")
            appendLine("- answer judge reason: ${result.answerJudgeReason}")
            appendLine("- memory llm calls: ${result.memoryLlmCalls.renderLlmCallsForSummary()}")
            appendLine("- remember statuses: ${result.rememberStatuses.joinToString()}")
            appendLine("- remember decisions: ${result.rememberDecisions.joinToString()}")
            appendLine("- remember reasons: ${result.rememberReasons.filter { it.isNotBlank() }.joinToString().ifBlank { "none" }}")
            appendLine("- durations: total=${result.durationMs.durationSummary()}, remember=${result.rememberDurationMs.durationSummary()}, enrich=${result.enrichDurationMs.durationSummary()}, answer=${result.answerDurationMs.durationSummary()}, judge=${result.answerJudgeDurationMs.durationSummary()}")
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
        memorySmokePassed: Boolean,
        memorySmokePassReason: String,
        answerHypothesis: String,
        answerHypothesisReasoning: String,
        answerJudgement: LongMemEvalAnswerJudgement,
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
        appendLine("answerSupportedByMemory | ${answerJudgement.supported}")
        appendLine("memorySmokePassed | $memorySmokePassed")
        appendLine("memorySmokePassReason | $memorySmokePassReason")
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
            appendLine("toolReason | ${remembered.result.reason ?: "none"}")
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
        appendLine("## Answer Hypothesis")
        appendLine()
        appendLine(answerHypothesis)
        appendLine()
        appendLine("Reasoning: $answerHypothesisReasoning")
        appendLine()
        appendLine("## Answer Judge")
        appendLine()
        appendLine("supported | ${answerJudgement.supported}")
        appendLine("reason | ${answerJudgement.reason}")
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
            appendLine("contextMode | ${result.plan.contextMode.name}")
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

    private fun List<MemoryRun.LlmCallTiming>.renderLlmCallsForProgress(): String =
        if (isEmpty()) {
            "llmCalls=none"
        } else {
            joinToString("; ", prefix = "llmCalls=") { call ->
                "${call.stageName}:attempt=${call.attempt}:status=${call.status.name}:latencyMs=${call.latencyMs}" +
                    ":timeoutMs=${call.timeoutMs ?: "unknown"}:input=${call.totalInputTokens ?: "unknown"}" +
                    ":output=${call.totalOutputTokens ?: "unknown"}:finish=${call.finishReason ?: "unknown"}"
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
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            path,
            "${Clock.System.now()} $message\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun JsonObject.stringValue(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.stringArrayValue(key: String): List<String> =
        this[key]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun benchmarkEvalLlmTimeoutMs(): Long =
        System.getProperty(EVAL_LLM_TIMEOUT_MS_PROPERTY)
            ?.trim()
            ?.toLongOrNull()
            ?.coerceAtLeast(1_000L)
            ?: 180_000L

    private fun benchmarkEvalLlmMaxAttempts(): Int =
        System.getProperty(EVAL_LLM_MAX_ATTEMPTS_PROPERTY)
            ?.trim()
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: 2

    private data class LongMemEvalSelectionProperties(
        val caseFilterTokens: List<String>,
        val typeFilterTokens: Set<String>,
        val sample: String,
        val offset: String,
        val limit: String,
    ) {
        fun renderForProgress(): String =
            listOf(
                "caseFilter=${caseFilterTokens.joinToString(",").ifBlank { "<none>" }}",
                "type=${typeFilterTokens.joinToString(",").ifBlank { "<none>" }}",
                "sample=${sample.ifBlank { "<none>" }}",
                "offset=${offset.ifBlank { "<none>" }}",
                "limit=${limit.ifBlank { "<none>" }}",
            ).joinToString(";")

        companion object {
            fun fromSystemProperties(): LongMemEvalSelectionProperties =
                LongMemEvalSelectionProperties(
                    caseFilterTokens = tokensFromSystemProperty(CASE_FILTER_PROPERTY),
                    typeFilterTokens = tokensFromSystemProperty(TYPE_FILTER_PROPERTY).toSet(),
                    sample = System.getProperty(SAMPLE_PROPERTY)?.trim().orEmpty(),
                    offset = System.getProperty(OFFSET_PROPERTY)?.trim().orEmpty(),
                    limit = System.getProperty(LIMIT_PROPERTY)?.trim().orEmpty(),
                )

            private fun tokensFromSystemProperty(name: String): List<String> =
                System.getProperty(name)
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
        }
    }

    private companion object {
        const val ENABLE_PROPERTY = "gromozeka.longmemeval"
        const val DATA_FILE_PROPERTY = "gromozeka.longmemeval.data"
        const val LIMIT_PROPERTY = "gromozeka.longmemeval.limit"
        const val OFFSET_PROPERTY = "gromozeka.longmemeval.offset"
        const val CASE_FILTER_PROPERTY = "gromozeka.longmemeval.caseFilter"
        const val TYPE_FILTER_PROPERTY = "gromozeka.longmemeval.type"
        const val SAMPLE_PROPERTY = "gromozeka.longmemeval.sample"
        const val MODEL_NAME_PROPERTY = "gromozeka.longmemeval.modelName"
        const val SUBSCRIPTION_CONFIG_PROPERTY = "gromozeka.longmemeval.subscriptionConfig"
        const val WEBSOCKET_RESPONSE_TIMEOUT_MS_PROPERTY = "gromozeka.longmemeval.websocketResponseTimeoutMs"
        const val WEBSOCKET_TRANSPORT_TIMEOUT_MS_PROPERTY = "gromozeka.longmemeval.websocketTransportTimeoutMs"
        const val EVAL_LLM_TIMEOUT_MS_PROPERTY = "gromozeka.longmemeval.evalLlmTimeoutMs"
        const val EVAL_LLM_MAX_ATTEMPTS_PROPERTY = "gromozeka.longmemeval.evalLlmMaxAttempts"
        const val MEMORY_LLM_MAX_ATTEMPTS_PROPERTY = "gromozeka.longmemeval.memoryLlmMaxAttempts"
        const val MEMORY_LLM_STAGE_TIMEOUT_MS_PROPERTY = "gromozeka.longmemeval.memoryLlmStageTimeoutMs"
        const val DIRECT_WEBSOCKET_RESPONSE_TIMEOUT_MS_PROPERTY = "gromozeka.ai.openai-subscription.websocket-response-timeout-ms"
        const val DIRECT_WEBSOCKET_TRANSPORT_TIMEOUT_MS_PROPERTY = "gromozeka.ai.openai-subscription.websocket-transport-timeout-ms"
        const val DIRECT_MEMORY_LLM_MAX_ATTEMPTS_PROPERTY = "gromozeka.memory.llm.maxAttempts"
        const val DIRECT_MEMORY_LLM_STAGE_TIMEOUT_MS_PROPERTY = "gromozeka.memory.llm.timeoutMs"
        const val MEMORY_WRITE_PARALLELISM_PROPERTY = "gromozeka.longmemeval.memoryWriteParallelism"
        const val DEFAULT_MODEL_NAME = "gpt-5.5"
        const val LONGMEMEVAL_NAMESPACE = "benchmark:longmemeval"
        const val MEMORY_CONTEXT_REPORT_CHARS = 20_000
        const val SELECTED_REFS_REPORT_CHARS = 8_000
        const val MAX_JUDGE_GOLD_EVIDENCE_CHARS = 35_000
        val ANSWER_JUDGE_RESPONSE_FORMAT = AiResponseFormat.JsonSchema(
            name = "longmemeval_answer_judge",
            schema = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                putJsonObject("properties") {
                    putJsonObject("supported") {
                        put("type", "boolean")
                        put("description", "Whether the candidate answer satisfies the expected answer.")
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "One short sentence explaining the judgement.")
                    }
                }
                putJsonArray("required") {
                    add("supported")
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

        fun systemProperty(primary: String, fallback: String, default: String): String =
            System.getProperty(primary) ?: System.getProperty(fallback) ?: default
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

private data class LongMemEvalGoldEvidenceSession(
    val haystackIndex: Int,
    val turns: List<LongMemEvalTurn>,
)

private data class MemoryToolJsonResult(
    val status: String,
    val decision: String?,
    val reason: String?,
    val retrievedCount: Int?,
    val memoryContext: String?,
    val answer: String?,
    val reasoning: String?,
    val sufficiency: String?,
    val countedItems: List<String>,
    val excludedRefs: List<String>,
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

private data class LongMemEvalAnswerJudgement(
    val supported: Boolean,
    val reason: String,
)

private class LongMemEvalDegenerateEvalResponseException(
    stageName: String,
    questionId: String,
    fieldName: String,
    value: String,
) : IllegalStateException(
    "LongMemEval eval response field is degenerate: stage=$stageName questionId=$questionId field=$fieldName " +
        "chars=${value.length} preview=${value.oneLineForArtifact(180)}"
)

private data class LongMemEvalBalancedSample(
    val perType: Int,
    val page: Int,
) {
    val offsetPerType: Int
        get() = perType * (page - 1)

    companion object {
        fun parse(value: String): LongMemEvalBalancedSample? {
            if (value == "balanced") return LongMemEvalBalancedSample(perType = 1, page = 1)
            if (!value.startsWith("balanced:")) return null
            val body = value.substringAfter("balanced:")
            val perTypeText = body.substringBefore("@")
            val pageText = body.substringAfter("@", "1")
            val perType = perTypeText.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("Invalid LongMemEval balanced sample count: $value")
            val page = pageText.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("Invalid LongMemEval balanced sample page: $value")
            return LongMemEvalBalancedSample(perType = perType, page = page)
        }
    }
}

private fun Throwable.isRetryableBenchmarkEvalFailure(): Boolean {
    if (this is LongMemEvalDegenerateEvalResponseException) return true
    if (this is TimeoutCancellationException) return true

    val chainText = generateSequence(this) { it.cause }
        .joinToString(" | ") { error ->
            "${error::class.simpleName.orEmpty()}: ${error.message.orEmpty()}"
        }
        .lowercase()

    return listOf(
        "server_error",
        "rate_limit",
        "temporarily",
        "try again",
        "timeout",
        "timed out",
        "transport",
        "websocket",
        "connection reset",
        "connection closed",
    ).any(chainText::contains)
}

private fun validateBenchmarkEvalText(
    stageName: String,
    questionId: String,
    fieldName: String,
    value: String,
) {
    val normalized = value.lowercase()
    val looksDegenerate = value.length > 1_200 ||
        listOf(
            "# valid channels",
            "<|end|>",
            "response format expects json",
            "current response final",
            "the generated final",
            "must ensure only json",
            "final already",
        ).any(normalized::contains)

    if (looksDegenerate) {
        throw LongMemEvalDegenerateEvalResponseException(
            stageName = stageName,
            questionId = questionId,
            fieldName = fieldName,
            value = value,
        )
    }
}

private fun Throwable.shortErrorForProgress(): String =
    "${this::class.simpleName.orEmpty()}:${message.orEmpty()}"
        .oneLineForArtifact(240)

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
    val memorySmokePassed: Boolean,
    val memorySmokePassReason: String,
    val expectedEvidenceSourceIds: List<String>,
    val selectedEvidenceSourceIds: List<String>,
    val selectedExpectedEvidenceSourceIds: List<String>,
    val evidenceSourceHit: Boolean?,
    val allEvidenceSourcesHit: Boolean?,
    val answerHypothesis: String,
    val answerHypothesisReasoning: String,
    val answerJudgeReason: String,
    val memoryLlmCalls: List<LongMemEvalMemoryLlmCallResult>,
    val rememberStatuses: List<String>,
    val rememberDecisions: List<String>,
    val rememberReasons: List<String>,
    val selectedRefs: String,
    val memoryContextPreview: String,
    val caseDossierPath: String,
    val rememberDurationMs: Long,
    val enrichDurationMs: Long,
    val answerDurationMs: Long,
    val answerJudgeDurationMs: Long,
    val durationMs: Long,
)

@Serializable
private data class LongMemEvalMemoryLlmCallResult(
    val scope: String,
    val stageName: String,
    val attempt: Int,
    val status: String,
    val latencyMs: Long,
    val timeoutMs: Long?,
    val finishReason: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
)

@Serializable
private data class LongMemEvalOfficialHypothesis(
    @SerialName("question_id")
    val questionId: String,
    val hypothesis: String,
)

private fun String.oneLineForArtifact(limit: Int): String =
    replace(Regex("\\s+"), " ")
        .trim()
        .let { if (it.length <= limit) it else it.take(limit - 3) + "..." }

private fun String.markdownTableCell(): String =
    replace("|", "\\|")

private fun MemoryRun.LlmCallTiming.toLongMemEvalMemoryLlmCallResult(scope: String): LongMemEvalMemoryLlmCallResult =
    LongMemEvalMemoryLlmCallResult(
        scope = scope,
        stageName = stageName,
        attempt = attempt,
        status = status.name,
        latencyMs = latencyMs,
        timeoutMs = timeoutMs,
        finishReason = finishReason,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
    )

private fun List<LongMemEvalMemoryLlmCallResult>.renderLlmCallsForSummary(): String =
    if (isEmpty()) {
        "none"
    } else {
        val totalLatencyMs = sumOf { it.latencyMs }
        val slowest = maxBy { it.latencyMs }
        "${size} calls, total=${totalLatencyMs.durationSummary()}, slowest=${slowest.scope}:${slowest.stageName}:${slowest.status}:${slowest.latencyMs.durationSummary()}"
    }

private fun StringBuilder.appendSlowestStages(results: List<LongMemEvalSmokeCaseResult>) {
    val rows = results.flatMap { result ->
        listOf(
            LongMemEvalStageDuration(stage = "total", durationMs = result.durationMs, result = result),
            LongMemEvalStageDuration(stage = "remember", durationMs = result.rememberDurationMs, result = result),
            LongMemEvalStageDuration(stage = "enrich", durationMs = result.enrichDurationMs, result = result),
            LongMemEvalStageDuration(stage = "answer", durationMs = result.answerDurationMs, result = result),
            LongMemEvalStageDuration(stage = "judge", durationMs = result.answerJudgeDurationMs, result = result),
        )
    }
        .filter { it.durationMs > 0L }
        .sortedByDescending { it.durationMs }
        .take(10)

    if (rows.isEmpty()) {
        return
    }

    appendLine("## Slowest Stages")
    appendLine()
    appendLine("| stage | case | duration | question |")
    appendLine("| --- | --- | ---: | --- |")
    rows.forEach { row ->
        appendLine(
            "| ${row.stage} | `${row.result.questionId}` | ${row.durationMs.durationSummary()} | ${
                row.result.question.oneLineForArtifact(120).markdownTableCell()
            } |",
        )
    }
}

private fun StringBuilder.appendSlowestMemoryLlmCalls(results: List<LongMemEvalSmokeCaseResult>) {
    val rows = results.flatMap { result ->
        result.memoryLlmCalls.map { call ->
            LongMemEvalMemoryLlmCallDuration(result = result, call = call)
        }
    }
        .filter { it.call.latencyMs > 0L }
        .sortedByDescending { it.call.latencyMs }
        .take(15)

    if (rows.isEmpty()) {
        return
    }

    appendLine("## Slowest Memory LLM Calls")
    appendLine()
    appendLine("| case | scope | stage | attempt | status | latency | timeout | tokens | question |")
    appendLine("| --- | --- | --- | ---: | --- | ---: | ---: | ---: | --- |")
    rows.forEach { row ->
        val call = row.call
        appendLine(
            "| `${row.result.questionId}` | `${call.scope.markdownTableCell()}` | `${call.stageName.markdownTableCell()}` | ${call.attempt} | ${call.status} | ${call.latencyMs.durationSummary()} | ${call.timeoutMs?.durationSummary() ?: "unknown"} | ${call.totalTokens ?: "unknown"} | ${
                row.result.question.oneLineForArtifact(120).markdownTableCell()
            } |",
        )
    }
}

private data class LongMemEvalStageDuration(
    val stage: String,
    val durationMs: Long,
    val result: LongMemEvalSmokeCaseResult,
)

private data class LongMemEvalMemoryLlmCallDuration(
    val result: LongMemEvalSmokeCaseResult,
    val call: LongMemEvalMemoryLlmCallResult,
)

private fun String.truncateForJudgeEvidence(limit: Int): String =
    trim()
        .let { if (it.length <= limit) it else it.take(limit) + "\n[truncated ${it.length - limit} chars]" }

private fun Long.durationSummary(): String =
    if (this < 1_000) {
        "${this}ms"
    } else {
        "${this / 1_000}.${(this % 1_000).toString().padStart(3, '0')}s"
    }
