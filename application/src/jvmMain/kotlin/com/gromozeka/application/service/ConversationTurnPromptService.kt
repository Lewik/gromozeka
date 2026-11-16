package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem.PromptMessage
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.*
import org.springframework.stereotype.Service
import kotlin.time.Clock

fun interface ConversationTurnPromptAssembler {
    suspend fun assemble(
        conversation: Conversation,
        agent: AgentDefinition,
        context: RuntimeEnvironmentContext,
        task: ConversationRuntimeTask,
        executor: ConversationRuntimeExecutorIdentity,
        nativeProvider: String?,
        emitMessage: suspend (Message) -> Unit,
    ): List<String>
}

/** Snapshot text lives in immutable messages; Runtime stores only its once-per-turn reference. */
@Service
class ConversationTurnPromptService(
    private val prompts: PromptApplicationService,
    private val threads: ThreadMessageRepository,
    private val history: ConversationHistoryReadService,
    private val messages: ConversationRuntimeMessageAppender,
    private val runtime: ConversationRuntimeCoordinator,
) : ConversationTurnPromptAssembler {
    override suspend fun assemble(
        conversation: Conversation,
        agent: AgentDefinition,
        context: RuntimeEnvironmentContext,
        task: ConversationRuntimeTask,
        executor: ConversationRuntimeExecutorIdentity,
        nativeProvider: String?,
        emitMessage: suspend (Message) -> Unit,
    ): List<String> {
        val state = runtime.schedulingSnapshot(conversation.id)
        check(state.state?.activeTaskId == task.id && state.state?.activeExecutor == executor) {
            "Runtime task no longer owns prompt preparation"
        }
        val turn = checkNotNull(state.lastTurn) { "Prompt preparation requires an agent turn" }
        check(turn.turnId == task.turnId) { "Prompt preparation belongs to another turn" }
        val captured = turn.promptMessageId?.let { id ->
            requireNotNull(history.findMessage(conversation.id, id)) { "Bound turn prompt message is unavailable" }
                .requirePrompt(agent.id)
        }
        if (captured != null) return prompts.assembleSnapshot(captured.snapshot, context)

        val captureId = Message.Id("${task.turnId.value}:prompt:${agent.id.value}")
        val durableCapture = history.findMessage(conversation.id, captureId)?.requirePrompt(agent.id)
        val selected = durableCapture?.snapshot ?: prompts.snapshot(agent.prompts, context)
        val previous = threads.getContextMessagesByThread(conversation.currentThread, nativeProvider)
            .lastOrNull { it.promptSnapshotOrNull()?.agentId == agent.id }
        val previousSnapshot = previous?.requirePrompt(agent.id)
        val unchanged = previousSnapshot?.snapshot?.fingerprint == selected.fingerprint
        if (unchanged) check(previousSnapshot?.snapshot == selected) { "Prompt fingerprint does not match its snapshot" }
        var created: Message? = null
        val messageId = if (unchanged) requireNotNull(previous).id else {
            val id = captureId
            val content = PromptMessage(
                agentId = agent.id,
                agentName = agent.name,
                snapshot = selected,
                action = when {
                    previous == null -> PromptMessage.Action.ATTACH
                    selected.entries.none { it is PromptSnapshot.Entry.Catalog } -> PromptMessage.Action.DETACH
                    else -> PromptMessage.Action.REPLACE
                },
                previousMessageId = previous?.id,
            )
            val existing = history.findMessage(conversation.id, id)
            if (existing != null) {
                check(existing.requirePrompt(agent.id).snapshot == selected) { "Prompt capture ID belongs to another snapshot" }
            } else {
                check(runtime.confirmActiveTaskOwner(conversation.id, task.id, executor)) { "Runtime ownership was lost before capturing prompts" }
                val message = Message(id = id, conversationId = conversation.id,
                    role = Message.Role.PROMPT, author = Message.Author.Runtime(),
                    content = listOf(content), createdAt = Clock.System.now())
                messages.appendRuntimeMessage(conversation.id, message)
                created = message
            }
            id
        }
        check(runtime.bindTurnPromptMessage(conversation.id, task.id, executor, messageId)) {
            "Runtime ownership was lost before binding the prompt snapshot"
        }
        created?.let { emitMessage(it) }
        return prompts.assembleSnapshot(selected, context)
    }

    private fun Message.requirePrompt(agentId: AgentDefinition.Id): PromptMessage {
        check(role == Message.Role.PROMPT) { "Turn prompt reference points to a non-prompt message" }
        val prompt = checkNotNull(promptSnapshotOrNull()) { "Turn prompt message has no snapshot" }
        check(prompt.agentId == agentId) { "Turn prompt snapshot belongs to another agent" }
        return prompt
    }
}
