package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.shared.uuid.uuid7
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import kotlin.time.Clock

/**
 * Read-only assembly, separate from turn preparation. The caller must authorize the peer first.
 * Never acquires a turn, repairs history, consumes inputs/secrets, or starts background generation.
 */
@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "false", matchIfMissing = true)
class ConversationContextSnapshotService(
    private val conversations: ConversationDomainService,
    private val history: ThreadMessageRepository,
    private val configuration: AiConfigurationProvider,
    private val prompts: AgentPromptAssemblyService,
    private val settings: SettingsProvider,
    private val toolCatalog: DistributedAiToolCatalog,
    private val skills: AgentSkillRuntimeCatalogService,
    private val capabilities: AiToolCapabilityCatalogService,
    private val toolSelection: AiToolRuntimeCatalogService,
    private val temporal: MessageTemporalContextService,
    private val sticky: StickyMessageInstructionService,
    private val artifacts: ConversationArtifactApplicationService,
    private val conversationPins: ConversationPinnedPrompt,
    private val enrichers: List<ConversationRequestEnricher> = emptyList(),
    private val collaboration: AgentCollaborationService? = null,
) {
    suspend fun capture(
        target: AgentEndpoint,
        conversation: Conversation,
        agent: AgentDefinition,
        actor: User.Id,
    ): ConversationContextSnapshot {
        require(conversation.id == target.conversationId && conversation.currentThread == target.threadId) {
            "Snapshot target does not match the authorized conversation branch"
        }
        require(agent.id == target.agentId && Conversation.Participant.Agent(agent.id) in conversation.participants)
        require(agent.type is AgentDefinition.Type.Global || agent.projectId == conversation.projectId)
        val capturedAt = Clock.System.now()
        val resolved = configuration.resolveAiRuntime(agent.runtimeSelection)
        requireToolFreeContextQueryBackend(resolved.connection.kind)
        val profile = settings.userProfile
        // A single ordered repository read pins the history prefix. Later appends/compacts are not admitted.
        val original = history.getMessagesByThread(target.threadId)
        val project = conversations.getProject(conversation.id)
        val context = RuntimeEnvironmentContext.ProjectBound(
            project = project,
            executor = RuntimeEnvironmentExecutor.Server,
            conversation = RuntimeEnvironmentContext.ConversationInfo(conversation.id, conversation.displayName),
        )
        val format = resolved.modelConfiguration.assistantResponseFormat
        val inlineSuggestions = conversation.externalChannel == null &&
            profile.suggestedRepliesSettings.mode == UserProfile.SuggestedRepliesSettings.Mode.INLINE
        val options = AiRuntimeOptions(
            maxOutputTokens = agent.runtimeOverrides.maxOutputTokens,
            reasoning = agent.runtimeOverrides.reasoning,
            toolChoice = AiToolChoice.None,
            toolAccess = ToolAccessPolicy.AllowOnly(),
            responseFormat = AssistantResponseFormatContract.runtimeResponseFormat(format, inlineSuggestions),
            assistantResponseFormat = format,
            usagePurpose = AI_CONTEXT_QUERY_PURPOSE,
            toolContext = mapOf(
                TOOL_CONTEXT_CONVERSATION_ID to conversation.id.value,
                TOOL_CONTEXT_THREAD_ID to target.threadId.value,
                TOOL_CONTEXT_PROJECT_ID to project.id.value,
                TOOL_CONTEXT_AGENT_DEFINITION_ID to agent.id.value,
                TOOL_CONTEXT_USER_ID to actor.value,
                "aiProvider" to resolved.connection.kind.provider.name,
                "modelName" to resolved.modelConfiguration.providerModelId,
            ),
        )
        val projectionRequest = AiRuntimeRequest(emptyList(), original, options = options)
        val nativeProvider = resolved.connection.kind.nativeCompactionProvider()
        val projected = projectionRequest.projectedMessages(nativeProvider)
        val prefix = completeToolGroupPrefix(projected)
        // Empty projection is not proof of empty usable history: a checkpoint in the discarded
        // tail may already have hidden a complete canonical prefix. Fail closed in that case.
        // Do not move the general pair check before projection; retained checkpoints may validly
        // cover old tool groups. Truly empty/wholly incomplete history still permits an empty view.
        if (prefix.isEmpty() && completeToolGroupPrefix(projectionRequest.originalCollaborationMessages()).isNotEmpty()) {
            throw ContextSnapshotUnavailableException("Projected cutoff would discard otherwise complete context")
        }
        // An excluded pending group may contain newer sticky instructions. It must not erase
        // instructions from the retained prefix. Preserve ordinary temporal/coverage semantics
        // by materializing the raw prefix (including covered sources), then projecting it again.
        val rawBoundary = prefix.lastOrNull()?.id?.let { last ->
            original.indexOfLast { it.id == last }.also { index ->
                check(index >= 0) { "Snapshot prefix has no canonical history boundary" }
            }
        } ?: -1
        val runtimeMessages = sticky.materialize(
            temporal.enrich(original.take(rawBoundary + 1), profile.agentSettings.includeMessageTemporalContext),
            profile.messageInstructionGroups,
        )
        val materializedPrefix = projectionRequest.copy(messages = runtimeMessages).projectedMessages(nativeProvider)
        if (materializedPrefix.map { it.id } != prefix.map { it.id }) throw ContextSnapshotUnavailableException(
            "Incomplete history checkpoint prevents a stable projected context prefix")
        val baseCatalog = toolCatalog.readSnapshot(project, agent.toolAccess, conversation)
        val skillContext = skills.prepare(agent, project.id, baseCatalog)
        val catalog = skillContext.toolCatalog
        val definitions = catalog.tools.map { it.definition }
        val memoryEnabled = conversation.externalChannel == null && (
            (profile.memorySettings.autoRemember && configuration.availableRuntimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE) != null) ||
                (profile.memorySettings.autoRecall && configuration.availableRuntimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_READ) != null)
            )
        val selectedTools = toolSelection.selectTools(agent, catalog, prefix, memoryEnabled)
        val peerContext = collaboration.takeIf { conversation.externalChannel == null }
        val systemPrompts = buildList {
            addAll(assembleConversationSystemPrompts(
                base = prompts.assembleSystemPrompt(agent, context),
                agent = agent,
                capabilityCatalog = capabilities.cachedPromptFor(definitions),
                environment = catalog.environmentPrompt,
                skills = skillContext.systemPrompt,
                responseFormat = format,
                includeSuggestedReplies = inlineSuggestions,
            ))
            if (peerContext != null) add(peerContext.prompt(target, peerContext.context(target, actor)))
            selectedTools.unavailableToolsSystemPrompt()?.let(::add)
        }
        val root = prefix.lastOrNull { it.role == Conversation.Message.Role.USER }?.id
            ?: Conversation.Message.Id("context-snapshot:${uuid7()}")
        var request = AiRuntimeRequest(systemPrompts, materializedPrefix, options = options.copy(
            toolContext = options.toolContext + (TOOL_CONTEXT_TARGET_MESSAGE_ID to root.value),
        ))
        val observationId = ConversationRuntimeTurnId("context-snapshot:${uuid7()}")
        for (enricher in enrichers) {
            request = enricher.enrichSnapshot(conversation.id, root, observationId, resolved.modelSpec, request)
        }
        request = request.copy(messages = artifacts.materializeSnapshot(conversation.id, request.messages))
        request = try { conversationPins.append(conversation.id, request) }
        catch (_: ConversationPinReadFailedException) {
            throw ContextSnapshotUnavailableException("Conversation pins are unavailable")
        }
        resolved.modelSpec.requireSupportsInputs(request.messages)
        check(request.tools.isEmpty() && request.options.toolChoice == AiToolChoice.None) {
            "A snapshot enricher enabled tools"
        }
        check(configuration.resolveAiRuntime(agent.runtimeSelection) == resolved) {
            "Selected AI configuration changed during context snapshot assembly"
        }
        return ConversationContextSnapshot(
            target = target,
            runtimeSelection = agent.runtimeSelection,
            resolvedRuntime = resolved,
            request = request,
            capturedAt = capturedAt,
            sourceMessageCount = original.size,
            omittedTrailingMessageCount = projected.size - prefix.size,
        )
    }
}

internal fun requireToolFreeContextQueryBackend(kind: AiConnection.Kind) {
    if (kind == AiConnection.Kind.GITHUB_COPILOT) throw UnsupportedOperationException(
        "GitHub Copilot does not support a strictly tool-free isolated context query; no fallback is used")
}
