package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Serializes admission decisions, never provider execution or persistence. */
class AiAdmissionQueue(
    scope: CoroutineScope,
    initialCatalog: AiCatalog,
    initialLeases: List<AiAdmissionLease> = emptyList(),
    initialRevision: Long = 0,
    initialTpmStates: List<AiTpmState> = emptyList(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class Waiting(
        val operation: AiAdmissionOperation,
        val workerId: String?,
        val workerRequestId: String?,
        val result: CompletableDeferred<AiAdmissionLease>,
    )

    private sealed interface Command {
        val reply: CompletableDeferred<*>?
        data class Wake(val generation: Long) : Command { override val reply = null }
        data class Acquire(val waiting: Waiting) : Command { override val reply get() = waiting.result }
        data class Cancel(val id: String, val recipient: CompletableDeferred<AiAdmissionLease>) : Command { override val reply = null }
        data class Release(val id: String, val feedback: AiTpmFeedback?, val refund: Boolean, override val reply: CompletableDeferred<Unit>) : Command
        data class Accept(val id: String, override val reply: CompletableDeferred<Unit>) : Command
        data class Update(val catalog: AiCatalog, val revision: Long, override val reply: CompletableDeferred<Unit>) : Command
        data class FinishWorkerRequest(val workerId: String, val requestId: String,
            override val reply: CompletableDeferred<Unit>) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableSnapshot = MutableStateFlow(AiAdmissionSnapshot(active = initialLeases))
    val snapshot = mutableSnapshot.asStateFlow()

    private val ledger = AiTpmLedger(initialCatalog.tpmGroups, initialTpmStates, nanoTime)
    init {
        require(initialLeases.map { it.operation.id }.distinct().size == initialLeases.size) { "Duplicate restored AI admission leases" }
        initialLeases.mapNotNull { it.operation.tpm }.forEach { reservation ->
            val state = requireNotNull(initialTpmStates.find { it.groupId == reservation.groupId }) { "Restored TPM lease has no durable balance" }
            require(state.cumulativeAdmitted >= Math.addExact(reservation.cumulativeBefore, reservation.estimatedUnits)) {
                "Restored TPM lease exceeds its durable admission sequence"
            }
        }
    }

    private val actor = scope.launch {
        var catalog = initialCatalog
        var revision = initialRevision
        val waiting = linkedMapOf<String, Waiting>()
        val active = initialLeases.associateByTo(linkedMapOf()) { it.operation.id }
        var wakeup: Job? = null
        var wakeupGeneration = 0L
        var processingReply: CompletableDeferred<*>? = null
        val completions = linkedMapOf<CompletableDeferred<*>, () -> Unit>()
        val recipients = mutableMapOf<String, CompletableDeferred<AiAdmissionLease>>()
        check(active.size == initialLeases.size) { "Duplicate restored AI admission leases" }

        fun dispatch() {
            val blockedTpm = mutableSetOf<AiTpmGroup.Id>()
            val iterator = waiting.iterator()
            while (iterator.hasNext()) {
                val (id, next) = iterator.next()
                if (next.result.isCancelled) { iterator.remove(); continue }
                val group = try {
                    val groupId = when (val target = next.operation.target) {
                        is AiAdmissionTarget.ModelConfiguration -> {
                            val configuration = requireNotNull(catalog.modelConfigurations.find { it.id == target.id }) { "AI model configuration no longer exists" }
                            require(configuration.connectionId == target.connectionId) { "AI admission connection does not match model configuration" }
                            configuration.concurrencyGroupId
                        }
                        is AiAdmissionTarget.Connection -> {
                            require(catalog.connections.any { it.id == target.id }) { "AI auxiliary connection no longer exists" }
                            AiConcurrencyGroup.DEFAULT_ID
                        }
                        AiAdmissionTarget.LocalInference -> AiConcurrencyGroup.DEFAULT_ID
                    }
                    catalog.concurrencyGroups.single { it.id == groupId }
                } catch (error: IllegalArgumentException) {
                    iterator.remove()
                    completions[next.result] = { next.result.completeExceptionally(error) }
                    continue
                } catch (error: NoSuchElementException) {
                    iterator.remove()
                    completions[next.result] = { next.result.completeExceptionally(error) }
                    continue
                }
                val tpm = next.operation.tpm
                if (tpm != null) {
                    try {
                        require(catalog.tpmGroups.any { it.id == tpm.groupId }) { "TPM group no longer exists" }
                        ledger.requireFits(tpm)
                    } catch (error: IllegalArgumentException) {
                        iterator.remove(); completions[next.result] = { next.result.completeExceptionally(error) }; continue
                    }
                    if (!blockedTpm.add(tpm.groupId)) continue
                    if (!ledger.eligible(tpm, active.values.toList())) continue
                }
                val limit = group.maxConcurrentCalls
                if (limit != null && active.values.count { it.groupId == group.id } >= limit) continue
                val prepared = if (tpm == null) next.operation else next.operation.copy(tpm = ledger.reserve(tpm, active.values.toList()))
                val lease = AiAdmissionLease(prepared, group.id, Clock.System.now(), next.workerId, next.workerRequestId)
                tpm?.let { blockedTpm.remove(it.groupId) }
                iterator.remove()
                check(active.put(id, lease) == null) { "Duplicate AI admission lease" }
                recipients[id] = next.result
                completions[next.result] = { next.result.complete(lease) }
            }
            mutableSnapshot.value = AiAdmissionSnapshot(waiting.values.map { it.operation }, active.values.toList(), ledger.snapshot())
            if (waiting.values.none { it.operation.tpm != null }) { wakeup?.cancel(); wakeup = null; wakeupGeneration++ }
            else if (wakeup == null) {
                val generation = ++wakeupGeneration
                wakeup = scope.launch {
                    delay(10_000)
                    commands.trySend(Command.Wake(generation))
                }
            }
        }

        try {
            for (command in commands) {
                processingReply = command.reply
                when (command) {
                    is Command.Wake -> if (command.generation == wakeupGeneration) wakeup = null
                    is Command.Acquire -> {
                        val id = command.waiting.operation.id
                        if (id in active || id in waiting) {
                            completions[command.reply] = { command.reply.completeExceptionally(IllegalStateException("Duplicate AI admission operation: $id")) }
                        } else if (waiting.size >= 4096) {
                            completions[command.reply] = { command.reply.completeExceptionally(IllegalStateException("AI admission queue is full")) }
                        } else waiting[id] = command.waiting
                    }
                    is Command.Cancel -> {
                        if (waiting[command.id]?.result === command.recipient) waiting.remove(command.id)?.result?.cancel()
                        if (recipients[command.id] === command.recipient) {
                            recipients.remove(command.id)
                            active.remove(command.id)?.operation?.tpm?.let(ledger::refundUndelivered)
                        }
                    }
                    is Command.Accept -> {
                        val lease = active[command.id]
                        if (lease == null) completions[command.reply] = { command.reply.completeExceptionally(IllegalStateException("AI admission lease is unavailable")) }
                        else { active[command.id] = lease.copy(accepted = true); completions[command.reply] = { command.reply.complete(Unit) } }
                    }
                    is Command.Release -> {
                        recipients.remove(command.id)
                        active.remove(command.id)?.operation?.tpm?.let { reservation ->
                            if (command.refund) ledger.refundUndelivered(reservation)
                            else command.feedback?.let { ledger.observe(reservation, it) }
                        }
                        completions[command.reply] = { command.reply.complete(Unit) }
                    }
                    is Command.Update -> {
                        if (command.revision > revision) { catalog = command.catalog; revision = command.revision; ledger.update(catalog.tpmGroups, active.values.mapNotNull { it.operation.tpm?.groupId }.toSet()) }
                        completions[command.reply] = { command.reply.complete(Unit) }
                    }
                    is Command.FinishWorkerRequest -> {
                        active.values.filter { it.workerId == command.workerId && it.workerRequestId == command.requestId &&
                            (it.operation.backgroundSessionId == null || !it.accepted) }
                            .map { it.operation.id }.forEach { active.remove(it); recipients.remove(it) }
                        val interrupted = waiting.values.filter { it.workerId == command.workerId && it.workerRequestId == command.requestId }
                        interrupted.forEach {
                            waiting.remove(it.operation.id)
                            it.result.completeExceptionally(CancellationException("Owning Worker request has finished"))
                        }
                        completions[command.reply] = { command.reply.complete(Unit) }
                    }
                }
                dispatch()
                val published = completions.values.toList()
                completions.clear()
                published.forEach { it() }
                processingReply = null
            }
        } finally {
            wakeup?.cancel()
            val error = IllegalStateException("AI admission coordinator stopped")
            commands.close(error)
            processingReply?.completeExceptionally(error)
            completions.keys.forEach { it.completeExceptionally(error) }
            recipients.values.forEach { it.completeExceptionally(error) }
            waiting.values.forEach { it.result.completeExceptionally(error) }
            while (true) (commands.tryReceive().getOrNull() ?: break).reply?.completeExceptionally(error)
        }
    }

    suspend fun acquire(operation: AiAdmissionOperation, workerId: String? = null, workerRequestId: String? = null): AiAdmissionLease {
        require((workerId == null) == (workerRequestId == null)) { "Incomplete AI admission owner" }
        val result = CompletableDeferred<AiAdmissionLease>()
        try {
            commands.send(Command.Acquire(Waiting(operation, workerId, workerRequestId, result)))
            return result.await()
        } catch (error: CancellationException) {
            result.cancel()
            withContext(NonCancellable) { commands.trySend(Command.Cancel(operation.id, result)) }
            throw error
        }
    }

    suspend fun accept(id: String) {
        val reply = CompletableDeferred<Unit>()
        commands.send(Command.Accept(id, reply))
        reply.await()
    }

    suspend fun release(id: String, feedback: AiTpmFeedback? = null, refund: Boolean = false) {
        val reply = CompletableDeferred<Unit>()
        commands.send(Command.Release(id, feedback, refund, reply))
        reply.await()
    }

    suspend fun updateCatalog(catalog: AiCatalog, revision: Long) {
        require(revision >= 0) { "Invalid AI catalog revision" }
        val reply = CompletableDeferred<Unit>()
        commands.send(Command.Update(catalog, revision, reply))
        reply.await()
    }

    suspend fun finishWorkerRequest(workerId: String, requestId: String) {
        val reply = CompletableDeferred<Unit>()
        commands.send(Command.FinishWorkerRequest(workerId, requestId, reply))
        reply.await()
    }

    suspend fun close() {
        commands.close()
        actor.join()
    }
}
