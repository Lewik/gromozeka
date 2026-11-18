package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiCallException
import com.gromozeka.domain.model.ai.AiCallFailure
import com.gromozeka.domain.service.*
import kotlinx.coroutines.*
import klog.KLoggers
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

/** Runs inference only; applying a checkpoint remains serialized by the Conversation Runtime. */
@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.server.enabled"], havingValue = "true", matchIfMissing = true)
class ProactiveCompactionJobs(
    private val coordinator: ConversationRuntimeCoordinator,
    private val stateSync: ConversationRuntimeStateSyncService,
    private val descriptor: ConversationRuntimeExecutorDescriptor,
    @Qualifier("applicationScope") private val scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private data class Running(val id: String, val job: Job)
    private val running = ConcurrentHashMap<Conversation.Id, Running>()

    suspend fun start(preparation: ProactiveCompaction, generate: suspend () -> Conversation.Message): Boolean {
        require(preparation.executor == descriptor.identity) { "Preparation belongs to another Server session" }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val started = System.nanoTime()
            try {
                currentCoroutineContext().ensureActive()
                if (coordinator.proactiveCompaction(preparation.conversationId) != preparation) return@launch
                log.info { "COMPACTION phase=start id=${preparation.id} mode=proactive inputTokens=${preparation.inputTokens} sources=${preparation.sourceCount} retained=${preparation.retainedCount}" }
                val checkpoint = generate()
                currentCoroutineContext().ensureActive()
                val ready = preparation.copy(status = ProactiveCompaction.Status.READY, checkpoint = checkpoint, updatedAt = Clock.System.now())
                val accepted = coordinator.updateProactiveCompaction(preparation, ready)
                log.info { "COMPACTION phase=${if (accepted) "ready" else "discarded"} id=${preparation.id} elapsedMs=${(System.nanoTime() - started) / 1_000_000}" }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    coordinator.updateProactiveCompaction(preparation, preparation.cancel(Clock.System.now()))
                }
                log.info { "COMPACTION phase=cancelled id=${preparation.id}" }
                throw cancelled
            } catch (_: CompactionScopeChangedException) {
                coordinator.updateProactiveCompaction(preparation, preparation.copy(status = ProactiveCompaction.Status.DISCARDED, updatedAt = Clock.System.now()))
                log.info { "COMPACTION phase=discarded id=${preparation.id} reason=scope_changed" }
            } catch (error: Exception) {
                val unknown = (error as? AiCallException)?.failure?.outcome == AiCallFailure.Outcome.UNKNOWN
                coordinator.updateProactiveCompaction(preparation, preparation.copy(
                    status = ProactiveCompaction.Status.FAILED, failureCode = (error as? AiCallException)?.failure?.kind?.name ?: error::class.simpleName ?: "CompactionFailure",
                    outcomeUnknown = unknown, updatedAt = Clock.System.now()))
                // Backend exception bodies may contain prompts, responses or credentials.
                log.warn { "COMPACTION phase=failed id=${preparation.id} type=${error::class.simpleName} outcomeUnknown=$unknown workerRequest=${(error as? AiCallException)?.failure?.workerRequestId} elapsedMs=${(System.nanoTime() - started) / 1_000_000}" }
            } finally {
                running.computeIfPresent(preparation.conversationId) { _, current -> current.takeUnless { it.id == preparation.id } }
                withContext(NonCancellable) { stateSync.invalidate(preparation.conversationId) }
            }
        }
        val entry = Running(preparation.id, job)
        if (running.putIfAbsent(preparation.conversationId, entry) != null) { job.cancel(); return false }
        try {
            if (!coordinator.beginProactiveCompaction(preparation)) { job.cancel(); return false }
            stateSync.invalidate(preparation.conversationId)
            job.start()
            return true
        } finally {
            if (!job.isActive && !job.isCompleted) { running.remove(preparation.conversationId, entry); job.cancel() }
            if (job.isCancelled) running.remove(preparation.conversationId, entry)
        }
    }

    suspend fun await(preparation: ProactiveCompaction) {
        val active = running[preparation.conversationId]?.takeIf { it.id == preparation.id }
        if (active != null) active.job.join()
        else if (coordinator.proactiveCompaction(preparation.conversationId) == preparation) {
            // Do not regenerate a lost request: the provider may already have executed it.
            failInterrupted(preparation, "PreparationUnavailable")
        }
    }

    /** The coordinator marks cancellation first, so a late completion cannot become READY. */
    fun cancel(conversationId: Conversation.Id, expectedId: String? = null) {
        running[conversationId]?.takeIf { expectedId == null || it.id == expectedId }?.job?.cancel()
    }

    @EventListener(ApplicationReadyEvent::class)
    fun recoverAfterRestart() {
        scope.launch {
            coordinator.runningProactiveCompactions().filter { it.executor != descriptor.identity }.forEach { failInterrupted(it, "ServerRestarted") }
        }
    }

    private suspend fun failInterrupted(preparation: ProactiveCompaction, reason: String) {
        if (coordinator.updateProactiveCompaction(preparation, preparation.copy(status = ProactiveCompaction.Status.FAILED,
                failureCode = reason, outcomeUnknown = true, updatedAt = Clock.System.now()))) {
            stateSync.invalidate(preparation.conversationId)
            log.warn { "COMPACTION phase=interrupted id=${preparation.id} reason=$reason outcomeUnknown=true" }
        }
    }

    suspend fun finish(expected: ProactiveCompaction, status: ProactiveCompaction.Status): Boolean {
        require(status in setOf(ProactiveCompaction.Status.APPLIED, ProactiveCompaction.Status.DISCARDED))
        val accepted = coordinator.updateProactiveCompaction(expected, expected.copy(status = status, checkpoint = null, updatedAt = Clock.System.now()))
        val committedAtomically = status == ProactiveCompaction.Status.APPLIED && coordinator.proactiveCompaction(expected.conversationId)
            ?.let { it.id == expected.id && it.status == ProactiveCompaction.Status.APPLIED } == true
        if (accepted || committedAtomically) {
            cancel(expected.conversationId, expected.id)
            stateSync.invalidate(expected.conversationId)
            log.info { "COMPACTION phase=${status.name.lowercase()} id=${expected.id}" }
        }
        return accepted || committedAtomically
    }
}
