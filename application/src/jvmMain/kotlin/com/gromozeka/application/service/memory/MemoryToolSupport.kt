package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryReadResult
import com.gromozeka.domain.model.memory.MemoryReadTrace
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSummary
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.tool.AiToolCallback
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

const val MEMORY_REMEMBER_TOOL_NAME = "memory_remember"
const val MEMORY_ENRICH_CONTEXT_TOOL_NAME = "memory_enrich_context"
const val MEMORY_ANSWER_QUESTION_TOOL_NAME = "memory_answer_question"
const val MEMORY_RUN_STATUS_TOOL_NAME = "memory_run_status"
const val MEMORY_QUEUE_STATUS_TOOL_NAME = "memory_queue_status"
const val MEMORY_MAINTENANCE_TOOL_NAME = "memory_maintenance"
const val MEMORY_REBUILD_EMBEDDINGS_TOOL_NAME = "memory_rebuild_embeddings"
const val MEMORY_EMBEDDING_STATUS_TOOL_NAME = "memory_embedding_status"
const val MEMORY_LIST_NAMESPACES_TOOL_NAME = "memory_list_namespaces"

fun List<AiToolCallback>.withoutMemoryManagementTools(): List<AiToolCallback> =
    filterNot { tool -> tool.definition.name in memoryManagementToolNames }

private val memoryManagementToolNames = setOf(
    MEMORY_REMEMBER_TOOL_NAME,
    MEMORY_ENRICH_CONTEXT_TOOL_NAME,
    MEMORY_ANSWER_QUESTION_TOOL_NAME,
    MEMORY_RUN_STATUS_TOOL_NAME,
    MEMORY_QUEUE_STATUS_TOOL_NAME,
    MEMORY_MAINTENANCE_TOOL_NAME,
    MEMORY_REBUILD_EMBEDDINGS_TOOL_NAME,
    MEMORY_EMBEDDING_STATUS_TOOL_NAME,
    MEMORY_LIST_NAMESPACES_TOOL_NAME,
)

object MemoryToolResultRenderer {
    fun rememberResultJsonString(result: DirectStructuredMemoryWriteResult?): String {
        if (result == null) {
            return buildJsonObject {
                put("status", "skipped")
                put("reason", "No memory-worthy content was extracted from the target message, or memory write failed defensively.")
            }.toString()
        }

        val source = result.sourceBatch.sources.firstOrNull()
        return buildJsonObject {
            put("status", "completed")
            put("decision", result.routeDecision.decision.name)
            putJsonArray("memory_types") {
                result.routeDecision.memoryTypes.map { it.name }.sorted().forEach { add(JsonPrimitive(it)) }
            }
            put("salience", result.routeDecision.salience)
            put("reason", result.routeDecision.reason)
            put("source_id", source?.id?.value ?: "")
            source?.let {
                put("namespace", it.namespace.value)
                it.sourceRefForToolResult()?.let { sourceRef -> put("source_ref", sourceRef) }
            }
            put("counts", result.memoryBatch.toCountsJson())
            putJsonArray("runs") {
                result.memoryBatch.runs.forEach { run ->
                    add(buildJsonObject {
                        put("id", run.id.value)
                        put("type", run.runType.name)
                        put("summary", run.summary.shortForMemoryToolResult())
                    })
                }
            }
            putJsonArray("claims") {
                result.memoryBatch.claims.take(8).forEach { claim ->
                    add(buildJsonObject {
                        put("id", claim.id.value)
                        put("predicate", claim.predicate)
                        put("status", claim.status.name)
                        put("text", claim.normalizedText.shortForMemoryToolResult())
                    })
                }
            }
            putJsonArray("notes") {
                result.memoryBatch.notes.take(8).forEach { note ->
                    add(buildJsonObject {
                        put("id", note.id.value)
                        put("type", note.noteType.name)
                        put("status", note.status.name)
                        put("title", note.title.shortForMemoryToolResult())
                        put("summary", note.summary.shortForMemoryToolResult())
                    })
                }
            }
            putJsonArray("actionItems") {
                result.memoryBatch.actionItems.take(8).forEach { actionItem ->
                    add(buildJsonObject {
                        put("id", actionItem.id.value)
                        put("status", actionItem.status.name)
                        put("priority", actionItem.priority.name)
                        put("title", actionItem.title.shortForMemoryToolResult())
                    })
                }
            }
            putJsonArray("entities") {
                result.memoryBatch.entities.take(12).forEach { entity ->
                    add(buildJsonObject {
                        put("id", entity.id.value)
                        put("type", entity.entityType.name)
                        put("name", entity.canonicalName.shortForMemoryToolResult())
                    })
                }
            }
        }.toString()
    }

    fun enrichContextResultJsonString(result: MemoryReadResult?): String {
        if (result == null) {
            return buildJsonObject {
                put("status", "failed")
                put("reason", "Runtime memory context enrichment failed defensively; answer without enriched memory.")
            }.toString()
        }

        return buildJsonObject {
            put("status", "completed")
            put("need_memory", result.plan.needMemory)
            put("context_mode", result.plan.contextMode.name)
            put("retrieved_count", result.retrievedHits.size)
            put(
                "usage_guidance",
                "Selected memory is the strongest available remembered context for the target context. Use it unless it is clearly irrelevant, insufficient, stale, internally conflicting, or contradicted by the current user message. Do not replace selected memory with guesses or general defaults. For exact quote, exact wording, source, or when-said questions, prefer complete source text from memory_context over shorter evidence excerpts."
            )
            put("memory_context", result.runtimePrompt ?: "No relevant persisted memory was retrieved for the target context.")
            putJsonArray("selected_refs") {
                result.selectedToolRefs(limit = 16).forEach { selectedRef ->
                    val hit = selectedRef.hit
                    add(buildJsonObject {
                        put("type", hit.ref.type.name)
                        put("id", hit.ref.id)
                        put("summary", hit.summary.shortForMemoryToolResult())
                        selectedRef.decision?.let { decision ->
                            put("rank", decision.rank)
                            put("selection_reason", decision.reason.shortForMemoryToolResult())
                        }
                        hit.predicate?.let { put("predicate", it) }
                        hit.status?.let { put("status", it) }
                        if (hit.evidenceSourceIds.isNotEmpty()) {
                            putJsonArray("evidence_source_ids") {
                                hit.evidenceSourceIds.forEach { sourceId ->
                                    add(JsonPrimitive(sourceId.value))
                                }
                            }
                        }
                    })
                }
            }
            putJsonArray("selector_decisions") {
                result.trace.selectorDecisions.take(16).forEach { decision ->
                    add(buildJsonObject {
                        put("type", decision.ref.type.name)
                        put("id", decision.ref.id)
                        put("selected", decision.selected)
                        put("rank", decision.rank)
                        put("reason", decision.reason.shortForMemoryToolResult())
                    })
                }
            }
        }.toString()
    }

    fun pendingEnrichContextResultJsonString(): String =
        buildJsonObject {
            put("status", "pending")
            put("need_memory", true)
            put("context_mode", "ASYNC_RECALL")
            put("retrieved_count", 0)
            put(
                "usage_guidance",
                "Runtime memory recall is running asynchronously. This pending state is not evidence that remembered information or its original source is unavailable. If the user question depends on remembered context, say only that memory is being checked, make no claim about source access or absence, and wait for the follow-up memory result instead of guessing."
            )
            put("memory_context", "Memory recall is pending. The follow-up memory result will contain any selected evidence.")
        }.toString()

    internal fun answerQuestionResultJsonString(result: MemoryQuestionAnswerResult?): String {
        if (result == null) {
            return buildJsonObject {
                put("status", "failed")
                put("reason", "Runtime memory question answering failed defensively.")
            }.toString()
        }

        val readResult = result.readResult
        return buildJsonObject {
            put("status", "completed")
            put("need_memory", readResult.plan.needMemory)
            put("context_mode", readResult.plan.contextMode.name)
            put("retrieved_count", readResult.retrievedHits.size)
            put("sufficiency", result.sufficiency.name.lowercase())
            put("answer", result.answer)
            put("reasoning", result.reasoning)
            put("memory_context", readResult.runtimePrompt ?: "No relevant persisted memory was retrieved for the question.")
            putJsonArray("evidence_refs") {
                result.evidenceRefs.forEach { add(JsonPrimitive(it)) }
            }
            putJsonArray("counted_items") {
                result.countedItems.forEach { add(JsonPrimitive(it)) }
            }
            putJsonArray("excluded_refs") {
                result.excludedRefs.forEach { add(JsonPrimitive(it)) }
            }
            putJsonArray("selected_refs") {
                readResult.selectedToolRefs(limit = 16).forEach { selectedRef ->
                    val hit = selectedRef.hit
                    add(buildJsonObject {
                        put("type", hit.ref.type.name)
                        put("id", hit.ref.id)
                        put("summary", hit.summary.shortForMemoryToolResult())
                        selectedRef.decision?.let { decision ->
                            put("rank", decision.rank)
                            put("selection_reason", decision.reason.shortForMemoryToolResult())
                        }
                        hit.predicate?.let { put("predicate", it) }
                        hit.status?.let { put("status", it) }
                        if (hit.evidenceSourceIds.isNotEmpty()) {
                            putJsonArray("evidence_source_ids") {
                                hit.evidenceSourceIds.forEach { sourceId ->
                                    add(JsonPrimitive(sourceId.value))
                                }
                            }
                        }
                    })
                }
            }
        }.toString()
    }

    private fun MemoryReadResult.selectedToolRefs(limit: Int): List<MemoryToolSelectedRef> {
        val hitsByRef = trace.selectedHits.associateBy { it.ref }
        val decisionsByRef = trace.selectorDecisions
            .filter { it.selected }
            .associateBy { it.ref }
        val ordered = mutableListOf<MemoryToolSelectedRef>()
        val addedRefs = mutableSetOf<MemoryItemRef>()

        trace.selectorDecisions
            .asSequence()
            .filter { it.selected }
            .filter { it.rank > 0 && it.rank < Int.MAX_VALUE }
            .sortedWith(compareBy<MemoryReadTrace.SelectorDecision> { it.rank }
                .thenBy { it.ref.type.name }
                .thenBy { it.ref.id })
            .forEach { decision ->
                val hit = hitsByRef[decision.ref] ?: return@forEach
                if (addedRefs.add(hit.ref)) {
                    ordered += MemoryToolSelectedRef(hit = hit, decision = decision)
                }
            }

        trace.selectedHits.forEach { hit ->
            if (addedRefs.add(hit.ref)) {
                ordered += MemoryToolSelectedRef(hit = hit, decision = decisionsByRef[hit.ref])
            }
        }

        return ordered.take(limit)
    }

    private data class MemoryToolSelectedRef(
        val hit: MemoryReadTrace.Hit,
        val decision: MemoryReadTrace.SelectorDecision?,
    )

    private fun MemorySource.sourceRefForToolResult(): String? =
        when (this) {
            is MemorySource.ChatTurn -> sourceMessageId?.value ?: threadId?.value ?: conversationId.value
            is MemorySource.ToolOutput -> toolName ?: sourceMessageId?.value ?: threadId?.value ?: conversationId?.value
            is MemorySource.ImportedNote -> importRef
            is MemorySource.ExternalRecord -> recordRef
        }?.takeIf { it.isNotBlank() }

    fun operationQueuedResultJsonString(result: MemoryOperationQueuedResult): String =
        buildJsonObject {
            put("status", "queued")
            put("run_id", result.runId.value)
            put("operation", result.operation.wireName)
            put("namespace", result.namespace.value)
            put("queue_size", result.queueSize)
            put(
                "message",
                "Memory ${result.operation.wireName} was queued for asynchronous processing. " +
                    "Call memory_run_status with this run_id to retrieve its state and completed result."
            )
        }.toString()

    fun maintenanceQueuedResultJsonString(result: MemoryMaintenanceQueuedResult): String =
        buildJsonObject {
            put("status", "queued")
            put("run_id", result.runId.value)
            put("action", result.action.toolName)
            put("target_kind", result.targetKind.wireName)
            put("target_value", result.targetValue)
            put("namespace", result.namespace.value)
            put("conversation_id", result.conversationId.value)
            put("queue_size", result.queueSize)
            put(
                "message",
                "Memory maintenance was queued for asynchronous processing. " +
                    "Call memory_run_status with this run_id to retrieve its state and completed result."
            )
        }.toString()

    fun runStatusJsonString(
        rootRun: MemoryRun,
        descendants: List<MemoryRun>,
        maxDepth: Int,
    ): String =
        buildJsonObject {
            put(
                "status",
                when {
                    rootRun.status == MemoryRun.Status.NEEDS_INPUT -> "needs_user_input"
                    rootRun.status.isTerminal() -> "completed"
                    else -> "running"
                }
            )
            put("run_id", rootRun.id.value)
            put("run_status", rootRun.status.name.lowercase())
            put("max_depth", maxDepth)
            put("descendant_count", descendants.size)
            if (rootRun.status.isTerminal()) {
                rootRun.output?.let { put("result", it) }
            }
            put("run", rootRun.toStatusJson(descendants, maxDepth))
        }.toString()

    fun queueStatusJsonString(operationStatus: MemoryOperationQueueStatus): String =
        buildJsonObject {
            put("status", "completed")
            put("kind", "memory_operation_queue")
            put("queued_jobs", operationStatus.queuedJobs)
            put("running_jobs", operationStatus.activeJobs.size)
            put("has_active_job", operationStatus.activeJobs.isNotEmpty())
            putJsonArray("active_jobs") {
                operationStatus.activeJobs.forEach { active ->
                    add(
                        buildJsonObject {
                            put("run_id", active.runId.value)
                            put("run_type", active.runType.name.lowercase())
                            active.operation?.let { put("operation", it.wireName) }
                            put("namespace", active.namespace.value)
                            active.startedAt?.let { put("started_at", it.toString()) }
                            active.executionLease?.let { lease ->
                                put("worker_id", lease.ownerId)
                                put("worker_session_id", lease.ownerSessionId)
                                put("lease_expires_at", lease.expiresAt.toString())
                            }
                            put("lease_expired", active.leaseExpired)
                        }
                    )
                }
            }
            put("worker_count", operationStatus.onlineWorkers.size)
            putJsonArray("online_workers") {
                operationStatus.onlineWorkers.forEach { worker ->
                    add(
                        buildJsonObject {
                            put("worker_id", worker.identity.workerId.value)
                            put("worker_session_id", worker.identity.sessionId.value)
                            put("version", worker.version)
                            put("last_heartbeat_at", worker.lastHeartbeatAt.toString())
                        }
                    )
                }
            }
            put("source_of_truth", "memory_runs")
            put("distributed", true)
            put("restart_policy", "resume_queued_fail_interrupted")
        }.toString()

    fun embeddingCoverageResultJsonString(coverage: MemoryEmbeddingCoverage): String =
        buildJsonObject {
            put("status", "completed")
            put("namespace", coverage.namespace.value)
            put("model_configuration_id", coverage.modelConfigurationId)
            put("provider_model_id", coverage.providerModelId)
            put("dimensions", coverage.dimensions)
            put("embeddable_items", coverage.embeddableItems)
            put("expected_embeddings", coverage.expectedEmbeddings)
            put("existing_embeddings", coverage.existingEmbeddings)
            put("missing_embeddings", coverage.missingEmbeddings)
            put("coverage_ratio", coverage.coverageRatio)
            put("coverage_percent", coverage.coverageRatio * 100.0)
            put("missing_only_rebuild_available", true)
            put("stale_detection", "not_checked")
        }.toString()

    fun namespaceListResultJsonString(
        summaries: List<MemoryNamespaceSummary>,
        defaultNamespace: MemoryNamespace,
    ): String =
        buildJsonObject {
            put("status", "completed")
            put("default_namespace", defaultNamespace.value)
            put("namespace_count", summaries.size)
            putJsonArray("namespaces") {
                summaries.forEach { summary ->
                    add(buildJsonObject {
                        put("namespace", summary.namespace.value)
                        put("display_name", summary.displayName)
                        put("kind", summary.kind.name)
                        put("is_default", summary.namespace == defaultNamespace)
                        put("last_updated_at", summary.lastUpdatedAt?.toString() ?: "")
                        put(
                            "counts",
                            buildJsonObject {
                                put("total_items", summary.counts.totalItems)
                                put("predicate_definitions", summary.counts.predicateDefinitions)
                                put("sources", summary.counts.sources)
                                put("runs", summary.counts.runs)
                                put("entities", summary.counts.entities)
                                put("claims", summary.counts.claims)
                                put("notes", summary.counts.notes)
                                put("actionItems", summary.counts.actionItems)
                                put("profiles", summary.counts.profiles)
                                put("episodes", summary.counts.episodes)
                            }
                        )
                    })
                }
            }
        }.toString()

    fun failureJsonString(reason: String): String =
        buildJsonObject {
            put("status", "failed")
            put("reason", reason)
        }.toString()
}

private fun MemoryRun.toStatusJson(
    descendants: List<MemoryRun>,
    maxDepth: Int,
    depth: Int = 0,
): kotlinx.serialization.json.JsonObject {
    val descendantsById = descendants.associateBy { it.id }
    val childrenByParent = descendants.groupBy { it.parentRunId }
    val directChildren = (
        childrenByParent[id].orEmpty() +
            childRunIds.mapNotNull(descendantsById::get)
        ).distinctBy { it.id }

    return buildJsonObject {
        put("id", id.value)
        put("namespace", namespace.value)
        put("type", runType.name)
        put("trigger_mode", triggerMode.name)
        put("run_status", status.name)
        parentRunId?.let { put("parent_run_id", it.value) }
        put("summary", summary.shortForMemoryToolResult())
        putJsonArray("source_ids") {
            sourceIds.forEach { add(JsonPrimitive(it.value)) }
        }
        putJsonArray("child_run_ids") {
            childRunIds.forEach { add(JsonPrimitive(it.value)) }
        }
        progress?.let { progress ->
            put(
                "progress",
                buildJsonObject {
                    put("total_units", progress.totalUnits)
                    put("completed_units", progress.completedUnits)
                    put("failed_units", progress.failedUnits)
                    progress.currentUnitLabel?.let { put("current_unit_label", it.shortForMemoryToolResult()) }
                    progress.currentSourceId?.let { put("current_source_id", it.value) }
                }
            )
        }
        retrievalBudget?.let { budget ->
            put(
                "retrieval_budget",
                buildJsonObject {
                    put("claims", budget.claims)
                    put("notes", budget.notes)
                    put("actionItems", budget.actionItems)
                    put("sources", budget.sources)
                    put("episodes", budget.episodes)
                }
            )
        }
        promptName?.let { put("prompt_name", it) }
        promptVersion?.let { put("prompt_version", it) }
        modelName?.let { put("model_name", it) }
        inputHash?.let { put("input_hash", it) }
        val visibleMetadata = JsonObject(metadata - MEMORY_OPERATION_REQUEST_METADATA_KEY)
        if (visibleMetadata.isNotEmpty()) {
            put("metadata", visibleMetadata)
        }
        output?.let { put("output", it) }
        put("applied_ops_count", appliedOps.size)
        put("repair_actions_count", repairActions.size)
        latencyMs?.let { put("latency_ms", it) }
        tokenInput?.let { put("token_input", it) }
        tokenOutput?.let { put("token_output", it) }
        if (llmCalls.isNotEmpty()) {
            put("llm_calls_count", llmCalls.size)
            put("llm_latency_ms", llmCalls.sumOf { it.latencyMs })
            putJsonArray("slowest_llm_calls") {
                llmCalls.sortedByDescending { it.latencyMs }
                    .take(10)
                    .forEach { call ->
                        add(
                            buildJsonObject {
                                put("stage", call.stageName)
                                put("attempt", call.attempt)
                                put("status", call.status.name)
                                put("latency_ms", call.latencyMs)
                                put("started_at", call.startedAt.toString())
                                put("completed_at", call.completedAt.toString())
                                call.timeoutMs?.let { put("timeout_ms", it) }
                                call.finishReason?.let { put("finish_reason", it) }
                                call.totalInputTokens?.let { put("total_input_tokens", it) }
                                call.totalOutputTokens?.let { put("total_output_tokens", it) }
                                call.totalTokens?.let { put("total_tokens", it) }
                                if (call.logContext.isNotBlank()) {
                                    put("context", call.logContext.shortForMemoryToolResult(500))
                                }
                                call.errorText?.let { put("error", it.shortForMemoryToolResult(500)) }
                            }
                        )
                    }
            }
        }
        errorText?.let { put("error_text", it.shortForMemoryToolResult(1_000)) }
        put("created_at", createdAt.toString())
        startedAt?.let { put("started_at", it.toString()) }
        completedAt?.let { put("completed_at", it.toString()) }
        if (depth < maxDepth) {
            putJsonArray("children") {
                directChildren.forEach { child ->
                    add(child.toStatusJson(descendants, maxDepth, depth + 1))
                }
            }
        } else if (directChildren.isNotEmpty()) {
            put("children_truncated", directChildren.size)
        }
    }
}

private fun MemoryRun.Status.isTerminal(): Boolean =
    this == MemoryRun.Status.NEEDS_INPUT ||
        this == MemoryRun.Status.SUCCESS ||
        this == MemoryRun.Status.FAILED ||
        this == MemoryRun.Status.PARTIAL ||
        this == MemoryRun.Status.CANCELLED

private fun List<MemoryUpdateBatch>.aggregateCountsJson() =
    buildJsonObject {
        put("predicate_definitions", sumOf { it.predicateDefinitions.size })
        put("sources", sumOf { it.sources.size })
        put("runs", sumOf { it.runs.size })
        put("entities", sumOf { it.entities.size })
        put("claims", sumOf { it.claims.size })
        put("notes", sumOf { it.notes.size })
        put("actionItems", sumOf { it.actionItems.size })
        put("profiles", sumOf { it.profiles.size })
        put("episodes", sumOf { it.episodes.size })
        put("embeddings", sumOf { it.embeddings.size })
    }

private fun MemoryUpdateBatch.toCountsJson() =
    buildJsonObject {
        put("predicate_definitions", predicateDefinitions.size)
        put("sources", sources.size)
        put("runs", runs.size)
        put("entities", entities.size)
        put("claims", claims.size)
        put("notes", notes.size)
        put("actionItems", actionItems.size)
        put("profiles", profiles.size)
        put("episodes", episodes.size)
        put("embeddings", embeddings.size)
    }

private fun String.shortForMemoryToolResult(maxLength: Int = 300): String {
    val singleLine = replace(Regex("\\s+"), " ").trim()
    return if (singleLine.length <= maxLength) {
        singleLine
    } else {
        singleLine.take(maxLength - 3) + "..."
    }
}
