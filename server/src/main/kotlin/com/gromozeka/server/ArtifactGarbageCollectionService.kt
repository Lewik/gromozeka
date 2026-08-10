package com.gromozeka.server

import com.gromozeka.application.service.ConversationArtifactApplicationService
import jakarta.annotation.PreDestroy
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Service
class ArtifactGarbageCollectionService(
    private val artifactService: ConversationArtifactApplicationService,
    @Value("\${gromozeka.artifacts.draft-retention-hours:168}")
    private val draftRetentionHours: Long = DEFAULT_DRAFT_RETENTION_HOURS,
    @Value("\${gromozeka.artifacts.gc-interval-minutes:360}")
    private val garbageCollectionIntervalMinutes: Long = DEFAULT_GC_INTERVAL_MINUTES,
) {
    private val log = KLoggers.logger(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("artifact-gc"))

    init {
        require(draftRetentionHours > 0) { "Artifact draft retention must be positive" }
        require(garbageCollectionIntervalMinutes > 0) { "Artifact GC interval must be positive" }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        scope.launch { runGarbageCollectionLoop() }
    }

    @PreDestroy
    fun close() {
        scope.cancel()
    }

    private suspend fun runGarbageCollectionLoop() {
        while (currentCoroutineContext().isActive) {
            collectGarbage()
            delay(garbageCollectionIntervalMinutes.minutes)
        }
    }

    private suspend fun collectGarbage() {
        try {
            var deletedDrafts = 0
            var deletedOrphanedContent = 0
            do {
                val result = artifactService.collectGarbage(
                    draftsCreatedBefore = Clock.System.now() - draftRetentionHours.hours,
                )
                deletedDrafts += result.deletedDrafts
                deletedOrphanedContent += result.deletedOrphanedContent
            } while (result.hasMoreExpiredDrafts)

            if (deletedDrafts > 0 || deletedOrphanedContent > 0) {
                log.info {
                    "Artifact garbage collection completed: drafts=$deletedDrafts " +
                        "orphanedContent=$deletedOrphanedContent"
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn(error) { "Artifact garbage collection failed: ${error.message}" }
        }
    }

    private companion object {
        const val DEFAULT_DRAFT_RETENTION_HOURS = 168L
        const val DEFAULT_GC_INTERVAL_MINUTES = 360L
    }
}
