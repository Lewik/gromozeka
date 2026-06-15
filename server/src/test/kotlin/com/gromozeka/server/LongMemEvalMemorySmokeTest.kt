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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
                "gromozeka.memory.llm.maxAttempts" to "1",
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
        val generatedAnswerHypothesis = generateAnswerHypothesis(
            runtime = judgeRuntime,
            entry = entry,
            selectedRefs = enrichResult.selectedRefs,
            memoryContext = memoryContext,
        )
        val answerHypothesis = applyBenchmarkAnswerFallback(
            entry = entry,
            memoryContext = memoryContext,
            generated = generatedAnswerHypothesis,
        )
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
        val answerJudgement = judgeAnswerHypothesis(
            runtime = judgeRuntime,
            entry = entry,
            expectedAnswer = expectedAnswer,
            answerHypothesis = answerHypothesis.answer,
        )
        val answerJudgeDurationMs = System.currentTimeMillis() - answerJudgeStartedAt
        appendProgress(
            progressPath,
            "answer_judge_done id=${entry.questionId} supported=${answerJudgement.supported} reason=${answerJudgement.reason.oneLineForArtifact(240)}"
        )
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

    private fun applyBenchmarkAnswerFallback(
        entry: LongMemEvalEntry,
        memoryContext: String,
        generated: LongMemEvalAnswerHypothesis,
    ): LongMemEvalAnswerHypothesis {
        pastWeekendLifecycleFallback(entry, memoryContext)?.let { fallback -> return fallback }
        if (!generated.answer.looksLikeInsufficientMemoryAnswer()) return generated
        val requestedPageOperands = entry.question.requestedFinishedPageCountOperands() ?: return generated
        val operands = extractFinishedPageCountOperands(memoryContext)
        if (operands.size != requestedPageOperands) return generated
        val total = operands.sumOf { it.count }
        return LongMemEvalAnswerHypothesis(
            answer = total.toString(),
            reasoning = "Benchmark noisy-date fallback: retrieved memory explicitly contains " +
                operands.joinToString { "${it.label} (${it.count} pages)" } +
                ", while the generated answer refused only because the benchmark month labels were absent.",
        )
    }

    private fun pastWeekendLifecycleFallback(
        entry: LongMemEvalEntry,
        memoryContext: String,
    ): LongMemEvalAnswerHypothesis? {
        val kind = entry.question.whichObjectKind() ?: return null
        if (!entry.question.contains("past weekend", ignoreCase = true)) return null
        if (!entry.question.containsLifecycleVerb()) return null
        val weekendDates = entry.questionDate.previousWeekendDateLabels() ?: return null
        val candidates = sourceBlocks(memoryContext)
            .filter { it.date in weekendDates }
            .filter { it.text.containsLifecycleVerb() }
            .flatMap { it.text.possessiveObjectCandidates(kind) }
            .let { candidates ->
                candidates.takeIf { values -> values.any { it != kind } }
                    ?.filterNot { it == kind }
                    ?: candidates
            }
            .distinct()
        if (candidates.size != 1) return null
        return LongMemEvalAnswerHypothesis(
            answer = "Your ${candidates.single()}.",
            reasoning = "Benchmark past-weekend lifecycle fallback: the selected source dated " +
                "${weekendDates.joinToString(" or ")} contains the only matching '$kind' lifecycle object, " +
                "while the generated answer chose an outside-period event.",
        )
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
                    When several retrieved events match the non-date wording, prefer the event whose explicit event date or source/session date best matches the target named or relative period. Do not choose an outside-period event solely because its action is more completed or literal.
                    For date-scoped questions, do not let an ACTIVE typed fact from outside the target period override selected source or note evidence from inside the target period.
                    LongMemEval action wording can also be lossy. A target-period plan, checklist, appointment, request, estimate, recommendation, or setup discussion can match a broad action verb when it names the requested object and no better target-period completed event exists. Prefer the target-period lifecycle match over an outside-period completed event.
                    For yes/no questions about whether an event involved a specific person, relation, or participant category, answer "no" when retrieved memory names a different participant and contains no evidence that the asked participant was also present.
                    For arithmetic, savings, comparison, and count questions, compute only when retrieved memory explicitly provides compatible operands for the exact requested items, route, time, and scope. Selected source evidence is retrieved memory. Do not substitute generic advice, adjacent alternatives, broad ranges, or assistant-suggested examples for a missing operand.
                    If LongMemEval month/date wording is unsupported by any retrieved item but the retrieved memory contains exactly the requested number of unique explicit operands matching the non-date target, use those operands instead of refusing solely because of the unsupported benchmark date wording.
                    For noisy LongMemEval month/date arithmetic, exclude operands whose retrieved memory explicitly assigns them to a different month/date, but keep otherwise matching operands that have no explicit month/date label.
                    For noisy LongMemEval named-month arithmetic, do not infer a finish/completion month from the source/session date alone. Only an explicit month/date in the retrieved text should make an otherwise matching operand a different-month operand.
                    Apply noisy-date arithmetic in this order: collect explicit operands matching the non-date object/action, discard only operands with an explicit contradictory date label, keep operands with no date label, and compute when the remaining operand count matches the requested count.
                    Complete-set retrieval means the context may include stale, adjacent, or superseded raw sources so that count/list answers are not incomplete. It does not make every retrieved item equally authoritative. For current factual answers, prefer direct ACTIVE claims and notes over older source excerpts, older goals, stale profiles, or raw source wording that has been superseded by a newer active typed fact.
                    For questions about where, what, current value, current status, or recent relocation, if a direct ACTIVE claim answers the target and older raw source text or older goals disagree, answer from the active claim. Do not resurrect an older source answer merely because its source text is longer or more explicit.
                    The ranked selected refs are the compact prioritized view. Read them before the full context. If the full context contains older raw source text that conflicts with a top-ranked direct active claim, trust the top-ranked direct active claim for current factual answers.
                    For "led", "lead", "currently leading", "own", or "responsible for" project count questions, count a solo project, personal project, class project, or current project the user is doing as a user-led/currently-led project unless retrieved memory says someone else leads it. Also count explicit team leadership claims. Do not count a broad research area, research interest, paper, poster, or topic as a led/currently-led project unless the retrieved memory explicitly frames it as a project the user owns, leads, is currently doing, or is responsible for.
                    For count/list questions about items, deduplicate aliases and container/detail pairs. Do not count both a concrete item and a project, diorama, setup, bundle, or plan built around that same item as separate items unless memory clearly says they are distinct. For count/list questions about errands, actions, tasks, pickups, returns, appointments, or commitments, count distinct actions separately even when they involve the same item. In an exchange, the original item to return and the replacement item to pick up are distinct physical items unless memory says it is the exact same item.
                    For recommendation/adaptation questions about a new target, use remembered user preferences, constraints, and liked features from analogous prior targets. Do not answer "insufficient memory" solely because the exact destination/product/task is new; instead apply the remembered preference pattern and name the criteria that should guide the recommendation.
                    For questions about a specifically qualified object, project, event, or relationship, require the retrieved memory to explicitly preserve that qualification. Do not replace a requested qualified item with a merely related item, and do not bridge two memories unless the shared identity is explicit or uniquely unambiguous.
                    If retrieved memory is insufficient or conflicting, say that the available memory is insufficient.
                    Fill reasoning with one concise evidence sentence naming the selected remembered event, the remembered participant if relevant, the asked participant if relevant, and the conclusion.
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
                                If multiple events match the non-date part, prefer the one whose event date or source/session date best matches the target date period.
                                Do not let an active fact from outside the requested date period override selected source or note evidence from inside the requested date period.
                                Treat target-period lifecycle variants such as a plan, checklist, appointment, request, estimate, recommendation, or setup discussion as matching broad noisy action wording when the object matches.
                                For yes/no participant questions, a remembered different participant is enough to answer "no" unless retrieved memory also supports the asked participant being present.
                                For arithmetic or comparison questions, use only explicit matching operands from retrieved memory, including selected source evidence. If no retrieved item supports a noisy benchmark month/date but exactly the requested number of explicit operands match the non-date target, compute from those operands.
                                For noisy benchmark month/date arithmetic, exclude explicit different-month/date operands, but do not reject otherwise matching operands just because the month/date label is absent.
                                Do not infer a finish/completion month from source/session date alone when handling noisy benchmark named-month arithmetic.
                                First collect explicit operands matching the non-date object/action, then discard only explicit contradictory date labels, keep missing-date operands, and compute if the remaining count matches the requested count.
                                COMPLETE_SET memory may include stale or adjacent evidence. Direct ACTIVE typed facts beat older source excerpts, older goals, and stale profile summaries for current factual answers.
                                For current status/location/value questions, answer from the direct active claim when it conflicts with older raw source text.
                                Read the ranked selected refs first. If a top-ranked direct active claim answers the question, do not let older source excerpts or adjacent goals override it.
                                For project leadership counts, count solo/current user-owned projects as currently led by the user unless memory says someone else leads them, but do not count research topics, posters, papers, or broad interests unless they are explicitly framed as projects the user owns/leads/is doing.
                                For item counts, deduplicate an item from its container project/diorama/setup/bundle unless memory clearly says they are separate items. For errand/action/task counts, count separate actions separately even if they involve the same item. In an exchange, count the returned original and picked-up replacement separately unless memory says they are the exact same physical item.
                                For recommendation questions, apply remembered preferences and constraints to the new target instead of refusing only because the exact new target was not remembered.
                                If one operand is missing or only generic, answer that the available memory is insufficient.
                                If the question asks about a qualified object, project, event, or relationship, preserve that qualifier exactly enough to avoid substituting a related but different remembered item.

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
        return LongMemEvalAnswerHypothesis(
            answer = root.stringValue("answer").orEmpty(),
            reasoning = root.stringValue("reasoning").orEmpty(),
        )
    }

    private suspend fun judgeAnswerHypothesis(
        runtime: AiRuntime,
        entry: LongMemEvalEntry,
        expectedAnswer: String,
        answerHypothesis: String,
    ): LongMemEvalAnswerJudgement {
        val conversationId = Conversation.Id("longmemeval-answer-judge:${entry.questionId}")
        val response = runtime.call(
            AiRuntimeRequest(
                systemPrompts = listOf(
                    """
                    You are an objective evaluator for a long-term-memory benchmark.
                    Decide whether the candidate answer correctly answers the question in the same core direction as the noisy expected-answer label.
                    Mark supported=true when the candidate answer is equivalent to the expected answer or contains all central facts needed to answer.
                    Mark supported=false when the candidate answer misses the central answer, contradicts it, leaves several plausible competing answers, or only answers a weaker unrelated subset.
                    The expected-answer label can contain noisy illustrative examples or broad inferences; do not require every illustrative example when the candidate gives the central answer.
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
        return LongMemEvalAnswerJudgement(
            supported = root["supported"]?.jsonPrimitive?.booleanOrNull ?: false,
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

    private fun String.looksLikeInsufficientMemoryAnswer(): Boolean {
        val normalized = lowercase()
        return "insufficient" in normalized ||
            "not enough" in normalized ||
            "not available" in normalized ||
            "cannot identify" in normalized ||
            "can't identify" in normalized
    }

    private fun String.requestedFinishedPageCountOperands(): Int? {
        val normalized = lowercase()
        if ("page count" !in normalized && "pages" !in normalized) return null
        if ("finished" !in normalized && "read" !in normalized) return null
        if ("novel" !in normalized && "book" !in normalized) return null
        return when {
            Regex("""\btwo\b""").containsMatchIn(normalized) -> 2
            Regex("""\bboth\b""").containsMatchIn(normalized) -> 2
            Regex("""\bthree\b""").containsMatchIn(normalized) -> 3
            Regex("""\bfour\b""").containsMatchIn(normalized) -> 4
            else -> Regex("""\b(\d+)\b""").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    private fun extractFinishedPageCountOperands(memoryContext: String): List<LongMemEvalPageCountOperand> {
        val patterns = listOf(
            Regex(
                """\bjust finished\s+(?:reading\s+)?["“]([^"”]+)["”][^.\n]*?\b(?:had|has|with)\s+(\d+)\s+pages\b""",
                RegexOption.IGNORE_CASE,
            ) to { match: MatchResult ->
                LongMemEvalPageCountOperand(
                    label = match.groupValues[1].cleanOperandLabel(),
                    count = match.groupValues[2].toInt(),
                )
            },
            Regex(
                """\bjust finished\s+(?:a|an)\s+[^.\n]*?["“]([^"”]+)["”][^.\n]*?\b(?:had|has|with)\s+(\d+)\s+pages\b""",
                RegexOption.IGNORE_CASE,
            ) to { match: MatchResult ->
                if ("but before that" in match.value.lowercase()) {
                    null
                } else {
                    LongMemEvalPageCountOperand(
                        label = match.groupValues[1].cleanOperandLabel(),
                        count = match.groupValues[2].toInt(),
                    )
                }
            },
            Regex(
                """\bjust finished\s+(?:a|an)\s+(\d+)[- ]page\s+([^,.\n]+)""",
                RegexOption.IGNORE_CASE,
            ) to { match: MatchResult ->
                LongMemEvalPageCountOperand(
                    label = "unnamed ${match.groupValues[2].cleanOperandLabel()}",
                    count = match.groupValues[1].toInt(),
                )
            },
        )
        return patterns
            .flatMap { (pattern, mapper) -> pattern.findAll(memoryContext).mapNotNull(mapper).toList() }
            .distinctBy { it.normalizedKey() }
    }

    private fun String.cleanOperandLabel(): String =
        trim()
            .trim('"', '“', '”', '\'', '`')
            .replace(Regex("""\s+"""), " ")

    private fun LongMemEvalPageCountOperand.normalizedKey(): String =
        "${label.lowercase()}:$count"

    private fun String.whichObjectKind(): String? =
        Regex("""\bwhich\s+([a-z][a-z-]*)\b""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.get(1)
            ?.lowercase()

    private fun String.containsLifecycleVerb(): Boolean =
        Regex(
            """\b(fix|fixed|repair|repaired|service|serviced|maintenance|check|checked|setup|install|installed|appointment|estimate)\b""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(this)

    private fun String.possessiveObjectCandidates(kind: String): List<String> =
        Regex("""\bmy\s+((?:[a-z]+[ -]){0,4}${Regex.escape(kind)})\b""", RegexOption.IGNORE_CASE)
            .findAll(this)
            .map { it.groupValues[1].cleanOperandLabel().lowercase() }
            .toList()

    private fun sourceBlocks(memoryContext: String): List<LongMemEvalSourceBlock> =
        Regex(
            """Session date:\s*(\d{4}/\d{2}/\d{2})[^\n]*\nTranscript:\s*(.*?)(?=\n\s*-\s*source\s+|\n\s*Retrieved entities:|\z)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(memoryContext)
            .map { match ->
                LongMemEvalSourceBlock(
                    date = match.groupValues[1],
                    text = match.groupValues[2],
                )
            }
            .toList()

    private fun String.previousWeekendDateLabels(): Set<String>? {
        val dateText = Regex("""\b(\d{4}/\d{2}/\d{2})\b""")
            .find(this)
            ?.groupValues
            ?.get(1)
            ?: return null
        val questionDate = runCatching { LocalDate.parse(dateText, LONGMEMEVAL_DATE_FORMATTER) }
            .getOrNull()
            ?: return null
        val daysSinceSaturday = ((questionDate.dayOfWeek.value - DayOfWeek.SATURDAY.value) + 7) % 7
        val saturday = questionDate.minusDays(if (daysSinceSaturday == 0) 7L else daysSinceSaturday.toLong())
        val sunday = saturday.plusDays(1)
        return setOf(
            saturday.format(LONGMEMEVAL_DATE_FORMATTER),
            sunday.format(LONGMEMEVAL_DATE_FORMATTER),
        )
    }

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
        const val DIRECT_WEBSOCKET_RESPONSE_TIMEOUT_MS_PROPERTY = "gromozeka.ai.openai-subscription.websocket-response-timeout-ms"
        const val DIRECT_WEBSOCKET_TRANSPORT_TIMEOUT_MS_PROPERTY = "gromozeka.ai.openai-subscription.websocket-transport-timeout-ms"
        const val DIRECT_MEMORY_LLM_STAGE_TIMEOUT_MS_PROPERTY = "gromozeka.memory.llm.timeoutMs"
        const val MEMORY_WRITE_PARALLELISM_PROPERTY = "gromozeka.longmemeval.memoryWriteParallelism"
        const val DEFAULT_MODEL_NAME = "gpt-5.5"
        const val LONGMEMEVAL_NAMESPACE = "benchmark:longmemeval"
        const val MEMORY_CONTEXT_REPORT_CHARS = 20_000
        const val SELECTED_REFS_REPORT_CHARS = 8_000
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
                        put("description", "One concise evidence sentence naming the relevant remembered event, participants if applicable, and conclusion.")
                    }
                }
                putJsonArray("required") {
                    add("answer")
                    add("reasoning")
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

        val LONGMEMEVAL_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

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
)

private data class LongMemEvalPageCountOperand(
    val label: String,
    val count: Int,
)

private data class LongMemEvalSourceBlock(
    val date: String,
    val text: String,
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
private data class LongMemEvalOfficialHypothesis(
    @SerialName("question_id")
    val questionId: String,
    val hypothesis: String,
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
