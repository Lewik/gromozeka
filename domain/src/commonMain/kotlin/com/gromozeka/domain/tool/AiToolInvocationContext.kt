package com.gromozeka.domain.tool

import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerRequestPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Data dependencies, not permissions. Identity and runtime-only fields cannot be supplied by tool arguments. */
@Serializable
enum class AiToolContextField(val contextKey: String, val selector: String? = null) {
    USER(TOOL_CONTEXT_USER_ID),
    PROJECT(TOOL_CONTEXT_PROJECT_ID, "project_id"),
    CONVERSATION(TOOL_CONTEXT_CONVERSATION_ID, "conversation_id"),
    AGENT(TOOL_CONTEXT_AGENT_DEFINITION_ID, "agent_id"),
    THREAD(TOOL_CONTEXT_THREAD_ID),
    MEMORY_NAMESPACE(TOOL_CONTEXT_MEMORY_NAMESPACE),
    TARGET_MESSAGE(TOOL_CONTEXT_TARGET_MESSAGE_ID),
    TOOL_CALL_ID("toolCallId"),
}

@Serializable
data class AiToolContextRequirements(
    val required: Set<AiToolContextField> = emptySet(),
    /** Used conditionally by an operation. Its business validation still decides when it is required. */
    val optional: Set<AiToolContextField> = emptySet(),
) {
    init { require(required.intersect(optional).isEmpty()) { "Context fields cannot be both required and optional" } }
    val fields: Set<AiToolContextField> get() = required + optional
}

/** Explicit trusted bindings of an entry point. A Conversation does not imply a workspace binding. */
data class AiToolContextBindings(
    val fields: Set<AiToolContextField> = emptySet(),
    val worker: Boolean = false,
    val workspaceMount: Boolean = false,
) {
    companion object {
        val ConversationRuntime = AiToolContextBindings(AiToolContextField.entries.toSet())
        fun from(context: ToolExecutionContext?) = AiToolContextBindings(
            fields = AiToolContextField.entries.filterTo(mutableSetOf()) { !context?.getString(it.contextKey).isNullOrBlank() },
            worker = !context?.getString(TOOL_CONTEXT_WORKER_ID).isNullOrBlank(),
            workspaceMount = !context?.getString(TOOL_CONTEXT_WORKSPACE_MOUNT_ID).isNullOrBlank(),
        )
    }
}

const val AI_TOOL_INVOCATION_CONTEXT_FIELD = "_grz"
private val timeoutFields = setOf("delivery_ttl_seconds", "execution_timeout_seconds", "wait_timeout_seconds")

private fun AiToolMetadata.selectorFields(): Set<AiToolContextField> = buildSet {
    addAll(contextRequirements.fields.filter { it.selector != null })
    if (AiToolContextField.THREAD in contextRequirements.fields) add(AiToolContextField.CONVERSATION)
}

/** Generate the public envelope from the same requirements used at invocation. Business schema stays intact. */
fun AiToolDescriptor.withInvocationContext(bindings: AiToolContextBindings): AiToolDescriptor {
    val schema = Json.parseToJsonElement(definition.inputSchema).jsonObject
    val properties = schema["properties"]?.jsonObject.orEmpty()
    require(AI_TOOL_INVOCATION_CONTEXT_FIELD !in properties && "execution_target" !in properties) {
        "Tool business schema declares a reserved invocation field"
    }
    val fields = metadata.selectorFields().filter { it !in bindings.fields }
    val requiredFields = metadata.contextRequirements.required.let {
        if (AiToolContextField.THREAD in it) it + AiToolContextField.CONVERSATION else it
    }
    val worker = metadata.executionScope == AiToolExecutionScope.WORKER
    val workspace = metadata.executionScope == AiToolExecutionScope.WORKSPACE
    val envelopeProperties = buildJsonObject {
        fields.forEach { field -> put(field.selector!!, identifierSchema("Calling ${field.name.lowercase()} context; not the business operation's target.")) }
        if (worker && !bindings.worker) put("worker_id", identifierSchema("Exact compatible Worker for this call."))
        if (workspace && !bindings.workspaceMount) put("workspace_mount_id", identifierSchema("Exact filesystem mount; determines its Worker and root path."))
        if (worker || workspace) timeoutFields.forEach { name ->
            put(name, buildJsonObject {
                put("type", "integer"); put("minimum", 1)
                put("maximum", if (name == "execution_timeout_seconds") 86400 else 604800)
                put("description", when (name) {
                    "delivery_ttl_seconds" -> "Delivery lifetime before execution starts. Default 30 seconds. Expired requests never start."
                    "execution_timeout_seconds" -> "Execution limit after starting. Default 1800 seconds."
                    else -> "Call wait, not cancellation. Default delivery plus execution limits, capped at 7 days. Inspect the returned request ID rather than resubmitting."
                })
            })
        }
    }
    if (envelopeProperties.isEmpty()) return this
    val envelopeRequired = buildSet {
        fields.filter { it in requiredFields }.forEach { field ->
            // A selected Conversation or mount supplies its project; never require duplicate selectors.
            if (field != AiToolContextField.PROJECT ||
                (AiToolContextField.CONVERSATION !in requiredFields && !workspace)) add(field.selector!!)
        }
        if (worker && !bindings.worker) add("worker_id")
        if (workspace && !bindings.workspaceMount) add("workspace_mount_id")
    }
    val envelope = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        put("description", "Gromozeka invocation context. Authenticated identity is supplied by the transport, never by arguments.")
        put("properties", envelopeProperties)
        if (envelopeRequired.isNotEmpty()) put("required", JsonArray(envelopeRequired.sorted().map(::JsonPrimitive)))
    }
    val required = schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet() +
        if (envelopeRequired.isNotEmpty()) setOf(AI_TOOL_INVOCATION_CONTEXT_FIELD) else emptySet()
    return copy(definition = definition.copy(inputSchema = JsonObject(schema + mapOf(
        "properties" to JsonObject(properties + (AI_TOOL_INVOCATION_CONTEXT_FIELD to envelope)),
        "required" to JsonArray(required.sorted().map(::JsonPrimitive)),
    )).toString()))
}

private fun identifierSchema(description: String) = buildJsonObject {
    put("type", "string"); put("minLength", 1); put("maxLength", 128); put("description", description)
}

data class AiToolInvocationInput(
    val arguments: JsonObject,
    val selectors: Map<AiToolContextField, String>,
    val executionTarget: AiToolExecutionTarget?,
) {
    fun requireResolvedSelectors(context: ToolExecutionContext?) {
        selectors.forEach { (field, value) ->
            require(context?.getString(field.contextKey) == value) { "Invocation selector has not been resolved: ${field.selector}" }
        }
    }
}

/** Pure parsing only: selectors are NOT promoted into trusted context here. Resolution is Server-owned. */
fun AiToolMetadata.parseInvocationInput(input: JsonElement, bound: ToolExecutionContext? = null): AiToolInvocationInput {
    val arguments = input as? JsonObject ?: error("Tool input must be an object")
    require("execution_target" !in arguments) { "execution_target is not supported; use _grz" }
    val envelope = if (AI_TOOL_INVOCATION_CONTEXT_FIELD in arguments)
        arguments[AI_TOOL_INVOCATION_CONTEXT_FIELD] as? JsonObject ?: error("_grz must be an object")
    else JsonObject(emptyMap())
    val selectable = selectorFields()
    val worker = executionScope == AiToolExecutionScope.WORKER
    val workspace = executionScope == AiToolExecutionScope.WORKSPACE
    val allowed = selectable.mapNotNull { it.selector }.toSet() + when {
        worker -> timeoutFields + "worker_id"
        workspace -> timeoutFields + "workspace_mount_id"
        else -> emptySet()
    }
    require(envelope.keys.all { it in allowed } && (allowed.isNotEmpty() || AI_TOOL_INVOCATION_CONTEXT_FIELD !in arguments)) {
        "Unsupported _grz field for this tool"
    }
    fun id(name: String): String? {
        if (name !in envelope) return null
        val value = envelope[name] as? JsonPrimitive
        require(value != null && value.isString && value.content.isNotBlank() && value.content.length <= 128 && value.content == value.content.trim()) {
            "Invalid _grz.$name"
        }
        return value.content
    }
    val selectors = selectable.mapNotNull { field -> id(field.selector!!)?.let { field to it } }.toMap()
    selectors.forEach { (field, value) ->
        bound?.getString(field.contextKey)?.let { require(it == value) { "_grz cannot override bound ${field.selector}" } }
    }
    fun targetId(name: String, key: String): String {
        val selected = id(name)
        val binding = bound?.getString(key)?.takeIf { it.isNotBlank() }
        require(selected == null || binding == null || selected == binding) { "_grz cannot override bound $name" }
        return selected ?: binding ?: error("_grz.$name is required")
    }
    val policy = if (envelope.keys.none { it in timeoutFields }) null else {
        fun seconds(name: String, defaultMillis: Long): Long {
            if (name !in envelope) return defaultMillis
            val value = envelope[name] as? JsonPrimitive
            val seconds = value?.takeUnless { it.isString }?.longOrNull
            val maximum = if (name == "execution_timeout_seconds") 86400L else 604800L
            require(seconds != null && seconds in 1..maximum) { "Invalid _grz.$name" }
            return seconds * 1000
        }
        val delivery = seconds("delivery_ttl_seconds", 30_000)
        val execution = seconds("execution_timeout_seconds", 1_800_000)
        WorkerRequestPolicy(deliveryTtlMillis = delivery, executionTimeoutMillis = execution,
            waitTimeoutMillis = seconds("wait_timeout_seconds", (delivery + execution).coerceAtMost(604_800_000)))
    }
    val target = when {
        worker -> AiToolExecutionTarget(workerId = ConversationRuntimeWorkerId(targetId("worker_id", TOOL_CONTEXT_WORKER_ID)), requestPolicy = policy)
        workspace -> AiToolExecutionTarget(workspaceMountId = WorkspaceMount.Id(targetId("workspace_mount_id", TOOL_CONTEXT_WORKSPACE_MOUNT_ID)), requestPolicy = policy)
        else -> null
    }
    return AiToolInvocationInput(JsonObject(arguments - AI_TOOL_INVOCATION_CONTEXT_FIELD), selectors, target)
}

/** Must run after trusted call-id injection and context resolution, before approval/callback effects. */
fun AiToolMetadata.requireInvocationContext(context: ToolExecutionContext?) {
    val required = contextRequirements.required + if (accessPolicy != null) setOf(AiToolContextField.USER) else emptySet()
    required.forEach { field ->
        require(!context?.getString(field.contextKey).isNullOrBlank()) { "Required tool context is missing: ${field.name.lowercase()}" }
    }
}

/** Declare minimum and conditional context at the authoring site, independent of execution scope. */
fun AiToolMetadata.withContextRequirements(
    vararg required: AiToolContextField,
    optional: Set<AiToolContextField> = emptySet(),
): AiToolMetadata = copy(contextRequirements = AiToolContextRequirements(required.toSet(), optional))
