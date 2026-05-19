package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.tool.AiToolCallback
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
class MemoryDocumentIngestQueue(
    private val memoryStore: MemoryStore,
    private val memoryMessageRoutingApplicationService: MemoryMessageRoutingApplicationService,
    @Qualifier("supervisorScope") private val coroutineScope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val jobs = Channel<MemoryDocumentIngestJob>(Channel.UNLIMITED)
    private val queuedJobs = AtomicInteger(0)
    private val activeJob = AtomicReference<ActiveDocumentIngestJob?>(null)
    private val totalEnqueuedJobs = AtomicLong(0)
    private val totalStartedJobs = AtomicLong(0)
    private val totalCompletedJobs = AtomicLong(0)
    private val totalFatallyFailedJobs = AtomicLong(0)

    init {
        coroutineScope.launch {
            for (job in jobs) {
                queuedJobs.updateAndGet { (it - 1).coerceAtLeast(0) }
                val startedAt = Clock.System.now()
                activeJob.set(
                    ActiveDocumentIngestJob(
                        runId = job.parentRun.id,
                        parentSourceId = job.parentSource.id,
                        sourceRef = job.sourceRef,
                        sectionsTotal = job.sections.size,
                        startedAt = startedAt,
                    )
                )
                totalStartedJobs.incrementAndGet()
                try {
                    runCatching {
                        process(job, startedAt)
                    }.onSuccess {
                        totalCompletedJobs.incrementAndGet()
                    }.onFailure { error ->
                        totalFatallyFailedJobs.incrementAndGet()
                        failJob(job, error, startedAt)
                    }
                } finally {
                    activeJob.set(null)
                }
            }
        }
    }

    internal fun enqueue(job: MemoryDocumentIngestJob): Int {
        queuedJobs.incrementAndGet()
        totalEnqueuedJobs.incrementAndGet()
        val result = jobs.trySend(job)
        if (result.isFailure) {
            queuedJobs.updateAndGet { (it - 1).coerceAtLeast(0) }
            totalEnqueuedJobs.updateAndGet { (it - 1).coerceAtLeast(0L) }
            result.getOrThrow()
        }
        return queuedJobs.get()
    }

    fun status(): MemoryDocumentIngestQueueStatus =
        MemoryDocumentIngestQueueStatus(
            pendingJobs = queuedJobs.get(),
            activeJob = activeJob.get(),
            totalEnqueuedJobs = totalEnqueuedJobs.get(),
            totalStartedJobs = totalStartedJobs.get(),
            totalCompletedJobs = totalCompletedJobs.get(),
            totalFatallyFailedJobs = totalFatallyFailedJobs.get(),
        )

    private suspend fun process(
        job: MemoryDocumentIngestJob,
        startedAt: Instant,
    ) {
        var parentRun = job.parentRun.copy(
            status = MemoryRun.Status.RUNNING,
            startedAt = startedAt,
            summary = "Document ingest running: 0/${job.sections.size} sections",
            progress = MemoryRun.Progress(
                totalUnits = job.sections.size,
                completedUnits = 0,
                failedUnits = 0,
            ),
        )
        val childRunIds = mutableListOf<MemoryRun.Id>()
        val sectionFailures = mutableListOf<SectionFailure>()
        var processedSections = 0
        var totalSections = job.sections.size
        var adaptiveSplits = 0
        persist(parentRun)

        log.info {
            "Memory document queue started: run=${parentRun.id.value} namespace=${job.namespace.value} " +
                "parentSource=${job.parentSource.id.value} sections=${job.sections.size} sourceRef=${job.sourceRef}"
        }

        job.sections.forEach { section ->
            val sectionSource = job.toSectionSource(section)
            parentRun = parentRun.copy(
                progress = MemoryRun.Progress(
                    totalUnits = totalSections,
                    completedUnits = processedSections + sectionFailures.size,
                    failedUnits = sectionFailures.size,
                    currentUnitLabel = section.headingLabel,
                    currentSourceId = sectionSource.id,
                ),
                summary = "Document ingest running: ${processedSections + sectionFailures.size}/$totalSections sections",
            )
            persist(parentRun)

            val sectionResult = MemoryDocumentAdaptiveIngest.processSection(section) { effectiveSection ->
                memoryMessageRoutingApplicationService.routeSource(
                    namespace = job.namespace,
                    source = job.toSectionSource(effectiveSection),
                    agent = job.agent,
                    project = job.project,
                    runtimeSystemPrompts = job.runtimeSystemPrompts,
                    runtimeTools = job.runtimeTools,
                    parentRunId = parentRun.id,
                    throwOnError = true,
                ) ?: throw IllegalStateException("Section routing returned no result.")
            }

            totalSections += (sectionResult.attemptedSections - 1).coerceAtLeast(0)
            adaptiveSplits += sectionResult.splitCount
            processedSections += sectionResult.processedSections
            sectionResult.results.forEach { result ->
                childRunIds += result.memoryBatch.runs.map { it.id }
            }
            sectionResult.failedSections.forEach { failure ->
                val failureSource = job.toSectionSource(failure.section)
                sectionFailures += SectionFailure(
                    sectionIndex = failure.section.index,
                    heading = failure.section.headingLabel,
                    sourceId = failureSource.id,
                    message = failure.message,
                )
                log.warn(failure.error) {
                    "Memory document section failed: run=${parentRun.id.value} namespace=${job.namespace.value} " +
                        "section=${failure.section.index} heading=${failure.section.headingLabel} " +
                        "source=${failureSource.id.value} error=${failure.message}"
                }
            }

            parentRun = parentRun.copy(
                childRunIds = childRunIds.distinct(),
                progress = MemoryRun.Progress(
                    totalUnits = totalSections,
                    completedUnits = processedSections + sectionFailures.size,
                    failedUnits = sectionFailures.size,
                    currentUnitLabel = section.headingLabel,
                    currentSourceId = sectionSource.id,
                ),
                summary = "Document ingest running: ${processedSections + sectionFailures.size}/$totalSections sections",
                errorText = sectionFailures.lastOrNull()?.message,
            )
            persist(parentRun)
        }

        val completedAt = Clock.System.now()
        val successfulSections = processedSections
        val failedSections = sectionFailures.size
        val finalStatus = when {
            failedSections == 0 -> MemoryRun.Status.SUCCESS
            successfulSections == 0 -> MemoryRun.Status.FAILED
            else -> MemoryRun.Status.PARTIAL
        }
        parentRun = parentRun.copy(
            status = finalStatus,
            childRunIds = childRunIds.distinct(),
            progress = MemoryRun.Progress(
                totalUnits = totalSections,
                completedUnits = successfulSections + failedSections,
                failedUnits = failedSections,
            ),
            summary = "Document ingest ${finalStatus.name.lowercase()}: $successfulSections/$totalSections sections",
            output = job.outputJson(
                expandedSections = totalSections,
                processedSections = successfulSections,
                failedSections = failedSections,
                adaptiveSplits = adaptiveSplits,
                sectionFailures = sectionFailures,
            ),
            errorText = sectionFailures.firstOrNull()?.message,
            latencyMs = completedAt.toEpochMilliseconds() - startedAt.toEpochMilliseconds(),
            completedAt = completedAt,
        )
        persist(parentRun)

        log.info {
            "Memory document queue completed: run=${parentRun.id.value} namespace=${job.namespace.value} " +
                "status=${finalStatus.name} processed=$successfulSections failed=$failedSections " +
                "adaptiveSplits=$adaptiveSplits expandedSections=$totalSections " +
                "childRuns=${childRunIds.size} latencyMs=${parentRun.latencyMs}"
        }
    }

    private suspend fun persist(run: MemoryRun) {
        memoryStore.apply(MemoryUpdateBatch(runs = listOf(run)))
    }

    private suspend fun failJob(
        job: MemoryDocumentIngestJob,
        error: Throwable,
        startedAt: Instant,
    ) {
        val completedAt = Clock.System.now()
        val failedRun = job.parentRun.copy(
            status = MemoryRun.Status.FAILED,
            startedAt = startedAt,
            summary = "Document ingest failed before completion: ${error.message ?: error::class.simpleName.orEmpty()}",
            errorText = error.message ?: error::class.simpleName.orEmpty(),
            progress = job.parentRun.progress ?: MemoryRun.Progress(totalUnits = job.sections.size),
            latencyMs = completedAt.toEpochMilliseconds() - startedAt.toEpochMilliseconds(),
            completedAt = completedAt,
        )
        persist(failedRun)
        log.warn(error) {
            "Memory document queue failed: run=${job.parentRun.id.value} namespace=${job.namespace.value} " +
                "parentSource=${job.parentSource.id.value} error=${error.message}"
        }
    }

    private fun MemoryDocumentIngestJob.toSectionSource(section: MarkdownDocumentSection): MemorySource.ExternalRecord {
        val now = Clock.System.now()
        val sectionHash = "$documentHash:${section.index}:${section.text.sha256()}".sha256()
        return MemorySource.ExternalRecord(
            id = MemorySource.Id("external:document-section:${sectionHash.take(32)}"),
            namespace = namespace,
            recordRef = "$parentRecordRef#section:${section.index}",
            authorLabel = "document section",
            contentText = section.toMemorySourceText(
                title = title,
                sourceRef = sourceRef,
                importedAt = now,
            ),
            contentPayload = buildJsonObject {
                put("memoryToolOrigin", "provided_document_section")
                put("sourceKind", "document")
                put("parentSourceId", parentSource.id.value)
                put("parentRunId", parentRun.id.value)
                put("documentHash", documentHash)
                put("inputKind", inputKind.name)
                put("documentType", documentType.name)
                put("sourceRef", sourceRef)
                put("importedAt", now.toString())
                if (forceWrite) put("forceMemoryWrite", true)
                title?.let { put("title", it) }
                put("sectionIndex", section.index)
                put("heading", section.headingLabel)
                put("startLine", section.startLine)
                put("endLine", section.endLine)
                putJsonArray("headingPath") {
                    section.headingPath.forEach { add(JsonPrimitive(it)) }
                }
                mode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
            },
            contentHash = sectionHash,
            observedAt = now,
            createdAt = now,
            retentionClass = MemorySource.RetentionClass.IMPORTED,
        )
    }

    private fun MemoryDocumentIngestJob.outputJson(
        expandedSections: Int,
        processedSections: Int,
        failedSections: Int,
        adaptiveSplits: Int,
        sectionFailures: List<SectionFailure>,
    ) = buildJsonObject {
        put("document_type", documentType.name)
        put("input_kind", inputKind.name)
        put("title", title.orEmpty())
        put("source_ref", sourceRef)
        put("parent_source_id", parentSource.id.value)
        put("sections_total", sections.size)
        put("sections_expanded_total", expandedSections)
        put("adaptive_splits", adaptiveSplits)
        put("sections_processed", processedSections)
        put("sections_failed", failedSections)
        putJsonArray("failures") {
            sectionFailures.take(50).forEach { failure ->
                add(
                    buildJsonObject {
                        put("section_index", failure.sectionIndex)
                        put("heading", failure.heading)
                        put("source_id", failure.sourceId.value)
                        put("message", failure.message)
                    }
                )
            }
            if (sectionFailures.size > 50) {
                add(JsonPrimitive("... ${sectionFailures.size - 50} more failures"))
            }
        }
    }

    private data class SectionFailure(
        val sectionIndex: Int,
        val heading: String,
        val sourceId: MemorySource.Id,
        val message: String,
    )

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

internal data class MemoryDocumentIngestJob(
    val namespace: MemoryNamespace,
    val parentRun: MemoryRun,
    val parentSource: MemorySource.ExternalRecord,
    val documentType: MemoryDocumentType,
    val inputKind: MemoryRememberInputKind,
    val title: String?,
    val sourceRef: String,
    val documentHash: String,
    val parentRecordRef: String,
    val sections: List<MarkdownDocumentSection>,
    val forceWrite: Boolean,
    val mode: String?,
    val agent: AgentDefinition,
    val project: Project,
    val runtimeSystemPrompts: List<String>,
    val runtimeTools: List<AiToolCallback>,
)

data class MemoryDocumentIngestQueueStatus(
    val pendingJobs: Int,
    val activeJob: ActiveDocumentIngestJob?,
    val totalEnqueuedJobs: Long,
    val totalStartedJobs: Long,
    val totalCompletedJobs: Long,
    val totalFatallyFailedJobs: Long,
)

data class ActiveDocumentIngestJob(
    val runId: MemoryRun.Id,
    val parentSourceId: MemorySource.Id,
    val sourceRef: String,
    val sectionsTotal: Int,
    val startedAt: Instant,
)
