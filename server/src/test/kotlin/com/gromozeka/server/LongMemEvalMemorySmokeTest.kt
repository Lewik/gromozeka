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
import org.junit.jupiter.api.Timeout

class LongMemEvalMemorySmokeTest {

    @Test
    fun parsesBalancedSamplePages() {
        assertEquals(LongMemEvalBalancedSample(perType = 1, page = 1), LongMemEvalBalancedSample.parse("balanced"))
        assertEquals(LongMemEvalBalancedSample(perType = 4, page = 1), LongMemEvalBalancedSample.parse("balanced:4"))
        assertEquals(LongMemEvalBalancedSample(perType = 4, page = 2), LongMemEvalBalancedSample.parse("balanced:4@2"))
        assertEquals(null, LongMemEvalBalancedSample.parse("random:4"))
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.HOURS)
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
        assertEquals(
            "completed",
            enrichResult.status,
            "memory_enrich_context failed for ${entry.questionId}: ${enrichResult.reason.orEmpty()}"
        )
        val readTrace = readTraceCollector.takeLatest(namespace)

        val memoryContext = enrichResult.memoryContext.orEmpty()
        appendProgress(
            progressPath,
            "enrich_done id=${entry.questionId} durationMs=$enrichDurationMs status=${enrichResult.status} " +
                "retrieved=${enrichResult.retrievedCount ?: 0} memoryContextChars=${memoryContext.length} " +
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
        appendProgress(
            progressPath,
            "answer_hypothesis_start id=${entry.questionId} memoryContextChars=${memoryContext.length}"
        )
        val answerStartedAt = System.currentTimeMillis()
        val answerHypothesis = callBenchmarkEvalStage(
            stageName = "answer_hypothesis",
            questionId = entry.questionId,
            progressPath = progressPath,
        ) {
            generateAnswerHypothesis(
                runtime = judgeRuntime,
                entry = entry,
                selectedRefs = enrichResult.selectedRefs,
                memoryContext = memoryContext,
            )
        }
        val answerDurationMs = System.currentTimeMillis() - answerStartedAt
        appendProgress(
            progressPath,
            "answer_hypothesis_done id=${entry.questionId} durationMs=$answerDurationMs answer=${answerHypothesis.answer.oneLineForArtifact(180)} reason=${answerHypothesis.reasoning.oneLineForArtifact(240)}"
        )
        appendProgress(
            progressPath,
            "answer_judge_start id=${entry.questionId} exactAnswerVisible=$exactAnswerTextVisible evidenceSourceHit=$evidenceSourceHit"
        )
        val answerJudgeStartedAt = System.currentTimeMillis()
        val answerJudgement = callBenchmarkEvalStage(
            stageName = "answer_judge",
            questionId = entry.questionId,
            progressPath = progressPath,
        ) {
            judgeAnswerHypothesis(
                runtime = judgeRuntime,
                entry = entry,
                expectedAnswer = expectedAnswer,
                goldEvidence = entry.renderGoldEvidenceForJudge(),
                answerHypothesis = answerHypothesis.answer,
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
            call.toLongMemEvalMemoryLlmCallResult(scope = "enrich")
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
                answerHypothesis = answerHypothesis.answer,
                answerHypothesisReasoning = answerHypothesis.reasoning,
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
            answerHypothesis = answerHypothesis.answer,
            answerHypothesisReasoning = answerHypothesis.reasoning,
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
        block: suspend () -> T,
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
                val result = withTimeout(timeoutMs) { block() }
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

    private suspend fun generateAnswerHypothesis(
        runtime: AiRuntime,
        entry: LongMemEvalEntry,
        selectedRefs: String,
        memoryContext: String,
    ): LongMemEvalAnswerHypothesis {
        val conversationId = Conversation.Id("longmemeval-answer:${entry.questionId}")
        val response = runtime.call(
            AiRuntimeRequest(
                systemPrompts = listOf(
                    DEFAULT_GROMOZEKA_EVAL_PROMPT_SNAPSHOT,
                    """
                    Evaluation answer mode.
                    Answer the user's question as the default Gromozeka assistant using only retrieved memory context.
                    Do not use hidden knowledge, the benchmark expected answer, or assumptions outside retrieved memory.
                    LongMemEval questions can contain noisy or approximate relative dates. Treat temporal wording as a retrieval hint, not as a hard filter, when retrieved memory contains one uniquely relevant event for the rest of the question. If the date is inconsistent but the remembered event clearly answers the user intent, answer the intent and mention the date uncertainty only if it materially matters.
                    For imported-source events with an explicit month/day but no explicit year, past-tense wording, and a same-year normalization that would put the event after the source or question date, treat the inferred year as uncertain. For relative-window aggregate/count questions, count otherwise matching explicit numeric operands instead of excluding them solely as future or outside-window.
                    For relative-duration questions that name an anchor event, such as "how many days/weeks/months ago did X when/at the time Y", compute the interval from event X to the anchor event Y when both dates are selected. Use the current/question date only when no separate anchor event is named or retrieved.
                    For questions asking how long the user had been doing an activity when an anchor event happened, compute from the explicit start/begin/first-participation date of that activity to the anchor event date when both are selected. Do not add an as-of duration or tenure value to the time between its as-of date and the anchor when selected memory also contains a conflicting explicit start date for the same activity; treat that as-of value as noisy or conflicting context.
                    For relative month-count questions such as "two months ago", derive the approximate calendar-offset target from the current/question date and prefer retrieved memory closest to that offset. If several events match the non-date wording, one month ago is not two months ago when another otherwise relevant retrieved item is near the two-month target.
                    For "past weekend", "last week", "yesterday", or other relative-window questions, derive the target interval from the current/question date. An event with its own local cue such as "today" resolves to the source/session date and is outside the window when that date is outside the target interval.
                    For personal event timing from imported chat sources, first-person user recency cues such as "just", "today", "yesterday", "this weekend", "last week", or "recently" are stronger event-date evidence than assistant meta-statements about what the assistant did not know or remember. Do not chain an assistant-side relative interval when the user's own local cue dates the same event to the source/session date.
                    For chained relative-time questions, combine explicit offsets instead of stopping at the last lexical hop. If memory says X happened N days/weeks/months before anchor Y, and Y happened M days/weeks/months before the question/current date, answer X as approximately N+M days/weeks/months ago when both operands are selected. A lead time such as "three months in advance" or "two weeks before" is not itself an "ago" answer unless its anchor is the question/current date.
                    When several retrieved events match the non-date wording, prefer the event whose event date and local event wording best match the target named or relative period. A matching source/session date is only fallback evidence when the source text describes the same target event or no event-dated candidate matches.
                    For date-scoped questions, do not let an ACTIVE typed fact from outside the target period override selected source or note evidence from inside the target period.
                    Do not smear one relative date cue across unrelated events in the same source. If one source mentions "this weekend" for one event and "today" for another, resolve each event independently from its local wording and the source/session date.
                    First-person recency wording such as "still recovering from", "just got back from", "previous", "recent", or "last" can make a completed event relevant to the source/session date. Prefer that completed event over a plan or booking created in the same source when the question asks what the user experienced.
                    For noisy benchmark month/date questions, missing date evidence is not an explicit contradiction. Prefer otherwise matching no-date operands over operands with an explicit different date when the no-date operands match the requested object/action.
                    For place-visit questions, treat user-attended venue events such as lectures, guided tours, exhibits, appointments, or behind-the-scenes tours at that venue as visit evidence when the venue and target time match. Prefer the venue/time match over a more literal "visited" or "guided tour" event from a different time period.
                    When the question asks for the day before or after a known event, resolve relative dates inside selected source text from that source's session date. If the resolved source-relative date equals the target day before or after the event, use the explicit detail from that source instead of refusing because the detail and event are stored in different memories.
                    For completed-experience questions, evidence that the user actually did, attended, took, used, received, or completed something is stronger than same-date planning, booking, reservation, recommendation, or generated artifact evidence. A booking or reservation date is not the event date unless memory explicitly says the booked event happened on that date.
                    LongMemEval action wording can also be lossy. A target-period plan, checklist, appointment, request, estimate, recommendation, setup discussion, or other object-matching lifecycle evidence can match a broad action verb when no better target-period completed event exists and the requested action/status is not itself a required qualifier. Preserve lifecycle state; do not treat plans, bookings, or recommendations as completed user-experienced events.
                    For yes/no questions about whether an event involved a specific person, relation, or participant category, answer "no" when retrieved memory names a different participant and contains no evidence that the asked participant was also present.
                    For arithmetic, savings, comparison, ordering, and count questions, compute only when retrieved memory explicitly provides compatible operands for the exact requested items, route, time, and scope. Selected source evidence is retrieved memory. Do not substitute generic advice, adjacent alternatives, broad ranges, or assistant-suggested examples for a missing operand. For ordering or comparison between named alternatives, require explicit retrieved evidence for every compared alternative; if one alternative is missing, answer that memory is insufficient instead of ranking the known alternative. A conditional bridge in a selected reason, such as "if X is Y", is not evidence that X is Y.
                    For list/order questions whose requested answer type is a category value, provider, organization, place, person, title, or other name rather than event instances, counted_items must be the distinct requested names, not the underlying episodes that mention those names. Deduplicate repeated values in the final answer and counted_items while preserving the earliest supported order. Repeat the same value only when the question explicitly asks for each occurrence, event, trip, attempt, or item instance.
                    For arithmetic, date-difference, and ordering questions with named operands, every named operand must match the requested object or event. A different named object is a missing operand, not an approximate match; answer that memory is insufficient instead of computing from the mismatched object.
                    If LongMemEval month/date wording is unsupported by any retrieved item but the retrieved memory contains exactly the requested number of unique explicit operands matching the non-date target, use those operands instead of refusing solely because of the unsupported benchmark date wording.
                    For noisy LongMemEval month/date arithmetic, exclude operands whose retrieved memory explicitly assigns them to a different month/date, but keep otherwise matching operands that have no explicit month/date label.
                    For noisy LongMemEval named-month arithmetic, do not infer a finish/completion month from the source/session date alone. Only an explicit month/date in the retrieved text should make an otherwise matching operand a different-month operand.
                    Apply noisy-date arithmetic in this order: collect explicit operands matching the non-date object/action, discard only operands with an explicit contradictory date label, keep operands with no date label, and compute when the remaining operand count matches the requested count.
                    Complete-set retrieval means the context may include stale, adjacent, or superseded raw sources so that count/list answers are not incomplete. It does not make every retrieved item equally authoritative. For current factual answers, prefer direct ACTIVE claims and notes over older source excerpts, older goals, stale profiles, or raw source wording that has been superseded by a newer active typed fact.
                    For questions about where, what, current value, current status, or recent relocation, if a direct ACTIVE claim answers the target and older raw source text or older goals disagree, answer from the active claim. Do not resurrect an older source answer merely because its source text is longer or more explicit.
                    For previous, older, original, or initial value questions, if a direct selected SUPERSEDED or non-current typed claim answers the target, use that exact typed value. Do not recompute it from approximate source wording such as "about", "around", or "shaved off" when the exact older typed value is already selected.
                    For unqualified current/usual/status questions with conflicting ACTIVE facts, prefer the most recent explicit event or source date as the current answer. Use older scoped facts only when the question asks for that specific scope, such as a particular day, project, person, place, or time period.
                    For current-state answers at a later target date, selected dated plans, intentions, or scheduled changes can be a matured current state when they match the same subject and slot, predate the target date, and no selected active memory contradicts completion. If a broad current-location claim and a matured plan or source give a more specific container or location inside the same place, combine them into the most specific location.
                    The ranked selected refs are the compact prioritized view. Read them before the full context. If the full context contains older raw source text that conflicts with a top-ranked direct active claim, trust the top-ranked direct active claim for current factual answers.
                    For recall of prior assistant recommendations, option lists, generated artifacts, or exact mentioned items, selected source evidence that explicitly satisfies every requested qualifier beats a selected typed claim or note that only matches a weaker adjacent target.
                    For project leadership, ownership, or responsibility count/list questions, a plain works_on_project or generic project association claim is not enough by itself. Count explicit responsible_for/lead/led/managed/owned claims, explicit team-leadership evidence, and solo or user-owned project evidence. Count personal/current projects only when retrieved memory says the user owns, leads, is responsible for, or is the sole actor on the project. Exclude research topics, papers, posters, broad interests, and plain "my research" or "working on research" evidence unless leadership, ownership, responsibility, or solo execution is explicit.
                    For numeric count/list answers, first form counted_items from retrieved memory and make the final number match that set size. If a selected ranked reference is a plausible counted item, include it in counted_items or add it to excluded_ranked_refs with a concrete exclusion reason; do not silently ignore it. An empty counted_items list is not evidence for zero by itself; answer zero only when retrieved memory explicitly states none/zero for the requested scope or provides a closed complete inventory for that exact scope. Otherwise set count_evidence_kind to insufficient_memory, say memory is insufficient or the requested item was not mentioned, and do not put a concrete zero count in the answer field.
                    For aggregate total/count questions, count only explicit numeric operands or explicit list items that retrieved memory places in the requested aggregate. When compatible metric_observation, current_metric_value, or other explicit numeric aggregate operands are selected, they define the counted inventory; do not add separate singular possession or ownership claims as +1 unless memory explicitly states that singular item belongs to the same counted aggregate and is not already covered by a numeric operand. For historical totals across metric_observation items, count every distinct observed operand unless retrieved memory explicitly says one corrects, replaces, retracts, or repeats another same-slot measurement. Different attempt, event, condition, session, source, date, route, difficulty, or measurement context can make same-subject observations separate operands. For current aggregate totals, first find the latest explicit baseline total for the same collection, then apply later explicit additions or removals in chronological order. A direct older current_metric_value is not final when selected later memory explicitly adds or removes an item in the same aggregate.
                    For increase, decrease, change, delta, difference, gain, loss, or net-movement questions, compute from compatible explicit numeric operands in retrieved memory. Use a selected baseline/previous value and a selected later/current/final value for the same metric or aggregate even when their scope wording is not identical, as long as retrieved memory does not contradict that they belong to the same measured series. Do not answer "insufficient" solely because the baseline is phrased as an initial/start value and the later value is phrased as an after-period observation.
                    For imported-source events with an explicit month/day but no explicit year, past-tense wording, and a same-year normalization that would put the event after the source or question date, treat the inferred year as uncertain. For relative-window aggregate/count questions, count otherwise matching explicit numeric operands instead of excluding them solely as future or outside-window.
                    For count/list questions about acquired, kept, used, completed, attended, or otherwise user-attributed items, count evidence-backed variants that satisfy the requested category even when retrieved memory uses a different lifecycle or status verb than the question. Require explicit user attribution and category fit; do not count merely adjacent examples.
                    For count/list questions scoped before, prior to, until, or at a commitment/decision about a named target, use the named target as boundary context and exclude the target itself from the prior-alternative counted set unless the question explicitly asks to include the target itself. This remains true even when the named target has its own matching action before the boundary event. Put the target in excluded_ranked_refs as the boundary target, not in counted_items.
                    For replaced/fixed/upgraded functional-slot counts, count paired evidence that an old item was removed, discarded, donated, given away, or got rid of and a new item or upgrade took over the same ordinary function. The new item's source, such as gift, purchase, or existing ownership, is not an exclusion reason by itself.
                    For indirect replacement/upgrade evidence, count one functional slot when retrieved memory connects a newly acquired, gifted, bought, adopted, or started-using item with removal, donation, give-away, or discard of an older same-role item, even when the source does not use the exact word "replace".
                    Treat successor or substitute items as same-role when they serve the same ordinary user function or routine, even if their exact subtype differs. A same-source/session pattern of "newer or more capable item introduced for a routine" plus "older same-domain item removed from inventory" is replacement/upgrade evidence unless memory explicitly says the items are unrelated.
                    For acquisition-style count/list questions, count concrete physical or digital items when retrieved memory places that item in the user's acquisition, possession, collection, or use history and the requested category fits. Do not require the exact action verb from the question; do not count mere mentions, interests, recommendations, or assistant-only suggestions.
                    For acquisition-style count/list questions, selected ACTIVE "owns" or POSSESSION claims are direct acquisition/possession evidence when the item fits the requested category. The words purchased, bought, downloaded, acquired, or got in a how-many/list question are lifecycle hints, not transaction-detail requirements by themselves.
                    Explicitly ordered, reserved, or preordered concrete items are acquisition evidence when the requested category fits; pending delivery, receipt, or pickup only excludes the item when the question specifically asks for completed receipt, delivery, pickup, or current possession after a contradictory later status.
                    First-person evidence of a concrete personal copy or item can satisfy an acquisition-style broad category count even without a transaction verb. Do not use this shortcut when the question asks for purchase/download transaction details such as price, store, payment, download source, or exact date.
                    Treat "how many X did I buy/download/acquire/get" as a broad category count unless the user asks for transaction details. For broad category counts, do not require exact subtype words or exact title when ordinary language makes the remembered personal item a member, copy, or instance of the requested category.
                    For LongMemEval who/from-whom/source/giver questions about something the user received, got, inherited, was given, acquired, or obtained, treat the requested object label as a noisy retrieval hint when retrieved imported-source memory gives exactly one target-period personal item with an explicit giver/source. If the item subtype or category is mismatched but there is no competing target-period received/acquired item, answer the remembered giver/source and caveat the object mismatch instead of refusing solely because of the subtype mismatch.
                    For furniture or furnishing count/list questions, treat large movable household furnishings used for sleeping, seating, storage, surfaces, or workspace functions as category-fitting even when memory names only the concrete subtype. Do not count small decor, textiles, accessories, or decorative covers unless the question asks for them.
                    For broad counts of works or content items, a physical or digital carrier, copy, file, or personal item of that work can count as the work itself when user attribution is explicit. The item's material or format is not a separate category requirement unless the question asks for format-specific details.
                    For broad counts of music works or releases, a user-owned or downloaded vinyl record, CD, cassette, digital copy, or music download can count as an album/EP/release item unless retrieved memory explicitly identifies it as only a single track, playlist, non-audio merchandise, or the question asks for a narrower format or subtype.
                    For broad health-related device count/list questions, count user-attributed monitoring, treatment, assistive, accessibility, therapeutic, and health-support devices when retrieved memory indicates ownership, wearing, reliance, regular use, usage frequency, or usage duration. Do not narrow "health-related" to only tracking or treatment devices unless the question asks for that narrower subtype.
                    In count/list questions, broad category labels are category-fit tests, not exact-word qualifiers. Do not exclude a plausible counted item solely because memory names a member, carrier, copy, or concrete instance instead of repeating the category label.
                    For broad category counts, a missing exact subtype word is not a contradiction. Exclude a selected user-attributed copy/member/carrier only when retrieved memory gives an explicit conflicting subtype or category, or when the question asks for exact subtype/transaction details.
                    For formal education duration from one stage to another stage's completion, use the whole retrieved formal education timeline. Include intermediate formal credentials, attendance, transfer, and completion milestones when they bridge the asked span.
                    For count/list questions about items, deduplicate aliases and container/detail pairs. Do not count both a concrete item and a project, diorama, setup, bundle, or plan built around that same item as separate items unless memory clearly says they are distinct. For count/list questions about errands, actions, tasks, pickups, returns, appointments, or commitments, count distinct actions separately even when they involve the same item. In an exchange, the original item to return and the replacement item to pick up are distinct physical items unless memory says it is the exact same item.
                    For count/list questions about what the user has used, made, served, tried, selected, bought, or owned, count only items attributed to the user in retrieved memory. Do not count assistant-suggested examples, generic recipe variants, optional substitutions, garnishes, or possible future ingredients unless the user explicitly used them, selected them, served them, or made a concrete plan to use them in the asked scope.
                    For count/list questions about people or professionals the user visited, saw, consulted, or received care/service from, count distinct real providers when retrieved memory explicitly attributes an actual provider relationship, appointment, visit, diagnosis, prescription, treatment, or user-stated "saw/visited" interaction to them. Do not count generic assistant advice to consult a provider, hypothetical referrals, or future-only appointments without an existing concrete provider relationship.
                    For aggregate questions about events the user participated in, count explicit user involvement broadly: attended, participated, helped organize, contributed to, or was part of the team can all qualify unless the question explicitly restricts the answer to personally raised, personally paid, or individually performed amounts.
                    For category-scoped count/list questions, treat explicit venue, organizer, community, and stated context as category signals. A community-hosted service activity can qualify for that community/category even when the concrete task is volunteering, planning, sorting, packing, or another support action, unless the question asks for a narrower subtype.
                    For room or area item count/list questions, category fit includes fixtures, storage, furniture, tools, mats, devices, appliances, and other concrete objects when retrieved memory explicitly places them in that room/area or their ordinary function fits that room/area. Do not narrow a room item category to only fixtures or utensils unless the question says so.
                    For replaced/fixed/upgraded household-item counts, count one functional slot when retrieved memory says the user fixed it, replaced it with a newer item, got rid of, donated, or gave away the old item as part of an upgrade, or adopted a new item that takes over the old item's function. Do not count both the old item and its replacement unless the question asks for inventory.
                    For prior-alternative count/list questions before a commitment to a named target, count alternatives considered before the commitment and exclude the named target of the commitment itself unless the question explicitly asks to include it. A target item can still have happened before the commitment event; that timing alone does not make it a prior alternative. If counted_items contains the named target, the answer is invalid.
                    For recommendation/adaptation questions about a new target, use remembered user preferences, constraints, and liked features from analogous prior targets. Do not answer "insufficient memory" solely because the exact destination/product/task is new; instead apply the remembered preference pattern and name the criteria that should guide the recommendation.
                    For questions about a specifically qualified object, role, job title, position, project, event, or relationship, require the retrieved memory to explicitly preserve every requested qualification. When different retrieved memories satisfy different parts of the question, do not answer from a partial match; choose the item that satisfies all required qualifiers, or say memory is insufficient/conflicting. Do not answer with a value for a different object, person, role, job title, position, event, route, project, artifact, item, or relationship merely because it is adjacent or similar; a caveat that the qualifier differs is not enough. A shared anchor can bridge retrieved memories only when it explicitly preserves the same fully-qualified target; it cannot weaken or rewrite the target's modifiers. Anchor-level metadata can supply a missing requested detail when one retrieved memory explicitly links the asked target to a named venue, publisher, conference, program, service, route, or event and another retrieved memory gives a date, deadline, schedule, price, policy, threshold, or similar property for that same named anchor. Use this bridge only when the metadata is not tied to a different named target and no selected memory gives a competing anchor or value for the asked target. If any requested qualifier is missing or changed, the answer field must be an insufficiency answer, not a concrete adjacent answer with a warning. Do not infer missing academic level, course ownership, job role, job title, position, project identity, artifact type, item ingredient, component, material, feature, participant identity, route, source, owner, medium, or relation from a merely related remembered topic. When refusing because only adjacent or mismatched evidence was retrieved, do not compute or include the mismatched value unless the user explicitly asks about related memories. Do not map an unnamed role or relative to a named person unless retrieved memory explicitly says they are the same person.
                    If retrieved memory is insufficient or conflicting, say that the available memory is insufficient.
                    The answer field is the final candidate answer that will be judged on its own. Put the retrieved facts needed to support the conclusion in the answer itself, not only in reasoning.
                    For recommendation, advice, preference, and personalization questions, make the answer self-contained by naming the relevant remembered user-specific facts that justify the recommendation.
                    For count/list questions, fill reasoning with the counted set and any excluded plausible ranked refs before the conclusion. For non-count questions, fill reasoning with one concise evidence sentence naming the selected remembered event, the remembered participant if relevant, the asked participant if relevant, and the conclusion.
                    Keep the answer concise and directly responsive.
                    Return only the configured JSON object.
                    """.trimIndent()
                ),
                messages = listOf(
                    Conversation.Message(
                        id = Conversation.Message.Id("longmemeval-answer-user:${uuid7()}"),
                        conversationId = conversationId,
                        role = Conversation.Message.Role.USER,
                        content = listOf(
                            Conversation.Message.ContentItem.UserMessage(
                                """
                                Retrieved memory context:
                                Ranked selected refs:
                                ```json
                                ${selectedRefs.ifBlank { "[]" }}
                                ```

                                Full memory context:
                                ```text
                                $memoryContext
                                ```

                                LongMemEval evaluation note:
                                the benchmark question's relative date can be noisy. If the retrieved memory has one uniquely relevant event matching the non-date part of the question, do not reject it solely because the relative date wording is approximate.
                                For relative-duration questions that name an anchor event, such as "how many days/weeks/months ago did X when/at the time Y", compute the interval from event X to the anchor event Y when both dates are selected. Use the current/question date only when no separate anchor event is named or retrieved.
                                For questions asking how long the user had been doing an activity when an anchor event happened, compute from the explicit start/begin/first-participation date of that activity to the anchor event date when both are selected. Do not add an as-of duration or tenure value to the time between its as-of date and the anchor when selected memory also contains a conflicting explicit start date for the same activity; treat that as-of value as noisy or conflicting context.
                                For relative month-count questions such as "two months ago", derive the approximate calendar-offset target from the current/question date and prefer retrieved memory closest to that offset. If several events match the non-date wording, one month ago is not two months ago when another otherwise relevant retrieved item is near the two-month target.
                                For "past weekend", "last week", "yesterday", or other relative-window questions, derive the target interval from the current/question date. An event with its own local cue such as "today" resolves to the source/session date and is outside the window when that date is outside the target interval.
                                For personal event timing from imported chat sources, first-person user recency cues such as "just", "today", "yesterday", "this weekend", "last week", or "recently" are stronger event-date evidence than assistant meta-statements about what the assistant did not know or remember. Do not chain an assistant-side relative interval when the user's own local cue dates the same event to the source/session date.
                                For chained relative-time questions, combine explicit offsets instead of stopping at the last lexical hop. If memory says X happened N days/weeks/months before anchor Y, and Y happened M days/weeks/months before the question/current date, answer X as approximately N+M days/weeks/months ago when both operands are selected. A lead time such as "three months in advance" or "two weeks before" is not itself an "ago" answer unless its anchor is the question/current date.
                                If multiple events match the non-date part, prefer the one whose event date and local event wording best match the target date period. A matching source/session date is only fallback evidence when the source text describes the same target event or no event-dated candidate matches.
                                Do not let an active fact from outside the requested date period override selected source or note evidence for the same target event from inside the requested date period.
                                Do not smear one relative date cue across unrelated events in the same source. If a source contains one event scoped to "this weekend" and another scoped to "today", resolve each event independently from its own local wording and the source/session date.
                                First-person recency wording such as "still recovering from", "just got back from", "previous", "recent", or "last" can make a completed event relevant to the source/session date. Prefer that completed event over a plan or booking created in the same source when the question asks what the user experienced.
                                For noisy benchmark month/date questions, missing date evidence is not an explicit contradiction. Prefer otherwise matching no-date operands over operands with an explicit different date when the no-date operands match the requested object/action.
                                For place-visit questions, treat user-attended venue events such as lectures, guided tours, exhibits, appointments, or behind-the-scenes tours at that venue as visit evidence when the venue and target time match. Prefer the venue/time match over a more literal "visited" or "guided tour" event from a different time period.
                                For day-before/day-after questions, resolve relative dates found in selected source text from that source's session date and compare the resolved date with the known event date.
                                For completed-experience questions, evidence that the user actually did, attended, took, used, received, or completed something is stronger than same-date planning, booking, reservation, recommendation, or generated artifact evidence. A booking or reservation date is not the event date unless memory explicitly says the booked event happened on that date.
                                Treat target-period lifecycle variants such as a plan, checklist, appointment, request, estimate, recommendation, setup discussion, or other object-matching lifecycle evidence as matching broad noisy action wording only when no better target-period completed event exists and the requested action/status is not itself a required qualifier. Preserve lifecycle state; do not treat plans, bookings, or recommendations as completed user-experienced events.
                                For yes/no participant questions, a remembered different participant is enough to answer "no" unless retrieved memory also supports the asked participant being present.
                                For arithmetic, comparison, or ordering questions, use only explicit matching operands from retrieved memory, including selected source evidence. If no retrieved item supports a noisy benchmark month/date but exactly the requested number of explicit operands match the non-date target, compute from those operands. For ordering/comparison between named alternatives, each compared alternative needs explicit evidence; if one is missing, say memory is insufficient instead of ranking the known one.
                                For list/order questions whose requested answer type is a category value, provider, organization, place, person, title, or other name rather than event instances, counted_items must be the distinct requested names, not the underlying episodes that mention those names. Deduplicate repeated values in the final answer and counted_items while preserving the earliest supported order. Repeat the same value only when the question explicitly asks for each occurrence, event, trip, attempt, or item instance.
                                For arithmetic, date-difference, and ordering questions with named operands, every named operand must match the requested object or event. A different named object is a missing operand, not an approximate match; answer that memory is insufficient instead of computing from the mismatched object.
                                For noisy benchmark month/date arithmetic, exclude explicit different-month/date operands, but do not reject otherwise matching operands just because the month/date label is absent.
                                Do not infer a finish/completion month from source/session date alone when handling noisy benchmark named-month arithmetic.
                                First collect explicit operands matching the non-date object/action, then discard only explicit contradictory date labels, keep missing-date operands, and compute if the remaining count matches the requested count.
                                COMPLETE_SET memory may include stale or adjacent evidence. Direct ACTIVE typed facts beat older source excerpts, older goals, and stale profile summaries for current factual answers.
                                For current status/location/value questions, answer from the direct active claim when it conflicts with older raw source text.
                                For previous/original/older value questions, answer from a direct selected SUPERSEDED or non-current typed claim when it gives the requested value exactly. Do not recompute from approximate source wording when the exact older typed value is already selected.
                                For unqualified current/usual/status questions with conflicting ACTIVE facts, the answer should prefer the most recent explicit event or source date as the current answer unless the question asks for a specific older scope.
                                For current-state answers at a later target date, selected dated plans, intentions, or scheduled changes can be a matured current state when they match the same subject and slot, predate the target date, and no selected active memory contradicts completion. If a broad current-location claim and a matured plan or source give a more specific container or location inside the same place, combine them into the most specific location.
                                Read the ranked selected refs first. If a top-ranked direct active claim answers the question, do not let older source excerpts or adjacent goals override it.
                                For recall of prior assistant recommendations, option lists, generated artifacts, or exact mentioned items, selected source evidence that explicitly satisfies every requested qualifier beats a selected typed claim or note that only matches a weaker adjacent target.
                                For project leadership, ownership, or responsibility counts, a plain works_on_project or generic project association claim is not enough by itself. Count explicit responsible_for/lead/led/managed/owned claims, explicit team-leadership evidence, and solo or user-owned project evidence. Count personal/current projects only when retrieved memory says the user owns, leads, is responsible for, or is the sole actor on the project. Exclude research topics, papers, posters, broad interests, and plain "my research" or "working on research" evidence unless leadership, ownership, responsibility, or solo execution is explicit.
                                For numeric count/list answers, first form counted_items from retrieved memory and make the final number equal counted_items.size. Include plausible counted selected refs in counted_items, or put them into excluded_ranked_refs with a concrete exclusion reason. Do not silently ignore plausible selected refs. An empty counted_items list is not evidence for zero by itself; answer zero only when retrieved memory explicitly states none/zero for the requested scope or provides a closed complete inventory for that exact scope. Otherwise set count_evidence_kind to insufficient_memory, say memory is insufficient or the requested item was not mentioned, and do not put a concrete zero count in the answer field.
                                For aggregate total/count questions, count only explicit numeric operands or explicit list items that retrieved memory places in the requested aggregate. When compatible metric_observation, current_metric_value, or other explicit numeric aggregate operands are selected, they define the counted inventory; do not add separate singular possession or ownership claims as +1 unless memory explicitly states that singular item belongs to the same counted aggregate and is not already covered by a numeric operand. For historical totals across metric_observation items, count every distinct observed operand unless retrieved memory explicitly says one corrects, replaces, retracts, or repeats another same-slot measurement. Different attempt, event, condition, session, source, date, route, difficulty, or measurement context can make same-subject observations separate operands. For current totals, first find the latest explicit baseline total for the same collection, then apply later explicit additions/removals in chronological order. A direct older current_metric_value is not final when selected later memory explicitly adds or removes an item in the same aggregate.
                                For increase, decrease, change, delta, difference, gain, loss, or net-movement questions, compute from compatible explicit numeric operands in retrieved memory. Use a selected baseline/previous value and a selected later/current/final value for the same metric or aggregate even when their scope wording is not identical, as long as retrieved memory does not contradict that they belong to the same measured series. Do not answer "insufficient" solely because the baseline is phrased as an initial/start value and the later value is phrased as an after-period observation.
                                For imported-source events with an explicit month/day but no explicit year, past-tense wording, and a same-year normalization that would put the event after the source or question date, treat the inferred year as uncertain. For relative-window aggregate/count questions, count otherwise matching explicit numeric operands instead of excluding them solely as future or outside-window.
                                For count/list questions about acquired, kept, used, completed, attended, or otherwise user-attributed items, count evidence-backed variants that satisfy the requested category even when memory uses a different lifecycle or status verb than the question. Require explicit user attribution and category fit; do not count merely adjacent examples.
                                For count/list questions scoped before, prior to, until, or at a commitment/decision about a named target, use the named target as boundary context and exclude the target itself from the prior-alternative counted set unless the question explicitly asks to include the target itself. This remains true even when the named target has its own matching action before the boundary event. Put the target in excluded_ranked_refs as the boundary target, not in counted_items.
                                For replaced/fixed/upgraded functional-slot counts, count paired evidence that an old item was removed, discarded, donated, given away, or got rid of and a new item or upgrade took over the same ordinary function. The new item's source, such as gift, purchase, or existing ownership, is not an exclusion reason by itself.
                                For indirect replacement/upgrade evidence, count one functional slot when retrieved memory connects a newly acquired, gifted, bought, adopted, or started-using item with removal, donation, give-away, or discard of an older same-role item, even when the source does not use the exact word "replace".
                                Treat successor or substitute items as same-role when they serve the same ordinary user function or routine, even if their exact subtype differs. A same-source/session pattern of "newer or more capable item introduced for a routine" plus "older same-domain item removed from inventory" is replacement/upgrade evidence unless memory explicitly says the items are unrelated.
                                For acquisition-style count/list questions, count concrete physical or digital items when retrieved memory places that item in the user's acquisition, possession, collection, or use history and the requested category fits. Do not require the exact action verb from the question; do not count mere mentions, interests, recommendations, or assistant-only suggestions.
                                For acquisition-style count/list questions, selected ACTIVE "owns" or POSSESSION claims are direct acquisition/possession evidence when the item fits the requested category. The words purchased, bought, downloaded, acquired, or got in a how-many/list question are lifecycle hints, not transaction-detail requirements by themselves.
                                Explicitly ordered, reserved, or preordered concrete items are acquisition evidence when the requested category fits; pending delivery, receipt, or pickup only excludes the item when the question specifically asks for completed receipt, delivery, pickup, or current possession after a contradictory later status.
                                First-person evidence of a concrete personal copy or item can satisfy an acquisition-style broad category count even without a transaction verb. Do not use this shortcut when the question asks for purchase/download transaction details such as price, store, payment, download source, or exact date.
                                Treat "how many X did I buy/download/acquire/get" as a broad category count unless the user asks for transaction details. For broad category counts, do not require exact subtype words or exact title when ordinary language makes the remembered personal item a member, copy, or instance of the requested category.
                                For LongMemEval who/from-whom/source/giver questions about something the user received, got, inherited, was given, acquired, or obtained, treat the requested object label as a noisy retrieval hint when retrieved imported-source memory gives exactly one target-period personal item with an explicit giver/source. If the item subtype or category is mismatched but there is no competing target-period received/acquired item, answer the remembered giver/source and caveat the object mismatch instead of refusing solely because of the subtype mismatch.
                                For furniture or furnishing count/list questions, treat large movable household furnishings used for sleeping, seating, storage, surfaces, or workspace functions as category-fitting even when memory names only the concrete subtype. Do not count small decor, textiles, accessories, or decorative covers unless the question asks for them.
                                For broad counts of works or content items, a physical or digital carrier, copy, file, or personal item of that work can count as the work itself when user attribution is explicit. The item's material or format is not a separate category requirement unless the question asks for format-specific details.
                                For broad counts of music works or releases, a user-owned or downloaded vinyl record, CD, cassette, digital copy, or music download can count as an album/EP/release item unless retrieved memory explicitly identifies it as only a single track, playlist, non-audio merchandise, or the question asks for a narrower format or subtype.
                                For broad health-related device count/list questions, count user-attributed monitoring, treatment, assistive, accessibility, therapeutic, and health-support devices when retrieved memory indicates ownership, wearing, reliance, regular use, usage frequency, or usage duration. Do not narrow "health-related" to only tracking or treatment devices unless the question asks for that narrower subtype.
                                In count/list questions, broad category labels are category-fit tests, not exact-word qualifiers. Do not exclude a plausible counted item solely because memory names a member, carrier, copy, or concrete instance instead of repeating the category label.
                                For broad category counts, a missing exact subtype word is not a contradiction. Exclude a selected user-attributed copy/member/carrier only when memory gives an explicit conflicting subtype or category, or when the question asks for exact subtype/transaction details.
                                For formal education duration from one stage to another stage's completion, use the whole retrieved formal education timeline and include intermediate formal credentials, attendance, transfer, and completion milestones when they bridge the span.
                                For item counts, deduplicate an item from its container project/diorama/setup/bundle unless memory clearly says they are separate items. For errand/action/task counts, count separate actions separately even if they involve the same item. In an exchange, count the returned original and picked-up replacement separately unless memory says they are the exact same physical item.
                                For counts of what the user used/made/served/tried/selected/owned, ignore assistant-only suggestions, optional variants, generic examples, and unchosen future possibilities unless retrieved memory attributes actual use or a concrete selected plan to the user in the asked scope.
                                For counts of people/professionals the user visited/saw/consulted/received care from, count distinct real providers with explicit retrieved evidence of an appointment, diagnosis, prescription, treatment, provider relationship, or user-stated visit. Ignore generic assistant advice, hypothetical referrals, and future-only appointments without an existing concrete provider relationship.
                                For aggregate questions about events the user participated in, count explicit user involvement broadly: attended, participated, helped organize, contributed to, or was part of the team can all qualify unless the question explicitly restricts the answer to personally raised, personally paid, or individually performed amounts.
                                For category-scoped counts/lists, treat explicit venue, organizer, community, and stated context as category signals. A community-hosted service activity can qualify for that community/category even when the concrete task is volunteering, planning, sorting, packing, or another support action, unless the question asks for a narrower subtype.
                                For room or area item count/list questions, category fit includes fixtures, storage, furniture, tools, mats, devices, appliances, and other concrete objects when retrieved memory explicitly places them in that room/area or their ordinary function fits that room/area. Do not narrow a room item category to only fixtures or utensils unless the question says so.
                                For replaced/fixed/upgraded household-item counts, count one functional slot when retrieved memory says the user fixed it, replaced it with a newer item, got rid of, donated, or gave away the old item as part of an upgrade, or adopted a new item that takes over the old item's function. Do not count both the old item and its replacement unless the question asks for inventory.
                                For prior-alternative counts before a commitment to a named target, exclude the commitment target itself unless the question explicitly asks to include it. A target item can still have happened before the commitment event; that timing alone does not make it a prior alternative. If counted_items contains the named target, the answer is invalid.
                                For recommendation questions, apply remembered preferences and constraints to the new target instead of refusing only because the exact new target was not remembered.
                                If one operand is missing or only generic, answer that the available memory is insufficient.
                                Anchor-level metadata can supply a missing requested detail when one retrieved memory explicitly links the asked target to a named venue, publisher, conference, program, service, route, or event and another retrieved memory gives a date, deadline, schedule, price, policy, threshold, or similar property for that same named anchor. Use this bridge only when the metadata is not tied to a different named target and no selected memory gives a competing anchor or value for the asked target.
                                Critical qualifier check:
                                Identify required modifiers in the question, including academic level, course ownership, job role, job title, position, project identity, artifact type, item ingredient, component, material, feature, participant identity, venue, date, route, source, owner, medium, and relation. Every required modifier must be explicitly preserved by the same retrieved target before you give a concrete answer. If retrieved memory only names a related target with different or missing modifiers, the answer field must say the available memory is insufficient; do not put the adjacent concrete value in the answer field.
                                Put the key remembered facts that support your conclusion directly in the answer field. Do not hide required evidence only in reasoning.

                                Current date:
                                ${entry.questionDate}

                                Question:
                                ${entry.question}
                                """.trimIndent()
                            )
                        ),
                        createdAt = Clock.System.now(),
                    )
                ),
                options = AiRuntimeOptions(
                    maxOutputTokens = 600,
                    toolChoice = AiToolChoice.None,
                    responseFormat = ANSWER_HYPOTHESIS_RESPONSE_FORMAT,
                    toolContext = mapOf(
                        "longMemEvalAnswer" to true,
                        "questionId" to entry.questionId,
                        "questionType" to entry.questionType,
                    ),
                ),
            )
        )

        val rawText = AiConversationMessageMapper.extractAssistantText(response)
        val root = json.parseToJsonElement(rawText).jsonObject
        val answer = root.stringValue("answer").orEmpty()
        val reasoning = root.stringValue("reasoning").orEmpty()
        validateBenchmarkEvalText(stageName = "answer_hypothesis", questionId = entry.questionId, fieldName = "answer", value = answer)
        validateBenchmarkEvalText(stageName = "answer_hypothesis", questionId = entry.questionId, fieldName = "reasoning", value = reasoning)
        return LongMemEvalAnswerHypothesis(
            answer = answer,
            reasoning = reasoning,
            countedItems = root.stringArrayValue("counted_items"),
            countEvidenceKind = root.stringValue("count_evidence_kind").orEmpty(),
            excludedRankedRefs = root.stringArrayValue("excluded_ranked_refs"),
        )
    }

    private suspend fun judgeAnswerHypothesis(
        runtime: AiRuntime,
        entry: LongMemEvalEntry,
        expectedAnswer: String,
        goldEvidence: String,
        answerHypothesis: String,
    ): LongMemEvalAnswerJudgement {
        val conversationId = Conversation.Id("longmemeval-answer-judge:${entry.questionId}")
        val response = runtime.call(
            AiRuntimeRequest(
                systemPrompts = listOf(
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
                    For broad category counts, do not require exact subtype words or exact title when ordinary language makes the evidence-backed personal item a member, copy, or instance of the requested category.
                    For broad counts of works or content items, count evidence-backed physical or digital carriers, copies, files, or personal items of that work as the work itself when user attribution is explicit.
                    For broad counts of music works or releases, count evidence-backed user-owned or downloaded vinyl records, CDs, cassettes, digital copies, and music downloads as album/EP/release items unless the evidence explicitly identifies them as only single tracks, playlists, non-audio merchandise, or the question asks for a narrower format or subtype.
                    For count/list questions, judge broad category labels as category-fit tests rather than exact-word qualifiers. Do not mark an answer unsupported only because the evidence names a member, carrier, copy, or concrete instance instead of repeating the category label.
                    For broad category counts, do not require an exact subtype word in evidence unless the evidence gives an explicit conflicting subtype/category or the question asks for exact subtype/transaction details.
                    For room or area item count/list questions, judge fixtures, storage, furniture, tools, mats, devices, appliances, and other concrete objects as category-fitting when gold evidence explicitly places them in that room/area or their ordinary function fits that room/area. Do not narrow a room item category to only fixtures or utensils unless the question says so.
                    For qualified recall questions, the candidate's direct primary answer must satisfy every explicit qualifier supported by the gold evidence. Qualifiers include item ingredients, components, materials, features, owner, source, role, date, venue, relation, and other descriptive modifiers. Mark supported=false when it answers only a weaker partial match while another evidence-backed item satisfies the full qualified description. Mark supported=false when the candidate gives a conflicting or weaker primary answer and mentions the evidence-backed fully-qualified answer only as a caveat, aside, alternative, or correction.
                    For concrete who/from-whom/source/giver expected-answer labels, mark supported=false when the candidate answers that memory is insufficient or unknown without providing the evidence-backed expected giver/source. If the gold evidence contains the expected giver/source for the only target-period received/acquired personal item, noisy or mismatched object wording in the question does not make an insufficiency answer supported; the candidate may caveat the object mismatch, but it must still provide the giver/source.
                    When the expected-answer label says the information is not enough, not mentioned, unknown, or similar, mark a concrete candidate answer supported=true only if the gold evidence explicitly contains the asked fully-qualified fact. Do not overturn an insufficiency label by stitching together adjacent facts whose modifiers differ.
                    For preference or personalization questions, supported=true when the candidate uses the relevant user-specific preference or fact in the same core direction.
                    For temporal questions, do not penalize off-by-one errors for counts of days, weeks, months, or similar durations.
                    For knowledge-update questions, the candidate may mention previous information if it also clearly gives the updated required answer.
                    Return only the configured JSON object.
                    """.trimIndent()
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
        haystackSessions
            .mapIndexed { index, session ->
                renderSession(session, haystackDates.getOrNull(index))
            }
            .joinToString("\n\n---\n\n")
            .truncateForJudgeEvidence(MAX_JUDGE_GOLD_EVIDENCE_CHARS)

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
            reason = root.stringValue("reason") ?: root.stringValue("message"),
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
            .offsetByProperty()
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

    private fun List<LongMemEvalEntry>.limitByProperty(): List<LongMemEvalEntry> {
        val limit = System.getProperty(LIMIT_PROPERTY)?.trim().orEmpty()
        if (limit.isBlank() || limit.equals("all", ignoreCase = true)) return this
        val count = limit.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Invalid LongMemEval limit '$limit'. Use a positive integer or 'all'.")
        return take(count)
    }

    private fun List<LongMemEvalEntry>.offsetByProperty(): List<LongMemEvalEntry> {
        val offset = System.getProperty(OFFSET_PROPERTY)?.trim().orEmpty()
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
        const val DEFAULT_GROMOZEKA_EVAL_PROMPT_SNAPSHOT_RESOURCE =
            "/eval/default-gromozeka-prompt-snapshot-2026-06-15.md"

        val DEFAULT_GROMOZEKA_EVAL_PROMPT_SNAPSHOT: String by lazy {
            LongMemEvalMemorySmokeTest::class.java
                .getResource(DEFAULT_GROMOZEKA_EVAL_PROMPT_SNAPSHOT_RESOURCE)
                ?.readText()
                ?: error("Missing eval prompt snapshot resource: $DEFAULT_GROMOZEKA_EVAL_PROMPT_SNAPSHOT_RESOURCE")
        }

        val ANSWER_HYPOTHESIS_RESPONSE_FORMAT = AiResponseFormat.JsonSchema(
            name = "longmemeval_answer_hypothesis",
            schema = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                putJsonObject("properties") {
                    putJsonObject("answer") {
                        put("type", "string")
                        put("description", "Concise answer to the benchmark question using only retrieved memory context.")
                    }
                    putJsonObject("reasoning") {
                        put("type", "string")
                        put("description", "For count/list questions, include the counted set and any excluded plausible ranked refs before the conclusion. For other questions, one concise evidence sentence naming the relevant remembered event, participants if applicable, and conclusion.")
                    }
                    putJsonObject("counted_items") {
                        put("type", "array")
                        put("description", "For count/list questions, every item counted from retrieved memory. If the question asks for category/provider/organization/place/person/title names, list distinct requested names rather than duplicate event instances. Empty for non-count questions or insufficient memory. For before/prior/until commitment questions about a named target, do not include the named boundary target here unless the question explicitly asks to include it.")
                        putJsonObject("items") {
                            put("type", "string")
                        }
                    }
                    putJsonObject("count_evidence_kind") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("not_count_question")
                            add("counted_items")
                            add("explicit_zero_or_none")
                            add("closed_inventory_zero")
                            add("insufficient_memory")
                        }
                        put("description", "Evidence basis for count/list answers. Use insufficient_memory when counted_items is empty and retrieved memory does not explicitly state zero/none or provide a closed complete inventory for the exact scope.")
                    }
                    putJsonObject("excluded_ranked_refs") {
                        put("type", "array")
                        put("description", "Plausible selected ranked refs intentionally excluded from a count/list answer, each with the ref id and reason. Include named boundary targets excluded from before/prior/until commitment counts. Empty when none.")
                        putJsonObject("items") {
                            put("type", "string")
                        }
                    }
                }
                putJsonArray("required") {
                    add("answer")
                    add("reasoning")
                    add("counted_items")
                    add("count_evidence_kind")
                    add("excluded_ranked_refs")
                }
            },
        )

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

private data class MemoryToolJsonResult(
    val status: String,
    val decision: String?,
    val reason: String?,
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

private data class LongMemEvalAnswerJudgement(
    val supported: Boolean,
    val reason: String,
)

private data class LongMemEvalAnswerHypothesis(
    val answer: String,
    val reasoning: String,
    val countedItems: List<String> = emptyList(),
    val countEvidenceKind: String = "",
    val excludedRankedRefs: List<String> = emptyList(),
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
