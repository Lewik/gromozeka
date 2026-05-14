package com.gromozeka.application.service

import com.gromozeka.application.service.memory.MEMORY_ENRICH_CONTEXT_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_REMEMBER_TOOL_NAME
import com.gromozeka.application.service.memory.MemoryDocumentIngestJob
import com.gromozeka.application.service.memory.MemoryDocumentIngestQueue
import com.gromozeka.application.service.memory.MemoryMessageRoutingApplicationService
import com.gromozeka.application.service.memory.MarkdownDocumentSlicer
import com.gromozeka.application.service.memory.MemoryRememberContentRequest
import com.gromozeka.application.service.memory.MemoryRememberContentResolver
import com.gromozeka.application.service.memory.MemoryRememberDocumentQueuedResult
import com.gromozeka.application.service.memory.MemoryResolvedRememberContent
import com.gromozeka.application.service.memory.MemoryToolResultRenderer
import com.gromozeka.application.service.memory.withoutMemoryManagementTools
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.memory.MemoryNamespace
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
    private val memoryStore: MemoryStore,
) {
    private val log = KLoggers.logger(this)
    private val rememberContentResolver = MemoryRememberContentResolver()

    suspend fun remember(
        conversationIdValue: String,
        targetMessageId: String? = null,
    ): String = runMemoryTool(MEMORY_REMEMBER_TOOL_NAME, conversationIdValue, targetMessageId) { context, targetMessage ->
        val result = memoryMessageRoutingApplicationService.routeMessage(
            conversationId = context.conversation.id,
            threadId = context.conversation.currentThread,
            message = targetMessage,
            agent = context.agent,
            project = context.project,
            runtimeSystemPrompts = context.systemPrompts,
            runtimeTools = context.memoryTools,
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
        mode: String? = null,
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
            return rememberResolvedDocument(conversationIdValue, resolvedContent, mode)
        }

        val normalizedText = resolvedContent.text.trim()
        if (normalizedText.isBlank()) {
            return MemoryToolResultRenderer.failureJsonString("Provided memory text is blank.")
        }

        if (conversationIdValue.isNullOrBlank()) {
            return rememberStandaloneProvidedText(normalizedText, mode)
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
        text: String,
        mode: String?,
    ): String {
        return runCatching {
            val agent = defaultAgentProvider.getDefault()
            val project = projectService.getOrCreate(defaultStandaloneProjectPath())
            val namespace = MemoryNamespace("project:${project.id.value}")
            val contentHash = text.sha256()
            val now = Clock.System.now()
            val source = MemorySource.ExternalRecord(
                id = MemorySource.Id("external:provided-text:${contentHash.take(32)}"),
                namespace = namespace,
                recordRef = "memory_remember:provided_text:${contentHash.take(16)}",
                authorLabel = "user-provided text",
                contentText = text,
                contentPayload = buildJsonObject {
                    put("memoryToolOrigin", "provided_text")
                    put("userConsentConfirmed", true)
                    put("standalone", true)
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
        mode: String?,
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

            val namespace = MemoryNamespace("project:${project.id.value}")
            val now = Clock.System.now()
            val documentHash = resolvedContent.text.sha256()
            val parentRecordRef = "memory_remember:document:${documentHash.take(16)}"
            val parentSource = MemorySource.ExternalRecord(
                id = MemorySource.Id("external:document:${documentHash.take(32)}"),
                namespace = namespace,
                recordRef = parentRecordRef,
                authorLabel = "user-provided document",
                contentText = resolvedContent.text,
                contentPayload = buildJsonObject {
                    put("memoryToolOrigin", "provided_document")
                    put("userConsentConfirmed", true)
                    put("inputKind", resolvedContent.kind.name)
                    put("documentType", documentType.name)
                    put("sourceRef", resolvedContent.sourceRef)
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
                    put("parent_source_id", parentSource.id.value)
                    put("sections_total", sections.size)
                },
                metadata = buildJsonObject {
                    put("memoryToolOrigin", "provided_document")
                    put("inputKind", resolvedContent.kind.name)
                    put("documentType", documentType.name)
                    put("sourceRef", resolvedContent.sourceRef)
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
        )
        MemoryToolResultRenderer.enrichContextResultJsonString(result)
    }

    suspend fun enrichProvidedContext(
        conversationIdValue: String?,
        contextText: String,
        mode: String? = null,
    ): String {
        val normalizedContext = contextText.trim()
        if (normalizedContext.isBlank()) {
            return MemoryToolResultRenderer.failureJsonString("Provided context is blank.")
        }

        if (conversationIdValue.isNullOrBlank()) {
            return enrichStandaloneContext(normalizedContext, mode)
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
        MemoryToolResultRenderer.queueStatusJsonString(memoryDocumentIngestQueue.status())

    private suspend fun enrichStandaloneContext(
        text: String,
        mode: String?,
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

    private fun syntheticEnrichmentTargetMessage(
        conversationId: Conversation.Id,
        text: String,
        mode: String?,
        standalone: Boolean,
    ): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
            providerMetadata = buildJsonObject {
                put("memoryToolOrigin", "provided_context")
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

    private fun defaultStandaloneProjectPath(): String =
        System.getProperty("gromozeka.project.root")
            ?: settingsService.homeDirectory

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class MemoryToolContext(
        val conversation: Conversation,
        val agent: AgentDefinition,
        val project: Project,
        val systemPrompts: List<String>,
        val memoryTools: List<AiToolCallback>,
        val threadMessages: List<Conversation.Message>,
    )
}
