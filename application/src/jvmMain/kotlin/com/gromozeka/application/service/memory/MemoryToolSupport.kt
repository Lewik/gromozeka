package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryReadResult
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
const val MEMORY_RUN_STATUS_TOOL_NAME = "memory_run_status"
const val MEMORY_QUEUE_STATUS_TOOL_NAME = "memory_queue_status"
const val MEMORY_MAINTENANCE_TOOL_NAME = "memory_maintenance"
const val MEMORY_LIST_NAMESPACES_TOOL_NAME = "memory_list_namespaces"

fun List<AiToolCallback>.withoutMemoryManagementTools(): List<AiToolCallback> =
    filterNot { tool -> tool.definition.name in memoryManagementToolNames }

private val memoryManagementToolNames = setOf(
    MEMORY_REMEMBER_TOOL_NAME,
    MEMORY_ENRICH_CONTEXT_TOOL_NAME,
    MEMORY_RUN_STATUS_TOOL_NAME,
    MEMORY_QUEUE_STATUS_TOOL_NAME,
    MEMORY_MAINTENANCE_TOOL_NAME,
    MEMORY_LIST_NAMESPACES_TOOL_NAME,
)

data class MemoryMaintenanceToolResult(
    val action: String,
    val targetKind: String,
    val targetValue: String,
    val namespace: MemoryNamespace,
    val conversationId: Conversation.Id,
    val summary: String,
    val memoryBatch: MemoryUpdateBatch,
    val details: JsonObject,
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
            putJsonArray("tasks") {
                result.memoryBatch.tasks.take(8).forEach { task ->
                    add(buildJsonObject {
                        put("id", task.id.value)
                        put("status", task.status.name)
                        put("priority", task.priority.name)
                        put("title", task.title.shortForMemoryToolResult())
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
            put("answer_mode", result.plan.answerMode.name)
            put("retrieved_count", result.retrievedHits.size)
            put(
                "usage_guidance",
                "Selected memory is the strongest available remembered context for the target context. Use it unless it is clearly irrelevant, insufficient, stale, internally conflicting, or contradicted by the current user message. Do not replace selected memory with guesses or general defaults. For exact quote, exact wording, source, or when-said questions, prefer complete source text from memory_context over shorter evidence excerpts."
            )
            put("memory_context", result.runtimePrompt ?: "No relevant persisted memory was retrieved for the target context.")
            putJsonArray("selected_refs") {
                result.trace.selectedHits.take(16).forEach { hit ->
                    add(buildJsonObject {
                        put("type", hit.ref.type.name)
                        put("id", hit.ref.id)
                        put("summary", hit.summary.shortForMemoryToolResult())
                        hit.predicate?.let { put("predicate", it) }
                        hit.status?.let { put("status", it) }
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

    fun queueStatusJsonString(status: MemoryDocumentIngestQueueStatus): String =
        buildJsonObject {
            put("status", "completed")
            put("kind", "memory_document_ingest_queue")
            put("pending_jobs", status.pendingJobs)
            put("has_active_job", status.activeJob != null)
            status.activeJob?.let { active ->
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
            put("total_enqueued_jobs", status.totalEnqueuedJobs)
            put("total_started_jobs", status.totalStartedJobs)
            put("total_completed_jobs", status.totalCompletedJobs)
            put("total_fatally_failed_jobs", status.totalFatallyFailedJobs)
            put("worker_count", 1)
            put("process_local", true)
            put("durable_resume", false)
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
                                put("tasks", summary.counts.tasks)
                                put("profiles", summary.counts.profiles)
                                put("episodes", summary.counts.episodes)
                            }
                        )
                    })
                }
            }
        }.toString()

    fun maintenanceResultJsonString(result: MemoryMaintenanceToolResult): String =
        buildJsonObject {
            put("status", "completed")
            put("action", result.action)
            put("target_kind", result.targetKind)
            put("target_value", result.targetValue)
            put("namespace", result.namespace.value)
            put("conversation_id", result.conversationId.value)
            put("summary", result.summary.shortForMemoryToolResult())
            put("counts", result.memoryBatch.toCountsJson())
            put("details", result.details)
            putJsonArray("runs") {
                result.memoryBatch.runs.forEach { run ->
                    add(buildJsonObject {
                        put("id", run.id.value)
                        put("type", run.runType.name)
                        put("summary", run.summary.shortForMemoryToolResult())
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
                    put("tasks", budget.tasks)
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
        put("tasks", sumOf { it.tasks.size })
        put("profiles", sumOf { it.profiles.size })
        put("episodes", sumOf { it.episodes.size })
    }

private fun MemoryUpdateBatch.toCountsJson() =
    buildJsonObject {
        put("predicate_definitions", predicateDefinitions.size)
        put("sources", sources.size)
        put("runs", runs.size)
        put("entities", entities.size)
        put("claims", claims.size)
        put("notes", notes.size)
        put("tasks", tasks.size)
        put("profiles", profiles.size)
        put("episodes", episodes.size)
    }

private fun String.shortForMemoryToolResult(maxLength: Int = 300): String {
    val singleLine = replace(Regex("\\s+"), " ").trim()
    return if (singleLine.length <= maxLength) {
        singleLine
    } else {
        singleLine.take(maxLength - 3) + "..."
    }
}
