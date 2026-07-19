package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSummary
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
            llmCalls = listOf(
                MemoryRun.LlmCallTiming(
                    stageName = "claim-extractor",
                    attempt = 1,
                    status = MemoryRun.LlmCallStatus.SUCCESS,
                    startedAt = createdAt,
                    completedAt = createdAt,
                    latencyMs = 1200,
                    totalTokens = 42,
                    logContext = "section=1/2",
                ),
                MemoryRun.LlmCallTiming(
                    stageName = "claim-reconciler",
                    attempt = 1,
                    status = MemoryRun.LlmCallStatus.SUCCESS,
                    startedAt = createdAt,
                    completedAt = createdAt,
                    latencyMs = 700,
                ),
            ),
            status = MemoryRun.Status.SUCCESS,
            createdAt = createdAt,
            completedAt = createdAt,
        )

        val json = Json.parseToJsonElement(
            MemoryToolResultRenderer.runStatusJsonString(parent, listOf(child), maxDepth = 4)
        ).jsonObject
        val run = json.getValue("run").jsonObject
        val children = run.getValue("children").jsonArray

        assertEquals("running", json.getValue("status").jsonPrimitive.content)
        assertEquals("document-ingest:run:parent", json.getValue("run_id").jsonPrimitive.content)
        assertEquals("RUNNING", run.getValue("run_status").jsonPrimitive.content)
        assertEquals("2", run.getValue("progress").jsonObject.getValue("total_units").jsonPrimitive.content)
        assertEquals("hot-path:run:child", children.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("document-ingest:run:parent", children.single().jsonObject.getValue("parent_run_id").jsonPrimitive.content)
        assertEquals("2", children.single().jsonObject.getValue("llm_calls_count").jsonPrimitive.content)
        assertEquals("1900", children.single().jsonObject.getValue("llm_latency_ms").jsonPrimitive.content)
        assertEquals(
            "claim-extractor",
            children.single().jsonObject.getValue("slowest_llm_calls").jsonArray
                .first().jsonObject.getValue("stage").jsonPrimitive.content,
        )
    }

    @Test
    fun runStatusExposesNeedsInputResultAsTerminal() {
        val run = MemoryRun(
            id = MemoryRun.Id("memory-operation:remember:needs-input"),
            namespace = namespace,
            runType = MemoryRun.Type.REMEMBER,
            summary = "Memory ingest needs explicit structure confirmation",
            output = buildJsonObject {
                put("status", "needs_user_input")
                put("required_action", "Ask the user to approve the proposed structure.")
            },
            status = MemoryRun.Status.NEEDS_INPUT,
            createdAt = createdAt,
            completedAt = createdAt,
        )

        val json = Json.parseToJsonElement(
            MemoryToolResultRenderer.runStatusJsonString(run, emptyList(), maxDepth = 0)
        ).jsonObject

        assertEquals("needs_user_input", json.getValue("status").jsonPrimitive.content)
        assertEquals("needs_input", json.getValue("run_status").jsonPrimitive.content)
        assertEquals(
            "Ask the user to approve the proposed structure.",
            json.getValue("result").jsonObject.getValue("required_action").jsonPrimitive.content,
        )
    }

    @Test
    fun queueStatusRendersDurableRunsAndOnlineWorkers() {
        val workerIdentity = ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId("memory-worker"),
            sessionId = ConversationRuntimeWorkerSessionId("session-1"),
        )
        val json = Json.parseToJsonElement(
            MemoryToolResultRenderer.queueStatusJsonString(
                MemoryOperationQueueStatus(
                    queuedJobs = 2,
                    activeJobs = listOf(
                        ActiveMemoryOperation(
                        runId = MemoryRun.Id("memory-operation:remember:run:active"),
                        runType = MemoryRun.Type.REMEMBER,
                        operation = MemoryOperationKind.REMEMBER,
                        namespace = namespace,
                        startedAt = createdAt,
                            executionLease = MemoryRun.ExecutionLease(
                                ownerId = workerIdentity.workerId.value,
                                ownerSessionId = workerIdentity.sessionId.value,
                                expiresAt = Instant.parse("2026-05-13T20:30:00Z"),
                            ),
                            leaseExpired = false,
                        )
                    ),
                    onlineWorkers = listOf(
                        ConversationRuntimeWorkerRegistration(
                            identity = workerIdentity,
                            capabilities = setOf(ConversationRuntimeWorkerCapability.MEMORY_PIPELINE),
                            tools = emptyList(),
                            version = "test",
                            startedAt = createdAt,
                            lastHeartbeatAt = createdAt,
                        )
                    ),
                ),
            )
        ).jsonObject

        assertEquals("completed", json.getValue("status").jsonPrimitive.content)
        assertEquals("2", json.getValue("queued_jobs").jsonPrimitive.content)
        assertEquals("true", json.getValue("has_active_job").jsonPrimitive.content)
        assertEquals(
            "memory-operation:remember:run:active",
            json.getValue("active_jobs").jsonArray
                .single().jsonObject
                .getValue("run_id").jsonPrimitive.content,
        )
        assertEquals("memory-worker", json.getValue("online_workers").jsonArray.single().jsonObject
            .getValue("worker_id").jsonPrimitive.content)
        assertEquals("memory_runs", json.getValue("source_of_truth").jsonPrimitive.content)
        assertEquals("resume_queued_fail_interrupted", json.getValue("restart_policy").jsonPrimitive.content)
    }

    @Test
    fun maintenanceQueuedResultRendersRunIdForStatusPolling() {
        val json = Json.parseToJsonElement(
            MemoryToolResultRenderer.maintenanceQueuedResultJsonString(
                MemoryMaintenanceQueuedResult(
                    runId = MemoryRun.Id("maintenance:repair:run:test"),
                    action = MemoryMaintenanceAction.REPAIR,
                    targetKind = MemoryMaintenanceTargetKind.WORKSPACE_ID,
                    targetValue = "workspace:test",
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
                defaultNamespace = namespace,
            )
        ).jsonObject
        val renderedNamespace = json.getValue("namespaces").jsonArray.single().jsonObject
        val counts = renderedNamespace.getValue("counts").jsonObject

        assertEquals("completed", json.getValue("status").jsonPrimitive.content)
        assertEquals("project:test", json.getValue("default_namespace").jsonPrimitive.content)
        assertEquals("project:test", renderedNamespace.getValue("namespace").jsonPrimitive.content)
        assertEquals("Test project (project:test)", renderedNamespace.getValue("display_name").jsonPrimitive.content)
        assertEquals("true", renderedNamespace.getValue("is_default").jsonPrimitive.content)
        assertEquals("6", counts.getValue("total_items").jsonPrimitive.content)
    }
}
