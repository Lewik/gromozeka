package com.gromozeka.infrastructure.ai.claude

import kotlinx.serialization.json.*

internal data class ClaudeCodeCompactionCheckpoint(
    val boundary: JsonObject,
    val replayState: JsonObject,
)

internal class ClaudeCodeReplayState(state: JsonObject? = null) {
    private val messages = state?.let(::readMessages)?.toMutableList() ?: mutableListOf()
    private var pendingBoundary: JsonObject? = null
    private var retainedIds: List<String>? = null
    private val completedBoundaries = mutableListOf<JsonObject>()

    fun accept(event: JsonObject) {
        when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "system" -> if (event["subtype"]?.jsonPrimitive?.contentOrNull == "compact_boundary") {
                check(pendingBoundary == null) { "Claude Code emitted a compaction boundary without its summary" }
                pendingBoundary = event
                val metadata = event["compact_metadata"]?.jsonObject
                    ?: error("Claude Code compaction metadata is missing")
                retainedIds = metadata["preserved_messages"]?.jsonObject?.get("all_uuids")?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    ?: metadata["preserved_segment"]?.jsonObject?.let { segment ->
                        val head = messages.indexOfFirst { it.id() == segment["head_uuid"]?.jsonPrimitive?.content }
                        val tail = messages.indexOfLast { it.id() == segment["tail_uuid"]?.jsonPrimitive?.content }
                        require(head >= 0 && tail >= head) { "Claude Code retained transcript segment is unavailable" }
                        messages.subList(head, tail + 1).map { it.id() }
                    }.orEmpty()
            }
            "user", "assistant" -> {
                if (event["parent_tool_use_id"]?.let { it !is JsonNull } == true) return
                val boundary = pendingBoundary
                if (boundary != null && event["type"]?.jsonPrimitive?.content == "user" &&
                    event["isSynthetic"]?.jsonPrimitive?.booleanOrNull == true
                ) {
                    val ids = requireNotNull(retainedIds).filter { it != event.id() }
                    // The CLI also retains internal attachment UUIDs that are not emitted as SDK messages.
                    val retainedSet = ids.toSet()
                    val retained = messages.filter { it.id() in retainedSet }
                    messages.clear()
                    messages += event
                    messages += retained
                    completedBoundaries += boundary
                    pendingBoundary = null
                    retainedIds = null
                } else {
                    require(boundary == null) { "Claude Code compaction summary was not delivered before continuation" }
                    messages.removeAll { it.id() == event.id() }
                    messages += event
                }
            }
        }
    }

    fun snapshot(): JsonObject = buildJsonObject {
        put("kind", KIND)
        put("messages", JsonArray(messages))
    }

    fun ensureComplete() {
        check(pendingBoundary == null) { "Claude Code compaction summary was not delivered" }
    }

    fun checkpointBeforeAssistantResponse(): ClaudeCodeCompactionCheckpoint? =
        completedBoundaries.lastOrNull()?.let { boundary ->
            ClaudeCodeCompactionCheckpoint(boundary, beforeAssistantResponse())
        }

    private fun beforeAssistantResponse(): JsonObject = buildJsonObject {
        put("kind", KIND)
        put("messages", JsonArray(messages.dropLastWhile { it["type"]?.jsonPrimitive?.contentOrNull == "assistant" }))
    }

    companion object {
        private const val KIND = "claude_code_transcript"

        fun readMessages(state: JsonObject): List<JsonObject> {
            require(state["kind"]?.jsonPrimitive?.contentOrNull == KIND) {
                "Claude Code compaction does not contain a replayable transcript"
            }
            return state.getValue("messages").jsonArray.map { it.jsonObject }
        }

        private fun JsonObject.id(): String = getValue("uuid").jsonPrimitive.content
    }
}

internal class ClaudeCodeSessionUnavailableException(message: String) : IllegalStateException(message)
