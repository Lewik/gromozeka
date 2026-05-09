package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult
import com.gromozeka.domain.model.memory.MemoryReadResult
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.tool.AiToolCallback
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

const val MEMORY_REMEMBER_TOOL_NAME = "memory_remember"
const val MEMORY_RECALL_TOOL_NAME = "memory_recall"

fun List<AiToolCallback>.withoutMemoryManagementTools(): List<AiToolCallback> =
    filterNot { tool ->
        tool.definition.name == MEMORY_REMEMBER_TOOL_NAME || tool.definition.name == MEMORY_RECALL_TOOL_NAME
    }

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

    fun recallResultJsonString(result: MemoryReadResult?): String {
        if (result == null) {
            return buildJsonObject {
                put("status", "failed")
                put("reason", "Runtime memory recall failed defensively; answer without recalled memory.")
            }.toString()
        }

        return buildJsonObject {
            put("status", "completed")
            put("need_memory", result.plan.needMemory)
            put("answer_mode", result.plan.answerMode.name)
            put("retrieved_count", result.retrievedHits.size)
            put("memory_context", result.runtimePrompt ?: "No relevant persisted memory was retrieved for the target message.")
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

    fun failureJsonString(reason: String): String =
        buildJsonObject {
            put("status", "failed")
            put("reason", reason)
        }.toString()
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
