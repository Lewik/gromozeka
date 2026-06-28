package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryReadResult
import com.gromozeka.domain.model.memory.MemoryReadTrace
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSummary
import com.gromozeka.domain.model.memory.MemoryRun
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

        return buildJsonObject {
            put("status", "completed")
            put("decision", result.routeDecision.decision.name)
            putJsonArray("memory_types") {
                result.routeDecision.memoryTypes.map { it.name }.sorted().forEach { add(JsonPrimitive(it)) }
            }
            put("salience", result.routeDecision.salience)
            put("reason", result.routeDecision.reason)
            put("source_id", result.sourceBatch.sources.firstOrNull()?.id?.value ?: "")
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

    internal fun rememberDocumentResultJsonString(result: MemoryRememberDocumentResult): String =
        buildJsonObject {
            put("status", if (result.sectionResults.size == result.sections.size) "completed" else "partial")
            put("document_type", result.documentType.name)
            put("input_kind", result.inputKind.name)
            put("title", result.title.orEmpty())
            put("source_ref", result.sourceRef)
            put("parent_source_id", result.parentSourceId)
            put("sections_total", result.sections.size)
            put("sections_processed", result.sectionResults.size)
            put("sections_failed", result.sections.size - result.sectionResults.size)
            put("counts", result.sectionResults.map { it.memoryBatch }.aggregateCountsJson())
            put("sections", result.sections.toSectionSummaryJson())
            putJsonArray("section_results") {
                result.sectionResults.take(24).forEach { sectionResult ->
                    add(buildJsonObject {
                        put("source_id", sectionResult.sourceBatch.sources.firstOrNull()?.id?.value.orEmpty())
                        put("decision", sectionResult.routeDecision.decision.name)
                        putJsonArray("memory_types") {
                            sectionResult.routeDecision.memoryTypes.map { it.name }.sorted().forEach { add(JsonPrimitive(it)) }
                        }
                        put("counts", sectionResult.memoryBatch.toCountsJson())
                        put("reason", sectionResult.routeDecision.reason.shortForMemoryToolResult())
                    })
                }
            }
        }.toString()

    internal fun rememberDocumentQueuedResultJsonString(result: MemoryRememberDocumentQueuedResult): String =
        buildJsonObject {
            put("status", "queued")
            put("run_id", result.runId)
            put("document_type", result.documentType.name)
            put("input_kind", result.inputKind.name)
            put("title", result.title.orEmpty())
            put("source_ref", result.sourceRef)
            put("parent_source_id", result.parentSourceId)
            put("sections_total", result.sections.size)
            put("queue_size", result.queueSize)
            put("message", "Document ingest was accepted and will continue in the memory document queue.")
            put("sections", result.sections.toSectionSummaryJson())
        }.toString()

    fun maintenanceQueuedResultJsonString(result: MemoryMaintenanceQueuedResult): String =
        buildJsonObject {
            put("status", "queued")
            put("run_id", result.runId.value)
            put("action", result.action.toolName)
            put("target_kind", result.targetKind)
            put("target_value", result.targetValue)
            put("namespace", result.namespace.value)
            put("conversation_id", result.conversationId.value)
            put("queue_size", result.queueSize)
            put("message", "Memory maintenance was accepted and will continue in the memory maintenance queue.")
        }.toString()

    fun runStatusJsonString(
        rootRun: MemoryRun,
        descendants: List<MemoryRun>,
        maxDepth: Int,
    ): String =
        buildJsonObject {
            put("status", "completed")
            put("run_id", rootRun.id.value)
            put("max_depth", maxDepth)
            put("descendant_count", descendants.size)
            put("run", rootRun.toStatusJson(descendants, maxDepth))
        }.toString()

    fun queueStatusJsonString(
        documentStatus: MemoryDocumentIngestQueueStatus,
        maintenanceStatus: MemoryMaintenanceQueueStatus,
        embeddingStatus: MemoryEmbeddingIndexStatus,
    ): String =
        buildJsonObject {
            put("status", "completed")
            put("kind", "memory_work_queues")
            put("pending_jobs", documentStatus.pendingJobs + maintenanceStatus.pendingJobs)
            put("has_active_job", documentStatus.activeJob != null || maintenanceStatus.activeJob != null)
            put(
                "document_ingest",
                buildJsonObject {
                    put("pending_jobs", documentStatus.pendingJobs)
                    put("has_active_job", documentStatus.activeJob != null)
                    documentStatus.activeJob?.let { active ->
                        put(
                            "active_job",
                            buildJsonObject {
                                put("run_id", active.runId.value)
                                put("parent_source_id", active.parentSourceId.value)
                                put("source_ref", active.sourceRef)
                                put("sections_total", active.sectionsTotal)
                                put("started_at", active.startedAt.toString())
                            }
                        )
                    }
                    put("total_enqueued_jobs", documentStatus.totalEnqueuedJobs)
                    put("total_started_jobs", documentStatus.totalStartedJobs)
                    put("total_completed_jobs", documentStatus.totalCompletedJobs)
                    put("total_fatally_failed_jobs", documentStatus.totalFatallyFailedJobs)
                    put("worker_count", 1)
                }
            )
            put(
                "maintenance",
                buildJsonObject {
                    put("pending_jobs", maintenanceStatus.pendingJobs)
                    put("has_active_job", maintenanceStatus.activeJob != null)
                    maintenanceStatus.activeJob?.let { active ->
                        put(
                            "active_job",
                            buildJsonObject {
                                put("run_id", active.runId.value)
                                put("action", active.action.toolName)
                                put("target_kind", active.targetKind)
                                put("target_value", active.targetValue)
                                put("namespace", active.namespace.value)
                                put("conversation_id", active.conversationId.value)
                                put("started_at", active.startedAt.toString())
                            }
                        )
                    }
                    put("total_enqueued_jobs", maintenanceStatus.totalEnqueuedJobs)
                    put("total_started_jobs", maintenanceStatus.totalStartedJobs)
                    put("total_completed_jobs", maintenanceStatus.totalCompletedJobs)
                    put("total_fatally_failed_jobs", maintenanceStatus.totalFatallyFailedJobs)
                    put("worker_count", 1)
                }
            )
            put(
                "embeddings",
                buildJsonObject {
                    put("mode", "synchronous_write_path")
                    put("pending_jobs", 0)
                    put("has_active_job", false)
                    put("total_embedding_requests", embeddingStatus.totalEmbeddingRequests)
                    put("total_embedded_items", embeddingStatus.totalEmbeddedItems)
                    put("total_rebuilds", embeddingStatus.totalRebuilds)
                    put("total_failed_requests", embeddingStatus.totalFailedRequests)
                    put("worker_count", 0)
                }
            )
            put("worker_count", 2)
            put("process_local", true)
            put("durable_resume", false)
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
        configuredDefaultNamespace: MemoryNamespace?,
    ): String =
        buildJsonObject {
            put("status", "completed")
            put("configured_default_namespace", configuredDefaultNamespace?.value ?: "")
            put("namespace_count", summaries.size)
            putJsonArray("namespaces") {
                summaries.forEach { summary ->
                    add(buildJsonObject {
                        put("namespace", summary.namespace.value)
                        put("display_name", summary.displayName)
                        put("kind", summary.kind.name)
                        put("is_configured_default", summary.namespace == configuredDefaultNamespace)
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
        if (metadata.isNotEmpty()) {
            put("metadata", metadata)
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
