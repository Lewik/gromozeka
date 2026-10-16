package com.gromozeka.server

import com.gromozeka.domain.tool.AiToolContextField.*
import com.gromozeka.domain.tool.AiToolContextRequirements

import com.gromozeka.application.service.ConversationAgentAccessChangedException
import com.gromozeka.application.service.ConversationAgentAccessService
import com.gromozeka.application.service.ConversationPinApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.ProjectAccessDeniedException
import com.gromozeka.domain.tool.*
import com.gromozeka.server.ConversationPinToolSupport.Operation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service

/** Conversation-owned text only: no history/source lookups, collaboration delivery or Runtime control. */
@Service
class ConversationPinToolContributor(
    private val pins: ConversationPinApplicationService,
    private val access: ConversationAgentAccessService,
) : AiToolCallbackContributor {
    override val callbacks: List<AiToolCallback> = Operation.entries.map { operation ->
        object : AiToolCallback {
            override val definition = AiToolDefinition(operation.toolName, description(operation), ConversationPinToolSupport.schema(operation))
            override val metadata = AiToolMetadata(
                contextRequirements = AiToolContextRequirements(setOf(USER, CONVERSATION, THREAD, AGENT) +
                    if (operation == Operation.CREATE) setOf(TOOL_CALL_ID) else emptySet()),
                executionScope = AiToolExecutionScope.SERVER,
                loadingPolicy = AiToolLoadingPolicy.ON_DEMAND, logInput = false, visibleToMemoryPipeline = false)
            override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
                context?.cancellationSignal?.throwIfCancellationRequested()
                val args = ConversationPinToolSupport.parse(operation, toolInput)
                val (source, actor) = eligible { access.source(context) }
                val requested = args.conversationId?.let(Conversation::Id) ?: source.conversationId
                ConversationPinToolSupport.requireCursorTarget(args.cursor, requested)
                val pinId = args.pinId?.let(ConversationPin::Id)
                val key = if (operation == Operation.CREATE) ConversationPinToolSupport.creationKey(source, actor, context?.getString("toolCallId")) else null
                // No permit is cached: the complete target-agent set (also for self) is checked
                // again on each operation, immediately before the ordinary authorized CRUD call.
                val target = eligible { access.resolveEligibleConversation(source, actor, requested.value) }
                if (target.id != requested) fail("PIN_TARGET_CHANGED")
                context?.cancellationSignal?.throwIfCancellationRequested()
                core { when (operation) {
                    Operation.LIST -> ConversationPinToolSupport.page(target.id,
                        pins.list(actor, target.id), args)
                    Operation.GET -> {
                        val pin = pins.get(actor, target.id, requireNotNull(pinId))
                            ?: fail("PIN_NOT_FOUND")
                        if (pin.conversationId != target.id || pin.id != pinId) fail("PIN_NOT_FOUND")
                        JsonObject(ConversationPinToolSupport.metadata(pin) + ("text" to JsonPrimitive(pin.text)))
                    }
                    Operation.CREATE -> {
                        val pin = pins.create(actor, target.id, requireNotNull(args.text), source = null, creationKey = key)
                        if (pin.conversationId != target.id) fail("PIN_OPERATION_FAILED")
                        ConversationPinToolSupport.metadata(pin)
                    }
                    Operation.UPDATE -> {
                        val pin = pins.update(actor, target.id, requireNotNull(pinId), requireNotNull(args.revision), requireNotNull(args.text))
                        if (pin.conversationId != target.id || pin.id != pinId) fail("PIN_OPERATION_FAILED")
                        ConversationPinToolSupport.metadata(pin)
                    }
                    Operation.DELETE -> {
                        pins.delete(actor, target.id, requireNotNull(pinId), requireNotNull(args.revision))
                        buildJsonObject { put("conversation_id", target.id.value); put("pin_id", requireNotNull(pinId).value); put("deleted", true) }
                    }
                }.toString() }
            }
        }
    }

    private suspend fun <T> eligible(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: ConversationAgentAccessChangedException) { fail("PIN_TARGET_CHANGED") }
    catch (_: Exception) { fail("TARGET_UNAVAILABLE") }

    private suspend fun <T> core(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (known: PinToolFailure) { throw known }
    catch (_: ProjectAccessDeniedException) { fail("TARGET_UNAVAILABLE") }
    catch (_: ConversationPinNotFoundException) { fail("PIN_NOT_FOUND") }
    catch (_: ConversationPinConflictException) { fail("PIN_CONFLICT") }
    catch (_: ConversationPinLimitException) { fail("PIN_LIMIT_EXCEEDED") }
    catch (_: Exception) { fail("PIN_OPERATION_FAILED") }

    // Fresh and content-free: no backend exception, cause, suppressed data or argument values escape.
    private class PinToolFailure(code: String) : IllegalStateException("Conversation pin request failed ($code).")
    private fun fail(code: String): Nothing = throw PinToolFailure(code)

    private fun description(operation: Operation): String {
        val common = " Omit conversation_id for the current Conversation; explicit null is invalid. Access requires the authenticated actor and matching tool policies for every connected target agent, including self. Pins belong to the Conversation, not a selected agent or history branch. Editing pins never starts, wakes, resumes or interrupts a target; the latest text is used by its next conversation-bound model request. Pin text does not grant permissions. Errors are not retried automatically."
        return when (operation) {
            Operation.LIST -> "List pin metadata and previews of at most 256 UTF-16 units, not full text. Stable ordinal/id order with best-effort cursor pagination (default 50, maximum 100); not an atomic snapshot. Use get for full text."
            Operation.GET -> "Read one Conversation pin's full literal text and current revision. A pin from another Conversation is not readable through this target."
            Operation.CREATE -> "Create an independent literal text pin with a generated ID. No source message is required or read. Requires the Runtime's trusted tool-call identity, never a model-supplied key. Replay of that same call is deduplicated; a different payload conflicts, and deletion is not undone by replay."
            Operation.UPDATE -> "Replace a pin's text using the last observed positive expected_revision. Stale revision conflicts, including with the same text. Read before deciding how to resolve a conflict; no automatic retry. A current-revision same-text update is a no-op."
            Operation.DELETE -> "Delete one pin only at the last observed positive expected_revision. Stale revision conflicts. This does not delete or modify an original message, history, or another copy of the pin."
        } + common
    }
}
