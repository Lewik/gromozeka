package com.gromozeka.application.service

import com.gromozeka.application.service.memory.MEMORY_ENRICH_CONTEXT_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_ANSWER_QUESTION_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_EMBEDDING_STATUS_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_LIST_NAMESPACES_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_MAINTENANCE_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_REMEMBER_TOOL_NAME
import com.gromozeka.application.service.memory.MemoryDocumentIngestJob
import com.gromozeka.application.service.memory.MemoryDocumentIngestQueue
import com.gromozeka.application.service.memory.MemoryEmbeddingIndexer
import com.gromozeka.application.service.memory.MemoryEmbeddingRebuildMode
import com.gromozeka.application.service.memory.MemoryMaintenanceAction
import com.gromozeka.application.service.memory.MemoryMaintenanceQueue
import com.gromozeka.application.service.memory.MemoryMessageRoutingApplicationService
import com.gromozeka.application.service.memory.MarkdownDocumentSlicer
import com.gromozeka.application.service.memory.MemoryRememberContentRequest
import com.gromozeka.application.service.memory.MemoryRememberContentResolver
import com.gromozeka.application.service.memory.MemoryRememberDocumentQueuedResult
import com.gromozeka.application.service.memory.MemoryResolvedRememberContent
import com.gromozeka.application.service.memory.MemoryToolResultRenderer
import com.gromozeka.application.service.memory.PROJECT_MEMORY_NAMESPACE_PREFIX
import com.gromozeka.application.service.memory.defaultMemoryNamespace
import com.gromozeka.application.service.memory.toConfiguredMemoryNamespace
import com.gromozeka.application.service.memory.withoutMemoryManagementTools
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSummary
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemorySourceUsagePolicy
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.DefaultAgentProvider
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.shared.uuid.uuid7
import java.security.MessageDigest
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import org.springframework.stereotype.Service

@Service
class MemoryToolApplicationService(
    private val conversationService: ConversationDomainService,
    private val agentDomainService: AgentDomainService,
    private val defaultAgentProvider: DefaultAgentProvider,
    private val projectService: ProjectDomainService,
    private val settingsService: SettingsService,
    private val aiToolProvider: AiToolProvider,
    private val memoryApplicationService: MemoryApplicationService,
    private val memoryMessageRoutingApplicationService: MemoryMessageRoutingApplicationService,
    private val memoryDocumentIngestQueue: MemoryDocumentIngestQueue,
    private val memoryMaintenanceQueue: MemoryMaintenanceQueue,
    private val memoryEmbeddingIndexer: MemoryEmbeddingIndexer,
    private val memoryStore: MemoryStore,
) {
    private val log = KLoggers.logger(this)
    private val rememberContentResolver = MemoryRememberContentResolver()

    suspend fun remember(
        conversationIdValue: String,
        targetMessageId: String? = null,
        forceWrite: Boolean = false,
        namespaceValue: String? = null,
    ): String = runMemoryTool(MEMORY_REMEMBER_TOOL_NAME, conversationIdValue, targetMessageId) { context, targetMessage ->
        val result = memoryMessageRoutingApplicationService.routeMessage(
            conversationId = context.conversation.id,
            threadId = context.conversation.currentThread,
            message = targetMessage,
            agent = context.agent,
            project = context.project,
            runtimeSystemPrompts = context.systemPrompts,
            runtimeTools = context.memoryTools,
            forceMemoryWrite = forceWrite,
            namespaceOverride = namespaceValue.toConfiguredMemoryNamespace(),
        )
        MemoryToolResultRenderer.rememberResultJsonString(result)
    }

    suspend fun rememberProvidedText(
        conversationIdValue: String?,
        text: String? = null,
        filePath: String? = null,
        rawUrl: String? = null,
        documentType: String? = null,
        title: String? = null,
        sourceRef: String? = null,
        forceWrite: Boolean = false,
        mode: String? = null,
        namespaceValue: String? = null,
    ): String {
        val resolvedContent = runCatching {
            rememberContentResolver.resolve(
                MemoryRememberContentRequest(
                    text = text,
                    filePath = filePath,
                    rawUrl = rawUrl,
                    documentType = documentType,
                    title = title,
                    sourceRef = sourceRef,
                )
            )
        }.getOrElse { error ->
            return MemoryToolResultRenderer.failureJsonString(error.message ?: "Failed to resolve memory input.")
        }

        if (resolvedContent.documentType != null) {
            return rememberResolvedDocument(conversationIdValue, resolvedContent, forceWrite, mode, namespaceValue)
        }

        val normalizedText = resolvedContent.text.trim()
        if (normalizedText.isBlank()) {
            return MemoryToolResultRenderer.failureJsonString("Provided memory text is blank.")
        }

        if (conversationIdValue.isNullOrBlank()) {
            return rememberStandaloneProvidedText(resolvedContent, normalizedText, forceWrite, mode, namespaceValue)
        }

        return runCatching {
            val conversationId = Conversation.Id(conversationIdValue)
            val context = resolveContext(conversationId)
            val targetMessage = Conversation.Message(
                id = Conversation.Message.Id(uuid7()),
                conversationId = context.conversation.id,
                role = Conversation.Message.Role.USER,
                content = listOf(Conversation.Message.ContentItem.UserMessage(normalizedText)),
                providerMetadata = buildJsonObject {
                    put("memoryToolOrigin", "provided_text")
                    put("userConsentConfirmed", true)
                    put("inputKind", resolvedContent.kind.name)
                    put("sourceRef", resolvedContent.sourceRef)
                    if (forceWrite) put("forceMemoryWrite", true)
                    mode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
                },
                createdAt = Clock.System.now(),
            )
            val result = memoryMessageRoutingApplicationService.routeMessage(
                conversationId = context.conversation.id,
                threadId = context.conversation.currentThread,
                message = targetMessage,
                agent = context.agent,
                project = context.project,
                runtimeSystemPrompts = context.systemPrompts,
                runtimeTools = context.memoryTools,
                threadContextMessages = context.threadMessages + targetMessage,
                forceMemoryWrite = forceWrite,
                namespaceOverride = namespaceValue.toConfiguredMemoryNamespace(),
            )
            MemoryToolResultRenderer.rememberResultJsonString(result)
        }.onFailure { error ->
            log.warn(error) {
                "Memory tool failed: tool=$MEMORY_REMEMBER_TOOL_NAME conversation=$conversationIdValue target=provided_text error=${error.message}"
            }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory tool failed.")
        }
    }

    private suspend fun rememberStandaloneProvidedText(
        resolvedContent: MemoryResolvedRememberContent,
        text: String,
        forceWrite: Boolean,
        mode: String?,
        namespaceValue: String?,
    ): String {
        return runCatching {
            val agent = defaultAgentProvider.getDefault()
            val project = projectService.getOrCreate(defaultStandaloneProjectPath())
            val namespace = resolveMemoryNamespace(namespaceValue, project)
            val contentHash = text.sha256()
            val now = Clock.System.now()
            val recordRef = resolvedContent.sourceRef
                ?.takeIf { it.isNotBlank() }
                ?: "memory_remember:provided_text:${contentHash.take(16)}"
            val source = MemorySource.ExternalRecord(
                id = externalRecordSourceId(
                    kind = "provided-text",
                    namespace = namespace,
                    recordRef = recordRef,
                    contentHash = contentHash,
                ),
                namespace = namespace,
                recordRef = recordRef,
                authorLabel = "user-provided text",
                contentText = text,
                contentPayload = buildJsonObject {
                    put("memoryToolOrigin", "provided_text")
                    put("userConsentConfirmed", true)
                    put("standalone", true)
                    put("inputKind", resolvedContent.kind.name)
                    put("sourceRef", resolvedContent.sourceRef)
                    resolvedContent.title?.let { put("title", it) }
                    if (forceWrite) put("forceMemoryWrite", true)
                    mode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
                },
                contentHash = contentHash,
                observedAt = now,
                createdAt = now,
            )
            val result = memoryMessageRoutingApplicationService.routeSource(
                namespace = namespace,
                source = source,
                agent = agent,
                project = project,
                runtimeSystemPrompts = agentDomainService.assembleSystemPrompt(agent, project),
                runtimeTools = aiToolProvider.getTools().withoutMemoryManagementTools(),
            )
            MemoryToolResultRenderer.rememberResultJsonString(result)
        }.onFailure { error ->
            log.warn(error) {
                "Memory tool failed: tool=$MEMORY_REMEMBER_TOOL_NAME target=provided_text standalone=true error=${error.message}"
            }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory tool failed.")
        }
    }

    private suspend fun rememberResolvedDocument(
        conversationIdValue: String?,
        resolvedContent: MemoryResolvedRememberContent,
        forceWrite: Boolean,
        mode: String?,
        namespaceValue: String?,
    ): String =
        runCatching {
            val documentType = requireNotNull(resolvedContent.documentType)
            val agent: AgentDefinition
            val project: Project
            val runtimeSystemPrompts: List<String>
            val runtimeTools: List<AiToolCallback>

            if (conversationIdValue.isNullOrBlank()) {
                agent = defaultAgentProvider.getDefault()
                project = projectService.getOrCreate(defaultStandaloneProjectPath())
                runtimeSystemPrompts = agentDomainService.assembleSystemPrompt(agent, project)
                runtimeTools = aiToolProvider.getTools().withoutMemoryManagementTools()
            } else {
                val context = resolveContext(Conversation.Id(conversationIdValue))
                agent = context.agent
                project = context.project
                runtimeSystemPrompts = context.systemPrompts
                runtimeTools = context.memoryTools
            }

            val namespace = resolveMemoryNamespace(namespaceValue, project)
            val now = Clock.System.now()
            val documentHash = resolvedContent.text.sha256()
            val parentRecordRef = "memory_remember:document:${documentHash.take(16)}"
            val parentSource = MemorySource.ExternalRecord(
                id = externalRecordSourceId(
                    kind = "document",
                    namespace = namespace,
                    recordRef = parentRecordRef,
                    contentHash = documentHash,
                ),
                namespace = namespace,
                recordRef = parentRecordRef,
                authorLabel = "user-provided document",
                contentText = resolvedContent.text,
                contentPayload = buildJsonObject {
                    put("memoryToolOrigin", "provided_document")
                    put("sourceKind", "document")
                    put("userConsentConfirmed", true)
                    put("inputKind", resolvedContent.kind.name)
                    put("documentType", documentType.name)
                    put("sourceRef", resolvedContent.sourceRef)
                    put("importedAt", now.toString())
                    if (forceWrite) put("forceMemoryWrite", true)
                    resolvedContent.title?.let { put("title", it) }
                    mode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
                },
                contentHash = documentHash,
                observedAt = now,
                createdAt = now,
                retentionClass = MemorySource.RetentionClass.IMPORTED,
                usagePolicy = MemorySourceUsagePolicy.AUDIT_ONLY.copy(
                    reason = "parent document; section sources are processed"
                ),
            )

            val sections = MarkdownDocumentSlicer.slice(resolvedContent.text)
            require(sections.isNotEmpty()) { "Document has no non-blank markdown sections." }

            val parentRun = MemoryRun(
                id = MemoryRun.Id("document-ingest:run:${uuid7()}"),
                namespace = namespace,
                runType = MemoryRun.Type.DOCUMENT_INGEST,
                triggerMode = MemoryRun.TriggerMode.MANUAL,
                summary = "Document ingest queued: ${sections.size} sections",
                sourceIds = listOf(parentSource.id),
                progress = MemoryRun.Progress(totalUnits = sections.size),
                inputHash = documentHash,
                output = buildJsonObject {
                    put("document_type", documentType.name)
                    put("input_kind", resolvedContent.kind.name)
                    put("title", resolvedContent.title.orEmpty())
                    put("source_ref", resolvedContent.sourceRef)
                    put("ingested_at", now.toString())
                    put("parent_source_id", parentSource.id.value)
                    put("sections_total", sections.size)
                },
                metadata = buildJsonObject {
                    put("memoryToolOrigin", "provided_document")
                    put("sourceKind", "document")
                    put("inputKind", resolvedContent.kind.name)
                    put("documentType", documentType.name)
                    put("sourceRef", resolvedContent.sourceRef)
                    put("importedAt", now.toString())
                    if (forceWrite) put("forceMemoryWrite", true)
                    resolvedContent.title?.let { put("title", it) }
                    mode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
                },
                status = MemoryRun.Status.QUEUED,
                createdAt = now,
            )
            log.info {
                "Memory document remember: namespace=${namespace.value} documentType=${documentType.name} " +
                    "inputKind=${resolvedContent.kind.name} parentRun=${parentRun.id.value} " +
                    "parentSource=${parentSource.id.value} sections=${sections.size} " +
                    "chars=${resolvedContent.text.length} sourceRef=${resolvedContent.sourceRef}"
            }

            memoryStore.apply(
                MemoryUpdateBatch(
                    sources = listOf(parentSource),
                    runs = listOf(parentRun),
                )
            )

            val queueSize = memoryDocumentIngestQueue.enqueue(
                MemoryDocumentIngestJob(
                    namespace = namespace,
                    parentRun = parentRun,
                    parentSource = parentSource,
                    documentType = documentType,
                    inputKind = resolvedContent.kind,
                    title = resolvedContent.title,
                    sourceRef = resolvedContent.sourceRef,
                    documentHash = documentHash,
                    parentRecordRef = parentRecordRef,
                    sections = sections,
                    forceWrite = forceWrite,
                    mode = mode,
                    agent = agent,
                    project = project,
                    runtimeSystemPrompts = runtimeSystemPrompts,
                    runtimeTools = runtimeTools,
                )
            )

            MemoryToolResultRenderer.rememberDocumentQueuedResultJsonString(
                MemoryRememberDocumentQueuedResult(
                    runId = parentRun.id.value,
                    documentType = documentType,
                    inputKind = resolvedContent.kind,
                    title = resolvedContent.title,
                    sourceRef = resolvedContent.sourceRef,
                    parentSourceId = parentSource.id.value,
                    sections = sections,
                    queueSize = queueSize,
                )
            )
        }.onFailure { error ->
            log.warn(error) {
                "Memory tool failed: tool=$MEMORY_REMEMBER_TOOL_NAME target=document " +
                    "inputKind=${resolvedContent.kind.name} sourceRef=${resolvedContent.sourceRef} error=${error.message}"
            }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory document remember failed.")
        }

    suspend fun enrichContext(
        conversationIdValue: String,
        targetMessageId: String? = null,
        namespaceValue: String? = null,
    ): String = runMemoryTool(MEMORY_ENRICH_CONTEXT_TOOL_NAME, conversationIdValue, targetMessageId) { context, targetMessage ->
        val result = memoryApplicationService.buildRuntimeMemoryReadResult(
            conversationId = context.conversation.id,
            threadId = context.conversation.currentThread,
            targetMessage = targetMessage,
            threadMessages = context.threadMessages,
            agent = context.agent,
            project = context.project,
            runtimeSystemPrompts = context.systemPrompts,
            runtimeTools = context.memoryTools,
            namespaceOverride = namespaceValue.toConfiguredMemoryNamespace(),
        )
        MemoryToolResultRenderer.enrichContextResultJsonString(result)
    }

    suspend fun answerQuestion(
        conversationIdValue: String,
        targetMessageId: String? = null,
        namespaceValue: String? = null,
    ): String = runMemoryTool(MEMORY_ANSWER_QUESTION_TOOL_NAME, conversationIdValue, targetMessageId) { context, targetMessage ->
        val result = memoryApplicationService.answerQuestionFromMemory(
            conversationId = context.conversation.id,
            threadId = context.conversation.currentThread,
            targetMessage = targetMessage,
            threadMessages = context.threadMessages,
            agent = context.agent,
            project = context.project,
            runtimeSystemPrompts = context.systemPrompts,
            runtimeTools = context.memoryTools,
            namespaceOverride = namespaceValue.toConfiguredMemoryNamespace(),
        )
        MemoryToolResultRenderer.answerQuestionResultJsonString(result)
    }

    suspend fun enrichProvidedContext(
        conversationIdValue: String?,
        contextText: String,
        mode: String? = null,
        namespaceValue: String? = null,
    ): String {
        val normalizedContext = contextText.trim()
        if (normalizedContext.isBlank()) {
            return MemoryToolResultRenderer.failureJsonString("Provided context is blank.")
        }

        if (conversationIdValue.isNullOrBlank()) {
            return enrichStandaloneContext(normalizedContext, mode, namespaceValue)
        }

        return runCatching {
            val conversationId = Conversation.Id(conversationIdValue)
            val context = resolveContext(conversationId)
            val targetMessage = syntheticEnrichmentTargetMessage(
                conversationId = context.conversation.id,
                text = normalizedContext,
                mode = mode,
                standalone = false,
            )
            val result = memoryApplicationService.buildRuntimeMemoryReadResult(
                conversationId = context.conversation.id,
                threadId = context.conversation.currentThread,
                targetMessage = targetMessage,
                threadMessages = context.threadMessages + targetMessage,
                agent = context.agent,
                project = context.project,
                runtimeSystemPrompts = context.systemPrompts,
                runtimeTools = context.memoryTools,
                namespaceOverride = namespaceValue.toConfiguredMemoryNamespace(),
            )
            MemoryToolResultRenderer.enrichContextResultJsonString(result)
        }.onFailure { error ->
            log.warn(error) {
                "Memory tool failed: tool=$MEMORY_ENRICH_CONTEXT_TOOL_NAME conversation=$conversationIdValue target=provided_context error=${error.message}"
            }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory tool failed.")
        }
    }

    suspend fun answerProvidedQuestion(
        conversationIdValue: String?,
        questionText: String,
        mode: String? = null,
        namespaceValue: String? = null,
    ): String {
        val normalizedQuestion = questionText.trim()
        if (normalizedQuestion.isBlank()) {
            return MemoryToolResultRenderer.failureJsonString("Provided question is blank.")
        }

        if (conversationIdValue.isNullOrBlank()) {
            return answerStandaloneQuestion(normalizedQuestion, mode, namespaceValue)
        }

        return runCatching {
            val conversationId = Conversation.Id(conversationIdValue)
            val context = resolveContext(conversationId)
            val targetMessage = syntheticEnrichmentTargetMessage(
                conversationId = context.conversation.id,
                text = normalizedQuestion,
                mode = mode,
                standalone = false,
                origin = "provided_question",
            )
            val result = memoryApplicationService.answerQuestionFromMemory(
                conversationId = context.conversation.id,
                threadId = context.conversation.currentThread,
                targetMessage = targetMessage,
                threadMessages = context.threadMessages + targetMessage,
                agent = context.agent,
                project = context.project,
                runtimeSystemPrompts = context.systemPrompts,
                runtimeTools = context.memoryTools,
                namespaceOverride = namespaceValue.toConfiguredMemoryNamespace(),
            )
            MemoryToolResultRenderer.answerQuestionResultJsonString(result)
        }.onFailure { error ->
            log.warn(error) {
                "Memory tool failed: tool=$MEMORY_ANSWER_QUESTION_TOOL_NAME conversation=$conversationIdValue target=provided_question error=${error.message}"
            }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory tool failed.")
        }
    }

    suspend fun memoryRunStatus(
        runIdValue: String,
        includeChildren: Boolean = true,
        maxDepth: Int = 4,
    ): String =
        runCatching {
            val runId = MemoryRun.Id(runIdValue.trim())
            require(runId.value.isNotBlank()) { "memory_run_status requires non-blank run_id." }
            val rootRun = memoryStore.findRunById(runId)
                ?: return MemoryToolResultRenderer.failureJsonString("Memory run not found: ${runId.value}")
            val boundedDepth = maxDepth.coerceIn(0, 8)
            val descendants = if (includeChildren) {
                loadRunDescendants(rootRun, boundedDepth)
            } else {
                emptyList()
            }
            MemoryToolResultRenderer.runStatusJsonString(
                rootRun = rootRun,
                descendants = descendants,
                maxDepth = boundedDepth,
            )
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory run status failed.")
        }

    fun memoryQueueStatus(): String =
        MemoryToolResultRenderer.queueStatusJsonString(
            documentStatus = memoryDocumentIngestQueue.status(),
            maintenanceStatus = memoryMaintenanceQueue.status(),
            embeddingStatus = memoryEmbeddingIndexer.status(),
        )

    suspend fun memoryEmbeddingStatus(
        conversationIdValue: String? = null,
        targetTypeValue: String? = null,
        targetValue: String? = null,
        projectPathValue: String? = null,
        runIdValue: String? = null,
        namespaceValue: String? = null,
    ): String =
        runCatching {
            val target = resolveMaintenanceTarget(
                conversationIdValue = conversationIdValue,
                targetTypeValue = targetTypeValue,
                targetValue = targetValue,
                projectPathValue = projectPathValue,
                runIdValue = runIdValue,
                namespaceValue = namespaceValue,
            )
            val context = resolveMaintenanceContext(target)
            val coverage = memoryEmbeddingIndexer.coverage(context.namespace)
            MemoryToolResultRenderer.embeddingCoverageResultJsonString(coverage)
        }.onFailure { error ->
            log.warn(error) {
                "Memory tool failed: tool=$MEMORY_EMBEDDING_STATUS_TOOL_NAME " +
                    "targetType=$targetTypeValue target=$targetValue conversation=$conversationIdValue error=${error.message}"
            }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory embedding status failed.")
        }

    suspend fun listNamespaces(): String =
        runCatching {
            val configuredDefault = settingsService.userProfile.memorySettings.defaultMemoryNamespace()
            val storedSummaries = memoryStore.listNamespaceSummaries()
            val summaries = (storedSummaries + configuredDefault.emptyNamespaceSummaryIfMissing(storedSummaries))
                .distinctBy { it.namespace.value }
                .map { it.withReadableDisplayName() }
                .sortedWith(compareByDescending<MemoryNamespaceSummary> { it.namespace == configuredDefault }.thenBy { it.namespace.value })

            MemoryToolResultRenderer.namespaceListResultJsonString(
                summaries = summaries,
                configuredDefaultNamespace = configuredDefault,
            )
        }.onFailure { error ->
            log.warn(error) { "Memory tool failed: tool=$MEMORY_LIST_NAMESPACES_TOOL_NAME error=${error.message}" }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory namespace list failed.")
        }

    suspend fun runMaintenance(
        actionValue: String,
        conversationIdValue: String? = null,
        targetTypeValue: String? = null,
        targetValue: String? = null,
        projectPathValue: String? = null,
        runIdValue: String? = null,
        namespaceValue: String? = null,
        embeddingRebuildModeValue: String? = null,
    ): String =
        runCatching {
            val action = MemoryMaintenanceAction.from(actionValue)
            val embeddingRebuildMode = MemoryEmbeddingRebuildMode.from(embeddingRebuildModeValue)
            val target = resolveMaintenanceTarget(
                conversationIdValue = conversationIdValue,
                targetTypeValue = targetTypeValue,
                targetValue = targetValue,
                projectPathValue = projectPathValue,
                runIdValue = runIdValue,
                namespaceValue = namespaceValue,
            )
            val context = resolveMaintenanceContext(target)
            val result = memoryMaintenanceQueue.enqueue(
                action = action,
                targetKind = target.kind.toolName,
                targetValue = target.value,
                conversationId = context.conversationId,
                agent = context.agent,
                project = context.project,
                namespace = context.namespace,
                runtimeSystemPrompts = context.systemPrompts,
                runtimeTools = context.memoryTools,
                embeddingRebuildMode = embeddingRebuildMode,
            )

            log.info {
                "Memory maintenance tool queued: run=${result.runId.value} action=${action.toolName} " +
                    "target=${target.kind.toolName}:${target.value} namespace=${context.namespace.value} " +
                    "embeddingMode=${embeddingRebuildMode.name.lowercase()} " +
                    "conversation=${context.conversationId.value} queueSize=${result.queueSize}"
            }
            MemoryToolResultRenderer.maintenanceQueuedResultJsonString(result)
        }.onFailure { error ->
            log.warn(error) {
                "Memory tool failed: tool=$MEMORY_MAINTENANCE_TOOL_NAME action=$actionValue " +
                    "targetType=$targetTypeValue target=$targetValue conversation=$conversationIdValue error=${error.message}"
            }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory maintenance failed.")
        }

    private suspend fun enrichStandaloneContext(
        text: String,
        mode: String?,
        namespaceValue: String?,
    ): String {
        return runCatching {
            val agent = defaultAgentProvider.getDefault()
            val project = projectService.getOrCreate(defaultStandaloneProjectPath())
            val conversationId = Conversation.Id("memory_enrich_context:standalone:${uuid7()}")
            val threadId = Conversation.Thread.Id("memory_enrich_context:standalone:${uuid7()}")
            val targetMessage = syntheticEnrichmentTargetMessage(
                conversationId = conversationId,
                text = text,
                mode = mode,
                standalone = true,
            )
            val result = memoryApplicationService.buildRuntimeMemoryReadResult(
                conversationId = conversationId,
                threadId = threadId,
                targetMessage = targetMessage,
                threadMessages = listOf(targetMessage),
                agent = agent,
                project = project,
                runtimeSystemPrompts = agentDomainService.assembleSystemPrompt(agent, project),
                runtimeTools = aiToolProvider.getTools().withoutMemoryManagementTools(),
                namespaceOverride = resolveMemoryNamespace(namespaceValue, project),
            )
            MemoryToolResultRenderer.enrichContextResultJsonString(result)
        }.onFailure { error ->
            log.warn(error) {
                "Memory tool failed: tool=$MEMORY_ENRICH_CONTEXT_TOOL_NAME target=provided_context standalone=true error=${error.message}"
            }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory tool failed.")
        }
    }

    private suspend fun answerStandaloneQuestion(
        text: String,
        mode: String?,
        namespaceValue: String?,
    ): String {
        return runCatching {
            val agent = defaultAgentProvider.getDefault()
            val project = projectService.getOrCreate(defaultStandaloneProjectPath())
            val conversationId = Conversation.Id("memory_answer_question:standalone:${uuid7()}")
            val threadId = Conversation.Thread.Id("memory_answer_question:standalone:${uuid7()}")
            val targetMessage = syntheticEnrichmentTargetMessage(
                conversationId = conversationId,
                text = text,
                mode = mode,
                standalone = true,
                origin = "provided_question",
            )
            val result = memoryApplicationService.answerQuestionFromMemory(
                conversationId = conversationId,
                threadId = threadId,
                targetMessage = targetMessage,
                threadMessages = listOf(targetMessage),
                agent = agent,
                project = project,
                runtimeSystemPrompts = agentDomainService.assembleSystemPrompt(agent, project),
                runtimeTools = aiToolProvider.getTools().withoutMemoryManagementTools(),
                namespaceOverride = resolveMemoryNamespace(namespaceValue, project),
            )
            MemoryToolResultRenderer.answerQuestionResultJsonString(result)
        }.onFailure { error ->
            log.warn(error) {
                "Memory tool failed: tool=$MEMORY_ANSWER_QUESTION_TOOL_NAME target=provided_question standalone=true error=${error.message}"
            }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory tool failed.")
        }
    }

    private fun syntheticEnrichmentTargetMessage(
        conversationId: Conversation.Id,
        text: String,
        mode: String?,
        standalone: Boolean,
        origin: String = "provided_context",
    ): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
            providerMetadata = buildJsonObject {
                put("memoryToolOrigin", origin)
                put("standalone", standalone)
                mode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
            },
            createdAt = Clock.System.now(),
        )

    private suspend fun runMemoryTool(
        toolName: String,
        conversationIdValue: String,
        targetMessageId: String?,
        action: suspend (MemoryToolContext, Conversation.Message) -> String,
    ): String {
        return runCatching {
            val conversationId = Conversation.Id(conversationIdValue)
            val context = resolveContext(conversationId)
            val targetMessage = resolveTargetMessage(context.threadMessages, targetMessageId)
            action(context, targetMessage)
        }.onFailure { error ->
            log.warn(error) {
                "Memory tool failed: tool=$toolName conversation=$conversationIdValue target=${targetMessageId ?: "previous_user_message"} error=${error.message}"
            }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory tool failed.")
        }
    }

    private suspend fun resolveContext(conversationId: Conversation.Id): MemoryToolContext {
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found: ${conversationId.value}")
        val agent = agentDomainService.findById(conversation.agentDefinitionId)
            ?: throw IllegalStateException("Agent not found for conversation ${conversationId.value}: ${conversation.agentDefinitionId.value}")
        val project = conversationService.getProject(conversationId)
        val systemPrompts = agentDomainService.assembleSystemPrompt(agent, project)
        val memoryTools = aiToolProvider.getTools().withoutMemoryManagementTools()
        val threadMessages = conversationService.loadCurrentMessages(conversationId)

        return MemoryToolContext(
            conversation = conversation,
            agent = agent,
            project = project,
            systemPrompts = systemPrompts,
            memoryTools = memoryTools,
            threadMessages = threadMessages,
        )
    }

    private fun resolveTargetMessage(
        threadMessages: List<Conversation.Message>,
        targetMessageId: String?,
    ): Conversation.Message {
        val explicitMessageId = targetMessageId?.takeIf { it.isNotBlank() }
        if (explicitMessageId != null) {
            return threadMessages.firstOrNull { message ->
                message.id.value == explicitMessageId && !message.isSyntheticMemoryMessage()
            } ?: throw IllegalArgumentException("Target message not found in the current thread: $explicitMessageId")
        }

        return threadMessages
            .asReversed()
            .firstOrNull { message ->
                message.hasUserAuthoredContent() && !message.isSyntheticMemoryMessage()
            }
            ?: throw IllegalArgumentException("No previous user-authored message found in the current thread.")
    }

    private fun Conversation.Message.hasUserAuthoredContent(): Boolean =
        content.any { it is Conversation.Message.ContentItem.UserMessage }

    private fun Conversation.Message.isSyntheticMemoryMessage(): Boolean =
        providerMetadata["syntheticKind"]?.jsonPrimitive?.contentOrNull == "memory"

    private suspend fun loadRunDescendants(
        rootRun: MemoryRun,
        maxDepth: Int,
    ): List<MemoryRun> {
        val visited = mutableSetOf(rootRun.id)
        val descendants = mutableListOf<MemoryRun>()
        var frontier = listOf(rootRun)

        repeat(maxDepth) {
            val next = frontier
                .flatMap { run -> loadDirectRunChildren(run) }
                .filter { run -> visited.add(run.id) }
            if (next.isEmpty()) {
                return descendants
            }
            descendants += next
            frontier = next
        }

        return descendants
    }

    private suspend fun loadDirectRunChildren(run: MemoryRun): List<MemoryRun> =
        (
            memoryStore.findRunsByParentRunId(run.id) +
                run.childRunIds.mapNotNull { childRunId -> memoryStore.findRunById(childRunId) }
            ).distinctBy { it.id }

    private suspend fun resolveMaintenanceTarget(
        conversationIdValue: String?,
        targetTypeValue: String?,
        targetValue: String?,
        projectPathValue: String?,
        runIdValue: String?,
        namespaceValue: String?,
    ): MemoryMaintenanceTarget {
        if (!conversationIdValue.isNullOrBlank()) {
            return MemoryMaintenanceTarget(MemoryMaintenanceTarget.Kind.CONVERSATION_ID, conversationIdValue.trim())
        }
        if (!projectPathValue.isNullOrBlank()) {
            return MemoryMaintenanceTarget(MemoryMaintenanceTarget.Kind.PROJECT_PATH, projectPathValue.trim())
        }
        if (!runIdValue.isNullOrBlank()) {
            return MemoryMaintenanceTarget(MemoryMaintenanceTarget.Kind.RUN_ID, runIdValue.trim())
        }
        if (!namespaceValue.isNullOrBlank()) {
            return MemoryMaintenanceTarget(MemoryMaintenanceTarget.Kind.NAMESPACE, namespaceValue.trim())
        }

        val normalizedTargetType = targetTypeValue?.trim().orEmpty()
        val normalizedTarget = targetValue?.trim().orEmpty()
        if (normalizedTargetType.isNotBlank() || normalizedTarget.isNotBlank()) {
            require(normalizedTargetType.isNotBlank()) { "memory_maintenance target_type is required when target is provided." }
            require(normalizedTarget.isNotBlank()) { "memory_maintenance target is required when target_type is provided." }
            return MemoryMaintenanceTarget(MemoryMaintenanceTarget.Kind.from(normalizedTargetType), normalizedTarget)
        }

        return MemoryMaintenanceTarget(MemoryMaintenanceTarget.Kind.PROJECT_PATH, defaultStandaloneProjectPath())
    }

    private suspend fun resolveMaintenanceContext(target: MemoryMaintenanceTarget): MemoryMaintenanceContext =
        when (target.kind) {
            MemoryMaintenanceTarget.Kind.CONVERSATION_ID -> resolveContext(Conversation.Id(target.value))
                .toMaintenanceContext(Conversation.Id(target.value))

            MemoryMaintenanceTarget.Kind.PROJECT_PATH -> {
                val project = projectService.getOrCreate(File(target.value).absolutePath)
                project.toStandaloneMaintenanceContext()
            }

            MemoryMaintenanceTarget.Kind.RUN_ID -> {
                val run = memoryStore.findRunById(MemoryRun.Id(target.value))
                    ?: throw IllegalArgumentException("Memory run not found: ${target.value}")
                run.namespace.toStandaloneMaintenanceContext()
            }

            MemoryMaintenanceTarget.Kind.NAMESPACE -> {
                MemoryNamespace(target.value).toStandaloneMaintenanceContext()
            }
        }

    private suspend fun Project.toStandaloneMaintenanceContext(): MemoryMaintenanceContext {
        return toStandaloneMaintenanceContext(namespace = defaultMemoryNamespace())
    }

    private suspend fun MemoryNamespace.toStandaloneMaintenanceContext(): MemoryMaintenanceContext {
        val project = projectService.getOrCreate(defaultStandaloneProjectPath())
        return project.toStandaloneMaintenanceContext(namespace = this)
    }

    private suspend fun Project.toStandaloneMaintenanceContext(namespace: MemoryNamespace): MemoryMaintenanceContext {
        val agent = defaultAgentProvider.getDefault()
        val systemPrompts = agentDomainService.assembleSystemPrompt(agent, this)
        return MemoryMaintenanceContext(
            conversationId = Conversation.Id("memory_maintenance:standalone:${uuid7()}"),
            agent = agent,
            project = this,
            namespace = namespace,
            systemPrompts = systemPrompts,
            memoryTools = aiToolProvider.getTools().withoutMemoryManagementTools(),
        )
    }

    private fun MemoryToolContext.toMaintenanceContext(conversationId: Conversation.Id): MemoryMaintenanceContext =
        MemoryMaintenanceContext(
            conversationId = conversationId,
            agent = agent,
            project = project,
            namespace = project.defaultMemoryNamespace(),
            systemPrompts = systemPrompts,
            memoryTools = memoryTools,
        )

    private fun defaultStandaloneProjectPath(): String =
        System.getProperty("gromozeka.project.root")
            ?: settingsService.homeDirectory

    private fun resolveMemoryNamespace(
        explicitNamespaceValue: String?,
        project: Project,
    ): MemoryNamespace =
        explicitNamespaceValue.toConfiguredMemoryNamespace()
            ?: settingsService.userProfile.memorySettings.defaultMemoryNamespace()
            ?: project.defaultMemoryNamespace()

    private fun MemoryNamespace?.emptyNamespaceSummaryIfMissing(existing: List<MemoryNamespaceSummary>): List<MemoryNamespaceSummary> {
        if (this == null || existing.any { it.namespace == this }) return emptyList()
        return listOf(MemoryNamespaceSummary(namespace = this))
    }

    private suspend fun MemoryNamespaceSummary.withReadableDisplayName(): MemoryNamespaceSummary =
        copy(displayName = readableNamespaceName(namespace))

    private suspend fun readableNamespaceName(namespace: MemoryNamespace): String {
        val projectId = namespace.value.removePrefix(PROJECT_MEMORY_NAMESPACE_PREFIX)
        if (projectId == namespace.value || projectId.isBlank()) return namespace.value
        val project = projectService.findById(Project.Id(projectId)) ?: return namespace.value
        val pathName = File(project.path).name.takeIf { it.isNotBlank() }
        val projectName = project.name.takeIf { it.isNotBlank() } ?: pathName ?: project.id.value
        return "$projectName (${namespace.value})"
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun externalRecordSourceId(
        kind: String,
        namespace: MemoryNamespace,
        recordRef: String,
        contentHash: String,
    ): MemorySource.Id {
        val identityHash = listOf(namespace.value, recordRef, contentHash)
            .joinToString("\n")
            .sha256()
            .take(32)
        return MemorySource.Id("external:$kind:$identityHash")
    }

    private data class MemoryToolContext(
        val conversation: Conversation,
        val agent: AgentDefinition,
        val project: Project,
        val systemPrompts: List<String>,
        val memoryTools: List<AiToolCallback>,
        val threadMessages: List<Conversation.Message>,
    )

    private data class MemoryMaintenanceContext(
        val conversationId: Conversation.Id,
        val agent: AgentDefinition,
        val project: Project,
        val namespace: MemoryNamespace,
        val systemPrompts: List<String>,
        val memoryTools: List<AiToolCallback>,
    )

    private data class MemoryMaintenanceTarget(
        val kind: Kind,
        val value: String,
    ) {
        enum class Kind(val toolName: String) {
            CONVERSATION_ID("conversation_id"),
            PROJECT_PATH("project_path"),
            RUN_ID("run_id"),
            NAMESPACE("namespace");

            companion object {
                fun from(value: String): Kind =
                    entries.firstOrNull { it.toolName == value.trim().lowercase() || it.name == value.trim().uppercase() }
                        ?: throw IllegalArgumentException("Unsupported memory_maintenance target_type: $value")
            }
        }
    }

}
