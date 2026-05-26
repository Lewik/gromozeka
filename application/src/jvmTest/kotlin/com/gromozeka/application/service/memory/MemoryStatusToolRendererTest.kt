package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSummary
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryStatusToolRendererTest {
    private val namespace = MemoryNamespace("project:test")
    private val createdAt = Instant.parse("2026-05-13T20:00:00Z")

    @Test
    fun runStatusRendersParentChildTree() {
        val parent = MemoryRun(
            id = MemoryRun.Id("document-ingest:run:parent"),
            namespace = namespace,
            runType = MemoryRun.Type.DOCUMENT_INGEST,
            triggerMode = MemoryRun.TriggerMode.MANUAL,
            childRunIds = listOf(MemoryRun.Id("hot-path:run:child")),
            summary = "Document ingest running: 1/2 sections",
            sourceIds = listOf(MemorySource.Id("external:document:parent")),
            progress = MemoryRun.Progress(totalUnits = 2, completedUnits = 1, failedUnits = 0),
            status = MemoryRun.Status.RUNNING,
            createdAt = createdAt,
            startedAt = createdAt,
        )
        val child = MemoryRun(
            id = MemoryRun.Id("hot-path:run:child"),
            namespace = namespace,
            runType = MemoryRun.Type.CONSTRUCT_NOTES,
            triggerMode = MemoryRun.TriggerMode.MANUAL,
            parentRunId = parent.id,
            summary = "Created note",
            status = MemoryRun.Status.SUCCESS,
            createdAt = createdAt,
            completedAt = createdAt,
        )

        val json = Json.parseToJsonElement(
            MemoryToolResultRenderer.runStatusJsonString(parent, listOf(child), maxDepth = 4)
        ).jsonObject
        val run = json.getValue("run").jsonObject
        val children = run.getValue("children").jsonArray

        assertEquals("completed", json.getValue("status").jsonPrimitive.content)
        assertEquals("document-ingest:run:parent", json.getValue("run_id").jsonPrimitive.content)
        assertEquals("RUNNING", run.getValue("run_status").jsonPrimitive.content)
        assertEquals("2", run.getValue("progress").jsonObject.getValue("total_units").jsonPrimitive.content)
        assertEquals("hot-path:run:child", children.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("document-ingest:run:parent", children.single().jsonObject.getValue("parent_run_id").jsonPrimitive.content)
    }

    @Test
    fun queueStatusRendersProcessLocalCounters() {
        val json = Json.parseToJsonElement(
            MemoryToolResultRenderer.queueStatusJsonString(
                documentStatus = MemoryDocumentIngestQueueStatus(
                    pendingJobs = 2,
                    activeJob = ActiveDocumentIngestJob(
                        runId = MemoryRun.Id("document-ingest:run:active"),
                        parentSourceId = MemorySource.Id("external:document:active"),
                        sourceRef = "agent_memory_handoff/README.md",
                        sectionsTotal = 7,
                        startedAt = createdAt,
                    ),
                    totalEnqueuedJobs = 5,
                    totalStartedJobs = 3,
                    totalCompletedJobs = 2,
                    totalFatallyFailedJobs = 1,
                ),
                maintenanceStatus = MemoryMaintenanceQueueStatus(
                    pendingJobs = 1,
                    activeJob = ActiveMemoryMaintenanceJob(
                        runId = MemoryRun.Id("maintenance:maintain_entities:run:active"),
                        action = MemoryMaintenanceAction.MAINTAIN_ENTITIES,
                        targetKind = "namespace",
                        targetValue = "project:test",
                        namespace = namespace,
                        conversationId = com.gromozeka.domain.model.Conversation.Id("conversation:test"),
                        startedAt = createdAt,
                    ),
                    totalEnqueuedJobs = 4,
                    totalStartedJobs = 2,
                    totalCompletedJobs = 1,
                    totalFatallyFailedJobs = 0,
                ),
                embeddingStatus = MemoryEmbeddingIndexStatus(
                    totalEmbeddedItems = 8,
                    totalEmbeddingRequests = 3,
                    totalRebuilds = 1,
                    totalFailedRequests = 0,
                ),
            )
        ).jsonObject

        assertEquals("completed", json.getValue("status").jsonPrimitive.content)
        assertEquals("3", json.getValue("pending_jobs").jsonPrimitive.content)
        assertEquals("true", json.getValue("has_active_job").jsonPrimitive.content)
        assertEquals(
            "document-ingest:run:active",
            json.getValue("document_ingest").jsonObject
                .getValue("active_job").jsonObject
                .getValue("run_id").jsonPrimitive.content,
        )
        assertEquals(
            "maintenance:maintain_entities:run:active",
            json.getValue("maintenance").jsonObject
                .getValue("active_job").jsonObject
                .getValue("run_id").jsonPrimitive.content,
        )
        assertEquals(
            "8",
            json.getValue("embeddings").jsonObject
                .getValue("total_embedded_items").jsonPrimitive.content,
        )
        assertEquals("false", json.getValue("durable_resume").jsonPrimitive.content)
    }

    @Test
    fun maintenanceQueuedResultRendersRunIdForStatusPolling() {
        val json = Json.parseToJsonElement(
            MemoryToolResultRenderer.maintenanceQueuedResultJsonString(
                MemoryMaintenanceQueuedResult(
                    runId = MemoryRun.Id("maintenance:repair:run:test"),
                    action = MemoryMaintenanceAction.REPAIR,
                    targetKind = "namespace",
                    targetValue = "project:test",
                    namespace = namespace,
                    conversationId = com.gromozeka.domain.model.Conversation.Id("conversation:test"),
                    queueSize = 2,
                )
            )
        ).jsonObject

        assertEquals("queued", json.getValue("status").jsonPrimitive.content)
        assertEquals("maintenance:repair:run:test", json.getValue("run_id").jsonPrimitive.content)
        assertEquals("repair", json.getValue("action").jsonPrimitive.content)
        assertEquals("2", json.getValue("queue_size").jsonPrimitive.content)
    }

    @Test
    fun namespaceListRendersDefaultAndCounts() {
        val json = Json.parseToJsonElement(
            MemoryToolResultRenderer.namespaceListResultJsonString(
                summaries = listOf(
                    MemoryNamespaceSummary(
                        namespace = namespace,
                        displayName = "Test project (project:test)",
                        counts = MemoryNamespaceSummary.Counts(
                            sources = 1,
                            claims = 2,
                            notes = 3,
                        ),
                        lastUpdatedAt = createdAt,
                    )
                ),
                configuredDefaultNamespace = namespace,
            )
        ).jsonObject
        val renderedNamespace = json.getValue("namespaces").jsonArray.single().jsonObject
        val counts = renderedNamespace.getValue("counts").jsonObject

        assertEquals("completed", json.getValue("status").jsonPrimitive.content)
        assertEquals("project:test", json.getValue("configured_default_namespace").jsonPrimitive.content)
        assertEquals("project:test", renderedNamespace.getValue("namespace").jsonPrimitive.content)
        assertEquals("Test project (project:test)", renderedNamespace.getValue("display_name").jsonPrimitive.content)
        assertEquals("true", renderedNamespace.getValue("is_configured_default").jsonPrimitive.content)
        assertEquals("6", counts.getValue("total_items").jsonPrimitive.content)
    }
}
