package com.gromozeka.server

import com.gromozeka.application.service.ConversationEngineService
import com.gromozeka.application.service.MemoryToolApplicationService
import com.gromozeka.application.service.memory.MemoryEmbeddingIndexer
import com.gromozeka.application.service.memory.MemoryMaintenanceTraceEvent
import com.gromozeka.application.service.memory.MemoryMaintenanceTraceSink
import com.gromozeka.application.service.memory.MemoryReadTraceEvent
import com.gromozeka.application.service.memory.MemoryReadTraceSink
import com.gromozeka.application.service.memory.MemoryWriteTraceEvent
import com.gromozeka.application.service.memory.MemoryWriteTraceSink
import com.gromozeka.application.service.memory.NoOpMemoryEmbeddingIndexer
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiRuntimeOverrides
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryClaimReconciliationOp
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryEpisodeCandidate
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryNoteLifecycleOp
import com.gromozeka.domain.model.memory.MemoryNoteReconciliationOp
import com.gromozeka.domain.model.memory.MemoryPredicateDefinition
import com.gromozeka.domain.model.memory.MemoryProfile
import com.gromozeka.domain.model.memory.MemoryReadTrace
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.model.memory.MemoryActionItemUpdateOp
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.model.memory.NoteConsolidationResult
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.model.Settings
import com.gromozeka.server.testsupport.app.ServerTestHarness
import com.gromozeka.server.testsupport.app.sanitizePathSegment
import com.gromozeka.server.testsupport.llm.AiRuntimeCassetteSettings
import com.gromozeka.server.testsupport.llm.AiRuntimeCassetteUsageRegistry
import com.gromozeka.server.testsupport.llm.AiRuntimeCassetteUsageReporter
import com.gromozeka.shared.uuid.uuid7
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import klog.KLoggers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.junit.jupiter.api.Timeout

class MemoryRealModelE2eTest {

    @Test
    @Timeout(value = 90, unit = TimeUnit.MINUTES)
    fun runMemoryE2eWithRealModel() {
        if (!java.lang.Boolean.getBoolean(ENABLE_PROPERTY)) {
            println(
                "Skipping real-model memory e2e. Run with -D$ENABLE_PROPERTY=true " +
                    "to execute the live scenarios."
            )
            return
        }

        val subscriptionPath = resolveSubscriptionConfigPath()
        val subscriptionSession = ServerTestHarness.subscriptionSessionFromConfig(subscriptionPath)
        assertNotNull(subscriptionSession, "OpenAI subscription config not found or incomplete: $subscriptionPath")

        val cases = loadCases()
        assertTrue(cases.isNotEmpty(), "No memory e2e cases were loaded")

        AiRuntimeCassetteUsageRegistry.reset()
        val cassetteSettings = AiRuntimeCassetteSettings.fromSystemProperties()
        val modelName = System.getProperty(MODEL_NAME_PROPERTY)?.trim().orEmpty().ifBlank { DEFAULT_MODEL_NAME }
        val postgresSchema = "memory_e2e_${uuid7().replace("-", "_")}"
        val runId = "${Clock.System.now()}-$postgresSchema".sanitizePathSegment()
        val settings = Settings(
            userProfile = ServerTestHarness.openAiSubscriptionProfile(modelName).copy(
                memorySettings = UserProfile.MemorySettings(
                    autoRemember = true,
                    autoRecall = true,
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
            ),
            additionalSources = listOf(MemoryRealModelE2eNoToolsConfig::class.java),
        ).use { harness ->
            val context = harness.context
            val conversationService = context.getBean(ConversationDomainService::class.java)
            val conversationEngineService = context.getBean(ConversationEngineService::class.java)
            val memoryToolApplicationService = context.getBean(MemoryToolApplicationService::class.java)
            val promptDomainService = context.getBean(PromptDomainService::class.java)
            val store = context.getBean(MemoryStore::class.java)
            val traceCollector = context.getBean(MemoryE2eReadTraceCollector::class.java)
            val writeTraceCollector = context.getBean(MemoryE2eWriteTraceCollector::class.java)
            val maintenanceTraceCollector = context.getBean(MemoryE2eMaintenanceTraceCollector::class.java)
            val artifactRootDirectory = Path.of("build", "test-artifacts", "memory-e2e")
                .resolve(this::class.simpleName.orEmpty().sanitizePathSegment())
            Files.createDirectories(artifactRootDirectory)
            val artifactDirectory = artifactRootDirectory.resolve(runId)
            Files.createDirectories(artifactDirectory)
            artifactRootDirectory.resolve("latest-run.txt").writeText(artifactDirectory.toAbsolutePath().normalize().toString())
            val progressPath = artifactDirectory.resolve("progress.log")
            progressPath.writeText("")
            appendProgress(
                progressPath,
                "suite_start postgresSchema=$postgresSchema model=$modelName cases=${cases.size} subscription=$subscriptionPath artifacts=$artifactDirectory"
            )

            val failures = mutableListOf<String>()
            runBlocking {
                cases.forEachIndexed { caseIndex, case ->
                    val caseStartedAt = System.currentTimeMillis()
                    appendProgress(
                        progressPath,
                        "case_start index=${caseIndex + 1}/${cases.size} id=${case.id} category=${case.category} timeoutSeconds=${case.timeoutSeconds}"
                    )
                    val result = runCatching {
                        withTimeout(case.timeoutSeconds * 1_000L) {
                            executeCase(
                                harness = harness,
                                conversationService = conversationService,
                                conversationEngineService = conversationEngineService,
                                memoryToolApplicationService = memoryToolApplicationService,
                                promptDomainService = promptDomainService,
                                store = store,
                                case = case,
                                modelName = modelName,
                                progressPath = progressPath,
                                traceCollector = traceCollector,
                                writeTraceCollector = writeTraceCollector,
                                maintenanceTraceCollector = maintenanceTraceCollector,
                            )
                        }
                    }.getOrElse { error ->
                        val reportPath = artifactDirectory.resolve("${case.id.sanitizePathSegment()}-startup-failure.md")
                        val report = renderCaseStartupFailure(
                            case = case,
                            modelName = modelName,
                            postgresSchema = postgresSchema,
                            error = error,
                        )
                        reportPath.writeText(report)
                        appendProgress(
                            progressPath,
                            "case_error index=${caseIndex + 1}/${cases.size} id=${case.id} durationMs=${System.currentTimeMillis() - caseStartedAt} error=${error::class.simpleName}:${error.message.oneLineForProgressLog(300)} report=$reportPath"
                        )
                        failures += "${case.id} [${case.category}] failed before assertions: ${error::class.simpleName}: ${error.message}\nArtifact: $reportPath"
                        return@forEachIndexed
                    }
                    val errors = evaluateCase(result)
                    val reportPath = artifactDirectory.resolve("${case.id.sanitizePathSegment()}-report.md")
                    reportPath.writeText(renderCaseReport(result, errors))
                    println("Memory e2e report saved to: $reportPath")

                    if (errors.isNotEmpty()) {
                        appendProgress(
                            progressPath,
                            "case_fail index=${caseIndex + 1}/${cases.size} id=${case.id} durationMs=${System.currentTimeMillis() - caseStartedAt} errors=${errors.size} report=$reportPath"
                        )
                        failures += buildString {
                            append(case.id)
                            append(" [")
                            append(case.category)
                            append("] failed:")
                            errors.forEach { error ->
                                appendLine()
                                append("- ")
                                append(error)
                            }
                            appendLine()
                            append("Artifact: ")
                            append(reportPath)
                        }
                    } else {
                        appendProgress(
                            progressPath,
                            "case_pass index=${caseIndex + 1}/${cases.size} id=${case.id} durationMs=${System.currentTimeMillis() - caseStartedAt} report=$reportPath"
                        )
                    }
                }
            }

            val summaryPath = artifactDirectory.resolve("summary.md")
            summaryPath.writeText(
                renderSuiteSummary(
                    postgresSchema = postgresSchema,
                    subscriptionPath = subscriptionPath,
                    modelName = modelName,
                    caseCount = cases.size,
                    failures = failures,
                )
            )
            copyDevLogArtifact(
                source = harness.homeDirectory.resolve("logs").resolve("dev.log"),
                target = artifactDirectory.resolve("$postgresSchema-dev.log"),
            )
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
            appendProgress(
                progressPath,
                "suite_end postgresSchema=$postgresSchema status=${if (failures.isEmpty()) "PASS" else "FAIL"} failures=${failures.size}"
            )

            if (failures.isNotEmpty()) {
                fail(failures.joinToString("\n\n"))
            }
        }
    }

    private fun loadCases(): List<MemoryE2eCase> {
        val casesDirectory = resolveCasesDirectory()
        check(casesDirectory.exists()) { "Memory e2e cases directory does not exist: $casesDirectory" }

        val files = mutableListOf<Path>()
        Files.newDirectoryStream(casesDirectory, "*.json").use { directory ->
            directory.forEach(files::add)
        }

        val loaded = files
            .sortedBy { it.fileName.toString() }
            .map { path -> json.decodeFromString<MemoryE2eCase>(path.readText()) }

        val filters = System.getProperty(CASE_FILTER_PROPERTY)
            ?.split(",")
            .orEmpty()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(String::lowercase)

        if (filters.isEmpty()) {
            return loaded
        }

        return loaded.filter { case ->
            val haystacks = listOf(case.id, case.category, case.description).map(String::lowercase)
            filters.any { filter -> haystacks.any { filter in it } }
        }
    }

    private suspend fun executeCase(
        harness: ServerTestHarness,
        conversationService: ConversationDomainService,
        conversationEngineService: ConversationEngineService,
        memoryToolApplicationService: MemoryToolApplicationService,
        promptDomainService: PromptDomainService,
        store: MemoryStore,
        case: MemoryE2eCase,
        modelName: String,
        progressPath: Path,
        traceCollector: MemoryE2eReadTraceCollector,
        writeTraceCollector: MemoryE2eWriteTraceCollector,
        maintenanceTraceCollector: MemoryE2eMaintenanceTraceCollector,
    ): ExecutedMemoryE2eCase {
        val projectPath = harness.homeDirectory
            .resolve("projects")
            .resolve(case.id.sanitizePathSegment())
        Files.createDirectories(projectPath)

        val prompt = promptDomainService.createEnvironmentPrompt(
            name = "Memory E2E system prompt",
            content = """
                You are a concise assistant inside a Gromozeka memory e2e test.
                Answer user questions directly.
                Do not use tools.
                If the question asks for only a short value, return only that value.
            """.trimIndent(),
        )
        val agent = buildAgent(modelName, prompt.id)
        val conversations = mutableMapOf<String, Conversation>()
        val seedResults = mutableListOf<ExecutedSeedTurn>()
        suspend fun conversation(sessionId: String): Conversation {
            conversations[sessionId]?.let { return it }
            val created = conversationService.create(
                projectPath = projectPath.absolutePathString(),
                displayName = "${case.id}::$sessionId",
                agentDefinitionId = agent.id,
            )
            conversations[sessionId] = created
            return created
        }

        if (case.preloadedMemory.isNotEmpty()) {
            val fixtureConversation = conversation("preloaded-memory")
            val fixtureNamespace = resolveNamespace(conversationService, conversations, projectPath.absolutePathString(), agent)
            val fixtureBatch = case.preloadedMemory.toMemoryUpdateBatch(
                caseId = case.id,
                namespace = fixtureNamespace,
                conversationId = fixtureConversation.id,
            )
            store.apply(fixtureBatch)
            appendProgress(
                progressPath,
                "preloaded_memory case=${case.id} fixtures=${case.preloadedMemory.size} namespace=${fixtureNamespace.value} " +
                    "sources=${fixtureBatch.sources.size} entities=${fixtureBatch.entities.size} predicates=${fixtureBatch.predicateDefinitions.size} claims=${fixtureBatch.claims.size}"
            )
        }

        case.seedSessions.forEach { session ->
            val currentConversation = conversation(session.id)
            session.turns.forEachIndexed { turnIndex, turnElement ->
                val turn = turnElement.toSeedTurn(case.id, session.id, turnIndex)
                val text = turn.progressText()
                val turnStartedAt = System.currentTimeMillis()
                appendProgress(
                    progressPath,
                    "seed_turn_start case=${case.id} session=${session.id} turn=${turnIndex + 1}/${session.turns.size} chars=${text.length} preview=${text.oneLineForProgressLog()}"
                )
                runCatching {
                    if (turn.isProvidedContent()) {
                        val namespaceValue = turn.namespace
                            ?: resolveNamespace(
                                conversationService = conversationService,
                                conversations = conversations,
                                projectPath = projectPath.absolutePathString(),
                                agent = agent,
                            ).value
                        rememberProvidedSeedTurn(
                            memoryToolApplicationService = memoryToolApplicationService,
                            store = store,
                            turn = turn,
                            namespaceValue = namespaceValue,
                            progressPath = progressPath,
                            caseId = case.id,
                            sessionId = session.id,
                            turnIndex = turnIndex,
                        )
                    } else {
                        val messageText = turn.requireText(case.id, session.id, turnIndex)
                        val sentTurn = sendUserTurn(
                            conversationEngineService = conversationEngineService,
                            conversation = currentConversation,
                            agent = agent,
                            text = messageText,
                            traceCollector = traceCollector,
                            writeTraceCollector = writeTraceCollector,
                        )
                        ExecutedSeedTurn(
                            sessionId = session.id,
                            text = messageText,
                            memoryWriteTraceExpectation = turn.memoryWriteTraceExpectation,
                            memoryWriteTrace = sentTurn.memoryWriteTrace,
                        )
                    }
                }.onSuccess { executedTurn ->
                    appendProgress(
                        progressPath,
                        "seed_turn_end case=${case.id} session=${session.id} turn=${turnIndex + 1}/${session.turns.size} durationMs=${System.currentTimeMillis() - turnStartedAt} input=${executedTurn.inputKind} run=${executedTurn.memoryRun?.id?.value ?: "none"} runStatus=${executedTurn.memoryRun?.status?.name ?: "none"} write=${executedTurn.memoryWriteTrace.progressSummaryForProgressLog()}"
                    )
                    seedResults += executedTurn
                }.onFailure { error ->
                    appendProgress(
                        progressPath,
                        "seed_turn_error case=${case.id} session=${session.id} turn=${turnIndex + 1}/${session.turns.size} durationMs=${System.currentTimeMillis() - turnStartedAt} error=${error::class.simpleName}:${error.message.oneLineForProgressLog(300)}"
                    )
                    throw error
                }
            }
        }

        val maintenanceResults = mutableListOf<ExecutedMaintenanceAction>()
        case.maintenanceAfterSeeds.forEachIndexed { maintenanceIndex, action ->
            val maintenanceConversation = conversations.values.firstOrNull()
                ?: conversation("maintenance-anchor")
            val actionStartedAt = System.currentTimeMillis()
            appendProgress(
                progressPath,
                "maintenance_start case=${case.id} action=${maintenanceIndex + 1}/${case.maintenanceAfterSeeds.size} type=$action conversation=${maintenanceConversation.id.value}"
            )
            when (action.trim().lowercase()) {
                "consolidate_notes", "note_consolidation" -> conversationEngineService.consolidateCurrentMemory(maintenanceConversation.id, agent)
                "repair_memory", "memory_repair" -> conversationEngineService.repairCurrentMemory(maintenanceConversation.id, agent)
                "maintain_entities", "entity_maintenance", "maintain_memory_entities" -> conversationEngineService.maintainMemoryEntities(maintenanceConversation.id, agent)
                "apply_retention", "retention_apply", "memory_retention" -> conversationEngineService.runCurrentMemoryRetention(maintenanceConversation.id)
                else -> error("Unknown memory maintenance action '$action' in case ${case.id}")
            }
            val traces = maintenanceTraceCollector.take(maintenanceConversation.id)
            maintenanceResults += ExecutedMaintenanceAction(
                action = action,
                expectation = case.maintenanceTraceExpectations.getOrNull(maintenanceIndex) ?: MemoryMaintenanceTraceExpectation(),
                traces = traces,
            )
            appendProgress(
                progressPath,
                "maintenance_end case=${case.id} action=${maintenanceIndex + 1}/${case.maintenanceAfterSeeds.size} type=$action durationMs=${System.currentTimeMillis() - actionStartedAt} traces=${traces.joinToString(",") { it.progressSummaryForProgressLog() }.ifBlank { "none" }}"
            )
        }

        val namespace = resolveNamespace(conversationService, conversations, projectPath.absolutePathString(), agent)
        val afterSeedsSnapshot = store.loadNamespaceSnapshot(namespace)
        appendProgress(
            progressPath,
            "after_seeds_snapshot case=${case.id} namespace=${namespace.value} predicates=${afterSeedsSnapshot.predicateDefinitions.size} sources=${afterSeedsSnapshot.sources.size} entities=${afterSeedsSnapshot.entities.size} claims=${afterSeedsSnapshot.claims.size} notes=${afterSeedsSnapshot.notes.size} actionItems=${afterSeedsSnapshot.actionItems.size}"
        )

        val recallResults = mutableListOf<ExecutedRecallTurn>()
        case.recallSessions.forEach { session ->
            val currentConversation = conversation(session.id)
            session.turns.forEachIndexed { turnIndex, turn ->
                val turnStartedAt = System.currentTimeMillis()
                appendProgress(
                    progressPath,
                    "recall_turn_start case=${case.id} session=${session.id} turn=${turnIndex + 1}/${session.turns.size} chars=${turn.text.length} preview=${turn.text.oneLineForProgressLog()}"
                )
                val answer = runCatching {
                    sendUserTurn(
                        conversationEngineService = conversationEngineService,
                        conversation = currentConversation,
                        agent = agent,
                        text = turn.text,
                        traceCollector = traceCollector,
                        writeTraceCollector = writeTraceCollector,
                        memoryRoutingFailFast = turn.memoryRoutingFailFast,
                    )
                }.getOrElse { error ->
                    appendProgress(
                        progressPath,
                        "recall_turn_error case=${case.id} session=${session.id} turn=${turnIndex + 1}/${session.turns.size} durationMs=${System.currentTimeMillis() - turnStartedAt} error=${error::class.simpleName}:${error.message.oneLineForProgressLog(300)}"
                    )
                    throw error
                }
                appendProgress(
                    progressPath,
                    "recall_turn_end case=${case.id} session=${session.id} turn=${turnIndex + 1}/${session.turns.size} durationMs=${System.currentTimeMillis() - turnStartedAt} answerChars=${answer.answer.length} answerPreview=${answer.answer.oneLineForProgressLog()} read=${answer.memoryReadTrace.progressSummaryForProgressLog()} write=${answer.memoryWriteTrace.progressSummaryForProgressLog()}"
                )
                recallResults += ExecutedRecallTurn(
                    sessionId = session.id,
                    text = turn.text,
                    answer = answer.answer,
                    expectation = turn.answerExpectation,
                    memoryTraceExpectation = turn.memoryTraceExpectation,
                    memoryReadTrace = answer.memoryReadTrace,
                    memoryWriteTraceExpectation = turn.memoryWriteTraceExpectation,
                    memoryWriteTrace = answer.memoryWriteTrace,
                )
            }
        }

        return ExecutedMemoryE2eCase(
            definition = case,
            namespace = namespace,
            projectPath = projectPath.absolutePathString(),
            modelName = modelName,
            afterSeedsSnapshot = afterSeedsSnapshot,
            finalSnapshot = store.loadNamespaceSnapshot(namespace),
            seedResults = seedResults,
            maintenanceResults = maintenanceResults,
            recallResults = recallResults,
        )
    }

    private suspend fun resolveNamespace(
        conversationService: ConversationDomainService,
        conversations: Map<String, Conversation>,
        projectPath: String,
        agent: AgentDefinition,
    ): MemoryNamespace {
        val conversation = conversations.values.firstOrNull()
            ?: conversationService.create(
                projectPath = projectPath,
                displayName = "namespace-bootstrap",
                agentDefinitionId = agent.id,
            )
        val project = conversationService.getProject(conversation.id)
        return MemoryNamespace("project:${project.id.value}")
    }

    private fun List<MemoryE2ePreloadedMemory>.toMemoryUpdateBatch(
        caseId: String,
        namespace: MemoryNamespace,
        conversationId: Conversation.Id,
    ): MemoryUpdateBatch {
        val batches = mapIndexed { index, fixture ->
            fixture.toMemoryUpdateBatch(
                caseId = caseId,
                namespace = namespace,
                conversationId = conversationId,
                index = index,
            )
        }

        return MemoryUpdateBatch(
            predicateDefinitions = batches.flatMap { it.predicateDefinitions },
            sources = batches.flatMap { it.sources },
            runs = batches.flatMap { it.runs },
            entities = batches.flatMap { it.entities },
            claims = batches.flatMap { it.claims },
            notes = batches.flatMap { it.notes },
            actionItems = batches.flatMap { it.actionItems },
            profiles = batches.flatMap { it.profiles },
            episodes = batches.flatMap { it.episodes },
        )
    }

    private fun MemoryE2ePreloadedMemory.toMemoryUpdateBatch(
        caseId: String,
        namespace: MemoryNamespace,
        conversationId: Conversation.Id,
        index: Int,
    ): MemoryUpdateBatch {
        val prefix = "${caseId.sanitizePathSegment()}-fixture-${index + 1}"
        val olderAt = kotlinx.datetime.Instant.parse("2026-01-01T00:00:00Z")
        val newerAt = kotlinx.datetime.Instant.parse("2026-01-02T00:00:00Z")
        val subjectEntityId = MemoryEntity.Id("$prefix-entity-subject")
        val subject = preloadedSubjectEntity(namespace, subjectEntityId, olderAt, newerAt)

        return when (type.trim().lowercase()) {
            "conflicting_claims" -> conflictingClaimsBatch(namespace, conversationId, prefix, subject, olderAt, newerAt)
            "duplicate_claims" -> duplicateClaimsBatch(namespace, conversationId, prefix, subject, olderAt, newerAt)
            "duplicate_notes" -> duplicateNotesBatch(namespace, conversationId, prefix, subject, olderAt, newerAt)
            "duplicate_action_items" -> duplicateActionItemsBatch(namespace, conversationId, prefix, subject, olderAt, newerAt)
            "duplicate_episodes" -> duplicateEpisodesBatch(namespace, conversationId, prefix, subject, olderAt, newerAt)
            "profile_drift" -> profileDriftBatch(namespace, conversationId, prefix, subject, olderAt, newerAt)
            "source_safety_replacement" -> sourceSafetyReplacementBatch(namespace, conversationId, prefix, subject, olderAt, newerAt)
            else -> error("Unsupported preloadedMemory type '$type' in case $caseId")
        }
    }

    private fun MemoryE2ePreloadedMemory.preloadedSubjectEntity(
        namespace: MemoryNamespace,
        subjectEntityId: MemoryEntity.Id,
        firstSeenAt: kotlinx.datetime.Instant,
        lastSeenAt: kotlinx.datetime.Instant,
    ): MemoryEntity =
        MemoryEntity(
            id = subjectEntityId,
            namespace = namespace,
            entityType = subjectType.toMemoryEntityType(),
            canonicalName = subjectName,
            normalizedName = subjectName.lowercase(),
            firstSeenAt = firstSeenAt,
            lastSeenAt = lastSeenAt,
            createdAt = firstSeenAt,
            updatedAt = lastSeenAt,
        )

    private fun MemoryE2ePreloadedMemory.conflictingClaimsBatch(
        namespace: MemoryNamespace,
        conversationId: Conversation.Id,
        prefix: String,
        subject: MemoryEntity,
        olderAt: kotlinx.datetime.Instant,
        newerAt: kotlinx.datetime.Instant,
    ): MemoryUpdateBatch {
        val olderSourceId = MemorySource.Id("$prefix-source-older")
        val newerSourceId = MemorySource.Id("$prefix-source-newer")
        val predicateDefinition = MemoryPredicateDefinition(
            namespace = namespace,
            predicate = predicate,
            description = predicateDescription.ifBlank { "Single current fact used by memory repair e2e." },
            subjectType = subjectType.toMemoryEntityType(),
            objectKind = MemoryPredicateDefinition.ObjectValueKind.STRING,
            cardinality = MemoryPredicateDefinition.Cardinality.SINGLE,
            temporalPolicy = MemoryPredicateDefinition.TemporalPolicy.ATEMPORAL,
            conflictPolicy = MemoryPredicateDefinition.ConflictPolicy.REPLACE,
            semanticKinds = setOf(MemoryPredicateDefinition.SemanticKind.OTHER),
            profileSync = true,
            defaultImportance = 8,
        )
        val olderSource = MemorySource.ChatTurn(
            id = olderSourceId,
            namespace = namespace,
            conversationId = conversationId,
            speakerRole = MemorySource.ActorRole.USER,
            authorLabel = "fixture-user",
            contentText = olderText,
            searchText = olderText,
            contentHash = "$prefix-older-hash",
            observedAt = olderAt,
            createdAt = olderAt,
        )
        val newerSource = MemorySource.ChatTurn(
            id = newerSourceId,
            namespace = namespace,
            conversationId = conversationId,
            speakerRole = MemorySource.ActorRole.USER,
            authorLabel = "fixture-user",
            contentText = newerText,
            searchText = newerText,
            contentHash = "$prefix-newer-hash",
            observedAt = newerAt,
            createdAt = newerAt,
        )

        return MemoryUpdateBatch(
            predicateDefinitions = listOf(predicateDefinition),
            entities = listOf(subject),
            sources = listOf(olderSource, newerSource),
            claims = listOf(
                MemoryClaim(
                    id = MemoryClaim.Id("$prefix-claim-older"),
                    namespace = namespace,
                    subjectEntityId = subject.id,
                    predicate = predicate,
                    predicateFamily = "fixture_current_fact",
                    predicatePolicy = predicateDefinition,
                    objectValue = JsonPrimitive(olderValue),
                    normalizedText = olderText,
                    scope = MemoryScope.Global(scopeText),
                    confidence = 0.99,
                    importance = 8,
                    firstSeenAt = olderAt,
                    lastSeenAt = olderAt,
                    evidenceRefs = listOf(
                        MemoryEvidenceRef(
                            sourceId = olderSourceId,
                            kind = MemoryEvidenceRef.Kind.DIRECT,
                            cachedQuote = olderText,
                        ),
                    ),
                    createdAt = olderAt,
                    updatedAt = olderAt,
                ),
                MemoryClaim(
                    id = MemoryClaim.Id("$prefix-claim-newer"),
                    namespace = namespace,
                    subjectEntityId = subject.id,
                    predicate = predicate,
                    predicateFamily = "fixture_current_fact",
                    predicatePolicy = predicateDefinition,
                    objectValue = JsonPrimitive(newerValue),
                    normalizedText = newerText,
                    scope = MemoryScope.Global(scopeText),
                    confidence = 0.99,
                    importance = 9,
                    firstSeenAt = newerAt,
                    lastSeenAt = newerAt,
                    evidenceRefs = listOf(
                        MemoryEvidenceRef(
                            sourceId = newerSourceId,
                            kind = MemoryEvidenceRef.Kind.DIRECT,
                            cachedQuote = newerText,
                        ),
                    ),
                    createdAt = newerAt,
                    updatedAt = newerAt,
                ),
            ),
        )
    }

    private fun MemoryE2ePreloadedMemory.duplicateClaimsBatch(
        namespace: MemoryNamespace,
        conversationId: Conversation.Id,
        prefix: String,
        subject: MemoryEntity,
        olderAt: kotlinx.datetime.Instant,
        newerAt: kotlinx.datetime.Instant,
    ): MemoryUpdateBatch {
        val firstSourceId = MemorySource.Id("$prefix-source-first")
        val secondSourceId = MemorySource.Id("$prefix-source-second")
        val sourceText = firstText.ifBlank { olderText.ifBlank { newerText } }
        val duplicateText = newerText.ifBlank { sourceText }
        val predicateDefinition = MemoryPredicateDefinition(
            namespace = namespace,
            predicate = predicate,
            description = predicateDescription.ifBlank { "Duplicate claim fixture predicate." },
            subjectType = subject.entityType,
            objectKind = MemoryPredicateDefinition.ObjectValueKind.STRING,
            cardinality = MemoryPredicateDefinition.Cardinality.MULTI,
            temporalPolicy = MemoryPredicateDefinition.TemporalPolicy.ATEMPORAL,
            conflictPolicy = MemoryPredicateDefinition.ConflictPolicy.COEXIST,
            semanticKinds = setOf(MemoryPredicateDefinition.SemanticKind.OTHER),
            profileSync = true,
            defaultImportance = 8,
        )

        return MemoryUpdateBatch(
            predicateDefinitions = listOf(predicateDefinition),
            entities = listOf(subject),
            sources = listOf(
                preloadedChatSource(namespace, conversationId, firstSourceId, sourceText, olderAt),
                preloadedChatSource(namespace, conversationId, secondSourceId, duplicateText, newerAt),
            ),
            claims = listOf(
                MemoryClaim(
                    id = MemoryClaim.Id("$prefix-claim-first"),
                    namespace = namespace,
                    subjectEntityId = subject.id,
                    predicate = predicate,
                    predicateFamily = "fixture_duplicate_fact",
                    predicatePolicy = predicateDefinition,
                    objectValue = JsonPrimitive(olderValue.ifBlank { newerValue }),
                    normalizedText = sourceText,
                    scope = MemoryScope.Global(scopeText),
                    confidence = 0.6,
                    importance = 6,
                    firstSeenAt = olderAt,
                    lastSeenAt = olderAt,
                    evidenceRefs = listOf(evidenceRef(firstSourceId, sourceText)),
                    createdAt = olderAt,
                    updatedAt = olderAt,
                ),
                MemoryClaim(
                    id = MemoryClaim.Id("$prefix-claim-second"),
                    namespace = namespace,
                    subjectEntityId = subject.id,
                    predicate = predicate,
                    predicateFamily = "fixture_duplicate_fact",
                    predicatePolicy = predicateDefinition,
                    objectValue = JsonPrimitive(olderValue.ifBlank { newerValue }),
                    normalizedText = sourceText,
                    scope = MemoryScope.Global(scopeText),
                    confidence = 0.95,
                    importance = 9,
                    firstSeenAt = newerAt,
                    lastSeenAt = newerAt,
                    evidenceRefs = listOf(evidenceRef(secondSourceId, duplicateText)),
                    createdAt = newerAt,
                    updatedAt = newerAt,
                ),
            ),
        )
    }

    private fun MemoryE2ePreloadedMemory.duplicateNotesBatch(
        namespace: MemoryNamespace,
        conversationId: Conversation.Id,
        prefix: String,
        subject: MemoryEntity,
        olderAt: kotlinx.datetime.Instant,
        newerAt: kotlinx.datetime.Instant,
    ): MemoryUpdateBatch {
        val firstSourceId = MemorySource.Id("$prefix-source-first")
        val secondSourceId = MemorySource.Id("$prefix-source-second")
        val summary = noteSummary.ifBlank { firstText.ifBlank { "Duplicate note fixture summary." } }
        val title = noteTitle.ifBlank { "Duplicate note fixture" }

        return MemoryUpdateBatch(
            entities = listOf(subject),
            sources = listOf(
                preloadedChatSource(namespace, conversationId, firstSourceId, firstText.ifBlank { summary }, olderAt),
                preloadedChatSource(namespace, conversationId, secondSourceId, secondText.ifBlank { summary }, newerAt),
            ),
            notes = listOf(
                MemoryNote(
                    id = MemoryNote.Id("$prefix-note-first"),
                    namespace = namespace,
                    noteType = noteType.toMemoryNoteType(),
                    title = title,
                    summary = summary,
                    scope = MemoryScope.Global(scopeText),
                    maturity = MemoryNote.Maturity.STABILIZING,
                    anchorEntityId = subject.id,
                    entityRefs = listOf(MemoryNote.EntityRef(subject.id, MemoryNote.EntityRef.Role.PRIMARY)),
                    confidence = 0.75,
                    importance = 8,
                    evidenceRefs = listOf(evidenceRef(firstSourceId, firstText.ifBlank { summary })),
                    createdAt = olderAt,
                    updatedAt = olderAt,
                ),
                MemoryNote(
                    id = MemoryNote.Id("$prefix-note-second"),
                    namespace = namespace,
                    noteType = noteType.toMemoryNoteType(),
                    title = title,
                    summary = summary,
                    scope = MemoryScope.Global(scopeText),
                    maturity = MemoryNote.Maturity.STABILIZING,
                    anchorEntityId = subject.id,
                    entityRefs = listOf(MemoryNote.EntityRef(subject.id, MemoryNote.EntityRef.Role.PRIMARY)),
                    confidence = 0.95,
                    importance = 9,
                    evidenceRefs = listOf(evidenceRef(secondSourceId, secondText.ifBlank { summary })),
                    createdAt = newerAt,
                    updatedAt = newerAt,
                ),
            ),
        )
    }

    private fun MemoryE2ePreloadedMemory.duplicateActionItemsBatch(
        namespace: MemoryNamespace,
        conversationId: Conversation.Id,
        prefix: String,
        subject: MemoryEntity,
        olderAt: kotlinx.datetime.Instant,
        newerAt: kotlinx.datetime.Instant,
    ): MemoryUpdateBatch {
        val firstSourceId = MemorySource.Id("$prefix-source-first")
        val secondSourceId = MemorySource.Id("$prefix-source-second")
        val title = taskTitle.ifBlank { "Duplicate actionItem fixture" }
        val description = taskDescription.ifBlank { firstText.ifBlank { "Duplicate actionItem fixture description." } }

        return MemoryUpdateBatch(
            entities = listOf(subject),
            sources = listOf(
                preloadedChatSource(namespace, conversationId, firstSourceId, firstText.ifBlank { title }, olderAt),
                preloadedChatSource(namespace, conversationId, secondSourceId, secondText.ifBlank { title }, newerAt),
            ),
            actionItems = listOf(
                MemoryActionItem(
                    id = MemoryActionItem.Id("$prefix-actionItem-first"),
                    namespace = namespace,
                    ownerEntityId = subject.id,
                    title = title,
                    description = description,
                    status = MemoryActionItem.Status.OPEN,
                    priority = MemoryActionItem.Priority.NORMAL,
                    scope = MemoryScope.Global(scopeText),
                    relatedEntityIds = listOf(subject.id),
                    confidence = 0.75,
                    evidenceRefs = listOf(evidenceRef(firstSourceId, firstText.ifBlank { title })),
                    createdAt = olderAt,
                    updatedAt = olderAt,
                ),
                MemoryActionItem(
                    id = MemoryActionItem.Id("$prefix-actionItem-second"),
                    namespace = namespace,
                    ownerEntityId = subject.id,
                    title = title,
                    description = description,
                    status = MemoryActionItem.Status.OPEN,
                    priority = MemoryActionItem.Priority.HIGH,
                    scope = MemoryScope.Global(scopeText),
                    relatedEntityIds = listOf(subject.id),
                    confidence = 0.95,
                    evidenceRefs = listOf(evidenceRef(secondSourceId, secondText.ifBlank { title })),
                    createdAt = newerAt,
                    updatedAt = newerAt,
                ),
            ),
        )
    }

    private fun MemoryE2ePreloadedMemory.duplicateEpisodesBatch(
        namespace: MemoryNamespace,
        conversationId: Conversation.Id,
        prefix: String,
        subject: MemoryEntity,
        olderAt: kotlinx.datetime.Instant,
        newerAt: kotlinx.datetime.Instant,
    ): MemoryUpdateBatch {
        val firstSourceId = MemorySource.Id("$prefix-source-first")
        val secondSourceId = MemorySource.Id("$prefix-source-second")
        val lesson = episodeLesson.ifBlank { "Duplicate episode fixture lesson." }
        val evidenceText = firstText.ifBlank { lesson }

        return MemoryUpdateBatch(
            entities = listOf(subject),
            sources = listOf(
                preloadedChatSource(namespace, conversationId, firstSourceId, evidenceText, olderAt),
                preloadedChatSource(namespace, conversationId, secondSourceId, secondText.ifBlank { evidenceText }, newerAt),
            ),
            episodes = listOf(
                MemoryEpisode(
                    id = MemoryEpisode.Id("$prefix-episode-first"),
                    namespace = namespace,
                    ownerEntityId = subject.id,
                    situation = episodeSituation,
                    action = episodeAction,
                    result = episodeResult,
                    lesson = lesson,
                    tags = listOf("fixture", "repair"),
                    successScore = 0.7,
                    evidenceRefs = listOf(evidenceRef(firstSourceId, evidenceText)),
                    createdAt = olderAt,
                    updatedAt = olderAt,
                ),
                MemoryEpisode(
                    id = MemoryEpisode.Id("$prefix-episode-second"),
                    namespace = namespace,
                    ownerEntityId = subject.id,
                    situation = episodeSituation,
                    action = episodeAction,
                    result = episodeResult,
                    lesson = lesson,
                    tags = listOf("fixture", "repair"),
                    successScore = 0.95,
                    evidenceRefs = listOf(evidenceRef(secondSourceId, secondText.ifBlank { evidenceText })),
                    createdAt = newerAt,
                    updatedAt = newerAt,
                ),
            ),
        )
    }

    private fun MemoryE2ePreloadedMemory.profileDriftBatch(
        namespace: MemoryNamespace,
        conversationId: Conversation.Id,
        prefix: String,
        subject: MemoryEntity,
        olderAt: kotlinx.datetime.Instant,
        newerAt: kotlinx.datetime.Instant,
    ): MemoryUpdateBatch {
        val sourceId = MemorySource.Id("$prefix-source-fresh")
        val claimText = newerText.ifBlank { firstText }
        val predicateDefinition = MemoryPredicateDefinition(
            namespace = namespace,
            predicate = predicate,
            description = predicateDescription.ifBlank { "Profile-synced fixture predicate." },
            subjectType = subject.entityType,
            objectKind = MemoryPredicateDefinition.ObjectValueKind.STRING,
            cardinality = MemoryPredicateDefinition.Cardinality.MULTI,
            temporalPolicy = MemoryPredicateDefinition.TemporalPolicy.ATEMPORAL,
            conflictPolicy = MemoryPredicateDefinition.ConflictPolicy.COEXIST,
            semanticKinds = setOf(MemoryPredicateDefinition.SemanticKind.OTHER),
            profileSync = true,
            defaultImportance = 8,
        )

        return MemoryUpdateBatch(
            predicateDefinitions = listOf(predicateDefinition),
            entities = listOf(subject),
            sources = listOf(preloadedChatSource(namespace, conversationId, sourceId, claimText, newerAt)),
            claims = listOf(
                MemoryClaim(
                    id = MemoryClaim.Id("$prefix-claim-fresh"),
                    namespace = namespace,
                    subjectEntityId = subject.id,
                    predicate = predicate,
                    predicateFamily = "fixture_profile_fact",
                    predicatePolicy = predicateDefinition,
                    objectValue = JsonPrimitive(newerValue.ifBlank { claimText }),
                    normalizedText = claimText,
                    scope = MemoryScope.Global(scopeText),
                    confidence = 0.95,
                    importance = 9,
                    firstSeenAt = newerAt,
                    lastSeenAt = newerAt,
                    evidenceRefs = listOf(evidenceRef(sourceId, claimText)),
                    createdAt = newerAt,
                    updatedAt = newerAt,
                )
            ),
            profiles = listOf(
                MemoryProfile(
                    id = MemoryProfile.Id("$prefix-profile"),
                    namespace = namespace,
                    ownerEntityId = subject.id,
                    profileText = profileText.ifBlank { "Profile for ${subject.canonicalName} (${subject.entityType.name}).\nNo active profile-synced memory." },
                    version = 1,
                    createdAt = olderAt,
                    updatedAt = olderAt,
                )
            ),
        )
    }

    private fun MemoryE2ePreloadedMemory.sourceSafetyReplacementBatch(
        namespace: MemoryNamespace,
        conversationId: Conversation.Id,
        prefix: String,
        subject: MemoryEntity,
        olderAt: kotlinx.datetime.Instant,
        newerAt: kotlinx.datetime.Instant,
    ): MemoryUpdateBatch {
        val olderSourceId = MemorySource.Id("$prefix-source-older")
        val newerSourceId = MemorySource.Id("$prefix-source-newer")
        val oldClaimId = MemoryClaim.Id("$prefix-claim-older")
        val newClaimId = MemoryClaim.Id("$prefix-claim-newer")
        val predicateDefinition = MemoryPredicateDefinition(
            namespace = namespace,
            predicate = predicate,
            description = predicateDescription.ifBlank { "Source safety replacement fixture predicate." },
            subjectType = subject.entityType,
            objectKind = MemoryPredicateDefinition.ObjectValueKind.STRING,
            cardinality = MemoryPredicateDefinition.Cardinality.SINGLE,
            temporalPolicy = MemoryPredicateDefinition.TemporalPolicy.ATEMPORAL,
            conflictPolicy = MemoryPredicateDefinition.ConflictPolicy.REPLACE,
            semanticKinds = setOf(MemoryPredicateDefinition.SemanticKind.OTHER),
            profileSync = true,
            defaultImportance = 8,
        )

        return MemoryUpdateBatch(
            predicateDefinitions = listOf(predicateDefinition),
            entities = listOf(subject),
            sources = listOf(
                preloadedChatSource(namespace, conversationId, olderSourceId, olderText, olderAt),
                preloadedChatSource(namespace, conversationId, newerSourceId, newerText, newerAt),
            ),
            claims = listOf(
                MemoryClaim(
                    id = oldClaimId,
                    namespace = namespace,
                    subjectEntityId = subject.id,
                    predicate = predicate,
                    predicateFamily = "fixture_current_fact",
                    predicatePolicy = predicateDefinition,
                    objectValue = JsonPrimitive(olderValue),
                    normalizedText = olderText,
                    scope = MemoryScope.Global(scopeText),
                    confidence = 0.96,
                    importance = 7,
                    status = MemoryClaim.Status.SUPERSEDED,
                    firstSeenAt = olderAt,
                    lastSeenAt = olderAt,
                    evidenceRefs = listOf(evidenceRef(olderSourceId, olderText)),
                    createdAt = olderAt,
                    updatedAt = newerAt,
                ),
                MemoryClaim(
                    id = newClaimId,
                    namespace = namespace,
                    subjectEntityId = subject.id,
                    predicate = predicate,
                    predicateFamily = "fixture_current_fact",
                    predicatePolicy = predicateDefinition,
                    objectValue = JsonPrimitive(newerValue),
                    normalizedText = newerText,
                    scope = MemoryScope.Global(scopeText),
                    confidence = 0.99,
                    importance = 9,
                    status = MemoryClaim.Status.ACTIVE,
                    supersedesClaimId = oldClaimId,
                    firstSeenAt = newerAt,
                    lastSeenAt = newerAt,
                    evidenceRefs = listOf(evidenceRef(newerSourceId, newerText)),
                    createdAt = newerAt,
                    updatedAt = newerAt,
                ),
            ),
        )
    }

    private fun preloadedChatSource(
        namespace: MemoryNamespace,
        conversationId: Conversation.Id,
        id: MemorySource.Id,
        text: String,
        observedAt: kotlinx.datetime.Instant,
    ): MemorySource.ChatTurn =
        MemorySource.ChatTurn(
            id = id,
            namespace = namespace,
            conversationId = conversationId,
            speakerRole = MemorySource.ActorRole.USER,
            authorLabel = "fixture-user",
            contentText = text,
            searchText = text,
            contentHash = "${id.value}-hash",
            observedAt = observedAt,
            createdAt = observedAt,
        )

    private fun evidenceRef(
        sourceId: MemorySource.Id,
        quote: String,
    ): MemoryEvidenceRef =
        MemoryEvidenceRef(
            sourceId = sourceId,
            kind = MemoryEvidenceRef.Kind.DIRECT,
            cachedQuote = quote,
        )

    private fun String.toMemoryEntityType(): MemoryEntity.Type =
        runCatching { MemoryEntity.Type.valueOf(trim().uppercase()) }
            .getOrElse { error("Unsupported memory entity type '$this'") }

    private fun String.toMemoryNoteType(): MemoryNote.Type =
        runCatching { MemoryNote.Type.valueOf(trim().uppercase()) }
            .getOrElse { error("Unsupported memory note type '$this'") }

    private suspend fun sendUserTurn(
        conversationEngineService: ConversationEngineService,
        conversation: Conversation,
        agent: AgentDefinition,
        text: String,
        traceCollector: MemoryE2eReadTraceCollector,
        writeTraceCollector: MemoryE2eWriteTraceCollector,
        memoryRoutingFailFast: Boolean? = null,
    ): SentUserTurn {
        val userMessage = buildUserMessage(conversation.id, text)
        val messages = withMemoryRoutingFailFast(memoryRoutingFailFast) {
            collectSubmittedTurn(conversationEngineService, conversation, agent, userMessage)
        }

        return SentUserTurn(
            answer = messages.renderAssistantText(),
            memoryReadTrace = traceCollector.take(userMessage.id),
            memoryWriteTrace = writeTraceCollector.take(userMessage.id),
        )
    }

    private suspend fun rememberProvidedSeedTurn(
        memoryToolApplicationService: MemoryToolApplicationService,
        store: MemoryStore,
        turn: MemoryE2eSeedTurnDefinition,
        namespaceValue: String,
        progressPath: Path,
        caseId: String,
        sessionId: String,
        turnIndex: Int,
    ): ExecutedSeedTurn {
        val toolResult = memoryToolApplicationService.rememberProvidedText(
            conversationIdValue = null,
            text = turn.text?.trim()?.takeIf { it.isNotBlank() && turn.documentType != null },
            filePath = turn.resolvedFilePath(resolveProjectRoot()),
            rawUrl = turn.rawUrl?.trim()?.takeIf { it.isNotBlank() },
            documentType = turn.documentType,
            title = turn.title,
            sourceRef = turn.sourceRef,
            forceWrite = turn.forceWrite,
            mode = turn.mode,
            namespaceValue = namespaceValue,
        )
        val runId = extractQueuedRunId(toolResult)
        val completedRun = if (runId != null) {
            awaitMemoryRunTerminal(
                store = store,
                runId = MemoryRun.Id(runId),
                progressPath = progressPath,
                caseId = caseId,
                sessionId = sessionId,
                turnIndex = turnIndex,
            )
        } else {
            ensureToolResultSucceeded(toolResult)
            null
        }
        return ExecutedSeedTurn(
            sessionId = sessionId,
            text = turn.progressText(),
            memoryWriteTraceExpectation = turn.memoryWriteTraceExpectation,
            memoryWriteTrace = null,
            inputKind = turn.inputKindForReport(),
            toolResult = toolResult,
            memoryRun = completedRun,
        )
    }

    private suspend fun awaitMemoryRunTerminal(
        store: MemoryStore,
        runId: MemoryRun.Id,
        progressPath: Path,
        caseId: String,
        sessionId: String,
        turnIndex: Int,
    ): MemoryRun =
        withTimeout(e2eTurnCompletionTimeoutMs()) {
            var lastStatus: MemoryRun.Status? = null
            while (true) {
                val run = store.findRunById(runId)
                if (run != null && run.status.isTerminal()) {
                    if (run.status != MemoryRun.Status.SUCCESS) {
                        error(
                            "Document seed run ${run.id.value} finished with ${run.status.name}: " +
                                (run.errorText ?: run.summary)
                        )
                    }
                    return@withTimeout run
                }
                if (run?.status != null && run.status != lastStatus) {
                    lastStatus = run.status
                    appendProgress(
                        progressPath,
                        "seed_document_run_status case=$caseId session=$sessionId turn=${turnIndex + 1} run=${run.id.value} status=${run.status.name} summary=${run.summary.oneLineForProgressLog()}"
                    )
                }
                delay(500)
            }
            error("Unreachable")
        }

    private fun extractQueuedRunId(toolResult: String): String? {
        val result = parseToolResult(toolResult)
        val status = result.stringValue("status")
        if (status == "failed") {
            error(result.stringValue("message") ?: toolResult)
        }
        return result.stringValue("run_id")
    }

    private fun ensureToolResultSucceeded(toolResult: String) {
        val result = parseToolResult(toolResult)
        val status = result.stringValue("status")
        if (status != "completed") {
            error("Unexpected memory tool result status '$status': $toolResult")
        }
    }

    private fun parseToolResult(toolResult: String): JsonObject =
        runCatching { json.parseToJsonElement(toolResult) as? JsonObject }
            .getOrNull()
            ?: error("Memory tool returned non-object JSON: $toolResult")

    private fun JsonObject.stringValue(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun MemoryRun.Status.isTerminal(): Boolean =
        this in setOf(MemoryRun.Status.SUCCESS, MemoryRun.Status.FAILED, MemoryRun.Status.PARTIAL, MemoryRun.Status.CANCELLED)

    private suspend fun collectSubmittedTurn(
        conversationEngineService: ConversationEngineService,
        conversation: Conversation,
        agent: AgentDefinition,
        userMessage: Conversation.Message,
    ): List<Conversation.Message> = coroutineScope {
        val emittedMessages = mutableListOf<Conversation.Message>()
        val observerReady = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            var liveEvents = false
            conversationEngineService.observeConversation(conversation.id).collect { event ->
                if (!liveEvents) {
                    if (event is ConversationRuntimeEvent.SnapshotUpdated) {
                        liveEvents = true
                        observerReady.complete(Unit)
                    }
                    return@collect
                }

                when (event) {
                    is ConversationRuntimeEvent.SnapshotUpdated -> Unit
                    is ConversationRuntimeEvent.MessageEmitted -> emittedMessages.add(event.message)
                    is ConversationRuntimeEvent.ExecutionCompleted -> completed.complete(Unit)
                    is ConversationRuntimeEvent.ExecutionFailed -> completed.completeExceptionally(
                        IllegalStateException("${event.failureType ?: "ConversationRuntimeError"}: ${event.message}")
                    )
                }
            }
        }

        try {
            withTimeout(e2eTurnCompletionTimeoutMs()) {
                observerReady.await()
            }
            assertTrue(
                conversationEngineService.submitMessage(
                    conversationId = conversation.id,
                    userMessage = userMessage,
                    agent = agent,
                ),
                "Runtime rejected submitted E2E message ${userMessage.id.value}",
            )
            withTimeout(e2eTurnCompletionTimeoutMs()) {
                completed.await()
            }
            emittedMessages.toList()
        } finally {
            observer.cancelAndJoin()
        }
    }

    private suspend fun <T> withMemoryRoutingFailFast(
        value: Boolean?,
        block: suspend () -> T,
    ): T {
        if (value == null) return block()

        val previous = System.getProperty(MEMORY_ROUTING_FAIL_FAST_PROPERTY)
        System.setProperty(MEMORY_ROUTING_FAIL_FAST_PROPERTY, value.toString())
        return try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(MEMORY_ROUTING_FAIL_FAST_PROPERTY)
            } else {
                System.setProperty(MEMORY_ROUTING_FAIL_FAST_PROPERTY, previous)
            }
        }
    }

    private fun e2eTurnCompletionTimeoutMs(): Long =
        (
            System.getProperty(TURN_COMPLETION_TIMEOUT_MS_PROPERTY)
                ?: System.getProperty(WEBSOCKET_RESPONSE_TIMEOUT_MS_PROPERTY)
                ?: "1200000"
        ).toLong()

    private fun buildAgent(
        modelName: String,
        promptId: com.gromozeka.domain.model.Prompt.Id,
    ): AgentDefinition {
        val now = Clock.System.now()
        return AgentDefinition(
            id = AgentDefinition.Id("memory-e2e-${uuid7()}"),
            name = "Memory E2E",
            prompts = listOf(promptId),
            runtimeSelection = ServerTestHarness.openAiSubscriptionRuntimeSelection(),
            runtimeOverrides = AiRuntimeOverrides(maxOutputTokens = 2_048),
            tools = emptyList(),
            description = "Inline agent used only for real-model memory e2e tests",
            type = AgentDefinition.Type.Inline,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun buildUserMessage(
        conversationId: Conversation.Id,
        text: String,
    ): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
            createdAt = Clock.System.now(),
        )

    private fun evaluateCase(result: ExecutedMemoryE2eCase): List<String> {
        val errors = mutableListOf<String>()
        evaluateSnapshot(
            label = "afterSeeds",
            snapshot = result.afterSeedsSnapshot,
            expectation = result.definition.expectedAfterSeeds,
            errors = errors,
        )
        evaluateSnapshot(
            label = "final",
            snapshot = result.finalSnapshot,
            expectation = result.definition.expectedFinal,
            errors = errors,
        )
        result.seedResults.forEachIndexed { index, seed ->
            evaluateMemoryWriteTrace(
                label = "seed[$index] ${seed.sessionId}",
                traceEvent = seed.memoryWriteTrace,
                expectation = seed.memoryWriteTraceExpectation,
                errors = errors,
            )
        }
        result.maintenanceResults.forEachIndexed { index, maintenance ->
            evaluateMemoryMaintenanceTrace(
                label = "maintenance[$index] ${maintenance.action}",
                traces = maintenance.traces,
                expectation = maintenance.expectation,
                errors = errors,
            )
        }
        result.recallResults.forEachIndexed { index, recall ->
            evaluateAnswer(
                label = "recall[$index] ${recall.sessionId}",
                answer = recall.answer,
                expectation = recall.expectation,
                errors = errors,
            )
            evaluateMemoryTrace(
                label = "recall[$index] ${recall.sessionId}",
                traceEvent = recall.memoryReadTrace,
                snapshot = result.finalSnapshot,
                expectation = recall.memoryTraceExpectation,
                errors = errors,
            )
            evaluateMemoryWriteTrace(
                label = "recall[$index] ${recall.sessionId}",
                traceEvent = recall.memoryWriteTrace,
                expectation = recall.memoryWriteTraceExpectation,
                errors = errors,
            )
        }
        return errors
    }

    private fun evaluateSnapshot(
        label: String,
        snapshot: MemoryNamespaceSnapshot,
        expectation: SnapshotExpectation,
        errors: MutableList<String>,
    ) {
        val activeClaims = snapshot.claims.filter { it.status == MemoryClaim.Status.ACTIVE && it.archivedAt == null }
        verifyCount("$label.activeClaims", activeClaims.size, expectation.activeClaimsMin, expectation.activeClaimsMax, errors)
        verifyCount("$label.sources", snapshot.sources.size, expectation.sourcesMin, expectation.sourcesMax, errors)
        verifyCount(
            "$label.userSources",
            snapshot.sources.countUserChatTurns(),
            expectation.userSourcesMin,
            expectation.userSourcesMax,
            errors,
        )
        verifyCount(
            "$label.assistantSources",
            snapshot.sources.countAssistantChatTurns(),
            expectation.assistantSourcesMin,
            expectation.assistantSourcesMax,
            errors,
        )
        verifyCount("$label.notes", snapshot.notes.size, expectation.notesMin, expectation.notesMax, errors)
        verifyCount("$label.actionItems", snapshot.actionItems.size, expectation.actionItemsMin, expectation.actionItemsMax, errors)
        verifyCount("$label.profiles", snapshot.profiles.size, expectation.profilesMin, expectation.profilesMax, errors)
        verifyCount("$label.episodes", snapshot.episodes.size, expectation.episodesMin, expectation.episodesMax, errors)
        verifyCount("$label.runs", snapshot.runs.size, expectation.runsMin, expectation.runsMax, errors)
        verifyCount(
            "$label.predicateDefinitions",
            snapshot.predicateDefinitions.size,
            expectation.predicateDefinitionsMin,
            expectation.predicateDefinitionsMax,
            errors,
        )

        expectation.requiredEntities.forEachIndexed { index, expected ->
            val matches = snapshot.entities.filter { it.matches(expected) }
            if (matches.size < expected.minMatches) {
                errors += "$label.requiredEntities[$index] expected at least ${expected.minMatches}, found ${matches.size}: ${expected.describe()}"
            }
        }

        expectation.requiredSources.forEachIndexed { index, expected ->
            val matches = snapshot.sources.filter { it.matches(expected) }
            if (matches.size < expected.minMatches) {
                errors += "$label.requiredSources[$index] expected at least ${expected.minMatches}, found ${matches.size}: ${expected.describe()}"
            }
        }

        expectation.requiredClaims.forEachIndexed { index, expected ->
            val matches = activeClaims.filter { it.matches(expected, snapshot) }
            if (matches.size < expected.minMatches) {
                errors += "$label.requiredClaims[$index] expected at least ${expected.minMatches}, found ${matches.size}: ${expected.describe()}"
            }
        }

        expectation.requiredNotes.forEachIndexed { index, expected ->
            val matches = snapshot.notes.filter { it.matches(expected, snapshot) }
            if (matches.size < expected.minMatches) {
                errors += "$label.requiredNotes[$index] expected at least ${expected.minMatches}, found ${matches.size}: ${expected.describe()}"
            }
        }

        expectation.requiredActionItems.forEachIndexed { index, expected ->
            val matches = snapshot.actionItems.filter { it.matches(expected, snapshot) }
            if (matches.size < expected.minMatches) {
                errors += "$label.requiredActionItems[$index] expected at least ${expected.minMatches}, found ${matches.size}: ${expected.describe()}"
            }
        }

        expectation.requiredProfiles.forEachIndexed { index, expected ->
            val matches = snapshot.profiles.filter { it.matches(expected, snapshot) }
            if (matches.size < expected.minMatches) {
                errors += "$label.requiredProfiles[$index] expected at least ${expected.minMatches}, found ${matches.size}: ${expected.describe()}"
            }
        }

        expectation.requiredEpisodes.forEachIndexed { index, expected ->
            val matches = snapshot.episodes.filter { it.matches(expected, snapshot) }
            if (matches.size < expected.minMatches) {
                errors += "$label.requiredEpisodes[$index] expected at least ${expected.minMatches}, found ${matches.size}: ${expected.describe()}"
            }
        }

        expectation.requiredPredicateDefinitions.forEachIndexed { index, expected ->
            val matches = snapshot.predicateDefinitions.filter { it.matches(expected) }
            if (matches.size < expected.minMatches) {
                errors += "$label.requiredPredicateDefinitions[$index] expected at least ${expected.minMatches}, found ${matches.size}: ${expected.describe()}"
            }
        }

        expectation.requiredRuns.forEachIndexed { index, expected ->
            val matches = snapshot.runs.filter { it.matches(expected) }
            if (matches.size < expected.minMatches) {
                errors += "$label.requiredRuns[$index] expected at least ${expected.minMatches}, found ${matches.size}: ${expected.describe()}"
            }
        }

        expectation.forbiddenClaims.forEachIndexed { index, expected ->
            val matches = activeClaims.filter { it.matches(expected, snapshot) }
            if (matches.isNotEmpty()) {
                errors += "$label.forbiddenClaims[$index] matched ${matches.size}: ${matches.joinToString { it.describe(snapshot) }}"
            }
        }

        expectation.forbiddenNotes.forEachIndexed { index, expected ->
            val matches = snapshot.notes.filter { it.matches(expected, snapshot) }
            if (matches.isNotEmpty()) {
                errors += "$label.forbiddenNotes[$index] matched ${matches.size}: ${matches.joinToString { it.describe(snapshot) }}"
            }
        }

        expectation.forbiddenActionItems.forEachIndexed { index, expected ->
            val matches = snapshot.actionItems.filter { it.matches(expected, snapshot) }
            if (matches.isNotEmpty()) {
                errors += "$label.forbiddenActionItems[$index] matched ${matches.size}: ${matches.joinToString { it.describe(snapshot) }}"
            }
        }

        expectation.forbiddenProfiles.forEachIndexed { index, expected ->
            val matches = snapshot.profiles.filter { it.matches(expected, snapshot) }
            if (matches.isNotEmpty()) {
                errors += "$label.forbiddenProfiles[$index] matched ${matches.size}: ${matches.joinToString { it.describe(snapshot) }}"
            }
        }

        expectation.forbiddenEpisodes.forEachIndexed { index, expected ->
            val matches = snapshot.episodes.filter { it.matches(expected, snapshot) }
            if (matches.isNotEmpty()) {
                errors += "$label.forbiddenEpisodes[$index] matched ${matches.size}: ${matches.joinToString { it.describe(snapshot) }}"
            }
        }

        if (expectation.allActiveClaimsEvidenceSpeakerRoleIn.isNotEmpty()) {
            val allowed = expectation.allActiveClaimsEvidenceSpeakerRoleIn.mapTo(mutableSetOf()) { it.uppercase() }
            activeClaims.forEach { claim ->
                if (claim.evidenceRefs.isEmpty()) {
                    errors += "$label.claimEvidence ${claim.id.value} has no evidence refs"
                    return@forEach
                }

                val roles = claim.evidenceRefs.mapNotNull { ref ->
                    snapshot.sources.firstOrNull { it.id == ref.sourceId }?.speakerRoleName()
                }
                if (roles.isEmpty()) {
                    errors += "$label.claimEvidence ${claim.id.value} has no resolvable chat evidence sources"
                } else if (roles.any { it !in allowed }) {
                    errors += "$label.claimEvidence ${claim.id.value} expected roles in $allowed, got $roles"
                }
            }
        }
    }

    private fun evaluateAnswer(
        label: String,
        answer: String,
        expectation: AnswerExpectation,
        errors: MutableList<String>,
    ) {
        val haystack = answer.lowercase()
        expectation.containsAll.forEach { needle ->
            if (needle.lowercase() !in haystack) {
                errors += "$label answer missing required text '$needle'. Answer: ${answer.take(500)}"
            }
        }
        expectation.containsAnyGroups.forEachIndexed { index, group ->
            if (group.none { it.lowercase() in haystack }) {
                errors += "$label answer missing any of group[$index] ${group.joinToString()}. Answer: ${answer.take(500)}"
            }
        }
        expectation.containsNone.forEach { needle ->
            if (needle.lowercase() in haystack) {
                errors += "$label answer contains forbidden text '$needle'. Answer: ${answer.take(500)}"
            }
        }
    }

    private fun evaluateMemoryTrace(
        label: String,
        traceEvent: MemoryReadTraceEvent?,
        snapshot: MemoryNamespaceSnapshot,
        expectation: MemoryTraceExpectation,
        errors: MutableList<String>,
    ) {
        if (!expectation.hasChecks()) return
        if (traceEvent == null) {
            errors += "$label memory trace missing"
            return
        }

        val result = traceEvent.result
        val trace = result.trace
        expectation.needMemory?.let { expected ->
            if (result.plan.needMemory != expected) {
                errors += "$label trace needMemory expected $expected, got ${result.plan.needMemory}"
            }
        }
        expectation.injectedPromptPresent?.let { expected ->
            val actual = !result.runtimePrompt.isNullOrBlank()
            if (actual != expected) {
                errors += "$label trace injectedPromptPresent expected $expected, got $actual"
            }
        }

        verifyCount(
            "$label.trace.selectedHits",
            trace.selectedHits.size,
            expectation.selectedHitsMin,
            expectation.selectedHitsMax,
            errors,
        )

        val selectedClaims = trace.selectedHits
            .filter { it.ref.type == MemoryItemRef.Type.CLAIM }
            .mapNotNull { selected -> snapshot.claims.firstOrNull { it.id.value == selected.ref.id } }
        verifyCount(
            "$label.trace.selectedClaims",
            selectedClaims.size,
            expectation.selectedClaimsMin,
            expectation.selectedClaimsMax,
            errors,
        )

        val selectedNotes = trace.selectedHits
            .filter { it.ref.type == MemoryItemRef.Type.NOTE }
            .mapNotNull { selected -> snapshot.notes.firstOrNull { it.id.value == selected.ref.id } }
        verifyCount(
            "$label.trace.selectedNotes",
            selectedNotes.size,
            expectation.selectedNotesMin,
            expectation.selectedNotesMax,
            errors,
        )

        val selectedActionItems = trace.selectedHits
            .filter { it.ref.type == MemoryItemRef.Type.ACTION_ITEM }
            .mapNotNull { selected -> snapshot.actionItems.firstOrNull { it.id.value == selected.ref.id } }
        verifyCount(
            "$label.trace.selectedActionItems",
            selectedActionItems.size,
            expectation.selectedActionItemsMin,
            expectation.selectedActionItemsMax,
            errors,
        )

        val selectedProfiles = trace.selectedHits
            .filter { it.ref.type == MemoryItemRef.Type.PROFILE }
            .mapNotNull { selected -> snapshot.profiles.firstOrNull { it.id.value == selected.ref.id } }
        verifyCount(
            "$label.trace.selectedProfiles",
            selectedProfiles.size,
            expectation.selectedProfilesMin,
            expectation.selectedProfilesMax,
            errors,
        )

        val selectedEpisodes = trace.selectedHits
            .filter { it.ref.type == MemoryItemRef.Type.EPISODE }
            .mapNotNull { selected -> snapshot.episodes.firstOrNull { it.id.value == selected.ref.id } }
        verifyCount(
            "$label.trace.selectedEpisodes",
            selectedEpisodes.size,
            expectation.selectedEpisodesMin,
            expectation.selectedEpisodesMax,
            errors,
        )

        expectation.requiredSelectedClaims.forEachIndexed { index, expected ->
            val matches = selectedClaims.filter { it.matches(expected, snapshot) }
            if (matches.size < expected.minMatches) {
                errors += "$label.trace.requiredSelectedClaims[$index] expected at least ${expected.minMatches}, found ${matches.size}: ${expected.describe()}"
            }
        }

        expectation.requiredSelectedNotes.forEachIndexed { index, expected ->
            val matches = selectedNotes.filter { it.matches(expected, snapshot) }
            if (matches.size < expected.minMatches) {
                errors += "$label.trace.requiredSelectedNotes[$index] expected at least ${expected.minMatches}, found ${matches.size}: ${expected.describe()}"
            }
        }

        expectation.requiredSelectedActionItems.forEachIndexed { index, expected ->
            val matches = selectedActionItems.filter { it.matches(expected, snapshot) }
            if (matches.size < expected.minMatches) {
                errors += "$label.trace.requiredSelectedActionItems[$index] expected at least ${expected.minMatches}, found ${matches.size}: ${expected.describe()}"
            }
        }

        expectation.requiredSelectedProfiles.forEachIndexed { index, expected ->
            val matches = selectedProfiles.filter { it.matches(expected, snapshot) }
            if (matches.size < expected.minMatches) {
                errors += "$label.trace.requiredSelectedProfiles[$index] expected at least ${expected.minMatches}, found ${matches.size}: ${expected.describe()}"
            }
        }

        expectation.requiredSelectedEpisodes.forEachIndexed { index, expected ->
            val matches = selectedEpisodes.filter { it.matches(expected, snapshot) }
            if (matches.size < expected.minMatches) {
                errors += "$label.trace.requiredSelectedEpisodes[$index] expected at least ${expected.minMatches}, found ${matches.size}: ${expected.describe()}"
            }
        }

        expectation.forbiddenSelectedClaims.forEachIndexed { index, expected ->
            val matches = selectedClaims.filter { it.matches(expected, snapshot) }
            if (matches.isNotEmpty()) {
                errors += "$label.trace.forbiddenSelectedClaims[$index] matched ${matches.size}: ${matches.joinToString { it.describe(snapshot) }}"
            }
        }

        expectation.forbiddenSelectedNotes.forEachIndexed { index, expected ->
            val matches = selectedNotes.filter { it.matches(expected, snapshot) }
            if (matches.isNotEmpty()) {
                errors += "$label.trace.forbiddenSelectedNotes[$index] matched ${matches.size}: ${matches.joinToString { it.describe(snapshot) }}"
            }
        }

        expectation.forbiddenSelectedActionItems.forEachIndexed { index, expected ->
            val matches = selectedActionItems.filter { it.matches(expected, snapshot) }
            if (matches.isNotEmpty()) {
                errors += "$label.trace.forbiddenSelectedActionItems[$index] matched ${matches.size}: ${matches.joinToString { it.describe(snapshot) }}"
            }
        }

        expectation.forbiddenSelectedProfiles.forEachIndexed { index, expected ->
            val matches = selectedProfiles.filter { it.matches(expected, snapshot) }
            if (matches.isNotEmpty()) {
                errors += "$label.trace.forbiddenSelectedProfiles[$index] matched ${matches.size}: ${matches.joinToString { it.describe(snapshot) }}"
            }
        }

        expectation.forbiddenSelectedEpisodes.forEachIndexed { index, expected ->
            val matches = selectedEpisodes.filter { it.matches(expected, snapshot) }
            if (matches.isNotEmpty()) {
                errors += "$label.trace.forbiddenSelectedEpisodes[$index] matched ${matches.size}: ${matches.joinToString { it.describe(snapshot) }}"
            }
        }

        val selectedHaystack = trace.selectedHits.joinToString("\n") {
            "${it.ref.type.name} ${it.ref.id} ${it.predicate.orEmpty()} ${it.status.orEmpty()} ${it.summary}"
        }.lowercase()
        expectation.selectedContainsAll.forEach { needle ->
            if (needle.lowercase() !in selectedHaystack) {
                errors += "$label.trace selected hits missing required text '$needle'. Selected: ${selectedHaystack.take(500)}"
            }
        }
        expectation.selectedContainsNone.forEach { needle ->
            if (needle.lowercase() in selectedHaystack) {
                errors += "$label.trace selected hits contain forbidden text '$needle'. Selected: ${selectedHaystack.take(500)}"
            }
        }

        val selectorSelectedHaystack = trace.selectorDecisions
            .filter { it.selected }
            .joinToString("\n") { it.renderForExpectation() }
            .lowercase()
        val selectorRejectedHaystack = trace.selectorDecisions
            .filterNot { it.selected }
            .joinToString("\n") { it.renderForExpectation() }
            .lowercase()
        expectation.selectorSelectedContainsAll.forEach { needle ->
            if (needle.lowercase() !in selectorSelectedHaystack) {
                errors += "$label.trace selector selected decisions missing required text '$needle'. Selected decisions: ${selectorSelectedHaystack.take(500)}"
            }
        }
        expectation.selectorSelectedContainsNone.forEach { needle ->
            if (needle.lowercase() in selectorSelectedHaystack) {
                errors += "$label.trace selector selected decisions contain forbidden text '$needle'. Selected decisions: ${selectorSelectedHaystack.take(500)}"
            }
        }
        expectation.selectorRejectedContainsAll.forEach { needle ->
            if (needle.lowercase() !in selectorRejectedHaystack) {
                errors += "$label.trace selector rejected decisions missing required text '$needle'. Rejected decisions: ${selectorRejectedHaystack.take(500)}"
            }
        }
        expectation.selectorRejectedContainsNone.forEach { needle ->
            if (needle.lowercase() in selectorRejectedHaystack) {
                errors += "$label.trace selector rejected decisions contain forbidden text '$needle'. Rejected decisions: ${selectorRejectedHaystack.take(500)}"
            }
        }

        val injectedPrompt = result.runtimePrompt.orEmpty().lowercase()
        expectation.injectedPromptContainsAll.forEach { needle ->
            if (needle.lowercase() !in injectedPrompt) {
                errors += "$label.trace injected prompt missing required text '$needle'. Prompt: ${result.runtimePrompt.orEmpty().take(500)}"
            }
        }
        expectation.injectedPromptContainsNone.forEach { needle ->
            if (needle.lowercase() in injectedPrompt) {
                errors += "$label.trace injected prompt contains forbidden text '$needle'. Prompt: ${result.runtimePrompt.orEmpty().take(500)}"
            }
        }

        val sourceSafety = trace.sourceSafety
        verifyCount(
            "$label.trace.sourceSafety.suppressedSources",
            sourceSafety.suppressedSources.size,
            expectation.sourceSafetySuppressedSourcesMin,
            expectation.sourceSafetySuppressedSourcesMax,
            errors,
        )
        verifyCount(
            "$label.trace.sourceSafety.restoredTypedHits",
            sourceSafety.restoredTypedHits.size,
            expectation.sourceSafetyRestoredTypedHitsMin,
            expectation.sourceSafetyRestoredTypedHitsMax,
            errors,
        )
        verifyContains(
            "$label.trace.sourceSafety.suppressedSources",
            sourceSafety.suppressedSources.renderTraceHitsForExpectation(),
            expectation.sourceSafetySuppressedContainsAll,
            expectation.sourceSafetySuppressedContainsNone,
            errors,
        )
        verifyContains(
            "$label.trace.sourceSafety.restoredTypedHits",
            sourceSafety.restoredTypedHits.renderTraceHitsForExpectation(),
            expectation.sourceSafetyRestoredContainsAll,
            expectation.sourceSafetyRestoredContainsNone,
            errors,
        )
    }

    private fun evaluateMemoryWriteTrace(
        label: String,
        traceEvent: MemoryWriteTraceEvent?,
        expectation: MemoryWriteTraceExpectation,
        errors: MutableList<String>,
    ) {
        if (!expectation.hasChecks()) return
        expectation.present?.let { expected ->
            if ((traceEvent != null) != expected) {
                errors += "$label write trace present expected $expected, got ${traceEvent != null}"
                return
            }
        }
        if (traceEvent == null) {
            errors += "$label write trace missing"
            return
        }

        val result = traceEvent.result
        if (expectation.routeDecisionIn.isNotEmpty() &&
            result.routeDecision.decision.name !in expectation.routeDecisionIn.map { it.uppercase() }
        ) {
            errors += "$label.write.route expected one of ${expectation.routeDecisionIn}, got ${result.routeDecision.decision.name}"
        }

        verifyCount("$label.write.retrievedHits", result.retrievedHits.size, expectation.retrievedHitsMin, expectation.retrievedHitsMax, errors)
        verifyCount("$label.write.entityOps", result.entityOps.size, expectation.entityOpsMin, expectation.entityOpsMax, errors)
        verifyCount("$label.write.noteCandidates", result.noteCandidates.size, expectation.noteCandidatesMin, expectation.noteCandidatesMax, errors)
        verifyCount("$label.write.rawNoteOps", result.rawNoteOps.size, expectation.rawNoteOpsMin, expectation.rawNoteOpsMax, errors)
        verifyCount("$label.write.noteOps", result.noteOps.size, expectation.noteOpsMin, expectation.noteOpsMax, errors)
        verifyCount("$label.write.claimCandidates", result.claimCandidates.size, expectation.claimCandidatesMin, expectation.claimCandidatesMax, errors)
        verifyCount("$label.write.rawClaimOps", result.rawClaimOps.size, expectation.rawClaimOpsMin, expectation.rawClaimOpsMax, errors)
        verifyCount("$label.write.claimOps", result.claimOps.size, expectation.claimOpsMin, expectation.claimOpsMax, errors)
        verifyCount("$label.write.rawActionItemOps", result.rawActionItemOps.size, expectation.rawActionItemOpsMin, expectation.rawActionItemOpsMax, errors)
        verifyCount("$label.write.actionItemOps", result.actionItemOps.size, expectation.actionItemOpsMin, expectation.actionItemOpsMax, errors)
        verifyCount("$label.write.materializedClaims", result.memoryBatch.claims.size, expectation.materializedClaimsMin, expectation.materializedClaimsMax, errors)
        verifyCount("$label.write.materializedNotes", result.memoryBatch.notes.size, expectation.materializedNotesMin, expectation.materializedNotesMax, errors)
        verifyCount("$label.write.materializedActionItems", result.memoryBatch.actionItems.size, expectation.materializedActionItemsMin, expectation.materializedActionItemsMax, errors)
        verifyCount("$label.write.materializedRuns", result.memoryBatch.runs.size, expectation.materializedRunsMin, expectation.materializedRunsMax, errors)

        verifyContains("$label.write", renderMemoryWriteTrace(traceEvent), expectation.containsAll, expectation.containsNone, errors)
        verifyContains("$label.write.rawNoteOps", result.rawNoteOps.renderNoteOpsForExpectation(), expectation.rawNoteOpsContainsAll, expectation.rawNoteOpsContainsNone, errors)
        verifyContains("$label.write.noteOps", result.noteOps.renderNoteOpsForExpectation(), expectation.noteOpsContainsAll, expectation.noteOpsContainsNone, errors)
        verifyContains("$label.write.rawClaimOps", result.rawClaimOps.renderClaimOpsForExpectation(), expectation.rawClaimOpsContainsAll, expectation.rawClaimOpsContainsNone, errors)
        verifyContains("$label.write.claimOps", result.claimOps.renderClaimOpsForExpectation(), expectation.claimOpsContainsAll, expectation.claimOpsContainsNone, errors)
        verifyContains("$label.write.rawActionItemOps", result.rawActionItemOps.renderTaskOpsForExpectation(), expectation.rawActionItemOpsContainsAll, expectation.rawActionItemOpsContainsNone, errors)
        verifyContains("$label.write.actionItemOps", result.actionItemOps.renderTaskOpsForExpectation(), expectation.actionItemOpsContainsAll, expectation.actionItemOpsContainsNone, errors)
    }

    private fun evaluateMemoryMaintenanceTrace(
        label: String,
        traces: List<MemoryMaintenanceTraceEvent>,
        expectation: MemoryMaintenanceTraceExpectation,
        errors: MutableList<String>,
    ) {
        if (!expectation.hasChecks()) return
        expectation.present?.let { expected ->
            if (traces.isNotEmpty() != expected) {
                errors += "$label maintenance trace present expected $expected, got ${traces.isNotEmpty()}"
                return
            }
        }
        if (traces.isEmpty()) {
            errors += "$label maintenance trace missing"
            return
        }

        val rendered = traces.joinToString("\n\n") { renderMemoryMaintenanceTrace(it) }
        if (expectation.stageIn.isNotEmpty()) {
            val allowed = expectation.stageIn.mapTo(mutableSetOf()) { it.uppercase() }
            val actualStages = traces.map { it.stage.name }
            if (actualStages.none { it in allowed }) {
                errors += "$label.maintenance.stage expected one of ${expectation.stageIn}, got $actualStages"
            }
        }
        verifyCount("$label.maintenance.traces", traces.size, expectation.tracesMin, expectation.tracesMax, errors)
        verifyContains("$label.maintenance", rendered, expectation.containsAll, expectation.containsNone, errors)

        val noteTrace = traces.firstNotNullOfOrNull { it.payload as? MemoryMaintenanceTraceEvent.Payload.NoteConsolidation }
        if (expectation.hasNoteConsolidationChecks()) {
            if (noteTrace == null) {
                errors += "$label.maintenance.noteConsolidation trace missing"
                return
            }
            val result = noteTrace.result
            verifyCount("$label.maintenance.selectedNotes", result.selectedNotes.size, expectation.selectedNotesMin, expectation.selectedNotesMax, errors)
            verifyCount("$label.maintenance.relatedHits", result.relatedHits.size, expectation.relatedHitsMin, expectation.relatedHitsMax, errors)
            verifyCount("$label.maintenance.rawClaimCandidates", result.rawConsolidationResult.claimCandidates.size, expectation.rawClaimCandidatesMin, expectation.rawClaimCandidatesMax, errors)
            verifyCount("$label.maintenance.finalClaimCandidates", result.consolidationResult.claimCandidates.size, expectation.finalClaimCandidatesMin, expectation.finalClaimCandidatesMax, errors)
            verifyCount("$label.maintenance.rawActionItemActions", result.rawConsolidationResult.actionItemActions.size, expectation.rawActionItemActionsMin, expectation.rawActionItemActionsMax, errors)
            verifyCount("$label.maintenance.finalActionItemActions", result.consolidationResult.actionItemActions.size, expectation.finalActionItemActionsMin, expectation.finalActionItemActionsMax, errors)
            verifyCount("$label.maintenance.rawEpisodeCandidates", result.rawConsolidationResult.episodeCandidates.size, expectation.rawEpisodeCandidatesMin, expectation.rawEpisodeCandidatesMax, errors)
            verifyCount("$label.maintenance.finalEpisodeCandidates", result.consolidationResult.episodeCandidates.size, expectation.finalEpisodeCandidatesMin, expectation.finalEpisodeCandidatesMax, errors)
            verifyCount("$label.maintenance.materializedClaims", result.memoryBatch.claims.size, expectation.materializedClaimsMin, expectation.materializedClaimsMax, errors)
            verifyCount("$label.maintenance.materializedNotes", result.memoryBatch.notes.size, expectation.materializedNotesMin, expectation.materializedNotesMax, errors)
            verifyCount("$label.maintenance.materializedActionItems", result.memoryBatch.actionItems.size, expectation.materializedActionItemsMin, expectation.materializedActionItemsMax, errors)
            verifyCount("$label.maintenance.materializedEpisodes", result.memoryBatch.episodes.size, expectation.materializedEpisodesMin, expectation.materializedEpisodesMax, errors)
            verifyCount("$label.maintenance.materializedRuns", result.memoryBatch.runs.size, expectation.materializedRunsMin, expectation.materializedRunsMax, errors)
            verifyContains(
                "$label.maintenance.rawConsolidation",
                result.rawConsolidationResult.renderForExpectation(),
                expectation.rawContainsAll,
                expectation.rawContainsNone,
                errors,
            )
            verifyContains(
                "$label.maintenance.finalConsolidation",
                result.consolidationResult.renderForExpectation(),
                expectation.finalContainsAll,
                expectation.finalContainsNone,
                errors,
            )
        }

        val repairTrace = traces.firstNotNullOfOrNull { it.payload as? MemoryMaintenanceTraceEvent.Payload.MemoryRepair }
        if (expectation.hasRepairChecks()) {
            if (repairTrace == null) {
                errors += "$label.maintenance.memoryRepair trace missing"
                return
            }
            val result = repairTrace.result
            verifyCount("$label.maintenance.repairCandidateClusters", result.candidateClusters.size, expectation.repairCandidateClustersMin, expectation.repairCandidateClustersMax, errors)
            verifyCount("$label.maintenance.repairSuspiciousHits", result.suspiciousHits.size, expectation.repairSuspiciousHitsMin, expectation.repairSuspiciousHitsMax, errors)
            verifyCount("$label.maintenance.repairActions", result.repairPlan.repairActions.size, expectation.repairActionsMin, expectation.repairActionsMax, errors)
            verifyCount("$label.maintenance.repairMaterializedClaims", result.memoryBatch.claims.size, expectation.repairMaterializedClaimsMin, expectation.repairMaterializedClaimsMax, errors)
            verifyCount("$label.maintenance.repairMaterializedNotes", result.memoryBatch.notes.size, expectation.repairMaterializedNotesMin, expectation.repairMaterializedNotesMax, errors)
            verifyCount("$label.maintenance.repairMaterializedActionItems", result.memoryBatch.actionItems.size, expectation.repairMaterializedActionItemsMin, expectation.repairMaterializedActionItemsMax, errors)
            verifyCount("$label.maintenance.repairMaterializedEpisodes", result.memoryBatch.episodes.size, expectation.repairMaterializedEpisodesMin, expectation.repairMaterializedEpisodesMax, errors)
            verifyCount("$label.maintenance.repairMaterializedProfiles", result.memoryBatch.profiles.size, expectation.repairMaterializedProfilesMin, expectation.repairMaterializedProfilesMax, errors)
            verifyCount("$label.maintenance.repairMaterializedRuns", result.memoryBatch.runs.size, expectation.repairMaterializedRunsMin, expectation.repairMaterializedRunsMax, errors)
            verifyCount("$label.maintenance.repairAppliedOps", result.memoryBatch.runs.sumOf { it.appliedOps.size }, expectation.repairAppliedOpsMin, expectation.repairAppliedOpsMax, errors)
        }
    }

    private fun verifyContains(
        label: String,
        actual: String,
        containsAll: List<String>,
        containsNone: List<String>,
        errors: MutableList<String>,
    ) {
        val haystack = actual.lowercase()
        containsAll.forEach { needle ->
            if (needle.lowercase() !in haystack) {
                errors += "$label missing required text '$needle'. Actual: ${actual.take(500)}"
            }
        }
        containsNone.forEach { needle ->
            if (needle.lowercase() in haystack) {
                errors += "$label contains forbidden text '$needle'. Actual: ${actual.take(500)}"
            }
        }
    }

    private fun verifyCount(
        label: String,
        actual: Int,
        min: Int?,
        max: Int?,
        errors: MutableList<String>,
    ) {
        if (min != null && actual < min) {
            errors += "$label expected >= $min, got $actual"
        }
        if (max != null && actual > max) {
            errors += "$label expected <= $max, got $actual"
        }
    }

    private fun MemoryReadTrace.SelectorDecision.renderForExpectation(): String =
        "${if (selected) "SELECT" else "REJECT"} ${ref.type.name}:${ref.id} rank=$rank summary=$summary reason=$reason"

    private fun List<MemoryReadTrace.Hit>.renderTraceHitsForExpectation(): String =
        joinToString("\n") { hit ->
            "- ${hit.ref.type.name}:${hit.ref.id} score=${hit.score} predicate=${hit.predicate ?: "-"} status=${hit.status ?: "-"} text=${hit.summary}"
        }

    private fun MemoryEntity.matches(expectation: EntityExpectation): Boolean {
        if (expectation.typeIn.isNotEmpty() && entityType.name !in expectation.typeIn.map { it.uppercase() }) return false
        if (expectation.canonicalNameIn.isNotEmpty() && canonicalName !in expectation.canonicalNameIn) return false
        if (expectation.normalizedNameIn.isNotEmpty() && normalizedName !in expectation.normalizedNameIn) return false
        val haystack = listOf(canonicalName, normalizedName, summary.orEmpty()).joinToString("\n").lowercase()
        return expectation.containsAll.all { it.lowercase() in haystack }
    }

    private fun MemorySource.matches(expectation: SourceExpectation): Boolean {
        if (expectation.roleIn.isNotEmpty() && speakerRoleName() !in expectation.roleIn.map { it.uppercase() }) return false
        val text = contentText.lowercase()
        val search = searchText.orEmpty().lowercase()
        val policyReason = usagePolicy.reason.lowercase()
        if (expectation.textContainsAll.any { it.lowercase() !in text }) return false
        if (expectation.searchTextContainsAll.any { it.lowercase() !in search }) return false
        expectation.searchTextContainsAnyGroups.forEach { group ->
            if (group.none { it.lowercase() in search }) return false
        }
        if (expectation.allowStructuredExtraction != null &&
            usagePolicy.allowStructuredExtraction != expectation.allowStructuredExtraction
        ) return false
        if (expectation.allowRecall != null && usagePolicy.allowRecall != expectation.allowRecall) return false
        if (expectation.allowEvidenceHydration != null &&
            usagePolicy.allowEvidenceHydration != expectation.allowEvidenceHydration
        ) return false
        if (expectation.usagePolicyReasonContainsAll.any { it.lowercase() !in policyReason }) return false
        return true
    }

    private fun SourceExpectation.describe(): String =
        "roleIn=$roleIn textContainsAll=$textContainsAll searchTextContainsAll=$searchTextContainsAll " +
            "searchTextContainsAnyGroups=$searchTextContainsAnyGroups allowStructuredExtraction=$allowStructuredExtraction " +
            "allowRecall=$allowRecall allowEvidenceHydration=$allowEvidenceHydration " +
            "usagePolicyReasonContainsAll=$usagePolicyReasonContainsAll"

    private fun MemoryNote.matches(expectation: NoteExpectation, snapshot: MemoryNamespaceSnapshot): Boolean {
        if (expectation.typeIn.isNotEmpty() && noteType.name !in expectation.typeIn.map { it.uppercase() }) return false
        if (expectation.statusIn.isNotEmpty() && status.name !in expectation.statusIn.map { it.uppercase() }) return false

        val evidenceQuotes = evidenceRefs.mapNotNull { it.cachedQuote }
        val evidenceTexts = evidenceRefs.mapNotNull { ref ->
            snapshot.sources.firstOrNull { it.id == ref.sourceId }?.contentText
        }
        val evidenceRoles = evidenceRefs.mapNotNull { ref ->
            snapshot.sources.firstOrNull { it.id == ref.sourceId }?.speakerRoleName()
        }
        val entityNames = entityRefs.mapNotNull { ref ->
            snapshot.entities.firstOrNull { it.id == ref.entityId }?.canonicalName
        }
        val haystack = listOf(
            title,
            summary,
            noteType.name,
            status.name,
            maturity.name,
            scope.text,
            keywords.joinToString("\n"),
            tags.joinToString("\n"),
            entityNames.joinToString("\n"),
            evidenceQuotes.joinToString("\n"),
            evidenceTexts.joinToString("\n"),
        ).joinToString("\n").lowercase()

        if (expectation.titleContainsAll.any { it.lowercase() !in title.lowercase() }) return false
        if (expectation.summaryContainsAll.any { it.lowercase() !in summary.lowercase() }) return false
        if (expectation.containsAll.any { it.lowercase() !in haystack }) return false
        if (expectation.evidenceQuoteContainsAll.any { needle ->
                evidenceQuotes.none { needle.lowercase() in it.lowercase() }
            }) return false
        if (expectation.evidenceSourceSpeakerRoleIn.isNotEmpty()) {
            val allowed = expectation.evidenceSourceSpeakerRoleIn.mapTo(mutableSetOf()) { it.uppercase() }
            if (evidenceRoles.isEmpty() || evidenceRoles.any { it !in allowed }) return false
        }

        return true
    }

    private fun NoteExpectation.describe(): String =
        "typeIn=$typeIn statusIn=$statusIn titleContainsAll=$titleContainsAll summaryContainsAll=$summaryContainsAll containsAll=$containsAll"

    private fun MemoryNote.describe(snapshot: MemoryNamespaceSnapshot): String {
        val evidence = evidenceRefs.mapNotNull { ref ->
            snapshot.sources.firstOrNull { it.id == ref.sourceId }?.contentText?.take(120)
        }
        return "${id.value}:${noteType.name}:${status.name}:${title.take(120)} evidence=$evidence"
    }

    private fun MemoryActionItem.matches(expectation: ActionItemExpectation, snapshot: MemoryNamespaceSnapshot): Boolean {
        if (expectation.statusIn.isNotEmpty() && status.name !in expectation.statusIn.map { it.uppercase() }) return false
        if (expectation.priorityIn.isNotEmpty() && priority.name !in expectation.priorityIn.map { it.uppercase() }) return false

        val evidenceQuotes = evidenceRefs.mapNotNull { it.cachedQuote }
        val evidenceTexts = evidenceRefs.mapNotNull { ref ->
            snapshot.sources.firstOrNull { it.id == ref.sourceId }?.contentText
        }
        val evidenceRoles = evidenceRefs.mapNotNull { ref ->
            snapshot.sources.firstOrNull { it.id == ref.sourceId }?.speakerRoleName()
        }
        val relatedNames = relatedEntityIds.mapNotNull { id ->
            snapshot.entities.firstOrNull { it.id == id }?.canonicalName
        }
        val haystack = listOf(
            title,
            description.orEmpty(),
            status.name,
            priority.name,
            scope.text,
            acceptanceCriteria.joinToString("\n"),
            blockers.joinToString("\n"),
            relatedNames.joinToString("\n"),
            evidenceQuotes.joinToString("\n"),
            evidenceTexts.joinToString("\n"),
        ).joinToString("\n").lowercase()

        if (expectation.titleContainsAll.any { it.lowercase() !in title.lowercase() }) return false
        if (expectation.descriptionContainsAll.any { it.lowercase() !in description.orEmpty().lowercase() }) return false
        if (expectation.containsAll.any { it.lowercase() !in haystack }) return false
        if (expectation.evidenceQuoteContainsAll.any { needle ->
                (evidenceQuotes + evidenceTexts).none { needle.lowercase() in it.lowercase() }
            }
        ) return false
        if (expectation.evidenceSourceSpeakerRoleIn.isNotEmpty()) {
            val allowed = expectation.evidenceSourceSpeakerRoleIn.mapTo(mutableSetOf()) { it.uppercase() }
            if (evidenceRoles.isEmpty() || evidenceRoles.any { it !in allowed }) return false
        }

        return true
    }

    private fun ActionItemExpectation.describe(): String =
        "statusIn=$statusIn priorityIn=$priorityIn titleContainsAll=$titleContainsAll descriptionContainsAll=$descriptionContainsAll containsAll=$containsAll"

    private fun MemoryActionItem.describe(snapshot: MemoryNamespaceSnapshot): String {
        val evidence = evidenceRefs.mapNotNull { ref ->
            snapshot.sources.firstOrNull { it.id == ref.sourceId }?.contentText?.take(120)
        }
        return "${id.value}:${status.name}:${priority.name}:${title.take(120)} evidence=$evidence"
    }

    private fun MemoryProfile.matches(expectation: ProfileExpectation, snapshot: MemoryNamespaceSnapshot): Boolean {
        val owner = snapshot.entities.firstOrNull { it.id == ownerEntityId }
        if (expectation.ownerEntityTypeIn.isNotEmpty() && owner?.entityType?.name !in expectation.ownerEntityTypeIn.map { it.uppercase() }) return false
        if (expectation.ownerNameIn.isNotEmpty() && owner?.canonicalName !in expectation.ownerNameIn) return false
        if (expectation.textContainsAll.any { it.lowercase() !in profileText.lowercase() }) return false

        val haystack = listOf(
            profileText,
            profileJson.toString(),
            owner?.canonicalName.orEmpty(),
            owner?.entityType?.name.orEmpty(),
        ).joinToString("\n").lowercase()
        return expectation.containsAll.all { it.lowercase() in haystack }
    }

    private fun ProfileExpectation.describe(): String =
        "ownerEntityTypeIn=$ownerEntityTypeIn ownerNameIn=$ownerNameIn textContainsAll=$textContainsAll containsAll=$containsAll"

    private fun MemoryProfile.describe(snapshot: MemoryNamespaceSnapshot): String {
        val owner = snapshot.entities.firstOrNull { it.id == ownerEntityId }
        return "${id.value}:${owner?.entityType?.name}:${owner?.canonicalName}:${profileText.take(160)}"
    }

    private fun MemoryEpisode.matches(expectation: EpisodeExpectation, snapshot: MemoryNamespaceSnapshot): Boolean {
        val owner = ownerEntityId?.let { id -> snapshot.entities.firstOrNull { it.id == id } }
        if (expectation.ownerEntityTypeIn.isNotEmpty() && owner?.entityType?.name !in expectation.ownerEntityTypeIn.map { it.uppercase() }) return false
        if (expectation.ownerNameIn.isNotEmpty() && owner?.canonicalName !in expectation.ownerNameIn) return false

        val evidenceQuotes = evidenceRefs.mapNotNull { it.cachedQuote }
        val evidenceTexts = evidenceRefs.mapNotNull { ref ->
            snapshot.sources.firstOrNull { it.id == ref.sourceId }?.contentText
        }
        val evidenceRoles = evidenceRefs.mapNotNull { ref ->
            snapshot.sources.firstOrNull { it.id == ref.sourceId }?.speakerRoleName()
        }
        val haystack = listOf(
            situation,
            action,
            result,
            lesson,
            tags.joinToString("\n"),
            owner?.canonicalName.orEmpty(),
            evidenceQuotes.joinToString("\n"),
            evidenceTexts.joinToString("\n"),
        ).joinToString("\n").lowercase()

        if (expectation.situationContainsAll.any { it.lowercase() !in situation.lowercase() }) return false
        if (expectation.actionContainsAll.any { it.lowercase() !in action.lowercase() }) return false
        if (expectation.resultContainsAll.any { it.lowercase() !in result.lowercase() }) return false
        if (expectation.lessonContainsAll.any { it.lowercase() !in lesson.lowercase() }) return false
        if (expectation.containsAll.any { it.lowercase() !in haystack }) return false
        if (expectation.evidenceQuoteContainsAll.any { needle ->
                (evidenceQuotes + evidenceTexts).none { needle.lowercase() in it.lowercase() }
            }
        ) return false
        if (expectation.evidenceSourceSpeakerRoleIn.isNotEmpty()) {
            val allowed = expectation.evidenceSourceSpeakerRoleIn.mapTo(mutableSetOf()) { it.uppercase() }
            if (evidenceRoles.isEmpty() || evidenceRoles.any { it !in allowed }) return false
        }

        return true
    }

    private fun EpisodeExpectation.describe(): String =
        "ownerEntityTypeIn=$ownerEntityTypeIn ownerNameIn=$ownerNameIn situationContainsAll=$situationContainsAll " +
            "actionContainsAll=$actionContainsAll resultContainsAll=$resultContainsAll lessonContainsAll=$lessonContainsAll containsAll=$containsAll"

    private fun MemoryEpisode.describe(snapshot: MemoryNamespaceSnapshot): String {
        val owner = ownerEntityId?.let { id -> snapshot.entities.firstOrNull { it.id == id }?.canonicalName ?: id.value }
        return "${id.value}:owner=$owner lesson=${lesson.take(160)}"
    }

    private fun MemoryRun.matches(expectation: RunExpectation): Boolean {
        if (expectation.runTypeIn.isNotEmpty() && runType.name !in expectation.runTypeIn.map { it.uppercase() }) return false
        if (expectation.statusIn.isNotEmpty() && status.name !in expectation.statusIn.map { it.uppercase() }) return false
        if (expectation.appliedOpsMin != null && appliedOps.size < expectation.appliedOpsMin) return false
        if (expectation.retrievedItemRefsMin != null && retrievedItemRefs.size < expectation.retrievedItemRefsMin) return false

        val haystack = listOf(
            summary,
            promptName.orEmpty(),
            promptVersion.orEmpty(),
            output?.toString().orEmpty(),
            appliedOps.toString(),
            repairActions.toString(),
        ).joinToString("\n").lowercase()

        return expectation.containsAll.all { it.lowercase() in haystack }
    }

    private fun RunExpectation.describe(): String =
        "runTypeIn=$runTypeIn statusIn=$statusIn containsAll=$containsAll appliedOpsMin=$appliedOpsMin retrievedItemRefsMin=$retrievedItemRefsMin"

    private fun com.gromozeka.domain.model.memory.MemoryPredicateDefinition.matches(
        expectation: PredicateDefinitionExpectation,
    ): Boolean {
        if (expectation.predicateIn.isNotEmpty() && predicate !in expectation.predicateIn) return false
        if (expectation.cardinalityIn.isNotEmpty() && cardinality.name !in expectation.cardinalityIn.map { it.uppercase() }) return false
        if (expectation.temporalPolicyIn.isNotEmpty() && temporalPolicy.name !in expectation.temporalPolicyIn.map { it.uppercase() }) return false
        if (expectation.conflictPolicyIn.isNotEmpty() && conflictPolicy.name !in expectation.conflictPolicyIn.map { it.uppercase() }) return false
        if (expectation.objectKindIn.isNotEmpty() && objectKind.name !in expectation.objectKindIn.map { it.uppercase() }) return false
        if (expectation.semanticKindsIn.isNotEmpty()) {
            val actual = semanticKinds.mapTo(mutableSetOf()) { it.name }
            if (actual.intersect(expectation.semanticKindsIn.mapTo(mutableSetOf()) { it.uppercase() }).isEmpty()) return false
        }
        if (expectation.aggregateEffectIn.isNotEmpty() && aggregateEffect.name !in expectation.aggregateEffectIn.map { it.uppercase() }) return false

        val haystack = listOf(
            predicate,
            description,
            subjectType?.name.orEmpty(),
            objectKind.name,
            cardinality.name,
            temporalPolicy.name,
            conflictPolicy.name,
            semanticKinds.joinToString("\n") { it.name },
            aggregateEffect.name,
        ).joinToString("\n").lowercase()

        return expectation.containsAll.all { it.lowercase() in haystack }
    }

    private fun MemoryClaim.matches(expectation: ClaimExpectation, snapshot: MemoryNamespaceSnapshot): Boolean {
        if (expectation.predicateIn.isNotEmpty() && predicate !in expectation.predicateIn) return false
        if (expectation.predicateFamilyIn.isNotEmpty() && predicateFamily !in expectation.predicateFamilyIn) return false
        if (expectation.predicatePolicyCardinalityIn.isNotEmpty() && predicatePolicy?.cardinality?.name !in expectation.predicatePolicyCardinalityIn.map { it.uppercase() }) return false
        if (expectation.predicatePolicyTemporalPolicyIn.isNotEmpty() && predicatePolicy?.temporalPolicy?.name !in expectation.predicatePolicyTemporalPolicyIn.map { it.uppercase() }) return false
        if (expectation.predicatePolicyConflictPolicyIn.isNotEmpty() && predicatePolicy?.conflictPolicy?.name !in expectation.predicatePolicyConflictPolicyIn.map { it.uppercase() }) return false
        if (expectation.predicatePolicySemanticKindsIn.isNotEmpty()) {
            val actual = predicatePolicy?.semanticKinds?.mapTo(mutableSetOf()) { it.name }.orEmpty()
            if (actual.intersect(expectation.predicatePolicySemanticKindsIn.mapTo(mutableSetOf()) { it.uppercase() }).isEmpty()) return false
        }
        if (expectation.predicatePolicyAggregateEffectIn.isNotEmpty() && predicatePolicy?.aggregateEffect?.name !in expectation.predicatePolicyAggregateEffectIn.map { it.uppercase() }) return false
        if (expectation.statusIn.isNotEmpty() && status.name !in expectation.statusIn.map { it.uppercase() }) return false

        val subject = snapshot.entities.firstOrNull { it.id == subjectEntityId }
        val objectEntity = objectEntityId?.let { id -> snapshot.entities.firstOrNull { it.id == id } }
        val evidenceQuotes = evidenceRefs.mapNotNull { it.cachedQuote }
        val evidenceTexts = evidenceRefs.mapNotNull { ref ->
            snapshot.sources.firstOrNull { it.id == ref.sourceId }?.contentText
        }
        val evidenceRoles = evidenceRefs.mapNotNull { ref ->
            snapshot.sources.firstOrNull { it.id == ref.sourceId }?.speakerRoleName()
        }
        val haystack = listOf(
            normalizedText,
            contextText.orEmpty(),
            predicate,
            predicateFamily.orEmpty(),
            predicatePolicy?.description.orEmpty(),
            subject?.canonicalName.orEmpty(),
            objectEntity?.canonicalName.orEmpty(),
            objectValue?.toString().orEmpty(),
            predicatePolicy?.semanticKinds?.joinToString("\n") { it.name }.orEmpty(),
            predicatePolicy?.aggregateEffect?.name.orEmpty(),
        ).joinToString("\n").lowercase()

        if (expectation.normalizedContainsAll.any { it.lowercase() !in normalizedText.lowercase() }) return false
        if (expectation.containsAll.any { it.lowercase() !in haystack }) return false
        if (expectation.confidenceMin != null && confidence < expectation.confidenceMin) return false
        if (expectation.confidenceMax != null && confidence > expectation.confidenceMax) return false
        if (expectation.subjectEntityTypeIn.isNotEmpty() && subject?.entityType?.name !in expectation.subjectEntityTypeIn.map { it.uppercase() }) return false
        if (expectation.subjectEntityCanonicalNameIn.isNotEmpty() && subject?.canonicalName !in expectation.subjectEntityCanonicalNameIn) return false
        if (expectation.objectEntityTypeIn.isNotEmpty() && objectEntity?.entityType?.name !in expectation.objectEntityTypeIn.map { it.uppercase() }) return false
        if (expectation.objectEntityCanonicalNameIn.isNotEmpty() && objectEntity?.canonicalName !in expectation.objectEntityCanonicalNameIn) return false
        if (expectation.objectValueStringIn.isNotEmpty() && objectValue?.toString()?.trim('"') !in expectation.objectValueStringIn) return false
        if (expectation.objectValueContainsAll.any { it.lowercase() !in objectValue.toString().lowercase() }) return false
        if (expectation.evidenceQuoteContainsAll.any { needle ->
                (evidenceQuotes + evidenceTexts).none { needle.lowercase() in it.lowercase() }
            }
        ) return false
        if (expectation.evidenceSourceSpeakerRoleIn.isNotEmpty()) {
            val allowed = expectation.evidenceSourceSpeakerRoleIn.mapTo(mutableSetOf()) { it.uppercase() }
            if (evidenceRoles.isEmpty() || evidenceRoles.any { it !in allowed }) return false
        }

        return true
    }

    private fun MemoryClaim.describe(snapshot: MemoryNamespaceSnapshot): String {
        val subject = snapshot.entities.firstOrNull { it.id == subjectEntityId }?.canonicalName ?: subjectEntityId.value
        val objectText = objectEntityId
            ?.let { id -> snapshot.entities.firstOrNull { it.id == id }?.canonicalName ?: id.value }
            ?: objectValue?.toString().orEmpty()
        return "$predicate($subject, $objectText): $normalizedText"
    }

    private fun EntityExpectation.describe(): String =
        "typeIn=$typeIn canonicalNameIn=$canonicalNameIn normalizedNameIn=$normalizedNameIn containsAll=$containsAll"

    private fun PredicateDefinitionExpectation.describe(): String =
        "predicateIn=$predicateIn cardinalityIn=$cardinalityIn temporalPolicyIn=$temporalPolicyIn " +
            "conflictPolicyIn=$conflictPolicyIn objectKindIn=$objectKindIn semanticKindsIn=$semanticKindsIn " +
            "aggregateEffectIn=$aggregateEffectIn containsAll=$containsAll"

    private fun ClaimExpectation.describe(): String =
        "predicateIn=$predicateIn predicateFamilyIn=$predicateFamilyIn " +
            "policyCardinality=$predicatePolicyCardinalityIn policyTemporal=$predicatePolicyTemporalPolicyIn policyConflict=$predicatePolicyConflictPolicyIn " +
            "policySemanticKinds=$predicatePolicySemanticKindsIn policyAggregateEffect=$predicatePolicyAggregateEffectIn " +
            "containsAll=$containsAll normalizedContainsAll=$normalizedContainsAll " +
            "subjectEntityCanonicalNameIn=$subjectEntityCanonicalNameIn objectEntityCanonicalNameIn=$objectEntityCanonicalNameIn " +
            "objectValueStringIn=$objectValueStringIn objectValueContainsAll=$objectValueContainsAll evidenceQuoteContainsAll=$evidenceQuoteContainsAll"

    private fun List<Conversation.Message>.renderAssistantText(): String =
        filter { it.role == Conversation.Message.Role.ASSISTANT }
            .flatMap { message ->
                message.content.mapNotNull { item ->
                    when (item) {
                        is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
                        else -> null
                    }
                }
            }
            .joinToString("\n")
            .trim()

    private fun List<MemorySource>.countUserChatTurns(): Int =
        count { it is MemorySource.ChatTurn && it.speakerRole == MemorySource.ActorRole.USER }

    private fun List<MemorySource>.countAssistantChatTurns(): Int =
        count { it is MemorySource.ChatTurn && it.speakerRole == MemorySource.ActorRole.ASSISTANT }

    private fun MemorySource.speakerRoleName(): String? =
        when (this) {
            is MemorySource.ChatTurn -> speakerRole.name
            is MemorySource.ExternalRecord,
            is MemorySource.ImportedNote,
            is MemorySource.ToolOutput,
            -> MemorySource.ActorRole.EXTERNAL.name
        }

    private fun renderCaseReport(
        result: ExecutedMemoryE2eCase,
        errors: List<String>,
    ): String = buildString {
        appendLine("# ${result.definition.id}")
        appendLine()
        appendLine("category | ${result.definition.category}")
        appendLine("model | ${result.modelName}")
        appendLine("namespace | ${result.namespace.value}")
        appendLine("projectPath | ${result.projectPath}")
        appendLine("status | ${if (errors.isEmpty()) "PASS" else "FAIL"}")
        appendLine()
        appendLine("## Errors")
        if (errors.isEmpty()) {
            appendLine("none")
        } else {
            errors.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("## Recall Answers")
        result.recallResults.forEach { recall ->
            appendLine("### ${recall.sessionId}")
            appendLine()
            appendLine("Question: ${recall.text}")
            appendLine()
            appendLine(recall.answer.ifBlank { "<blank>" })
            appendLine()
        }
        appendLine("## Seed Write Traces")
        result.seedResults.forEach { seed ->
            appendLine("### ${seed.sessionId}")
            appendLine()
            appendLine("Input kind: ${seed.inputKind}")
            appendLine()
            appendLine("Message: ${seed.text.oneLineForProgressLog(1_000)}")
            if (seed.toolResult != null) {
                appendLine()
                appendLine("Tool result: ${seed.toolResult.oneLineForProgressLog(1_000)}")
            }
            if (seed.memoryRun != null) {
                appendLine()
                appendLine("Run: ${seed.memoryRun.id.value} ${seed.memoryRun.status.name} ${seed.memoryRun.summary}")
            }
            appendLine()
            appendLine(renderMemoryWriteTrace(seed.memoryWriteTrace))
            appendLine()
        }
        appendLine("## Maintenance Traces")
        result.maintenanceResults.forEachIndexed { index, maintenance ->
            appendLine("### ${index + 1}. ${maintenance.action}")
            appendLine()
            if (maintenance.traces.isEmpty()) {
                appendLine("missing")
            } else {
                maintenance.traces.forEach { trace ->
                    appendLine(renderMemoryMaintenanceTrace(trace))
                    appendLine()
                }
            }
        }
        appendLine("## Recall Write Traces")
        result.recallResults.forEach { recall ->
            appendLine("### ${recall.sessionId}")
            appendLine()
            appendLine("Question: ${recall.text}")
            appendLine()
            appendLine(renderMemoryWriteTrace(recall.memoryWriteTrace))
            appendLine()
        }
        appendLine("## Recall Traces")
        result.recallResults.forEach { recall ->
            appendLine("### ${recall.sessionId}")
            appendLine()
            appendLine(renderMemoryReadTrace(recall.memoryReadTrace))
            appendLine()
        }
        appendLine("## After Seeds Snapshot")
        appendLine(renderSnapshot(result.afterSeedsSnapshot))
        appendLine()
        appendLine("## Final Snapshot")
        appendLine(renderSnapshot(result.finalSnapshot))
    }

    private fun renderCaseStartupFailure(
        case: MemoryE2eCase,
        modelName: String,
        postgresSchema: String,
        error: Throwable,
    ): String = buildString {
        appendLine("# ${case.id}")
        appendLine()
        appendLine("category | ${case.category}")
        appendLine("model | $modelName")
        appendLine("postgresSchema | $postgresSchema")
        appendLine("timeoutSeconds | ${case.timeoutSeconds}")
        appendLine("status | FAIL")
        appendLine()
        appendLine("## Error")
        appendLine("`${error::class.simpleName}: ${error.message}`")
        appendLine()
        appendLine("## Scenario")
        appendLine("```json")
        appendLine(json.encodeToString(case))
        appendLine("```")
    }

    private fun renderSuiteSummary(
        postgresSchema: String,
        subscriptionPath: Path,
        modelName: String,
        caseCount: Int,
        failures: List<String>,
    ): String = buildString {
        appendLine("# Memory Real Model E2E Summary")
        appendLine()
        appendLine("postgresSchema | $postgresSchema")
        appendLine("subscription | $subscriptionPath")
        appendLine("model | $modelName")
        appendLine("caseCount | $caseCount")
        appendLine("databaseCleanup | unique schema per run, retained after run")
        appendLine("status | ${if (failures.isEmpty()) "PASS" else "FAIL"}")
        appendLine()
        if (failures.isNotEmpty()) {
            appendLine("## Failures")
            failures.forEach { failure ->
                appendLine("```")
                appendLine(failure)
                appendLine("```")
            }
        }
    }

    private fun copyDevLogArtifact(source: Path, target: Path) {
        if (!source.exists()) return
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        println("Memory e2e dev log saved to: $target")
    }

    private fun renderMemoryMaintenanceTrace(event: MemoryMaintenanceTraceEvent): String =
        buildString {
            appendLine("stage | ${event.stage.name}")
            when (val payload = event.payload) {
                is MemoryMaintenanceTraceEvent.Payload.NoteConsolidation -> {
                    val result = payload.result
                    appendLine("selectedNotes | ${result.selectedNotes.size}")
                    appendLine("relatedHits | ${result.relatedHits.size} ${result.relatedHits.breakdownForReport()}")
                    appendLine("rawClaimCandidates | ${result.rawConsolidationResult.claimCandidates.size}")
                    appendLine("finalClaimCandidates | ${result.consolidationResult.claimCandidates.size}")
                    appendLine("rawActionItemActions | ${result.rawConsolidationResult.actionItemActions.size} ${result.rawConsolidationResult.actionItemActions.actionItemOpsActionBreakdownForReport()}")
                    appendLine("finalActionItemActions | ${result.consolidationResult.actionItemActions.size} ${result.consolidationResult.actionItemActions.actionItemOpsActionBreakdownForReport()}")
                    appendLine("rawEpisodeCandidates | ${result.rawConsolidationResult.episodeCandidates.size}")
                    appendLine("finalEpisodeCandidates | ${result.consolidationResult.episodeCandidates.size}")
                    appendLine("rawNoteActions | ${result.rawConsolidationResult.noteActions.size} ${result.rawConsolidationResult.noteActions.noteLifecycleActionBreakdownForReport()}")
                    appendLine("finalNoteActions | ${result.consolidationResult.noteActions.size} ${result.consolidationResult.noteActions.noteLifecycleActionBreakdownForReport()}")
                    appendLine("materialized | runs=${result.memoryBatch.runs.size} notes=${result.memoryBatch.notes.size} claims=${result.memoryBatch.claims.size} actionItems=${result.memoryBatch.actionItems.size} episodes=${result.memoryBatch.episodes.size} profiles=${result.memoryBatch.profiles.size}")
                    appendLine()
                    appendLine("Selected notes:")
                    appendLine(result.selectedNotes.joinToString("\n") { "- ${it.id.value}: ${it.noteType.name}:${it.status.name}/${it.maturity.name}: ${it.title}; ${it.summary}" }.ifBlank { "- none" })
                    appendLine()
                    appendLine("Related hits:")
                    appendLine(result.relatedHits.renderHitsForExpectation().ifBlank { "- none" })
                    appendLine()
                    appendLine("Raw consolidation:")
                    appendLine(result.rawConsolidationResult.renderForExpectation().ifBlank { "- none" })
                    appendLine()
                    appendLine("Final consolidation:")
                    appendLine(result.consolidationResult.renderForExpectation().ifBlank { "- none" })
                }

                is MemoryMaintenanceTraceEvent.Payload.MemoryRepair -> {
                    val result = payload.result
                    appendLine("candidateClusters | ${result.candidateClusters.size}")
                    appendLine("suspiciousHits | ${result.suspiciousHits.size} ${result.suspiciousHits.breakdownForReport()}")
                    appendLine("actions | ${result.repairPlan.repairActions.size}")
                    appendLine("materialized | runs=${result.memoryBatch.runs.size} notes=${result.memoryBatch.notes.size} claims=${result.memoryBatch.claims.size} actionItems=${result.memoryBatch.actionItems.size} episodes=${result.memoryBatch.episodes.size} profiles=${result.memoryBatch.profiles.size}")
                    appendLine("summary | ${result.repairPlan.summary}")
                    appendLine("Clusters:")
                    appendLine(
                        result.candidateClusters.joinToString("\n") { cluster ->
                            buildString {
                                appendLine("- ${cluster.id}: ${cluster.kind.name}; hits=${cluster.hits.size}; reason=${cluster.reason}")
                                append(cluster.hits.renderHitsForExpectation())
                            }.trim()
                        }.ifBlank { "- none" }
                    )
                    appendLine("Actions:")
                    appendLine(result.repairPlan.repairActions.joinToString("\n") { "- ${it.action.name}: ${it.targetType.name}:${it.targetIds.joinToString(",")} reason=${it.reason}" }.ifBlank { "- none" })
                    appendLine("Materialized claims:")
                    appendLine(result.memoryBatch.claims.joinToString("\n") { "- ${it.id.value}: ${it.status.name.lifecycleStatusForReport(it.archivedAt)}:${it.predicate}:${it.normalizedText}" }.ifBlank { "- none" })
                    appendLine("Materialized notes:")
                    appendLine(result.memoryBatch.notes.joinToString("\n") { "- ${it.id.value}: ${it.status.name.lifecycleStatusForReport(it.archivedAt)}:${it.noteType.name}:${it.title}; ${it.summary}" }.ifBlank { "- none" })
                    appendLine("Materialized actionItems:")
                    appendLine(result.memoryBatch.actionItems.joinToString("\n") { "- ${it.id.value}: ${it.status.name.lifecycleStatusForReport(it.archivedAt)}:${it.title}; ${it.description.orEmpty()}" }.ifBlank { "- none" })
                    appendLine("Materialized episodes:")
                    appendLine(result.memoryBatch.episodes.joinToString("\n") { "- ${it.id.value}: ${it.archivedAt.episodeLifecycleStatusForReport()}: ${it.lesson}" }.ifBlank { "- none" })
                    appendLine("Materialized profiles:")
                    appendLine(result.memoryBatch.profiles.joinToString("\n") { "- ${it.id.value}: ${it.profileText.oneLineForProgressLog(500)}" }.ifBlank { "- none" })
                }

                is MemoryMaintenanceTraceEvent.Payload.EntityMaintenance -> {
                    val result = payload.result
                    appendLine("candidateGroups | ${result.candidateGroups.size}")
                    appendLine("actions | ${result.maintenancePlan.actions.size}")
                    appendLine("materialized | runs=${result.memoryBatch.runs.size} entities=${result.memoryBatch.entities.size} notes=${result.memoryBatch.notes.size} claims=${result.memoryBatch.claims.size} actionItems=${result.memoryBatch.actionItems.size} episodes=${result.memoryBatch.episodes.size} profiles=${result.memoryBatch.profiles.size}")
                    appendLine("summary | ${result.maintenancePlan.summary}")
                    appendLine(result.maintenancePlan.actions.joinToString("\n") { "- ${it.action.name}: winner=${it.winnerEntityId ?: "null"} losers=${it.loserEntityIds.joinToString(",")} reason=${it.reason}" }.ifBlank { "- none" })
                }

                is MemoryMaintenanceTraceEvent.Payload.Retention -> {
                    val result = payload.result
                    appendLine("candidates | ${result.candidates.size} ${result.candidates.breakdownForReport()}")
                    appendLine("actions | ${result.retentionPlan.retentionActions.size}")
                    appendLine("materialized | runs=${result.memoryBatch.runs.size} notes=${result.memoryBatch.notes.size} claims=${result.memoryBatch.claims.size} actionItems=${result.memoryBatch.actionItems.size}")
                    appendLine("summary | ${result.retentionPlan.summary}")
                    appendLine(result.retentionPlan.retentionActions.joinToString("\n") { "- ${it.action.name}: ${it.targetType.name}:${it.targetIds.joinToString(",")} reason=${it.reason}" }.ifBlank { "- none" })
                }
            }
        }

    private fun String.lifecycleStatusForReport(archivedAt: kotlinx.datetime.Instant?): String =
        archivedAt?.let { "ARCHIVED(status=$this,archivedAt=$it)" } ?: this

    private fun kotlinx.datetime.Instant?.episodeLifecycleStatusForReport(): String =
        this?.let { "ARCHIVED(archivedAt=$it)" } ?: "ACTIVE"

    private fun renderMemoryWriteTrace(event: MemoryWriteTraceEvent?): String {
        if (event == null) return "missing"

        val result = event.result
        return buildString {
            appendLine("routeDecision | ${result.routeDecision.decision.name}")
            appendLine("memoryTypes | ${result.routeDecision.memoryTypes.joinToString { it.name }.ifBlank { "none" }}")
            appendLine("salience | ${result.routeDecision.salience}")
            appendLine("reason | ${result.routeDecision.reason}")
            appendLine("sourcePolicy | structured=${result.routeDecision.sourcePolicy.allowStructuredExtraction} recall=${result.routeDecision.sourcePolicy.allowRecall} evidence=${result.routeDecision.sourcePolicy.allowEvidenceHydration}")
            appendLine("retrievalPlan | ${result.retrievalPlan?.renderForReport() ?: "none"}")
            appendLine("retrievedHits | ${result.retrievedHits.size} ${result.retrievedHits.breakdownForReport()}")
            appendLine("entityOps | ${result.entityOps.size} ${result.entityOps.entityOpsActionBreakdownForReport()}")
            appendLine("noteCandidates | ${result.noteCandidates.size}")
            appendLine("rawNoteOps | ${result.rawNoteOps.size} ${result.rawNoteOps.noteOpsActionBreakdownForReport()}")
            appendLine("noteOps | ${result.noteOps.size} ${result.noteOps.noteOpsActionBreakdownForReport()}")
            appendLine("claimCandidates | ${result.claimCandidates.size}")
            appendLine("rawClaimOps | ${result.rawClaimOps.size} ${result.rawClaimOps.claimOpsActionBreakdownForReport()}")
            appendLine("claimOps | ${result.claimOps.size} ${result.claimOps.claimOpsActionBreakdownForReport()}")
            appendLine("rawActionItemOps | ${result.rawActionItemOps.size} ${result.rawActionItemOps.actionItemOpsActionBreakdownForReport()}")
            appendLine("actionItemOps | ${result.actionItemOps.size} ${result.actionItemOps.actionItemOpsActionBreakdownForReport()}")
            appendLine("materialized | runs=${result.memoryBatch.runs.size} entities=${result.memoryBatch.entities.size} notes=${result.memoryBatch.notes.size} claims=${result.memoryBatch.claims.size} actionItems=${result.memoryBatch.actionItems.size} profiles=${result.memoryBatch.profiles.size}")
            appendLine()
            appendLine("Retrieved hits:")
            appendLine(result.retrievedHits.renderHitsForExpectation().ifBlank { "- none" })
            appendLine()
            appendLine("Entity ops:")
            appendLine(result.entityOps.renderEntityOpsForExpectation().ifBlank { "- none" })
            appendLine()
            appendLine("Note candidates:")
            appendLine(result.noteCandidates.joinToString("\n") { "- ${it.noteType.name}: ${it.title}; ${it.summary}" }.ifBlank { "- none" })
            appendLine()
            appendLine("Raw note ops:")
            appendLine(result.rawNoteOps.renderNoteOpsForExpectation().ifBlank { "- none" })
            appendLine()
            appendLine("Final note ops:")
            appendLine(result.noteOps.renderNoteOpsForExpectation().ifBlank { "- none" })
            appendLine()
            appendLine("Claim candidates:")
            appendLine(result.claimCandidates.joinToString("\n") { "- ${it.predicate}: ${it.normalizedText}" }.ifBlank { "- none" })
            appendLine()
            appendLine("Raw claim ops:")
            appendLine(result.rawClaimOps.renderClaimOpsForExpectation().ifBlank { "- none" })
            appendLine()
            appendLine("Final claim ops:")
            appendLine(result.claimOps.renderClaimOpsForExpectation().ifBlank { "- none" })
            appendLine()
            appendLine("Raw actionItem ops:")
            appendLine(result.rawActionItemOps.renderTaskOpsForExpectation().ifBlank { "- none" })
            appendLine()
            appendLine("Final actionItem ops:")
            appendLine(result.actionItemOps.renderTaskOpsForExpectation().ifBlank { "- none" })
        }
    }

    private fun com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan.renderForReport(): String =
        "need=$needRetrieval types=${memoryTypes.joinToString { it.name }.ifBlank { "none" }} " +
            "entityQueries=${entityQueries.joinToString("|").ifBlank { "none" }} " +
            "textQueries=${textQueries.joinToString("|").ifBlank { "none" }} " +
            "predicateHints=${predicateHints.joinToString("|").ifBlank { "none" }} budget=$retrievalBudget"

    private fun List<MemoryStore.SearchHit>.breakdownForReport(): String =
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

    private fun List<MemoryStore.SearchHit>.renderHitsForExpectation(): String =
        joinToString("\n") { hit ->
            when (hit) {
                is MemoryStore.SearchHit.SourceHit -> "- SOURCE:${hit.source.id.value}: ${hit.source.contentText.oneLineForProgressLog(240)}"
                is MemoryStore.SearchHit.EntityHit -> "- ENTITY:${hit.entity.id.value}: ${hit.entity.entityType.name}:${hit.entity.canonicalName}"
                is MemoryStore.SearchHit.ClaimHit -> "- CLAIM:${hit.claim.id.value}: ${hit.claim.predicate}:${hit.claim.normalizedText}"
                is MemoryStore.SearchHit.NoteHit -> "- NOTE:${hit.note.id.value}: ${hit.note.noteType.name}:${hit.note.title}; ${hit.note.summary}"
                is MemoryStore.SearchHit.ActionItemHit -> "- ACTION_ITEM:${hit.actionItem.id.value}: ${hit.actionItem.status.name}:${hit.actionItem.title}"
                is MemoryStore.SearchHit.ProfileHit -> "- PROFILE:${hit.profile.id.value}: ${hit.profile.profileText.oneLineForProgressLog(240)}"
                is MemoryStore.SearchHit.EpisodeHit -> "- EPISODE:${hit.episode.id.value}: ${hit.episode.lesson}"
                is MemoryStore.SearchHit.RunHit -> "- RUN:${hit.run.id.value}: ${hit.run.runType.name}:${hit.run.summary}"
            }
        }

    private fun List<MemoryEntityCanonicalizationOp>.entityOpsActionBreakdownForReport(): String =
        groupingBy { it.action.name }.eachCount().entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "none" }

    private fun List<MemoryEntityCanonicalizationOp>.renderEntityOpsForExpectation(): String =
        joinToString("\n") { op ->
            "- ${op.action.name}: mention=${op.mention} entity=${op.entityId?.value ?: "null"} name=${op.newEntity?.canonicalName ?: op.aliasText ?: "null"} reason=${op.reason}"
        }

    private fun List<MemoryNoteReconciliationOp>.noteOpsActionBreakdownForReport(): String =
        groupingBy { it.action.name }.eachCount().entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "none" }

    private fun List<MemoryNoteReconciliationOp>.renderNoteOpsForExpectation(): String =
        joinToString("\n") { op ->
            "- ${op.action.name}: target=${op.targetNoteId?.value ?: "null"} candidate=${op.candidate?.let { "${it.noteType.name}:${it.title}; ${it.summary}" } ?: "null"} reason=${op.reason}"
        }

    private fun List<MemoryClaimReconciliationOp>.claimOpsActionBreakdownForReport(): String =
        groupingBy { it.action.name }.eachCount().entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "none" }

    private fun List<MemoryClaimReconciliationOp>.renderClaimOpsForExpectation(): String =
        joinToString("\n") { op ->
            "- ${op.action.name}: target=${op.targetClaimId?.value ?: "null"} candidate=${op.candidate?.let { "${it.predicate}:${it.normalizedText}" } ?: "null"} reason=${op.reason}"
        }

    private fun List<MemoryActionItemUpdateOp>.actionItemOpsActionBreakdownForReport(): String =
        groupingBy { it.action.name }.eachCount().entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "none" }

    private fun List<MemoryActionItemUpdateOp>.renderTaskOpsForExpectation(): String =
        joinToString("\n") { op ->
            "- ${op.action.name}: target=${op.targetActionItemId?.value ?: "null"} actionItem=${op.actionItem?.let { "${it.status.name}:${it.title}; ${it.description ?: ""}" } ?: "null"} reason=${op.reason}"
        }

    private fun NoteConsolidationResult.renderForExpectation(): String = buildString {
        appendLine("Claim candidates:")
        appendLine(claimCandidates.renderClaimCandidatesForExpectation().ifBlank { "- none" })
        appendLine("Task actions:")
        appendLine(actionItemActions.renderTaskOpsForExpectation().ifBlank { "- none" })
        appendLine("Episode candidates:")
        appendLine(episodeCandidates.renderEpisodeCandidatesForExpectation().ifBlank { "- none" })
        appendLine("Note actions:")
        appendLine(noteActions.renderNoteLifecycleOpsForExpectation().ifBlank { "- none" })
        appendLine("Profile projection:")
        appendLine(profileProjection?.profileText ?: "- none")
        appendLine("Summary:")
        appendLine(summary)
    }

    private fun List<MemoryClaimCandidate>.renderClaimCandidatesForExpectation(): String =
        joinToString("\n") { candidate ->
            "- ${candidate.predicate}: subject=${candidate.subjectEntityId.value} object=${candidate.objectEntityId?.value ?: candidate.objectValue.toString()} originNote=${candidate.originNoteId?.value ?: "null"} text=${candidate.normalizedText} reason=${candidate.reason}"
        }

    private fun List<MemoryEpisodeCandidate>.renderEpisodeCandidatesForExpectation(): String =
        joinToString("\n") { candidate ->
            "- originNote=${candidate.originNoteId?.value ?: "null"} owner=${candidate.ownerEntityId?.value ?: "null"} situation=${candidate.situation}; action=${candidate.action}; result=${candidate.result}; lesson=${candidate.lesson}; reason=${candidate.reason}"
        }

    private fun List<MemoryNoteLifecycleOp>.noteLifecycleActionBreakdownForReport(): String =
        groupingBy { it.action.name }.eachCount().entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "none" }

    private fun List<MemoryNoteLifecycleOp>.renderNoteLifecycleOpsForExpectation(): String =
        joinToString("\n") { op ->
            "- ${op.action.name}: note=${op.noteId.value} reason=${op.reason}"
        }

    private fun renderMemoryReadTrace(event: MemoryReadTraceEvent?): String {
        if (event == null) return "missing"

        val result = event.result
        val trace = result.trace
        return buildString {
            appendLine("needMemory | ${result.plan.needMemory}")
            appendLine("answerMode | ${result.plan.answerMode.name}")
            appendLine("selectedHits | ${trace.selectedHits.size}")
            appendLine("injectedPromptChars | ${result.runtimePrompt?.length ?: 0}")
            appendLine()
            appendLine("Source safety:")
            appendLine("suppressedSources | ${trace.sourceSafety.suppressedSources.size}")
            appendLine(trace.sourceSafety.suppressedSources.renderTraceHitsForExpectation().ifBlank { "- none" })
            appendLine("restoredTypedHits | ${trace.sourceSafety.restoredTypedHits.size}")
            appendLine(trace.sourceSafety.restoredTypedHits.renderTraceHitsForExpectation().ifBlank { "- none" })
            appendLine()
            appendLine("Selector decisions:")
            if (trace.selectorDecisions.isEmpty()) {
                appendLine("- none")
            } else {
                trace.selectorDecisions.forEach { decision ->
                    appendLine("- ${decision.renderForExpectation()}")
                }
            }
            appendLine()
            appendLine("Selected hits:")
            if (trace.selectedHits.isEmpty()) {
                appendLine("- none")
            } else {
                trace.selectedHits.forEach { hit ->
                    appendLine("- ${hit.ref.type.name}:${hit.ref.id} score=${hit.score} predicate=${hit.predicate ?: "-"} text=${hit.summary}")
                }
            }
            appendLine()
            appendLine("Search steps:")
            if (trace.searchSteps.isEmpty()) {
                appendLine("- none")
            } else {
                trace.searchSteps.forEach { step ->
                    appendLine("- ${step.stage} scope=${step.scope} raw=${step.rawCount} candidates=${step.candidateCount} selected=${step.selectedCount} query=${step.query.oneLineForProgressLog()}")
                }
            }
            appendLine()
            appendLine("Injected prompt preview:")
            appendLine(trace.injectedPrompt?.preview ?: "none")
        }
    }

    private fun renderSnapshot(snapshot: MemoryNamespaceSnapshot): String =
        json.encodeToString(
            RenderedSnapshot(
                predicateDefinitions = snapshot.predicateDefinitions.map {
                    RenderedPredicateDefinition(
                        id = it.id.value,
                        predicate = it.predicate,
                        description = it.description,
                        subjectType = it.subjectType?.name,
                        objectKind = it.objectKind.name,
                        cardinality = it.cardinality.name,
                        temporalPolicy = it.temporalPolicy.name,
                        conflictPolicy = it.conflictPolicy.name,
                        semanticKinds = it.semanticKinds.map { kind -> kind.name },
                        aggregateEffect = it.aggregateEffect.name,
                        profileSync = it.profileSync,
                        actionItemSync = it.actionItemSync,
                        defaultImportance = it.defaultImportance,
                    )
                },
                sources = snapshot.sources.map { source ->
                    RenderedSource(
                        id = source.id.value,
                        type = source::class.simpleName.orEmpty(),
                        role = source.speakerRoleName(),
                        text = source.contentText.take(500),
                        searchText = source.searchText?.take(500),
                    )
                },
                entities = snapshot.entities.map {
                    RenderedEntity(
                        id = it.id.value,
                        type = it.entityType.name,
                        canonicalName = it.canonicalName,
                        normalizedName = it.normalizedName,
                    )
                },
                claims = snapshot.claims.map { claim ->
                    RenderedClaim(
                        id = claim.id.value,
                        status = claim.status.name,
                        predicate = claim.predicate,
                        predicateFamily = claim.predicateFamily,
                        predicatePolicy = claim.predicatePolicy?.let {
                            RenderedPredicatePolicy(
                                cardinality = it.cardinality.name,
                                temporalPolicy = it.temporalPolicy.name,
                                conflictPolicy = it.conflictPolicy.name,
                                semanticKinds = it.semanticKinds.map { kind -> kind.name },
                                aggregateEffect = it.aggregateEffect.name,
                                description = it.description,
                            )
                        },
                        subject = snapshot.entities.firstOrNull { it.id == claim.subjectEntityId }?.canonicalName,
                        objectEntity = claim.objectEntityId?.let { id ->
                            snapshot.entities.firstOrNull { it.id == id }?.canonicalName ?: id.value
                        },
                        objectValue = claim.objectValue?.toString(),
                        normalizedText = claim.normalizedText,
                        confidence = claim.confidence,
                        evidenceQuotes = claim.evidenceRefs.mapNotNull { it.cachedQuote },
                        evidenceSourceRoles = claim.evidenceRefs.mapNotNull { ref ->
                            snapshot.sources.firstOrNull { it.id == ref.sourceId }?.speakerRoleName()
                        },
                    )
                },
                notes = snapshot.notes.map { note ->
                    RenderedNote(
                        id = note.id.value,
                        type = note.noteType.name,
                        status = note.status.name,
                        maturity = note.maturity.name,
                        title = note.title,
                        summary = note.summary,
                        scope = note.scope.text,
                        keywords = note.keywords,
                        tags = note.tags,
                        evidenceQuotes = note.evidenceRefs.mapNotNull { it.cachedQuote },
                        evidenceSourceRoles = note.evidenceRefs.mapNotNull { ref ->
                            snapshot.sources.firstOrNull { it.id == ref.sourceId }?.speakerRoleName()
                        },
                    )
                },
                actionItems = snapshot.actionItems.map { actionItem ->
                    RenderedTask(
                        id = actionItem.id.value,
                        status = actionItem.status.name,
                        priority = actionItem.priority.name,
                        title = actionItem.title,
                        description = actionItem.description,
                        scope = actionItem.scope.text,
                        dueAt = actionItem.dueAt?.toString(),
                        acceptanceCriteria = actionItem.acceptanceCriteria,
                        blockers = actionItem.blockers,
                        evidenceQuotes = actionItem.evidenceRefs.mapNotNull { it.cachedQuote },
                        evidenceSourceRoles = actionItem.evidenceRefs.mapNotNull { ref ->
                            snapshot.sources.firstOrNull { it.id == ref.sourceId }?.speakerRoleName()
                        },
                    )
                },
                profiles = snapshot.profiles.size,
                episodes = snapshot.episodes.map { episode ->
                    RenderedEpisode(
                        id = episode.id.value,
                        owner = episode.ownerEntityId?.let { id ->
                            snapshot.entities.firstOrNull { it.id == id }?.canonicalName ?: id.value
                        },
                        situation = episode.situation,
                        action = episode.action,
                        result = episode.result,
                        lesson = episode.lesson,
                        tags = episode.tags,
                        successScore = episode.successScore,
                        evidenceQuotes = episode.evidenceRefs.mapNotNull { it.cachedQuote },
                        evidenceSourceRoles = episode.evidenceRefs.mapNotNull { ref ->
                            snapshot.sources.firstOrNull { it.id == ref.sourceId }?.speakerRoleName()
                        },
                    )
                },
                runs = snapshot.runs.size,
            )
        )

    private fun resolveCasesDirectory(): Path =
        Path.of(resolveProjectRoot())
            .resolve("server")
            .resolve("src")
            .resolve("test")
            .resolve("resources")
            .resolve("memory")
            .resolve("e2e-real-model-cases")

    private fun resolveProjectRoot(): String {
        val cwd = Path.of("").toAbsolutePath().normalize()
        if (cwd.resolve("settings.gradle.kts").exists()) return cwd.absolutePathString()
        return cwd.parent
            ?.takeIf { it.resolve("settings.gradle.kts").exists() }
            ?.absolutePathString()
            ?: cwd.absolutePathString()
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

    private fun appendProgress(path: Path, message: String) {
        val line = "${Clock.System.now()} $message\n"
        progressLog.info { "Memory e2e progress: $message" }
        Files.writeString(
            path,
            line,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun MemoryReadTraceEvent?.progressSummaryForProgressLog(): String {
        val event = this ?: return "missing"

        val readResult = event.result
        val selected = readResult.trace.selectedHits
            .take(4)
            .joinToString("|") { hit ->
                "${hit.ref.type.name.lowercase()}:${hit.ref.id}:${hit.predicate.orEmpty()}:${hit.summary.oneLineForProgressLog(100)}"
            }
            .ifBlank { "none" }
        val selectorRejected = readResult.trace.selectorDecisions.count { !it.selected }
        return "need=${readResult.plan.needMemory} mode=${readResult.plan.answerMode.name} selected=${readResult.trace.selectedHits.size} selectorRejected=$selectorRejected injectedChars=${readResult.runtimePrompt?.length ?: 0} top=$selected"
    }

    private fun MemoryWriteTraceEvent?.progressSummaryForProgressLog(): String {
        val event = this ?: return "missing"
        val result = event.result
        return "route=${result.routeDecision.decision.name} retrieved=${result.retrievedHits.size} " +
            "rawNote=${result.rawNoteOps.noteOpsActionBreakdownForReport()} note=${result.noteOps.noteOpsActionBreakdownForReport()} " +
            "rawClaim=${result.rawClaimOps.claimOpsActionBreakdownForReport()} claim=${result.claimOps.claimOpsActionBreakdownForReport()} " +
            "rawTask=${result.rawActionItemOps.actionItemOpsActionBreakdownForReport()} actionItem=${result.actionItemOps.actionItemOpsActionBreakdownForReport()} " +
            "batch=notes:${result.memoryBatch.notes.size},claims:${result.memoryBatch.claims.size},actionItems:${result.memoryBatch.actionItems.size}"
    }

    private fun MemoryMaintenanceTraceEvent.progressSummaryForProgressLog(): String =
        when (val payload = payload) {
            is MemoryMaintenanceTraceEvent.Payload.NoteConsolidation -> {
                val result = payload.result
                "stage=${stage.name} selected=${result.selectedNotes.size} " +
                    "rawClaims=${result.rawConsolidationResult.claimCandidates.size} finalClaims=${result.consolidationResult.claimCandidates.size} " +
                    "rawTasks=${result.rawConsolidationResult.actionItemActions.actionItemOpsActionBreakdownForReport()} finalTasks=${result.consolidationResult.actionItemActions.actionItemOpsActionBreakdownForReport()} " +
                    "rawEpisodes=${result.rawConsolidationResult.episodeCandidates.size} finalEpisodes=${result.consolidationResult.episodeCandidates.size} " +
                    "batch=notes:${result.memoryBatch.notes.size},claims:${result.memoryBatch.claims.size},actionItems:${result.memoryBatch.actionItems.size},episodes:${result.memoryBatch.episodes.size}"
            }

            is MemoryMaintenanceTraceEvent.Payload.MemoryRepair ->
                "stage=${stage.name} clusters=${payload.result.candidateClusters.size} suspicious=${payload.result.suspiciousHits.size} actions=${payload.result.repairPlan.repairActions.size}"

            is MemoryMaintenanceTraceEvent.Payload.EntityMaintenance ->
                "stage=${stage.name} groups=${payload.result.candidateGroups.size} actions=${payload.result.maintenancePlan.actions.size}"

            is MemoryMaintenanceTraceEvent.Payload.Retention ->
                "stage=${stage.name} candidates=${payload.result.candidates.size} actions=${payload.result.retentionPlan.retentionActions.size}"
        }

    private fun String?.oneLineForProgressLog(maxChars: Int = 180): String {
        val normalized = orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length <= maxChars) return normalized
        return normalized.take(maxChars) + "...[truncated ${normalized.length - maxChars} chars]"
    }

    private companion object {
        val progressLog = KLoggers.logger("MemoryRealModelE2eProgress")
        const val ENABLE_PROPERTY = "gromozeka.memory.e2e"
        const val SUBSCRIPTION_CONFIG_PROPERTY = "gromozeka.memory.e2e.subscriptionConfig"
        const val CASE_FILTER_PROPERTY = "gromozeka.memory.e2e.caseFilter"
        const val MODEL_NAME_PROPERTY = "gromozeka.memory.e2e.modelName"
        const val WEBSOCKET_RESPONSE_TIMEOUT_MS_PROPERTY = "gromozeka.memory.e2e.websocketResponseTimeoutMs"
        const val WEBSOCKET_TRANSPORT_TIMEOUT_MS_PROPERTY = "gromozeka.memory.e2e.websocketTransportTimeoutMs"
        const val TURN_COMPLETION_TIMEOUT_MS_PROPERTY = "gromozeka.memory.e2e.turnCompletionTimeoutMs"
        const val MEMORY_LLM_STAGE_TIMEOUT_MS_PROPERTY = "gromozeka.memory.e2e.memoryLlmStageTimeoutMs"
        const val DIRECT_WEBSOCKET_RESPONSE_TIMEOUT_MS_PROPERTY = "gromozeka.ai.openai-subscription.websocket-response-timeout-ms"
        const val DIRECT_WEBSOCKET_TRANSPORT_TIMEOUT_MS_PROPERTY = "gromozeka.ai.openai-subscription.websocket-transport-timeout-ms"
        const val DIRECT_MEMORY_LLM_STAGE_TIMEOUT_MS_PROPERTY = "gromozeka.memory.llm.timeoutMs"
        const val MEMORY_WRITE_PARALLELISM_PROPERTY = "gromozeka.memory.e2e.memoryWriteParallelism"
        const val MEMORY_ROUTING_FAIL_FAST_PROPERTY = "gromozeka.memory.routing.failFast"
        const val DEFAULT_MODEL_NAME = "gpt-5.5"

        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        fun systemProperty(primary: String, fallback: String, default: String): String =
            System.getProperty(primary) ?: System.getProperty(fallback) ?: default
    }
}

@TestConfiguration(proxyBeanMethods = false)
class MemoryRealModelE2eNoToolsConfig {
    @Bean
    @Primary
    fun aiToolProvider(): AiToolProvider = object : AiToolProvider {
        override fun getTools(): List<AiToolCallback> = emptyList()
    }

    @Bean
    @Primary
    fun memoryEmbeddingIndexer(): MemoryEmbeddingIndexer = NoOpMemoryEmbeddingIndexer

    @Bean
    fun memoryE2eReadTraceCollector(): MemoryE2eReadTraceCollector = MemoryE2eReadTraceCollector()

    @Bean
    fun memoryE2eWriteTraceCollector(): MemoryE2eWriteTraceCollector = MemoryE2eWriteTraceCollector()

    @Bean
    fun memoryE2eMaintenanceTraceCollector(): MemoryE2eMaintenanceTraceCollector = MemoryE2eMaintenanceTraceCollector()
}

class MemoryE2eReadTraceCollector : MemoryReadTraceSink {
    private val events = ConcurrentHashMap<String, MemoryReadTraceEvent>()
    private val eventsByNamespace = ConcurrentHashMap<String, ConcurrentLinkedQueue<MemoryReadTraceEvent>>()

    override fun onMemoryRead(event: MemoryReadTraceEvent) {
        events[event.targetMessageId.value] = event
        eventsByNamespace.computeIfAbsent(event.namespace.value) { ConcurrentLinkedQueue() }.add(event)
    }

    fun take(messageId: Conversation.Message.Id): MemoryReadTraceEvent? =
        events.remove(messageId.value)

    fun takeLatest(namespace: MemoryNamespace): MemoryReadTraceEvent? {
        val queue = eventsByNamespace[namespace.value] ?: return null
        var latest: MemoryReadTraceEvent? = null
        while (true) {
            latest = queue.poll() ?: return latest
        }
    }
}

class MemoryE2eWriteTraceCollector : MemoryWriteTraceSink {
    private val events = ConcurrentHashMap<String, MemoryWriteTraceEvent>()
    private val eventsBySourceId = ConcurrentHashMap<String, ConcurrentLinkedQueue<MemoryWriteTraceEvent>>()

    override fun onMemoryWrite(event: MemoryWriteTraceEvent) {
        events[event.targetMessageId.value] = event
        event.result.sourceBatch.sources.forEach { source ->
            eventsBySourceId.computeIfAbsent(source.id.value) { ConcurrentLinkedQueue() }.add(event)
        }
    }

    fun take(messageId: Conversation.Message.Id): MemoryWriteTraceEvent? =
        events.remove(messageId.value)

    fun takeBySourceId(sourceId: String): MemoryWriteTraceEvent? =
        eventsBySourceId[sourceId]?.poll()
}

class MemoryE2eMaintenanceTraceCollector : MemoryMaintenanceTraceSink {
    private val events = ConcurrentHashMap<String, ConcurrentLinkedQueue<MemoryMaintenanceTraceEvent>>()

    override fun onMemoryMaintenance(event: MemoryMaintenanceTraceEvent) {
        events.computeIfAbsent(event.conversationId.value) { ConcurrentLinkedQueue() }.add(event)
    }

    fun take(conversationId: Conversation.Id): List<MemoryMaintenanceTraceEvent> {
        val queue = events.remove(conversationId.value) ?: return emptyList()
        return buildList {
            while (true) {
                add(queue.poll() ?: break)
            }
        }
    }
}

@Serializable
private data class MemoryE2eCase(
    val id: String,
    val category: String,
    val description: String = "",
    val timeoutSeconds: Long = 600,
    val preloadedMemory: List<MemoryE2ePreloadedMemory> = emptyList(),
    val seedSessions: List<MemoryE2eSeedSession> = emptyList(),
    val maintenanceAfterSeeds: List<String> = emptyList(),
    val maintenanceTraceExpectations: List<MemoryMaintenanceTraceExpectation> = emptyList(),
    val recallSessions: List<MemoryE2eRecallSession> = emptyList(),
    val expectedAfterSeeds: SnapshotExpectation = SnapshotExpectation(),
    val expectedFinal: SnapshotExpectation = SnapshotExpectation(),
)

@Serializable
private data class MemoryE2ePreloadedMemory(
    val type: String,
    val subjectName: String = "Gromozeka",
    val subjectType: String = "PROJECT",
    val predicate: String = "fixture_memory",
    val predicateDescription: String = "",
    val scopeText: String = "Project memory",
    val olderValue: String = "",
    val olderText: String = "",
    val newerValue: String = "",
    val newerText: String = "",
    val firstText: String = "",
    val secondText: String = "",
    val noteType: String = "DECISION",
    val noteTitle: String = "",
    val noteSummary: String = "",
    val taskTitle: String = "",
    val taskDescription: String = "",
    val episodeSituation: String = "",
    val episodeAction: String = "",
    val episodeResult: String = "",
    val episodeLesson: String = "",
    val profileText: String = "",
)

@Serializable
private data class MemoryE2eSeedSession(
    val id: String,
    val turns: List<JsonElement>,
)

@Serializable
private data class MemoryE2eSeedTurnDefinition(
    val text: String? = null,
    val filePath: String? = null,
    val rawUrl: String? = null,
    val documentType: String? = null,
    val title: String? = null,
    val sourceRef: String? = null,
    val forceWrite: Boolean = false,
    val mode: String? = null,
    val namespace: String? = null,
    val memoryWriteTraceExpectation: MemoryWriteTraceExpectation = MemoryWriteTraceExpectation(),
) {
    fun isProvidedContent(): Boolean =
        !filePath.isNullOrBlank() || !rawUrl.isNullOrBlank() || documentType != null

    fun requireText(
        caseId: String,
        sessionId: String,
        turnIndex: Int,
    ): String =
        text?.takeIf { it.isNotBlank() }
            ?: error("Seed turn ${turnIndex + 1} in $caseId/$sessionId requires non-blank text")

    fun progressText(): String =
        when {
            !filePath.isNullOrBlank() -> "file_path:${filePath.trim()}"
            !rawUrl.isNullOrBlank() -> "raw_url:${rawUrl.trim()}"
            else -> text.orEmpty()
        }

    fun inputKindForReport(): String =
        when {
            !filePath.isNullOrBlank() -> "file_path"
            !rawUrl.isNullOrBlank() -> "raw_url"
            documentType != null -> "provided_document"
            else -> "chat_message"
        }

    fun resolvedFilePath(projectRoot: String): String? =
        filePath?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { value ->
                val path = Path.of(value)
                if (path.isAbsolute) {
                    path.normalize().absolutePathString()
                } else {
                    Path.of(projectRoot).resolve(path).normalize().absolutePathString()
                }
            }
}

private val seedTurnJson = Json {
    ignoreUnknownKeys = true
}

private fun JsonElement.toSeedTurn(
    caseId: String,
    sessionId: String,
    turnIndex: Int,
): MemoryE2eSeedTurnDefinition =
    when (this) {
        is JsonPrimitive -> MemoryE2eSeedTurnDefinition(
            text = contentOrNull ?: error("Seed turn ${turnIndex + 1} in $caseId/$sessionId is not a string"),
        )

        is JsonObject -> seedTurnJson.decodeFromJsonElement<MemoryE2eSeedTurnDefinition>(this)
            .also { it.validate(caseId, sessionId, turnIndex) }

        else -> error("Seed turn ${turnIndex + 1} in $caseId/$sessionId must be a string or object")
    }

private fun MemoryE2eSeedTurnDefinition.validate(
    caseId: String,
    sessionId: String,
    turnIndex: Int,
) {
    val providedInputs = listOfNotNull(
        text?.takeIf { it.isNotBlank() }?.let { "text" },
        filePath?.takeIf { it.isNotBlank() }?.let { "filePath" },
        rawUrl?.takeIf { it.isNotBlank() }?.let { "rawUrl" },
    )
    if (isProvidedContent()) {
        require(providedInputs.size == 1) {
            "Provided seed turn ${turnIndex + 1} in $caseId/$sessionId requires exactly one of text+documentType, filePath, rawUrl."
        }
        require(text.isNullOrBlank() || documentType != null) {
            "Provided text seed turn ${turnIndex + 1} in $caseId/$sessionId requires documentType."
        }
    } else {
        require(!text.isNullOrBlank()) {
            "Chat seed turn ${turnIndex + 1} in $caseId/$sessionId requires non-blank text."
        }
    }
}

@Serializable
private data class MemoryE2eRecallSession(
    val id: String,
    val turns: List<MemoryE2eRecallTurn>,
)

@Serializable
private data class MemoryE2eRecallTurn(
    val text: String,
    val memoryRoutingFailFast: Boolean? = null,
    val answerExpectation: AnswerExpectation = AnswerExpectation(),
    val memoryTraceExpectation: MemoryTraceExpectation = MemoryTraceExpectation(),
    val memoryWriteTraceExpectation: MemoryWriteTraceExpectation = MemoryWriteTraceExpectation(),
)

@Serializable
private data class SnapshotExpectation(
    val activeClaimsMin: Int? = null,
    val activeClaimsMax: Int? = null,
    val sourcesMin: Int? = null,
    val sourcesMax: Int? = null,
    val userSourcesMin: Int? = null,
    val userSourcesMax: Int? = null,
    val assistantSourcesMin: Int? = null,
    val assistantSourcesMax: Int? = null,
    val notesMin: Int? = null,
    val notesMax: Int? = null,
    val actionItemsMin: Int? = null,
    val actionItemsMax: Int? = null,
    val profilesMin: Int? = null,
    val profilesMax: Int? = null,
    val episodesMin: Int? = null,
    val episodesMax: Int? = null,
    val runsMin: Int? = null,
    val runsMax: Int? = null,
    val predicateDefinitionsMin: Int? = null,
    val predicateDefinitionsMax: Int? = null,
    val requiredEntities: List<EntityExpectation> = emptyList(),
    val requiredSources: List<SourceExpectation> = emptyList(),
    val requiredPredicateDefinitions: List<PredicateDefinitionExpectation> = emptyList(),
    val requiredClaims: List<ClaimExpectation> = emptyList(),
    val requiredNotes: List<NoteExpectation> = emptyList(),
    val requiredActionItems: List<ActionItemExpectation> = emptyList(),
    val requiredProfiles: List<ProfileExpectation> = emptyList(),
    val requiredEpisodes: List<EpisodeExpectation> = emptyList(),
    val requiredRuns: List<RunExpectation> = emptyList(),
    val forbiddenClaims: List<ClaimExpectation> = emptyList(),
    val forbiddenNotes: List<NoteExpectation> = emptyList(),
    val forbiddenActionItems: List<ActionItemExpectation> = emptyList(),
    val forbiddenProfiles: List<ProfileExpectation> = emptyList(),
    val forbiddenEpisodes: List<EpisodeExpectation> = emptyList(),
    val allActiveClaimsEvidenceSpeakerRoleIn: List<String> = emptyList(),
)

@Serializable
private data class SourceExpectation(
    val roleIn: List<String> = emptyList(),
    val textContainsAll: List<String> = emptyList(),
    val searchTextContainsAll: List<String> = emptyList(),
    val searchTextContainsAnyGroups: List<List<String>> = emptyList(),
    val allowStructuredExtraction: Boolean? = null,
    val allowRecall: Boolean? = null,
    val allowEvidenceHydration: Boolean? = null,
    val usagePolicyReasonContainsAll: List<String> = emptyList(),
    val minMatches: Int = 1,
)

@Serializable
private data class EntityExpectation(
    val typeIn: List<String> = emptyList(),
    val canonicalNameIn: List<String> = emptyList(),
    val normalizedNameIn: List<String> = emptyList(),
    val containsAll: List<String> = emptyList(),
    val minMatches: Int = 1,
)

@Serializable
private data class PredicateDefinitionExpectation(
    val predicateIn: List<String> = emptyList(),
    val cardinalityIn: List<String> = emptyList(),
    val temporalPolicyIn: List<String> = emptyList(),
    val conflictPolicyIn: List<String> = emptyList(),
    val objectKindIn: List<String> = emptyList(),
    val semanticKindsIn: List<String> = emptyList(),
    val aggregateEffectIn: List<String> = emptyList(),
    val containsAll: List<String> = emptyList(),
    val minMatches: Int = 1,
)

@Serializable
private data class ClaimExpectation(
    val predicateIn: List<String> = emptyList(),
    val predicateFamilyIn: List<String> = emptyList(),
    val predicatePolicyCardinalityIn: List<String> = emptyList(),
    val predicatePolicyTemporalPolicyIn: List<String> = emptyList(),
    val predicatePolicyConflictPolicyIn: List<String> = emptyList(),
    val predicatePolicySemanticKindsIn: List<String> = emptyList(),
    val predicatePolicyAggregateEffectIn: List<String> = emptyList(),
    val statusIn: List<String> = emptyList(),
    val subjectEntityTypeIn: List<String> = emptyList(),
    val subjectEntityCanonicalNameIn: List<String> = emptyList(),
    val objectEntityTypeIn: List<String> = emptyList(),
    val objectEntityCanonicalNameIn: List<String> = emptyList(),
    val objectValueStringIn: List<String> = emptyList(),
    val objectValueContainsAll: List<String> = emptyList(),
    val normalizedContainsAll: List<String> = emptyList(),
    val containsAll: List<String> = emptyList(),
    val confidenceMin: Double? = null,
    val confidenceMax: Double? = null,
    val evidenceQuoteContainsAll: List<String> = emptyList(),
    val evidenceSourceSpeakerRoleIn: List<String> = emptyList(),
    val minMatches: Int = 1,
)

@Serializable
private data class NoteExpectation(
    val typeIn: List<String> = emptyList(),
    val statusIn: List<String> = emptyList(),
    val titleContainsAll: List<String> = emptyList(),
    val summaryContainsAll: List<String> = emptyList(),
    val containsAll: List<String> = emptyList(),
    val evidenceQuoteContainsAll: List<String> = emptyList(),
    val evidenceSourceSpeakerRoleIn: List<String> = emptyList(),
    val minMatches: Int = 1,
)

@Serializable
private data class ActionItemExpectation(
    val statusIn: List<String> = emptyList(),
    val priorityIn: List<String> = emptyList(),
    val titleContainsAll: List<String> = emptyList(),
    val descriptionContainsAll: List<String> = emptyList(),
    val containsAll: List<String> = emptyList(),
    val evidenceQuoteContainsAll: List<String> = emptyList(),
    val evidenceSourceSpeakerRoleIn: List<String> = emptyList(),
    val minMatches: Int = 1,
)

@Serializable
private data class ProfileExpectation(
    val ownerEntityTypeIn: List<String> = emptyList(),
    val ownerNameIn: List<String> = emptyList(),
    val textContainsAll: List<String> = emptyList(),
    val containsAll: List<String> = emptyList(),
    val minMatches: Int = 1,
)

@Serializable
private data class EpisodeExpectation(
    val ownerEntityTypeIn: List<String> = emptyList(),
    val ownerNameIn: List<String> = emptyList(),
    val situationContainsAll: List<String> = emptyList(),
    val actionContainsAll: List<String> = emptyList(),
    val resultContainsAll: List<String> = emptyList(),
    val lessonContainsAll: List<String> = emptyList(),
    val containsAll: List<String> = emptyList(),
    val evidenceQuoteContainsAll: List<String> = emptyList(),
    val evidenceSourceSpeakerRoleIn: List<String> = emptyList(),
    val minMatches: Int = 1,
)

@Serializable
private data class RunExpectation(
    val runTypeIn: List<String> = emptyList(),
    val statusIn: List<String> = emptyList(),
    val containsAll: List<String> = emptyList(),
    val appliedOpsMin: Int? = null,
    val retrievedItemRefsMin: Int? = null,
    val minMatches: Int = 1,
)

@Serializable
private data class AnswerExpectation(
    val containsAll: List<String> = emptyList(),
    val containsAnyGroups: List<List<String>> = emptyList(),
    val containsNone: List<String> = emptyList(),
)

@Serializable
private data class MemoryWriteTraceExpectation(
    val present: Boolean? = null,
    val routeDecisionIn: List<String> = emptyList(),
    val retrievedHitsMin: Int? = null,
    val retrievedHitsMax: Int? = null,
    val entityOpsMin: Int? = null,
    val entityOpsMax: Int? = null,
    val noteCandidatesMin: Int? = null,
    val noteCandidatesMax: Int? = null,
    val rawNoteOpsMin: Int? = null,
    val rawNoteOpsMax: Int? = null,
    val noteOpsMin: Int? = null,
    val noteOpsMax: Int? = null,
    val claimCandidatesMin: Int? = null,
    val claimCandidatesMax: Int? = null,
    val rawClaimOpsMin: Int? = null,
    val rawClaimOpsMax: Int? = null,
    val claimOpsMin: Int? = null,
    val claimOpsMax: Int? = null,
    val rawActionItemOpsMin: Int? = null,
    val rawActionItemOpsMax: Int? = null,
    val actionItemOpsMin: Int? = null,
    val actionItemOpsMax: Int? = null,
    val materializedClaimsMin: Int? = null,
    val materializedClaimsMax: Int? = null,
    val materializedNotesMin: Int? = null,
    val materializedNotesMax: Int? = null,
    val materializedActionItemsMin: Int? = null,
    val materializedActionItemsMax: Int? = null,
    val materializedRunsMin: Int? = null,
    val materializedRunsMax: Int? = null,
    val containsAll: List<String> = emptyList(),
    val containsNone: List<String> = emptyList(),
    val rawNoteOpsContainsAll: List<String> = emptyList(),
    val rawNoteOpsContainsNone: List<String> = emptyList(),
    val noteOpsContainsAll: List<String> = emptyList(),
    val noteOpsContainsNone: List<String> = emptyList(),
    val rawClaimOpsContainsAll: List<String> = emptyList(),
    val rawClaimOpsContainsNone: List<String> = emptyList(),
    val claimOpsContainsAll: List<String> = emptyList(),
    val claimOpsContainsNone: List<String> = emptyList(),
    val rawActionItemOpsContainsAll: List<String> = emptyList(),
    val rawActionItemOpsContainsNone: List<String> = emptyList(),
    val actionItemOpsContainsAll: List<String> = emptyList(),
    val actionItemOpsContainsNone: List<String> = emptyList(),
) {
    fun hasChecks(): Boolean =
        present != null ||
            routeDecisionIn.isNotEmpty() ||
            retrievedHitsMin != null ||
            retrievedHitsMax != null ||
            entityOpsMin != null ||
            entityOpsMax != null ||
            noteCandidatesMin != null ||
            noteCandidatesMax != null ||
            rawNoteOpsMin != null ||
            rawNoteOpsMax != null ||
            noteOpsMin != null ||
            noteOpsMax != null ||
            claimCandidatesMin != null ||
            claimCandidatesMax != null ||
            rawClaimOpsMin != null ||
            rawClaimOpsMax != null ||
            claimOpsMin != null ||
            claimOpsMax != null ||
            rawActionItemOpsMin != null ||
            rawActionItemOpsMax != null ||
            actionItemOpsMin != null ||
            actionItemOpsMax != null ||
            materializedClaimsMin != null ||
            materializedClaimsMax != null ||
            materializedNotesMin != null ||
            materializedNotesMax != null ||
            materializedActionItemsMin != null ||
            materializedActionItemsMax != null ||
            materializedRunsMin != null ||
            materializedRunsMax != null ||
            containsAll.isNotEmpty() ||
            containsNone.isNotEmpty() ||
            rawNoteOpsContainsAll.isNotEmpty() ||
            rawNoteOpsContainsNone.isNotEmpty() ||
            noteOpsContainsAll.isNotEmpty() ||
            noteOpsContainsNone.isNotEmpty() ||
            rawClaimOpsContainsAll.isNotEmpty() ||
            rawClaimOpsContainsNone.isNotEmpty() ||
            claimOpsContainsAll.isNotEmpty() ||
            claimOpsContainsNone.isNotEmpty() ||
            rawActionItemOpsContainsAll.isNotEmpty() ||
            rawActionItemOpsContainsNone.isNotEmpty() ||
            actionItemOpsContainsAll.isNotEmpty() ||
            actionItemOpsContainsNone.isNotEmpty()
}

@Serializable
private data class MemoryMaintenanceTraceExpectation(
    val present: Boolean? = null,
    val stageIn: List<String> = emptyList(),
    val tracesMin: Int? = null,
    val tracesMax: Int? = null,
    val selectedNotesMin: Int? = null,
    val selectedNotesMax: Int? = null,
    val relatedHitsMin: Int? = null,
    val relatedHitsMax: Int? = null,
    val rawClaimCandidatesMin: Int? = null,
    val rawClaimCandidatesMax: Int? = null,
    val finalClaimCandidatesMin: Int? = null,
    val finalClaimCandidatesMax: Int? = null,
    val rawActionItemActionsMin: Int? = null,
    val rawActionItemActionsMax: Int? = null,
    val finalActionItemActionsMin: Int? = null,
    val finalActionItemActionsMax: Int? = null,
    val rawEpisodeCandidatesMin: Int? = null,
    val rawEpisodeCandidatesMax: Int? = null,
    val finalEpisodeCandidatesMin: Int? = null,
    val finalEpisodeCandidatesMax: Int? = null,
    val materializedClaimsMin: Int? = null,
    val materializedClaimsMax: Int? = null,
    val materializedNotesMin: Int? = null,
    val materializedNotesMax: Int? = null,
    val materializedActionItemsMin: Int? = null,
    val materializedActionItemsMax: Int? = null,
    val materializedEpisodesMin: Int? = null,
    val materializedEpisodesMax: Int? = null,
    val materializedRunsMin: Int? = null,
    val materializedRunsMax: Int? = null,
    val repairCandidateClustersMin: Int? = null,
    val repairCandidateClustersMax: Int? = null,
    val repairSuspiciousHitsMin: Int? = null,
    val repairSuspiciousHitsMax: Int? = null,
    val repairActionsMin: Int? = null,
    val repairActionsMax: Int? = null,
    val repairMaterializedClaimsMin: Int? = null,
    val repairMaterializedClaimsMax: Int? = null,
    val repairMaterializedNotesMin: Int? = null,
    val repairMaterializedNotesMax: Int? = null,
    val repairMaterializedActionItemsMin: Int? = null,
    val repairMaterializedActionItemsMax: Int? = null,
    val repairMaterializedEpisodesMin: Int? = null,
    val repairMaterializedEpisodesMax: Int? = null,
    val repairMaterializedProfilesMin: Int? = null,
    val repairMaterializedProfilesMax: Int? = null,
    val repairMaterializedRunsMin: Int? = null,
    val repairMaterializedRunsMax: Int? = null,
    val repairAppliedOpsMin: Int? = null,
    val repairAppliedOpsMax: Int? = null,
    val containsAll: List<String> = emptyList(),
    val containsNone: List<String> = emptyList(),
    val rawContainsAll: List<String> = emptyList(),
    val rawContainsNone: List<String> = emptyList(),
    val finalContainsAll: List<String> = emptyList(),
    val finalContainsNone: List<String> = emptyList(),
) {
    fun hasChecks(): Boolean =
        present != null ||
            stageIn.isNotEmpty() ||
            tracesMin != null ||
            tracesMax != null ||
            hasNoteConsolidationChecks() ||
            hasRepairChecks() ||
            containsAll.isNotEmpty() ||
            containsNone.isNotEmpty()

    fun hasNoteConsolidationChecks(): Boolean =
        selectedNotesMin != null ||
            selectedNotesMax != null ||
            relatedHitsMin != null ||
            relatedHitsMax != null ||
            rawClaimCandidatesMin != null ||
            rawClaimCandidatesMax != null ||
            finalClaimCandidatesMin != null ||
            finalClaimCandidatesMax != null ||
            rawActionItemActionsMin != null ||
            rawActionItemActionsMax != null ||
            finalActionItemActionsMin != null ||
            finalActionItemActionsMax != null ||
            rawEpisodeCandidatesMin != null ||
            rawEpisodeCandidatesMax != null ||
            finalEpisodeCandidatesMin != null ||
            finalEpisodeCandidatesMax != null ||
            materializedClaimsMin != null ||
            materializedClaimsMax != null ||
            materializedNotesMin != null ||
            materializedNotesMax != null ||
            materializedActionItemsMin != null ||
            materializedActionItemsMax != null ||
            materializedEpisodesMin != null ||
            materializedEpisodesMax != null ||
            materializedRunsMin != null ||
            materializedRunsMax != null ||
            rawContainsAll.isNotEmpty() ||
            rawContainsNone.isNotEmpty() ||
            finalContainsAll.isNotEmpty() ||
            finalContainsNone.isNotEmpty()

    fun hasRepairChecks(): Boolean =
        repairCandidateClustersMin != null ||
            repairCandidateClustersMax != null ||
            repairSuspiciousHitsMin != null ||
            repairSuspiciousHitsMax != null ||
            repairActionsMin != null ||
            repairActionsMax != null ||
            repairMaterializedClaimsMin != null ||
            repairMaterializedClaimsMax != null ||
            repairMaterializedNotesMin != null ||
            repairMaterializedNotesMax != null ||
            repairMaterializedActionItemsMin != null ||
            repairMaterializedActionItemsMax != null ||
            repairMaterializedEpisodesMin != null ||
            repairMaterializedEpisodesMax != null ||
            repairMaterializedProfilesMin != null ||
            repairMaterializedProfilesMax != null ||
            repairMaterializedRunsMin != null ||
            repairMaterializedRunsMax != null ||
            repairAppliedOpsMin != null ||
            repairAppliedOpsMax != null
}

@Serializable
private data class MemoryTraceExpectation(
    val needMemory: Boolean? = null,
    val injectedPromptPresent: Boolean? = null,
    val selectedHitsMin: Int? = null,
    val selectedHitsMax: Int? = null,
    val selectedClaimsMin: Int? = null,
    val selectedClaimsMax: Int? = null,
    val selectedNotesMin: Int? = null,
    val selectedNotesMax: Int? = null,
    val selectedActionItemsMin: Int? = null,
    val selectedActionItemsMax: Int? = null,
    val selectedProfilesMin: Int? = null,
    val selectedProfilesMax: Int? = null,
    val selectedEpisodesMin: Int? = null,
    val selectedEpisodesMax: Int? = null,
    val selectedContainsAll: List<String> = emptyList(),
    val selectedContainsNone: List<String> = emptyList(),
    val selectorSelectedContainsAll: List<String> = emptyList(),
    val selectorSelectedContainsNone: List<String> = emptyList(),
    val selectorRejectedContainsAll: List<String> = emptyList(),
    val selectorRejectedContainsNone: List<String> = emptyList(),
    val injectedPromptContainsAll: List<String> = emptyList(),
    val injectedPromptContainsNone: List<String> = emptyList(),
    val sourceSafetySuppressedSourcesMin: Int? = null,
    val sourceSafetySuppressedSourcesMax: Int? = null,
    val sourceSafetyRestoredTypedHitsMin: Int? = null,
    val sourceSafetyRestoredTypedHitsMax: Int? = null,
    val sourceSafetySuppressedContainsAll: List<String> = emptyList(),
    val sourceSafetySuppressedContainsNone: List<String> = emptyList(),
    val sourceSafetyRestoredContainsAll: List<String> = emptyList(),
    val sourceSafetyRestoredContainsNone: List<String> = emptyList(),
    val requiredSelectedClaims: List<ClaimExpectation> = emptyList(),
    val forbiddenSelectedClaims: List<ClaimExpectation> = emptyList(),
    val requiredSelectedNotes: List<NoteExpectation> = emptyList(),
    val forbiddenSelectedNotes: List<NoteExpectation> = emptyList(),
    val requiredSelectedActionItems: List<ActionItemExpectation> = emptyList(),
    val forbiddenSelectedActionItems: List<ActionItemExpectation> = emptyList(),
    val requiredSelectedProfiles: List<ProfileExpectation> = emptyList(),
    val forbiddenSelectedProfiles: List<ProfileExpectation> = emptyList(),
    val requiredSelectedEpisodes: List<EpisodeExpectation> = emptyList(),
    val forbiddenSelectedEpisodes: List<EpisodeExpectation> = emptyList(),
) {
    fun hasChecks(): Boolean =
        needMemory != null ||
            injectedPromptPresent != null ||
            selectedHitsMin != null ||
            selectedHitsMax != null ||
            selectedClaimsMin != null ||
            selectedClaimsMax != null ||
            selectedNotesMin != null ||
            selectedNotesMax != null ||
            selectedActionItemsMin != null ||
            selectedActionItemsMax != null ||
            selectedProfilesMin != null ||
            selectedProfilesMax != null ||
            selectedEpisodesMin != null ||
            selectedEpisodesMax != null ||
            selectedContainsAll.isNotEmpty() ||
            selectedContainsNone.isNotEmpty() ||
            selectorSelectedContainsAll.isNotEmpty() ||
            selectorSelectedContainsNone.isNotEmpty() ||
            selectorRejectedContainsAll.isNotEmpty() ||
            selectorRejectedContainsNone.isNotEmpty() ||
            injectedPromptContainsAll.isNotEmpty() ||
            injectedPromptContainsNone.isNotEmpty() ||
            sourceSafetySuppressedSourcesMin != null ||
            sourceSafetySuppressedSourcesMax != null ||
            sourceSafetyRestoredTypedHitsMin != null ||
            sourceSafetyRestoredTypedHitsMax != null ||
            sourceSafetySuppressedContainsAll.isNotEmpty() ||
            sourceSafetySuppressedContainsNone.isNotEmpty() ||
            sourceSafetyRestoredContainsAll.isNotEmpty() ||
            sourceSafetyRestoredContainsNone.isNotEmpty() ||
            requiredSelectedClaims.isNotEmpty() ||
            forbiddenSelectedClaims.isNotEmpty() ||
            requiredSelectedNotes.isNotEmpty() ||
            forbiddenSelectedNotes.isNotEmpty() ||
            requiredSelectedActionItems.isNotEmpty() ||
            forbiddenSelectedActionItems.isNotEmpty() ||
            requiredSelectedProfiles.isNotEmpty() ||
            forbiddenSelectedProfiles.isNotEmpty() ||
            requiredSelectedEpisodes.isNotEmpty() ||
            forbiddenSelectedEpisodes.isNotEmpty()
}

private data class SentUserTurn(
    val answer: String,
    val memoryReadTrace: MemoryReadTraceEvent?,
    val memoryWriteTrace: MemoryWriteTraceEvent?,
)

private data class ExecutedMemoryE2eCase(
    val definition: MemoryE2eCase,
    val namespace: MemoryNamespace,
    val projectPath: String,
    val modelName: String,
    val afterSeedsSnapshot: MemoryNamespaceSnapshot,
    val finalSnapshot: MemoryNamespaceSnapshot,
    val seedResults: List<ExecutedSeedTurn>,
    val maintenanceResults: List<ExecutedMaintenanceAction>,
    val recallResults: List<ExecutedRecallTurn>,
)

private data class ExecutedSeedTurn(
    val sessionId: String,
    val text: String,
    val memoryWriteTraceExpectation: MemoryWriteTraceExpectation,
    val memoryWriteTrace: MemoryWriteTraceEvent?,
    val inputKind: String = "chat_message",
    val toolResult: String? = null,
    val memoryRun: MemoryRun? = null,
)

private data class ExecutedMaintenanceAction(
    val action: String,
    val expectation: MemoryMaintenanceTraceExpectation,
    val traces: List<MemoryMaintenanceTraceEvent>,
)

private data class ExecutedRecallTurn(
    val sessionId: String,
    val text: String,
    val answer: String,
    val expectation: AnswerExpectation,
    val memoryTraceExpectation: MemoryTraceExpectation,
    val memoryReadTrace: MemoryReadTraceEvent?,
    val memoryWriteTraceExpectation: MemoryWriteTraceExpectation,
    val memoryWriteTrace: MemoryWriteTraceEvent?,
)

@Serializable
private data class RenderedSnapshot(
    val predicateDefinitions: List<RenderedPredicateDefinition>,
    val sources: List<RenderedSource>,
    val entities: List<RenderedEntity>,
    val claims: List<RenderedClaim>,
    val notes: List<RenderedNote>,
    val actionItems: List<RenderedTask>,
    val profiles: Int,
    val episodes: List<RenderedEpisode>,
    val runs: Int,
)

@Serializable
private data class RenderedPredicateDefinition(
    val id: String,
    val predicate: String,
    val description: String,
    val subjectType: String?,
    val objectKind: String,
    val cardinality: String,
    val temporalPolicy: String,
    val conflictPolicy: String,
    val semanticKinds: List<String>,
    val aggregateEffect: String,
    val profileSync: Boolean,
    val actionItemSync: Boolean,
    val defaultImportance: Int,
)

@Serializable
private data class RenderedSource(
    val id: String,
    val type: String,
    val role: String?,
    val text: String,
    val searchText: String?,
)

@Serializable
private data class RenderedEntity(
    val id: String,
    val type: String,
    val canonicalName: String,
    val normalizedName: String,
)

@Serializable
private data class RenderedClaim(
    val id: String,
    val status: String,
    val predicate: String,
    val predicateFamily: String?,
    val predicatePolicy: RenderedPredicatePolicy?,
    val subject: String?,
    val objectEntity: String?,
    val objectValue: String?,
    val normalizedText: String,
    val confidence: Double,
    val evidenceQuotes: List<String>,
    val evidenceSourceRoles: List<String>,
)

@Serializable
private data class RenderedNote(
    val id: String,
    val type: String,
    val status: String,
    val maturity: String,
    val title: String,
    val summary: String,
    val scope: String,
    val keywords: List<String>,
    val tags: List<String>,
    val evidenceQuotes: List<String>,
    val evidenceSourceRoles: List<String>,
)

@Serializable
private data class RenderedTask(
    val id: String,
    val status: String,
    val priority: String,
    val title: String,
    val description: String?,
    val scope: String,
    val dueAt: String?,
    val acceptanceCriteria: List<String>,
    val blockers: List<String>,
    val evidenceQuotes: List<String>,
    val evidenceSourceRoles: List<String>,
)

@Serializable
private data class RenderedEpisode(
    val id: String,
    val owner: String?,
    val situation: String,
    val action: String,
    val result: String,
    val lesson: String,
    val tags: List<String>,
    val successScore: Double?,
    val evidenceQuotes: List<String>,
    val evidenceSourceRoles: List<String>,
)

@Serializable
private data class RenderedPredicatePolicy(
    val cardinality: String,
    val temporalPolicy: String,
    val conflictPolicy: String,
    val semanticKinds: List<String>,
    val aggregateEffect: String,
    val description: String,
)
