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
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Serializes admission decisions, never provider execution or persistence. */
class AiAdmissionQueue(
    scope: CoroutineScope,
    initialCatalog: AiCatalog,
    initialLeases: List<AiAdmissionLease> = emptyList(),
    initialRevision: Long = 0,
) {
    private data class Waiting(
        val operation: AiAdmissionOperation,
        val workerId: String?,
        val workerRequestId: String?,
        val result: CompletableDeferred<AiAdmissionLease>,
    )

    private sealed interface Command {
        val reply: CompletableDeferred<*>?
        data class Acquire(val waiting: Waiting) : Command { override val reply get() = waiting.result }
        data class Cancel(val id: String, val recipient: CompletableDeferred<AiAdmissionLease>) : Command { override val reply = null }
        data class Release(val id: String, override val reply: CompletableDeferred<Unit>) : Command
        data class Accept(val id: String, override val reply: CompletableDeferred<Unit>) : Command
        data class Update(val catalog: AiCatalog, val revision: Long, override val reply: CompletableDeferred<Unit>) : Command
        data class FinishWorkerRequest(val workerId: String, val requestId: String,
            override val reply: CompletableDeferred<Unit>) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableSnapshot = MutableStateFlow(AiAdmissionSnapshot(active = initialLeases))
    val snapshot = mutableSnapshot.asStateFlow()

    private val actor = scope.launch {
        var catalog = initialCatalog
        var revision = initialRevision
        val waiting = linkedMapOf<String, Waiting>()
        val active = initialLeases.associateByTo(linkedMapOf()) { it.operation.id }
        val recipients = mutableMapOf<String, CompletableDeferred<AiAdmissionLease>>()
        check(active.size == initialLeases.size) { "Duplicate restored AI admission leases" }

        fun dispatch() {
            val iterator = waiting.iterator()
            while (iterator.hasNext()) {
                val (id, next) = iterator.next()
                if (next.result.isCancelled) { iterator.remove(); continue }
                val group = try {
                    val groupId = when (val target = next.operation.target) {
                        is AiAdmissionTarget.Connection -> requireNotNull(catalog.connections.find { it.id == target.id }) {
                            "AI connection no longer exists: ${target.id.value}"
                        }.concurrencyGroupId
                        AiAdmissionTarget.LocalInference -> AiConcurrencyGroup.DEFAULT_ID
                    }
                    catalog.concurrencyGroups.single { it.id == groupId }
                } catch (error: IllegalArgumentException) {
                    iterator.remove()
                    next.result.completeExceptionally(error)
                    continue
                } catch (error: NoSuchElementException) {
                    iterator.remove()
                    next.result.completeExceptionally(error)
                    continue
                }
                val limit = group.maxConcurrentCalls
                if (limit != null && active.values.count { it.groupId == group.id } >= limit) continue
                val lease = AiAdmissionLease(next.operation, group.id, Clock.System.now(), next.workerId, next.workerRequestId)
                iterator.remove()
                check(active.put(id, lease) == null) { "Duplicate AI admission lease" }
                recipients[id] = next.result
                next.result.complete(lease)
            }
            mutableSnapshot.value = AiAdmissionSnapshot(waiting.values.map { it.operation }, active.values.toList())
        }

        try {
            for (command in commands) {
                when (command) {
                    is Command.Acquire -> {
                        val id = command.waiting.operation.id
                        if (id in active || id in waiting) {
                            command.reply.completeExceptionally(IllegalStateException("Duplicate AI admission operation: $id"))
                        } else if (waiting.size >= 4096) {
                            command.reply.completeExceptionally(IllegalStateException("AI admission queue is full"))
                        } else waiting[id] = command.waiting
                    }
                    is Command.Cancel -> {
                        if (waiting[command.id]?.result === command.recipient) waiting.remove(command.id)?.result?.cancel()
                        if (recipients[command.id] === command.recipient) {
                            recipients.remove(command.id)
                            active.remove(command.id)
                        }
                    }
                    is Command.Accept -> {
                        val lease = active[command.id]
                        if (lease == null) command.reply.completeExceptionally(IllegalStateException("AI admission lease is unavailable"))
                        else { active[command.id] = lease.copy(accepted = true); command.reply.complete(Unit) }
                    }
                    is Command.Release -> {
                        recipients.remove(command.id)
                        active.remove(command.id)
                        command.reply.complete(Unit)
                    }
                    is Command.Update -> {
                        if (command.revision > revision) { catalog = command.catalog; revision = command.revision }
                        command.reply.complete(Unit)
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
                        command.reply.complete(Unit)
                    }
                }
                dispatch()
            }
        } finally {
            val error = IllegalStateException("AI admission coordinator stopped")
            commands.close(error)
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

    suspend fun release(id: String) {
        val reply = CompletableDeferred<Unit>()
        commands.send(Command.Release(id, reply))
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
