package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationPin
import com.gromozeka.domain.model.ConversationPinReadFailedException
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.repository.ConversationPinRepository
import com.gromozeka.domain.service.AiRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.springframework.stereotype.Service

/** A fresh read per application request, explicitly owned by a Conversation, not usage attribution. */
@Service
class ConversationPinnedPrompt(private val repository: ConversationPinRepository) {
    suspend fun append(conversationId: Conversation.Id, systemPrompts: List<String>): List<String> {
        val pins = try { repository.list(conversationId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw ConversationPinReadFailedException() }
        return appendConversationPins(systemPrompts, pins)
    }

    suspend fun append(conversationId: Conversation.Id, request: AiRuntimeRequest): AiRuntimeRequest {
        val prompts = append(conversationId, request.systemPrompts)
        return if (prompts === request.systemPrompts) request else request.copy(systemPrompts = prompts)
    }

    /** Only memory builders use this adapter. A synthetic execution id never selects a pin catalog.
     * Captures owner identity, NOT the catalog; retries/repair stages read afresh at their actual call.
     * Standalone/workspace operations deliberately keep the original runtime untouched.
     */
    internal fun bindMemoryRuntime(context: RuntimeEnvironmentContext, runtime: AiRuntime): AiRuntime {
        val owner = (context as? RuntimeEnvironmentContext.ProjectBound)?.conversation?.id ?: return runtime
        return object : AiRuntime by runtime {
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse = runtime.call(append(owner, request))
            override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = flow {
                emitAll(runtime.stream(append(owner, request)))
            }
        }
    }
}

internal fun appendConversationPins(systemPrompts: List<String>, pins: List<ConversationPin>): List<String> {
    if (pins.isEmpty()) return systemPrompts
    val block = pins.sortedBy { it.ordinal }.joinToString(separator = "\n\n---\n\n", prefix = "Pinned conversation context:\n\n") { it.text }
    return systemPrompts + block
}
