package com.gromozeka.application.service.memory

import com.gromozeka.application.service.MemoryApplicationService
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryIngestPlan
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemorySourceUsagePolicy
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.shared.uuid.uuid7
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.springframework.stereotype.Service

@Service
class MemoryOperationExecutor internal constructor(
    private val contextResolver: MemoryOperationContextResolver,
    private val memoryApplicationService: MemoryApplicationService,
    private val memoryMessageRoutingApplicationService: MemoryMessageRoutingApplicationService,
    private val segmentedIngestProcessor: MemorySegmentedIngestProcessor,
    private val ingestPreflight: MemoryIngestPreflightApplicationService,
    private val settingsProvider: SettingsProvider,
    private val memoryStore: MemoryStore,
) {
    private val rememberContentResolver = MemoryRememberContentResolver()
    private val sourceMapper = ConversationMessageMemorySourceMapper()
    private val resultJson = Json { ignoreUnknownKeys = true }

    suspend fun prepareRememberMessage(
        conversationIdValue: String,
        targetMessageId: String? = null,
        forceWrite: Boolean? = null,
        confirmedPreflightRunId: String? = null,
        namespaceValue: String? = null,
    ): PreparedMemoryOperation {
        val conversationId = Conversation.Id(conversationIdValue)
        val context = contextResolver.resolveConversation(conversationId)
        val targetMessage = contextResolver.resolveTargetMessage(context.threadMessages, targetMessageId)
        val namespace = contextResolver.resolveNamespace(namespaceValue)
        return PreparedMemoryOperation(
            request = MemoryOperationRequest.RememberMessage(
                namespace = namespace,
                conversationId = conversationId,
                threadId = requireNotNull(context.conversation).currentThread,
                targetMessageId = targetMessage.id,
                forceWrite = forceWrite,
                confirmedPreflightRunId = confirmedPreflightRunId.toMemoryRunIdOrNull(),
            ),
            summary = "Memory remember queued",
            inputHash = targetMessage.id.value.sha256(),
        )
    }

    suspend fun prepareRememberProvidedContent(
        conversationIdValue: String?,
        text: String? = null,
        filePath: String? = null,
        rawUrl: String? = null,
        documentType: String? = null,
        title: String? = null,
        sourceRef: String? = null,
        forceWrite: Boolean? = null,
        confirmedPreflightRunId: String? = null,
        mode: String? = null,
        namespaceValue: String? = null,
        writeSurface: MemoryWriteSurface = MemoryWriteSurface.CHAT_TOOL,
    ): PreparedMemoryOperation {
        val content = MemoryRememberContentRequest.fromExternal(
            text = text,
            filePath = filePath,
            rawUrl = rawUrl,
            documentType = documentType,
            title = title,
            sourceRef = sourceRef,
        )
        val conversationId = conversationIdValue.toConversationIdOrNull()
        val context = resolveContext(conversationId)
        val namespace = contextResolver.resolveNamespace(namespaceValue)
        return PreparedMemoryOperation(
            request = MemoryOperationRequest.RememberProvidedContent(
                namespace = namespace,
                conversationId = conversationId,
                threadId = context.conversation?.currentThread,
                content = content,
                forceWrite = forceWrite,
                confirmedPreflightRunId = confirmedPreflightRunId.toMemoryRunIdOrNull(),
                mode = mode,
                writeSurface = writeSurface,
            ),
            summary = if (content.documentType == null) "Memory remember queued" else "Document ingest queued",
            inputHash = content.input.identityValue().sha256(),
        )
    }

    suspend fun prepareEnrichMessage(
        conversationIdValue: String,
        targetMessageId: String? = null,
        namespaceValue: String? = null,
    ): PreparedMemoryOperation {
        val conversationId = Conversation.Id(conversationIdValue)
        val context = contextResolver.resolveConversation(conversationId)
        val targetMessage = contextResolver.resolveTargetMessage(context.threadMessages, targetMessageId)
        val namespace = contextResolver.resolveNamespace(namespaceValue)
        return PreparedMemoryOperation(
            request = MemoryOperationRequest.EnrichMessage(
                namespace = namespace,
                conversationId = conversationId,
                threadId = requireNotNull(context.conversation).currentThread,
                targetMessageId = targetMessage.id,
            ),
            summary = "Memory context enrichment queued",
            inputHash = targetMessage.id.value.sha256(),
        )
    }

    suspend fun prepareEnrichProvidedContext(
        conversationIdValue: String?,
        contextText: String,
        mode: String? = null,
        namespaceValue: String? = null,
    ): PreparedMemoryOperation {
        val normalizedContext = contextText.trim()
        require(normalizedContext.isNotBlank()) { "Provided context is blank." }
        val conversationId = conversationIdValue.toConversationIdOrNull()
        val context = resolveContext(conversationId)
        val namespace = contextResolver.resolveNamespace(namespaceValue)
        return PreparedMemoryOperation(
            request = MemoryOperationRequest.EnrichProvidedContext(
                namespace = namespace,
                conversationId = conversationId,
                threadId = context.conversation?.currentThread,
                context = normalizedContext,
                mode = mode,
            ),
            summary = "Memory context enrichment queued",
            inputHash = normalizedContext.sha256(),
        )
    }

    suspend fun prepareAnswerMessage(
        conversationIdValue: String,
        targetMessageId: String? = null,
        namespaceValue: String? = null,
    ): PreparedMemoryOperation {
        val conversationId = Conversation.Id(conversationIdValue)
        val context = contextResolver.resolveConversation(conversationId)
        val targetMessage = contextResolver.resolveTargetMessage(context.threadMessages, targetMessageId)
        val namespace = contextResolver.resolveNamespace(namespaceValue)
        return PreparedMemoryOperation(
            request = MemoryOperationRequest.AnswerMessage(
                namespace = namespace,
                conversationId = conversationId,
                threadId = requireNotNull(context.conversation).currentThread,
                targetMessageId = targetMessage.id,
            ),
            summary = "Memory question answering queued",
            inputHash = targetMessage.id.value.sha256(),
        )
    }

    suspend fun prepareAnswerProvidedQuestion(
        conversationIdValue: String?,
        questionText: String,
        mode: String? = null,
        namespaceValue: String? = null,
    ): PreparedMemoryOperation {
        val normalizedQuestion = questionText.trim()
        require(normalizedQuestion.isNotBlank()) { "Provided memory question is blank." }
        val conversationId = conversationIdValue.toConversationIdOrNull()
        val context = resolveContext(conversationId)
        val namespace = contextResolver.resolveNamespace(namespaceValue)
        return PreparedMemoryOperation(
            request = MemoryOperationRequest.AnswerProvidedQuestion(
                namespace = namespace,
                conversationId = conversationId,
                threadId = context.conversation?.currentThread,
                question = normalizedQuestion,
                mode = mode,
            ),
            summary = "Memory question answering queued",
            inputHash = normalizedQuestion.sha256(),
        )
    }

    internal suspend fun execute(
        rootRun: MemoryRun,
        request: MemoryOperationRequest,
    ): MemoryOperationExecution = executeRequest(rootRun, request)

    suspend fun executeSynchronously(prepared: PreparedMemoryOperation): String {
        if (prepared.request.kind != MemoryOperationKind.REMEMBER) {
            return executeRequest(rootRun = null, request = prepared.request).output.toString()
        }

        val startedAt = Clock.System.now()
        val rootRun = MemoryRun(
            id = MemoryRun.Id("memory-operation:${prepared.request.kind.wireName}:run:${uuid7()}"),
            namespace = prepared.namespace,
            runType = prepared.runType,
            triggerMode = MemoryRun.TriggerMode.MANUAL,
            summary = prepared.summary,
            sourceIds = prepared.sourceIds,
            progress = prepared.progress,
            inputHash = prepared.inputHash,
            output = prepared.initialOutput,
            status = MemoryRun.Status.RUNNING,
            createdAt = startedAt,
            startedAt = startedAt,
        )
        memoryStore.apply(MemoryUpdateBatch(sources = prepared.sources, runs = listOf(rootRun)))
        return try {
            val execution = executeRequest(rootRun, prepared.request)
            val completedAt = Clock.System.now()
            memoryStore.apply(
                MemoryUpdateBatch(
                    runs = listOf(
                        rootRun.complete(execution, startedAt, completedAt)
                    )
                )
            )
            execution.output.toString()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val completedAt = Clock.System.now()
            val failedBaseRun = memoryStore.findRunById(rootRun.id) ?: rootRun
            memoryStore.apply(
                MemoryUpdateBatch(
                    runs = listOf(
                        failedBaseRun.copy(
                            status = MemoryRun.Status.FAILED,
                            summary = "Memory remember failed",
                            errorText = error.message ?: error::class.simpleName.orEmpty(),
                            output = parseResult(
                                MemoryToolResultRenderer.failureJsonString(
                                    error.message ?: "Memory remember failed."
                                )
                            ),
                            latencyMs = completedAt.toEpochMilliseconds() - startedAt.toEpochMilliseconds(),
                            completedAt = completedAt,
                        )
                    )
                )
            )
            throw error
        }
    }

    private suspend fun executeRequest(
        rootRun: MemoryRun?,
        request: MemoryOperationRequest,
    ): MemoryOperationExecution =
        when (request) {
            is MemoryOperationRequest.RememberMessage -> executeRememberMessage(rootRun, request)
            is MemoryOperationRequest.RememberProvidedContent -> executeRememberProvidedContent(rootRun, request)
            is MemoryOperationRequest.EnrichMessage -> executeEnrichMessage(request)
            is MemoryOperationRequest.EnrichProvidedContext -> executeEnrichProvidedContext(request)
            is MemoryOperationRequest.AnswerMessage -> executeAnswerMessage(request)
            is MemoryOperationRequest.AnswerProvidedQuestion -> executeAnswerProvidedQuestion(request)
        }

    private suspend fun executeRememberMessage(
        rootRun: MemoryRun?,
        request: MemoryOperationRequest.RememberMessage,
    ): MemoryOperationExecution {
        val context = contextResolver.resolveConversation(request.conversationId, request.threadId)
        val targetMessage = contextResolver.loadTargetMessage(request.conversationId, request.targetMessageId)
        val parentSource = requireNotNull(
            sourceMapper.toChatTurn(
                namespace = request.namespace,
                conversationId = request.conversationId,
                threadId = request.threadId,
                message = targetMessage,
            )
        ) { "Target message has no memory-ingestable text." }
        val document = MarkdownDocumentImportDetector.detect(parentSource.contentText)
        val effectiveForceWrite = resolveMemoryForceWrite(
            explicitForceWrite = request.forceWrite,
            documentInput = document != null,
            forceDocumentsByDefault = settingsProvider.userProfile.memorySettings.forceWriteForDocumentIngest,
        )
        val preflight = resolvePreflight(
            contentText = document?.markdown ?: parentSource.contentText,
            sourceLabel = parentSource.id.value,
            context = context,
            namespace = request.namespace,
            confirmedPreflightRunId = request.confirmedPreflightRunId,
        )
        if (preflight.plan.decision != MemoryIngestPlan.Decision.READY) {
            return preflight.needsInputExecution()
        }
        if (document != null || preflight.sections.size > 1) {
            return executeSegmentedMessage(
                rootRun = requireNotNull(rootRun) { "Segmented message ingest requires a persisted root run." },
                request = request,
                parentSource = parentSource,
                preflight = preflight,
                effectiveForceWrite = effectiveForceWrite,
                document = document,
                context = context,
            )
        }

        val result = memoryMessageRoutingApplicationService.routeMessage(
            conversationId = request.conversationId,
            threadId = request.threadId,
            message = targetMessage,
            agent = context.agent,
            project = context.project,
            runtimeSystemPrompts = context.systemPrompts,
            runtimeTools = context.memoryTools,
            threadContextMessages = context.threadMessages.withTargetMessage(targetMessage),
            forceMemoryWrite = effectiveForceWrite,
            namespaceOverride = request.namespace,
            parentRunId = rootRun?.id,
        )
        return rememberExecution(result).copy(
            inputHash = preflight.contentHash,
            llmCalls = preflight.llmCalls,
        )
    }

    private suspend fun executeRememberProvidedContent(
        rootRun: MemoryRun?,
        request: MemoryOperationRequest.RememberProvidedContent,
    ): MemoryOperationExecution {
        val resolvedContent = rememberContentResolver.resolve(request.content)
        val contentText = resolvedContent.text
        require(contentText.isNotBlank()) { "Provided memory content is blank." }
        val context = resolveContext(request.conversationId, request.threadId)
        val documentType = resolvedContent.documentType
        val effectiveForceWrite = resolveMemoryForceWrite(
            explicitForceWrite = request.forceWrite,
            documentInput = documentType != null,
            forceDocumentsByDefault = settingsProvider.userProfile.memorySettings.forceWriteForDocumentIngest,
        )
        val preflight = resolvePreflight(
            contentText = contentText,
            sourceLabel = resolvedContent.sourceRef,
            context = context,
            namespace = request.namespace,
            confirmedPreflightRunId = request.confirmedPreflightRunId,
        )
        if (preflight.plan.decision != MemoryIngestPlan.Decision.READY) {
            return preflight.needsInputExecution()
        }
        if (preflight.sections.size > 1) {
            return executeSegmentedProvidedContent(
                rootRun = requireNotNull(rootRun) { "Segmented content ingest requires a persisted root run." },
                request = request,
                resolvedContent = resolvedContent,
                preflight = preflight,
                effectiveForceWrite = effectiveForceWrite,
                context = context,
            )
        }
        return executeAtomicProvidedContent(
            rootRun = rootRun,
            request = request,
            resolvedContent = resolvedContent,
            preflight = preflight,
            effectiveForceWrite = effectiveForceWrite,
            context = context,
        )
    }

    private suspend fun executeAtomicProvidedContent(
        rootRun: MemoryRun?,
        request: MemoryOperationRequest.RememberProvidedContent,
        resolvedContent: MemoryResolvedRememberContent,
        preflight: MemoryIngestPreflightResult,
        effectiveForceWrite: Boolean,
        context: MemoryOperationContext,
    ): MemoryOperationExecution {
        val now = Clock.System.now()
        val documentType = resolvedContent.documentType
        val sourceKind = if (documentType == null) "provided-text" else "document"
        val source = MemorySource.ExternalRecord(
            id = externalRecordSourceId(
                kind = sourceKind,
                namespace = request.namespace,
                recordRef = resolvedContent.sourceRef,
                contentHash = preflight.contentHash,
            ),
            namespace = request.namespace,
            recordRef = resolvedContent.sourceRef,
            authorLabel = if (documentType == null) "user-provided text" else "user-provided document",
            contentText = resolvedContent.text,
            contentPayload = MemoryWriteOrigin(
                kind = if (documentType == null) {
                    MemoryWriteOriginKind.PROVIDED_TEXT
                } else {
                    MemoryWriteOriginKind.PROVIDED_DOCUMENT
                },
                surface = request.writeSurface,
                sourceKind = MemoryWriteSourceKind.DOCUMENT.takeIf { documentType != null },
                userConsentConfirmed = true,
                standalone = context.conversation == null,
                inputKind = resolvedContent.kind,
                documentType = documentType,
                sourceRef = resolvedContent.sourceRef,
                title = resolvedContent.title,
                importedAt = now.takeIf { documentType != null },
                forceWrite = effectiveForceWrite,
                mode = request.mode,
            ).toMetadataJson(),
            contentHash = preflight.contentHash,
            observedAt = now,
            createdAt = now,
            retentionClass = if (documentType == null) {
                MemorySource.RetentionClass.STANDARD
            } else {
                MemorySource.RetentionClass.IMPORTED
            },
        )
        val result = memoryMessageRoutingApplicationService.routeSource(
            namespace = request.namespace,
            source = source,
            agent = context.agent,
            project = context.project,
            runtimeSystemPrompts = context.systemPrompts,
            runtimeTools = context.memoryTools,
            parentRunId = rootRun?.id,
        )
        return rememberExecution(result).copy(
            inputHash = preflight.contentHash,
            llmCalls = preflight.llmCalls,
        )
    }

    private suspend fun executeSegmentedProvidedContent(
        rootRun: MemoryRun,
        request: MemoryOperationRequest.RememberProvidedContent,
        resolvedContent: MemoryResolvedRememberContent,
        preflight: MemoryIngestPreflightResult,
        effectiveForceWrite: Boolean,
        context: MemoryOperationContext,
    ): MemoryOperationExecution {
        val now = Clock.System.now()
        val documentType = resolvedContent.documentType
        val ingestKind = if (documentType == null) "provided_text" else "document"
        val parentSource = MemorySource.ExternalRecord(
            id = externalRecordSourceId(
                kind = "$ingestKind-parent",
                namespace = request.namespace,
                recordRef = resolvedContent.sourceRef,
                contentHash = preflight.contentHash,
            ),
            namespace = request.namespace,
            recordRef = resolvedContent.sourceRef,
            authorLabel = if (documentType == null) "user-provided text" else "user-provided document",
            contentText = resolvedContent.text,
            contentPayload = MemoryWriteOrigin(
                kind = if (documentType == null) MemoryWriteOriginKind.PROVIDED_TEXT else MemoryWriteOriginKind.PROVIDED_DOCUMENT,
                surface = request.writeSurface,
                sourceKind = MemoryWriteSourceKind.DOCUMENT.takeIf { documentType != null },
                userConsentConfirmed = true,
                standalone = context.conversation == null,
                inputKind = resolvedContent.kind,
                documentType = documentType,
                sourceRef = resolvedContent.sourceRef,
                title = resolvedContent.title,
                importedAt = now.takeIf { documentType != null },
                forceWrite = effectiveForceWrite,
                mode = request.mode,
            ).toMetadataJson(),
            contentHash = preflight.contentHash,
            observedAt = now,
            createdAt = now,
            retentionClass = if (documentType == null) MemorySource.RetentionClass.STANDARD else MemorySource.RetentionClass.IMPORTED,
            usagePolicy = MemorySourceUsagePolicy.AUDIT_ONLY.copy(reason = "segmented ingest parent; section sources are processed"),
        )
        val preparedRootRun = rootRun.preparedForSegmentedIngest(
            parentSource = parentSource,
            preflight = preflight,
            ingestKind = ingestKind,
            sourceRef = resolvedContent.sourceRef,
            title = resolvedContent.title,
        )
        memoryStore.apply(MemoryUpdateBatch(sources = listOf(parentSource), runs = listOf(preparedRootRun)))

        return segmentedIngestProcessor.process(
            rootRun = preparedRootRun,
            request = MemorySegmentedIngestRequest(
                kind = ingestKind,
                sourceRef = resolvedContent.sourceRef,
                title = resolvedContent.title,
            ),
            parentSource = parentSource,
            sections = preflight.sections,
            context = MemorySegmentedIngestContext(
                agent = context.agent,
                project = context.project,
                systemPrompts = context.systemPrompts,
                memoryTools = context.memoryTools,
            ),
            sectionSourceFactory = { section ->
                providedSectionSource(
                    rootRun = preparedRootRun,
                    parentSource = parentSource,
                    section = section,
                    request = request,
                    resolvedContent = resolvedContent,
                    contentHash = preflight.contentHash,
                    effectiveForceWrite = effectiveForceWrite,
                )
            },
        ).copy(
            inputHash = preflight.contentHash,
            llmCalls = preflight.llmCalls,
        )
    }

    private suspend fun executeSegmentedMessage(
        rootRun: MemoryRun,
        request: MemoryOperationRequest.RememberMessage,
        parentSource: MemorySource.ChatTurn,
        preflight: MemoryIngestPreflightResult,
        effectiveForceWrite: Boolean,
        document: MarkdownDocumentImport?,
        context: MemoryOperationContext,
    ): MemoryOperationExecution {
        val auditParent = parentSource.copy(
            usagePolicy = MemorySourceUsagePolicy.AUDIT_ONLY.copy(reason = "segmented chat message parent; section sources are processed"),
        )
        val ingestKind = if (document == null) "chat_message" else "pasted_document"
        val sourceRef = document?.sourceRef ?: parentSource.sourceMessageId?.value ?: parentSource.id.value
        val preparedRootRun = rootRun.preparedForSegmentedIngest(
            parentSource = auditParent,
            preflight = preflight,
            ingestKind = ingestKind,
            sourceRef = sourceRef,
            title = document?.title,
        )
        memoryStore.apply(MemoryUpdateBatch(sources = listOf(auditParent), runs = listOf(preparedRootRun)))

        return segmentedIngestProcessor.process(
            rootRun = preparedRootRun,
            request = MemorySegmentedIngestRequest(
                kind = ingestKind,
                sourceRef = sourceRef,
                title = document?.title,
            ),
            parentSource = auditParent,
            sections = preflight.sections,
            context = MemorySegmentedIngestContext(
                agent = context.agent,
                project = context.project,
                systemPrompts = context.systemPrompts,
                memoryTools = context.memoryTools,
            ),
            sectionSourceFactory = { section ->
                parentSource.toSegmentSource(
                    rootRun = preparedRootRun,
                    section = section,
                    contentHash = preflight.contentHash,
                    effectiveForceWrite = effectiveForceWrite,
                    document = document,
                )
            },
        ).copy(
            inputHash = preflight.contentHash,
            llmCalls = preflight.llmCalls,
        )
    }

    private suspend fun resolvePreflight(
        contentText: String,
        sourceLabel: String,
        context: MemoryOperationContext,
        namespace: com.gromozeka.domain.model.memory.MemoryNamespace,
        confirmedPreflightRunId: MemoryRun.Id?,
    ): MemoryIngestPreflightResult {
        if (confirmedPreflightRunId == null) {
            return ingestPreflight.inspect(
                contentText = contentText,
                sourceLabel = sourceLabel,
                project = context.project,
                runtimeSystemPrompts = context.systemPrompts,
            )
        }

        val previousRun = memoryStore.findRunById(confirmedPreflightRunId)
            ?: throw IllegalArgumentException("Confirmed memory preflight run not found: ${confirmedPreflightRunId.value}")
        require(previousRun.namespace == namespace) {
            "Confirmed memory preflight run belongs to namespace ${previousRun.namespace.value}, not ${namespace.value}."
        }
        require(previousRun.runType == MemoryRun.Type.REMEMBER || previousRun.runType == MemoryRun.Type.DOCUMENT_INGEST) {
            "Confirmed run ${confirmedPreflightRunId.value} is not a memory remember operation."
        }
        require(previousRun.status == MemoryRun.Status.NEEDS_INPUT) {
            "Confirmed run ${confirmedPreflightRunId.value} is ${previousRun.status}, not NEEDS_INPUT."
        }
        val previousOutput = requireNotNull(previousRun.output) {
            "Confirmed memory preflight run has no result."
        }.jsonObject
        val proposedPlan = previousOutput["preflight_plan"]
            ?.let { resultJson.decodeFromJsonElement<MemoryIngestPlan>(it) }
            ?: throw IllegalArgumentException("Confirmed memory preflight run has no proposed structure.")
        val approved = ingestPreflight.approve(contentText, proposedPlan)
        val expectedHash = previousOutput["content_hash"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Confirmed memory preflight run has no content hash.")
        require(approved.contentHash == expectedHash) {
            "Memory content changed after structure was proposed; request a new preflight instead of confirming the old one."
        }
        return approved
    }

    private fun MemoryIngestPreflightResult.needsInputExecution(): MemoryOperationExecution {
        val confirmationAvailable = plan.decision == MemoryIngestPlan.Decision.NEEDS_USER_CONFIRMATION
        val requiredAction = if (confirmationAvailable) {
            "Ask the user to explicitly approve the proposed structure or provide a revised structured source. Do not continue or set confirmed_preflight_run_id without explicit user confirmation."
        } else {
            "Ask the user to divide the content into coherent sections or paragraphs and submit it again. This input cannot be safely ingested as-is."
        }
        return MemoryOperationExecution(
            status = MemoryRun.Status.NEEDS_INPUT,
            summary = if (confirmationAvailable) {
                "Memory ingest needs explicit structure confirmation"
            } else {
                "Memory ingest needs a better structured source"
            },
            output = buildJsonObject {
                put("status", "needs_user_input")
                put("reason", plan.reason)
                put("content_hash", contentHash)
                put("wrote_memory", false)
                put("confirmation_available", confirmationAvailable)
                put("required_action", requiredAction)
                put("preflight_plan", resultJson.encodeToJsonElement(plan))
                putJsonArray("proposed_structure") {
                    sections.forEach { section ->
                        add(
                            buildJsonObject {
                                put("title", section.headingLabel)
                                put("start_line", section.startLine)
                                put("end_line", section.endLine)
                            }
                        )
                    }
                }
            },
            inputHash = contentHash,
            llmCalls = llmCalls,
            progress = MemoryRun.Progress(totalUnits = 1, completedUnits = 1),
        )
    }

    private fun MemoryRun.preparedForSegmentedIngest(
        parentSource: MemorySource,
        preflight: MemoryIngestPreflightResult,
        ingestKind: String,
        sourceRef: String,
        title: String?,
    ): MemoryRun = copy(
        summary = "Memory $ingestKind ingest queued: ${preflight.sections.size} sections",
        sourceIds = (sourceIds + parentSource.id).distinct(),
        progress = MemoryRun.Progress(totalUnits = preflight.sections.size),
        inputHash = preflight.contentHash,
        output = buildJsonObject {
            put("ingest_kind", ingestKind)
            put("title", title.orEmpty())
            put("source_ref", sourceRef)
            put("parent_source_id", parentSource.id.value)
            put("sections_total", preflight.sections.size)
            put("preflight_reason", preflight.plan.reason)
        },
    )

    private fun providedSectionSource(
        rootRun: MemoryRun,
        parentSource: MemorySource.ExternalRecord,
        section: MemoryIngestSection,
        request: MemoryOperationRequest.RememberProvidedContent,
        resolvedContent: MemoryResolvedRememberContent,
        contentHash: String,
        effectiveForceWrite: Boolean,
    ): MemorySource.ExternalRecord {
        val now = Clock.System.now()
        val documentType = resolvedContent.documentType
        val sectionHash = "$contentHash:${section.index}:${section.text.sha256()}".sha256()
        val sourceKind = if (documentType == null) "provided-text-section" else "document-section"
        return MemorySource.ExternalRecord(
            id = externalRecordSourceId(
                kind = sourceKind,
                namespace = rootRun.namespace,
                recordRef = "${resolvedContent.sourceRef}#section:${section.index}",
                contentHash = sectionHash,
            ),
            namespace = rootRun.namespace,
            recordRef = "${resolvedContent.sourceRef}#section:${section.index}",
            authorLabel = if (documentType == null) "user-provided text section" else "document section",
            contentText = if (documentType == null) {
                section.text
            } else {
                section.toMemorySourceText(
                    title = resolvedContent.title,
                    sourceRef = resolvedContent.sourceRef,
                    importedAt = null,
                )
            },
            contentPayload = MemoryWriteOrigin(
                kind = if (documentType == null) {
                    MemoryWriteOriginKind.PROVIDED_TEXT_SECTION
                } else {
                    MemoryWriteOriginKind.PROVIDED_DOCUMENT_SECTION
                },
                surface = request.writeSurface,
                sourceKind = MemoryWriteSourceKind.DOCUMENT.takeIf { documentType != null },
                userConsentConfirmed = true,
                inputKind = resolvedContent.kind,
                documentType = documentType,
                sourceRef = resolvedContent.sourceRef,
                title = resolvedContent.title,
                importedAt = now.takeIf { documentType != null },
                forceWrite = effectiveForceWrite,
                mode = request.mode,
                parentSourceId = parentSource.id,
                parentRunId = rootRun.id,
                documentHash = contentHash.takeIf { documentType != null },
                section = section,
            ).toMetadataJson(),
            contentHash = sectionHash,
            observedAt = parentSource.observedAt,
            createdAt = now,
            retentionClass = if (documentType == null) MemorySource.RetentionClass.STANDARD else MemorySource.RetentionClass.IMPORTED,
        )
    }

    private fun MemorySource.ChatTurn.toSegmentSource(
        rootRun: MemoryRun,
        section: MemoryIngestSection,
        contentHash: String,
        effectiveForceWrite: Boolean,
        document: MarkdownDocumentImport?,
    ): MemorySource.ChatTurn {
        val now = Clock.System.now()
        val sectionHash = "$contentHash:${section.index}:${section.text.sha256()}".sha256()
        val sectionMetadata = MemoryWriteOrigin(
            kind = if (document == null) MemoryWriteOriginKind.CHAT_MESSAGE_SECTION else MemoryWriteOriginKind.PASTED_DOCUMENT_SECTION,
            surface = MemoryWriteSurface.CHAT_TOOL,
            sourceKind = MemoryWriteSourceKind.DOCUMENT.takeIf { document != null },
            documentType = MemoryDocumentType.MARKDOWN.takeIf { document != null },
            sourceRef = document?.sourceRef ?: sourceMessageId?.value ?: id.value,
            title = document?.title,
            importedAt = now.takeIf { document != null },
            forceWrite = effectiveForceWrite,
            parentSourceId = id,
            parentRunId = rootRun.id,
            section = section,
        ).toMetadataJson()
        return copy(
            id = MemorySource.Id("${id.value}:section:${sectionHash.take(16)}"),
            contentText = if (document == null) {
                section.text
            } else {
                section.toMemorySourceText(
                    title = document.title,
                    sourceRef = document.sourceRef,
                    importedAt = null,
                )
            },
            searchText = null,
            contentPayload = JsonObject((contentPayload as? JsonObject).orEmpty() + sectionMetadata),
            contentHash = sectionHash,
            createdAt = now,
            usagePolicy = MemorySourceUsagePolicy.STANDARD,
        )
    }

    private suspend fun executeEnrichMessage(
        request: MemoryOperationRequest.EnrichMessage,
    ): MemoryOperationExecution {
        val context = contextResolver.resolveConversation(request.conversationId, request.threadId)
        val conversation = requireNotNull(context.conversation)
        val targetMessage = contextResolver.loadTargetMessage(request.conversationId, request.targetMessageId)
        val result = memoryApplicationService.buildRuntimeMemoryReadResult(
            conversationId = conversation.id,
            threadId = request.threadId,
            targetMessage = targetMessage,
            threadMessages = context.threadMessages.withTargetMessage(targetMessage),
            agent = context.agent,
            project = context.project,
            runtimeSystemPrompts = context.systemPrompts,
            runtimeTools = context.memoryTools,
            namespaceOverride = request.namespace,
        )
        return completedExecution(
            summary = "Memory context enrichment completed",
            output = MemoryToolResultRenderer.enrichContextResultJsonString(result),
        )
    }

    private suspend fun executeEnrichProvidedContext(
        request: MemoryOperationRequest.EnrichProvidedContext,
    ): MemoryOperationExecution {
        val context = resolveContext(request.conversationId, request.threadId)
        val conversationId = context.conversation?.id ?: Conversation.Id("memory_enrich_context:standalone:${uuid7()}")
        val threadId = request.threadId ?: Conversation.Thread.Id("memory_enrich_context:standalone:${uuid7()}")
        val targetMessage = syntheticTargetMessage(
            conversationId = conversationId,
            text = request.context,
            providerMetadata = buildJsonObject {
                put("memoryToolOrigin", "provided_context")
                put("standalone", context.conversation == null)
                request.mode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
            },
        )
        val result = memoryApplicationService.buildRuntimeMemoryReadResult(
            conversationId = conversationId,
            threadId = threadId,
            targetMessage = targetMessage,
            threadMessages = context.threadMessages + targetMessage,
            agent = context.agent,
            project = context.project,
            runtimeSystemPrompts = context.systemPrompts,
            runtimeTools = context.memoryTools,
            namespaceOverride = request.namespace,
        )
        return completedExecution(
            summary = "Memory context enrichment completed",
            output = MemoryToolResultRenderer.enrichContextResultJsonString(result),
        )
    }

    private suspend fun executeAnswerMessage(
        request: MemoryOperationRequest.AnswerMessage,
    ): MemoryOperationExecution {
        val context = contextResolver.resolveConversation(request.conversationId, request.threadId)
        val conversation = requireNotNull(context.conversation)
        val targetMessage = contextResolver.loadTargetMessage(request.conversationId, request.targetMessageId)
        val result = memoryApplicationService.answerQuestionFromMemory(
            conversationId = conversation.id,
            threadId = request.threadId,
            targetMessage = targetMessage,
            threadMessages = context.threadMessages.withTargetMessage(targetMessage),
            agent = context.agent,
            project = context.project,
            runtimeSystemPrompts = context.systemPrompts,
            runtimeTools = context.memoryTools,
            namespaceOverride = request.namespace,
        )
        return completedExecution(
            summary = "Memory question answering completed",
            output = MemoryToolResultRenderer.answerQuestionResultJsonString(result),
        )
    }

    private suspend fun executeAnswerProvidedQuestion(
        request: MemoryOperationRequest.AnswerProvidedQuestion,
    ): MemoryOperationExecution {
        val context = resolveContext(request.conversationId, request.threadId)
        val conversationId = context.conversation?.id ?: Conversation.Id("memory_answer_question:standalone:${uuid7()}")
        val threadId = request.threadId ?: Conversation.Thread.Id("memory_answer_question:standalone:${uuid7()}")
        val targetMessage = syntheticTargetMessage(
            conversationId = conversationId,
            text = request.question,
            providerMetadata = buildJsonObject {
                put("memoryToolOrigin", "provided_question")
                put("standalone", context.conversation == null)
                request.mode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
            },
        )
        val result = memoryApplicationService.answerQuestionFromMemory(
            conversationId = conversationId,
            threadId = threadId,
            targetMessage = targetMessage,
            threadMessages = context.threadMessages + targetMessage,
            agent = context.agent,
            project = context.project,
            runtimeSystemPrompts = context.systemPrompts,
            runtimeTools = context.memoryTools,
            namespaceOverride = request.namespace,
        )
        return completedExecution(
            summary = "Memory question answering completed",
            output = MemoryToolResultRenderer.answerQuestionResultJsonString(result),
        )
    }

    private suspend fun resolveContext(
        conversationId: Conversation.Id?,
        threadId: Conversation.Thread.Id? = null,
    ): MemoryOperationContext =
        conversationId
            ?.let { contextResolver.resolveConversation(it, threadId) }
            ?: contextResolver.resolveStandalone()

    private fun String?.toConversationIdOrNull(): Conversation.Id? =
        this?.trim()?.takeIf { it.isNotBlank() }?.let(Conversation::Id)

    private fun String?.toMemoryRunIdOrNull(): MemoryRun.Id? =
        this?.trim()?.takeIf { it.isNotBlank() }?.let(MemoryRun::Id)

    private fun MemoryRememberContentInput.identityValue(): String =
        when (this) {
            is MemoryRememberContentInput.Text -> value
            is MemoryRememberContentInput.FilePath -> value
            is MemoryRememberContentInput.RawUrl -> value
        }

    private fun List<Conversation.Message>.withTargetMessage(targetMessage: Conversation.Message): List<Conversation.Message> =
        if (any { it.id == targetMessage.id }) this else this + targetMessage

    private fun rememberExecution(result: com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult?): MemoryOperationExecution =
        MemoryOperationExecution(
            status = MemoryRun.Status.SUCCESS,
            summary = if (result == null) "Memory remember completed without a write" else "Memory remember completed",
            output = parseResult(MemoryToolResultRenderer.rememberResultJsonString(result)),
            sourceIds = result?.sourceBatch?.sources?.map { it.id }.orEmpty(),
            childRunIds = result?.memoryBatch?.runs?.map { it.id }.orEmpty(),
        )

    private fun completedExecution(summary: String, output: String): MemoryOperationExecution =
        MemoryOperationExecution(
            status = MemoryRun.Status.SUCCESS,
            summary = summary,
            output = parseResult(output),
        )

    private fun parseResult(value: String): JsonElement = resultJson.parseToJsonElement(value)

    private fun syntheticTargetMessage(
        conversationId: Conversation.Id,
        text: String,
        providerMetadata: kotlinx.serialization.json.JsonObject,
    ): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
            providerMetadata = providerMetadata,
            createdAt = Clock.System.now(),
        )

    private fun externalRecordSourceId(
        kind: String,
        namespace: com.gromozeka.domain.model.memory.MemoryNamespace,
        recordRef: String,
        contentHash: String,
    ): MemorySource.Id {
        val identityHash = listOf(namespace.value, recordRef, contentHash)
            .joinToString("\n")
            .sha256()
            .take(32)
        return MemorySource.Id("external:$kind:$identityHash")
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

internal fun resolveMemoryForceWrite(
    explicitForceWrite: Boolean?,
    documentInput: Boolean,
    forceDocumentsByDefault: Boolean,
): Boolean =
    explicitForceWrite ?: (documentInput && forceDocumentsByDefault)

internal fun MemoryRun.complete(
    execution: MemoryOperationExecution,
    startedAt: kotlinx.datetime.Instant,
    completedAt: kotlinx.datetime.Instant,
): MemoryRun =
    copy(
        status = execution.status,
        summary = execution.summary,
        sourceIds = (sourceIds + execution.sourceIds).distinct(),
        childRunIds = execution.childRunIds.distinct(),
        progress = execution.progress,
        inputHash = execution.inputHash ?: inputHash,
        llmCalls = (llmCalls + execution.llmCalls).distinctBy { call ->
            listOf(call.stageName, call.attempt, call.startedAt, call.completedAt)
        },
        output = execution.output,
        errorText = execution.errorText,
        latencyMs = completedAt.toEpochMilliseconds() - startedAt.toEpochMilliseconds(),
        startedAt = startedAt,
        completedAt = completedAt,
    )
