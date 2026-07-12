package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.springframework.stereotype.Service

internal data class MemorySegmentedIngestContext(
    val agent: AgentDefinition,
    val project: Project,
    val systemPrompts: List<String>,
    val memoryTools: List<AiToolCallback>,
)

internal data class MemorySegmentedIngestRequest(
    val kind: String,
    val sourceRef: String,
    val title: String?,
)

@Service
internal class MemorySegmentedIngestProcessor(
    private val memoryStore: MemoryStore,
    private val memoryMessageRoutingApplicationService: MemoryMessageRoutingApplicationService,
) {
    private val log = KLoggers.logger(this)

    suspend fun process(
        rootRun: MemoryRun,
        request: MemorySegmentedIngestRequest,
        parentSource: MemorySource,
        sections: List<MemoryIngestSection>,
        context: MemorySegmentedIngestContext,
        sectionSourceFactory: (MemoryIngestSection) -> MemorySource,
    ): MemoryOperationExecution {
        val namespace = rootRun.namespace
        require(sections.isNotEmpty()) { "Segmented memory ingest has no sections." }

        var currentRun = rootRun.copy(
            summary = "Memory ${request.kind} ingest running: 0/${sections.size} sections",
            progress = MemoryRun.Progress(totalUnits = sections.size),
        )
        val childRunIds = mutableListOf<MemoryRun.Id>()
        val sectionFailures = mutableListOf<SectionFailure>()
        var processedSections = 0
        var totalSections = sections.size
        var adaptiveSplits = 0
        persist(currentRun)

        log.info {
            "Memory segmented ingest started: run=${rootRun.id.value} namespace=${namespace.value} " +
                "kind=${request.kind} parentSource=${parentSource.id.value} sections=${sections.size} sourceRef=${request.sourceRef}"
        }

        sections.forEach { section ->
            val sectionSource = sectionSourceFactory(section)
            currentRun = currentRun.copy(
                progress = MemoryRun.Progress(
                    totalUnits = totalSections,
                    completedUnits = processedSections + sectionFailures.size,
                    failedUnits = sectionFailures.size,
                    currentUnitLabel = section.headingLabel,
                    currentSourceId = sectionSource.id,
                ),
                summary = "Memory ${request.kind} ingest running: ${processedSections + sectionFailures.size}/$totalSections sections",
            )
            persist(currentRun)

            val sectionResult = MemoryAdaptiveIngest.processSection(
                section = section,
                failFastOnError = java.lang.Boolean.getBoolean("gromozeka.memory.routing.failFast"),
            ) { effectiveSection ->
                memoryMessageRoutingApplicationService.routeSource(
                    namespace = namespace,
                    source = sectionSourceFactory(effectiveSection),
                    agent = context.agent,
                    project = context.project,
                    runtimeSystemPrompts = context.systemPrompts,
                    runtimeTools = context.memoryTools,
                    parentRunId = rootRun.id,
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
                val failureSource = sectionSourceFactory(failure.section)
                sectionFailures += SectionFailure(
                    sectionIndex = failure.section.index,
                    heading = failure.section.headingLabel,
                    sourceId = failureSource.id,
                    message = failure.message,
                )
                log.warn(failure.error) {
                    "Memory segmented section failed: run=${rootRun.id.value} namespace=${namespace.value} " +
                        "kind=${request.kind} section=${failure.section.index} heading=${failure.section.headingLabel} " +
                        "source=${failureSource.id.value} error=${failure.message}"
                }
            }

            currentRun = currentRun.copy(
                childRunIds = childRunIds.distinct(),
                progress = MemoryRun.Progress(
                    totalUnits = totalSections,
                    completedUnits = processedSections + sectionFailures.size,
                    failedUnits = sectionFailures.size,
                    currentUnitLabel = section.headingLabel,
                    currentSourceId = sectionSource.id,
                ),
                summary = "Memory ${request.kind} ingest running: ${processedSections + sectionFailures.size}/$totalSections sections",
                errorText = sectionFailures.lastOrNull()?.message,
            )
            persist(currentRun)
        }

        val successfulSections = processedSections
        val failedSections = sectionFailures.size
        val status = when {
            failedSections == 0 -> MemoryRun.Status.SUCCESS
            successfulSections == 0 -> MemoryRun.Status.FAILED
            else -> MemoryRun.Status.PARTIAL
        }
        val progress = MemoryRun.Progress(
            totalUnits = totalSections,
            completedUnits = successfulSections + failedSections,
            failedUnits = failedSections,
        )
        val summary = "Memory ${request.kind} ingest ${status.name.lowercase()}: $successfulSections/$totalSections sections"
        val output = buildJsonObject {
            put(
                "status",
                when (status) {
                    MemoryRun.Status.SUCCESS -> "completed"
                    MemoryRun.Status.PARTIAL -> "partial"
                    MemoryRun.Status.FAILED -> "failed"
                    else -> error("Unexpected segmented ingest terminal status: $status")
                }
            )
            put("ingest_kind", request.kind)
            put("title", request.title.orEmpty())
            put("source_ref", request.sourceRef)
            put("parent_source_id", parentSource.id.value)
            put("sections_total", sections.size)
            put("sections_expanded_total", totalSections)
            put("adaptive_splits", adaptiveSplits)
            put("sections_processed", successfulSections)
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

        log.info {
            "Memory segmented ingest completed: run=${rootRun.id.value} namespace=${namespace.value} " +
                "kind=${request.kind} status=${status.name} processed=$successfulSections failed=$failedSections " +
                "adaptiveSplits=$adaptiveSplits expandedSections=$totalSections childRuns=${childRunIds.size}"
        }

        return MemoryOperationExecution(
            status = status,
            summary = summary,
            output = output,
            sourceIds = listOf(parentSource.id),
            childRunIds = childRunIds.distinct(),
            progress = progress,
            errorText = sectionFailures.firstOrNull()?.message,
        )
    }

    private suspend fun persist(run: MemoryRun) {
        memoryStore.apply(MemoryUpdateBatch(runs = listOf(run)))
    }

    private data class SectionFailure(
        val sectionIndex: Int,
        val heading: String,
        val sourceId: MemorySource.Id,
        val message: String,
    )
}
