package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.visual.*
import com.gromozeka.shared.uuid.uuid7
import com.gromozeka.statesync.StateSyncSnapshot
import com.gromozeka.statesync.StateSyncSource
import com.gromozeka.statesync.StateSyncSubscription
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "false", matchIfMissing = true)
class VisualApplicationService(
    private val repository: VisualRepository,
    private val conversations: ConversationDomainService,
    private val access: ProjectAccessService,
    private val commands: VisualCommandRuntime,
    private val delivery: VisualInteractionDelivery,
    @param:Qualifier("applicationScope") private val scope: CoroutineScope,
    private val highlights: VisualHighlightDelivery = VisualHighlightDelivery { _, _ -> 0 },
) {
    private val locks = Array(128) { Mutex() }
    private val compiled = ConcurrentHashMap<String, Pair<Long, VisualDocument>>()
    private val source = StateSyncSource(scope = scope, sourceEpoch = uuid7(), loader = repository::list)
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val log = klog.KLoggers.logger(this)

    suspend fun subscribe(id: Conversation.Id): StateSyncSubscription<Conversation.Id, List<Visual>> = source.subscribe(id)
    suspend fun snapshot(id: Conversation.Id): StateSyncSnapshot<Conversation.Id, List<Visual>> = source.snapshot(id)

    suspend fun list(actor: User, conversationId: Conversation.Id): List<Visual> {
        authorize(actor, conversationId, ProjectPermission.READ)
        return repository.list(conversationId)
    }

    suspend fun create(actor: User, conversationId: Conversation.Id, request: VisualCreate, agentId: AgentDefinition.Id? = null): Visual {
        val conversation = authorize(actor, conversationId)
        val document = VisualDocumentCompiler.compile(request.document)
        VisualDocumentCompiler.validateState(document, request.state)
        val handler = request.handler?.let { commands.prepare(actor, conversation, it) }
        val now = Clock.System.now()
        val visual = Visual(
            id = uuid7(), conversationId = conversationId, createdBy = actor.id,
            agentDefinitionId = agentId ?: conversation.autoRespondAgentIds.singleOrNull()
                ?: conversation.participants.filterIsInstance<Conversation.Participant.Agent>().singleOrNull()?.agentDefinitionId,
            document = request.document, title = document.title, state = request.state,
            handler = handler, status = if (handler == null) VisualStatus.ACTIVE else VisualStatus.STARTING,
            createdAt = now, updatedAt = now,
        )
        mutex(conversationId.value).withLock {
            require(repository.list(conversationId).size < VisualLimits.PER_CONVERSATION) { "A conversation may have at most ${VisualLimits.PER_CONVERSATION} visuals" }
            repository.save(visual)
            compiled[visual.id] = visual.documentRevision to document
        }
        source.invalidate(conversationId)
        if (handler != null) startHandler(actor, visual)
        return repository.find(conversationId, visual.id) ?: error("Visual was closed while its handler was starting")
    }

    suspend fun update(actor: User, conversationId: Conversation.Id, id: String, request: VisualUpdate): Visual {
        val conversation = authorize(actor, conversationId)
        require(request.document != null || request.state != null || request.updateHandler) { "No visual update was supplied" }
        require(request.updateHandler || request.handler == null) { "Changing handler requires updateHandler" }
        val nextDocument = request.document?.let(VisualDocumentCompiler::compile)
        val prepared = if (request.updateHandler) request.handler?.let { commands.prepare(actor, conversation, it) } else null
        var previous: Visual? = null
        val updated = mutex(id).withLock {
            val old = requireNotNull(repository.find(conversationId, id)) { "Visual not found in this conversation" }
            previous = old
            val state = request.state?.let { replaceVisualStateSections(old.state, it) } ?: old.state
            val document = nextDocument ?: document(old)
            VisualDocumentCompiler.validateState(document, state)
            val next = old.copy(
                document = request.document ?: old.document,
                title = document.title,
                state = state,
                revision = old.revision + 1,
                documentRevision = old.documentRevision + if (request.document != null) 1 else 0,
                formRevision = old.formRevision + if (request.state?.containsKey("form") == true) 1 else 0,
                formEventId = if (request.state?.containsKey("form") == true) null else old.formEventId,
                handler = if (request.updateHandler) prepared else old.handler,
                status = if (request.updateHandler) { if (prepared == null) VisualStatus.ACTIVE else VisualStatus.STARTING } else old.status,
                diagnostics = if (request.document != null || request.updateHandler) emptyList() else old.diagnostics,
                updatedAt = Clock.System.now(),
            )
            repository.save(next)
            compiled[id] = next.documentRevision to document
            next
        }
        source.invalidate(conversationId)
        if (request.updateHandler) {
            previous?.takeIf { it.handler != null }?.let { cancelQuietly(it) }
            if (updated.handler != null) startHandler(actor, updated)
        }
        return repository.find(conversationId, id) ?: error("Visual was closed during update")
    }

    /** Highlight is an imperative client command, not a mutation of accepted Visual state. */
    suspend fun highlight(actor: User, conversationId: Conversation.Id, id: String, elementIds: List<String>): Int {
        authorize(actor, conversationId)
        val command = mutex(id).withLock {
            val visual = requireNotNull(repository.find(conversationId, id)) { "Visual not found in this conversation" }
            val command = VisualHighlightCommand(uuid7(), conversationId, id, visual.documentRevision, elementIds)
            if (elementIds.isNotEmpty()) {
                val renderedIds = mutableSetOf<String>()
                fun collect(node: VisualRenderNode) {
                    node.string("id")?.let(renderedIds::add)
                    node.children.forEach(::collect)
                }
                VisualDocumentCompiler.render(document(visual), visual.state).forEach(::collect)
                require(elementIds.all { it in renderedIds }) {
                    "Highlight targets are not currently rendered: ${(elementIds - renderedIds).joinToString()}"
                }
            }
            command
        }
        // No revision increment, repository write, form submission, or model reminder.
        return highlights.send(actor.id, command)
    }

    suspend fun close(actor: User, conversationId: Conversation.Id, id: String) {
        authorize(actor, conversationId)
        val old = mutex(id).withLock {
            val visual = repository.find(conversationId, id) ?: return@withLock null
            repository.delete(conversationId, id)
            compiled.remove(id)
            visual
        }
        source.invalidate(conversationId)
        old?.let { cancelQuietly(it) }
    }

    suspend fun act(actor: User, conversationId: Conversation.Id, action: VisualAction): VisualActionResult {
        authorize(actor, conversationId)
        require(Regex("[A-Za-z0-9_-]{8,128}").matches(action.eventId)) { "Invalid visual event id" }
        require(action.buttonId.isNotBlank() && action.buttonId.length <= 128) { "Invalid button id" }
        validateVisualStateShape(action.state)
        val input = buildJsonObject {
            put("visual-id", action.visualId); put("event-id", action.eventId)
            put("button-id", action.buttonId); put("state", action.state)
        }
        val fingerprint = hash(input.toString())
        var repeated: VisualActionResult? = null
        lateinit var interaction: Conversation.Message.ContentItem.VisualInteraction
        val visual = mutex(action.visualId).withLock {
            val old = requireNotNull(repository.find(conversationId, action.visualId)) { "Visual not found in this conversation" }
            repository.findAction(action.eventId)?.let { receipt ->
                require(receipt.visualId == old.id && receipt.actorUserId == actor.id && receipt.payloadHash == fingerprint) { "Event id was already used for another action" }
                repeated = receipt.result ?: VisualActionResult(action.eventId, false, "This event was already submitted; delivery is pending or uncertain. It will not be sent again.")
                return@withLock old
            }
            check(old.status == VisualStatus.ACTIVE) { "Visual handler is not active" }
            require(action.documentRevision == old.documentRevision) { "Visual document changed; refresh before submitting" }
            val document = document(old)
            VisualDocumentCompiler.validateState(document, action.state)
            // The incoming data is context for the handler, never an instruction to roll server data back.
            val state = replaceVisualStateSections(old.state, buildJsonObject { put("form", action.state.getValue("form")) })
            VisualDocumentCompiler.validateState(document, state)
            val button = VisualDocumentCompiler.buttons(VisualDocumentCompiler.render(document, state))
                .singleOrNull { it.string("id") == action.buttonId } ?: error("Button is no longer visible")
            require(!button.bool("disabled")) { "Button is disabled" }
            val clickedButton = VisualDocumentCompiler.buttons(VisualDocumentCompiler.render(document, action.state))
                .singleOrNull { it.string("id") == action.buttonId } ?: error("Button is absent from the submitted snapshot")
            fun buttonText(node: VisualRenderNode): String =
                if (node.tag == "br") " " else node.text.orEmpty() + node.children.joinToString("") { buttonText(it) }
            interaction = Conversation.Message.ContentItem.VisualInteraction(
                visualId = old.id, visualTitle = old.title, documentRevision = old.documentRevision,
                eventId = action.eventId, buttonId = action.buttonId,
                buttonLabel = buttonText(clickedButton).replace(Regex("\\s+"), " ").trim().ifBlank { action.buttonId },
                snapshot = action.state,
            )
            val next = old.copy(state = state, revision = old.revision + 1, formRevision = old.formRevision + 1,
                formEventId = action.eventId,
                updatedAt = Clock.System.now())
            val reserved = repository.acceptAction(next, VisualActionReceipt(action.eventId, old.id, conversationId, actor.id, fingerprint, Clock.System.now()))
            check(reserved) { "Event was concurrently submitted; do not resend it" }
            next
        }
        repeated?.let { return it }
        source.invalidate(conversationId)
        // Pipe I/O must not hold the visual mutex: close/update must be able to cancel a blocked handler.
        val result = try {
            if (visual.handler != null) commands.sendInput(actor, visual, input.toString() + "\n")
            else check(delivery.send(actor, visual, interaction)) { "Conversation did not accept the visual action" }
            VisualActionResult(action.eventId, true)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { repository.finishAction(action.eventId, VisualActionResult(action.eventId, false, "Delivery was interrupted and may have partial effects. Not retried.")) }
            throw cancelled
        } catch (error: Exception) {
            val message = safeMessage(error)
            diagnostic(conversationId, visual.id, "action-delivery", message, visual.handler?.generation)
            VisualActionResult(action.eventId, false, message)
        }
        repository.finishAction(action.eventId, result)
        return result
    }

    /** Called only by the authenticated Worker gateway after verifying its exact session. */
    suspend fun acceptOutput(
        worker: ConversationRuntimeWorkerIdentity,
        id: String,
        conversationId: Conversation.Id,
        generation: String,
        task: CommandTask,
        records: List<VisualOutputRecord>,
        finished: Boolean,
    ): Boolean {
        require(records.size <= 256) { "Visual output batch is too large" }
        require(records.sumOf { it.json?.encodeToByteArray()?.size ?: 0 } <= 65_536) { "Visual output batch exceeds 64 KiB" }
        var changed = false
        val active = mutex(id).withLock {
            var visual = repository.find(conversationId, id) ?: return@withLock false
            val handler = visual.handler ?: return@withLock false
            if (handler.generation != generation || visual.status == VisualStatus.STOPPED) return@withLock false
            require(handler.worker == worker && task.workerId == worker.workerId && task.visualId == id && task.conversationId == conversationId && task.workspaceMountId == handler.spec.workspaceMountId) { "Visual output owner does not match" }
            require(handler.taskId == null || handler.taskId == task.id) { "Visual output task does not match" }
            val original = visual
            var cursor = handler.outputCursor
            var previousByte = -1L
            for (record in records) {
                require(record.endByte > previousByte && record.endByte <= task.outputBytes) { "Invalid visual output cursor" }
                previousByte = record.endByte
                if (record.endByte <= cursor) continue // Idempotent retry after a lost gateway acknowledgement.
                require((record.json == null) != (record.error == null)) { "Output record must have JSON or a framing error" }
                try {
                    record.error?.let { error(it.take(300)) }
                    val text = requireNotNull(record.json)
                    require(text.encodeToByteArray().size <= VisualLimits.OUTPUT_LINE_BYTES) { "Output record exceeds ${VisualLimits.OUTPUT_LINE_BYTES} bytes" }
                    val sections = json.parseToJsonElement(text) as? JsonObject ?: error("Output record must be a JSON object")
                    val state = replaceVisualStateSections(visual.state, sections)
                    VisualDocumentCompiler.validateState(document(visual), state)
                    visual = visual.copy(state = state,
                        formRevision = visual.formRevision + if ("form" in sections) 1 else 0,
                        formEventId = if ("form" in sections) null else visual.formEventId)
                } catch (error: Exception) {
                    visual = withDiagnostic(visual, "handler-output", "Output at byte ${record.endByte}: ${safeMessage(error)}")
                }
                cursor = record.endByte
            }
            visual = visual.copy(handler = handler.copy(taskId = task.id, outputCursor = cursor),
                status = if (finished) VisualStatus.STOPPED else VisualStatus.ACTIVE)
            if (finished) visual = withDiagnostic(visual, "handler-stopped", "Handler stopped${task.exitCode?.let { " (exit $it)" }.orEmpty()}. Close the visual or explicitly replace its handler to start again.")
            if (visual != original) {
                visual = visual.copy(revision = original.revision + 1, updatedAt = Clock.System.now())
                repository.save(visual)
                changed = true
            }
            !finished
        }
        if (changed) source.invalidate(conversationId)
        return active
    }

    private suspend fun startHandler(actor: User, requested: Visual) {
        try {
            val task = commands.start(actor, requested)
            var abandoned = false
            mutex(requested.id).withLock {
                val current = repository.find(requested.conversationId, requested.id)
                if (current == null || current.handler?.generation != requested.handler?.generation || current.status == VisualStatus.STOPPED) {
                    abandoned = true
                } else {
                    val currentHandler = requireNotNull(current.handler)
                    check(currentHandler.taskId == null || currentHandler.taskId == task.id) { "Handler startup returned another task" }
                    repository.save(current.copy(handler = currentHandler.copy(taskId = task.id),
                        revision = current.revision + 1, status = VisualStatus.ACTIVE, updatedAt = Clock.System.now()))
                }
            }
            if (abandoned) cancelQuietly(requested.copy(handler = requested.handler?.copy(taskId = task.id)))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                diagnostic(requested.conversationId, requested.id, "handler-start", "Handler startup was interrupted; not retried.", requested.handler?.generation, stop = true)
                cancelQuietly(requested)
            }
            throw cancelled
        } catch (error: Exception) {
            diagnostic(requested.conversationId, requested.id, "handler-start", safeMessage(error), requested.handler?.generation, stop = true)
            cancelQuietly(requested)
        }
        source.invalidate(requested.conversationId)
    }

    private suspend fun diagnostic(conversationId: Conversation.Id, id: String, code: String, message: String, generation: String? = null, stop: Boolean = false) {
        mutex(id).withLock {
            val old = repository.find(conversationId, id) ?: return@withLock
            if (generation != null && old.handler?.generation != generation) return@withLock
            val updated = withDiagnostic(old, code, message).copy(revision = old.revision + 1,
                status = if (stop) VisualStatus.STOPPED else old.status, updatedAt = Clock.System.now())
            repository.save(updated)
        }
        source.invalidate(conversationId)
    }

    private fun withDiagnostic(visual: Visual, code: String, message: String): Visual {
        val clean = message.take(600)
        val previous = visual.diagnostics.lastOrNull()
        val entry = if (previous?.code == code && previous.message == clean) previous.copy(at = Clock.System.now(), occurrences = (previous.occurrences + 1).coerceAtMost(1_000_000))
            else VisualDiagnostic(code, clean, Clock.System.now())
        val retained = if (previous?.code == code && previous.message == clean) visual.diagnostics.dropLast(1) else visual.diagnostics
        return visual.copy(diagnostics = (retained + entry).takeLast(VisualLimits.DIAGNOSTICS))
    }

    private fun document(visual: Visual): VisualDocument {
        val old = compiled[visual.id]
        if (old?.first == visual.documentRevision) return old.second
        return VisualDocumentCompiler.compile(visual.document).also { compiled[visual.id] = visual.documentRevision to it }
    }

    private suspend fun cancelQuietly(visual: Visual) {
        if (visual.handler == null) return
        try { commands.cancel(visual) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { log.warn(error) { "Could not cancel visual handler ${visual.id}; managed Worker lifetime still applies" } }
    }

    private suspend fun authorize(actor: User, conversationId: Conversation.Id, permission: ProjectPermission = ProjectPermission.WRITE): Conversation {
        val conversation = conversations.findById(conversationId) ?: throw ProjectAccessDeniedException()
        access.requirePermission(actor.id, conversation.projectId, permission)
        if (Conversation.Participant.User(actor.id) !in conversation.participants) throw ProjectAccessDeniedException()
        return conversation
    }

    @EventListener(ApplicationReadyEvent::class)
    fun watchHandlerLifetime() {
        scope.launch(CoroutineName("visual-handler-lifecycle")) {
            while (isActive) {
                delay(3_000)
                try {
                    repository.withHandlers().forEach { visual ->
                        commands.failure(visual)?.let { reason ->
                            diagnostic(visual.conversationId, visual.id, "handler-unavailable", reason, visual.handler?.generation, stop = true)
                            cancelQuietly(visual)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { log.warn(error) { "Visual handler lifecycle check failed" } }
            }
        }
    }

    private fun mutex(id: String) = locks[(id.hashCode().toLong() and 0x7fffffff).toInt() % locks.size]
    private fun safeMessage(error: Throwable) = (error.message ?: error::class.simpleName ?: "Visual operation failed").take(500)
    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
