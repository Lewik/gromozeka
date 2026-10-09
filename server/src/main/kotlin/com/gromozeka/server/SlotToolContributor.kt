package com.gromozeka.server

import com.gromozeka.application.service.SlotApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.slot.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service

@Service
class SlotToolContributor(private val slots: SlotApplicationService, private val users: UserDirectoryService) : AiToolCallbackContributor {
    private val json = Json { encodeDefaults = true }
    override val callbacks: List<AiToolCallback> = listOf(
        tool("grz_slot_list", "List your development slots in this project, their exact mounts, held leases and pending requests. Numbers identify slots; leases belong to Conversations. This is cooperative coordination, not filesystem isolation.", "{}", emptyList()) { _, actor, conversation, _ ->
            json.encodeToString(slots.list(actor).filter { it.snapshot.slot.projectId == conversation.projectId })
        },
        tool("grz_slot_get", "Inspect a numbered development slot and its occupants/waiters in this project. Current saved state, not a fresh filesystem inspection. Lease IDs identify exact occupations, not agents or internal history threads.", NUMBER, listOf("slot_number")) { input, actor, conversation, _ ->
            json.encodeToString(slots.list(actor).singleOrNull { it.snapshot.slot.number == input.number() && it.snapshot.slot.projectId == conversation.projectId } ?: error("Slot unavailable"))
        },
        tool("grz_slot_manage", "Register, update or retire slot metadata. Register an explicitly prepared full clone through its existing workspace_mount_id. Does not create/copy/delete directories, run setup, repair Git or release leases. Retire requires no active leases or pending requests. Slot numbers are stable and never reused.", """{"action":{"type":"string","enum":["register","update","retire"]},"slot_number":{"type":"integer","minimum":1},"workspace_mount_id":{"type":"string"},"base_branch":{"type":"string"}}""", listOf("action")) { input, actor, conversation, _ ->
            val branch = input["base_branch"]?.jsonPrimitive?.content
            val result = when (input.string("action")) {
                "register" -> {
                    require("slot_number" !in input)
                    slots.register(actor, conversation.id, WorkspaceMount.Id(input.string("workspace_mount_id")), branch)
                }
                "update", "retire" -> {
                    require("workspace_mount_id" !in input)
                    slots.update(actor, conversation.id, input.number(), branch, input.string("action") == "retire")
                }
                else -> error("Unknown action")
            }
            json.encodeToString(result)
        },
        tool("grz_slot_acquire", "Submit a durable request for a numbered slot. ALWAYS returns acceptance, never waits in this tool, even when free. Runtime later notifies you of the granted lease and resumes work; do not poll. WRITE allows readers but excludes other writers. READ is shared and does not promise a snapshot. A Conversation may hold several different slots. Use the returned mount for commands; the Runtime assigns their slot marker and GRZ_SLOT automatically.", """{"slot_number":{"type":"integer","minimum":1},"access":{"type":"string","enum":["READ","WRITE"]}}""", listOf("slot_number", "access")) { input, actor, conversation, context ->
            val agent = AgentDefinition.Id(requireNotNull(context.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID)))
            val key = listOf(conversation.id.value, context.getString(TOOL_CONTEXT_THREAD_ID), context.getString("toolCallId") ?: error("Tool-call identity required")).joinToString(":")
            val request = slots.acquire(actor, conversation.id, agent, input.number(), SlotAccess.valueOf(input.string("access")), key)
            buildJsonObject { put("status", "accepted"); put("request_id", request.id); put("request", json.encodeToJsonElement(request)) }.toString()
        },
        tool("grz_slot_cancel_wait", "Cancel this Conversation's pending slot request. If granting won the race, returns the granted request; never implicitly releases its lease or stops commands.", """{"request_id":{"type":"string"}}""", listOf("request_id")) { input, actor, conversation, _ ->
            json.encodeToString(slots.cancel(actor, conversation.id, input.string("request_id")))
        },
        tool("grz_slot_release", "Return this Conversation's exact occupation of a numbered slot. lease_id comes from its grant/current state and protects later occupations from stale calls. Read-only checks may return released=false and a confirmation_id; fix the observations or call again with that issued ID to deliberately hand off as-is. No upfront force, automatic Git repair, process termination or mandatory drain. Confirmation acknowledges warnings; it does not require an unchanged directory. Unknown observations are not proof of cleanliness.", """{"slot_number":{"type":"integer","minimum":1},"lease_id":{"type":"string"},"confirmation_id":{"type":"string"}}""", listOf("slot_number", "lease_id")) { input, actor, conversation, _ ->
            json.encodeToString(slots.release(actor, conversation.id, input.number(), input.string("lease_id"), input["confirmation_id"]?.jsonPrimitive?.content))
        },
        tool("grz_slot_reclaim", "Request HUMAN confirmation to reclaim an exact lease from your personal catalog. Does not release anything by itself. The user must explicitly approve in the native Slots UI; there is no model-supplied confirmation boolean or tool to approve. Reclaim does not stop processes or alter files.", """{"slot_number":{"type":"integer","minimum":1},"lease_id":{"type":"string"}}""", listOf("slot_number", "lease_id")) { input, actor, conversation, _ ->
            val view = slots.list(actor).singleOrNull { it.snapshot.slot.number == input.number() && it.snapshot.slot.projectId == conversation.projectId } ?: error("Slot unavailable")
            require(view.snapshot.leases.any { it.id == input.string("lease_id") }) { "Active lease not found in this slot" }
            slots.prepareReclaim(actor, input.string("lease_id"))
            """{"status":"awaiting_human_confirmation","released":false}"""
        },
    )

    private fun tool(name: String, description: String, properties: String, required: List<String>,
        action: suspend (JsonObject, User, Conversation, ToolExecutionContext) -> String): AiToolCallback {
        val props = json.parseToJsonElement(properties).jsonObject
        return object : AiToolCallback {
            override val definition = AiToolDefinition(name, description, buildJsonObject {
                put("type", "object"); put("properties", props); put("required", JsonArray(required.map(::JsonPrimitive))); put("additionalProperties", false)
            }.toString())
            override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.SERVER,
                loadingPolicy = AiToolLoadingPolicy.ON_DEMAND, visibleToMemoryPipeline = false, logInput = false)
            override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
                val input = json.parseToJsonElement(toolInput).jsonObject
                require(input.keys.all { it in props } && required.all { it in input }) { "Unexpected or missing arguments" }
                val trusted = requireNotNull(context) { "Conversation context required" }
                val actor = users.findActiveById(trusted.requiredUserId()) ?: error("Active user required")
                val id = Conversation.Id(requireNotNull(trusted.getString(TOOL_CONTEXT_CONVERSATION_ID)))
                val conversation = slots.authorizeConversation(actor, id, if (name in setOf("grz_slot_list", "grz_slot_get")) ProjectPermission.READ else ProjectPermission.WRITE)
                trusted.cancellationSignal?.throwIfCancellationRequested()
                action(input, actor, conversation, trusted)
            }
        }
    }
    private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("$key must be a string")
    private fun JsonObject.number(): Long = getValue("slot_number").jsonPrimitive.let { require(!it.isString); it.long }.also { require(it > 0) }
    private companion object { const val NUMBER = """{"slot_number":{"type":"integer","minimum":1}}""" }
}
