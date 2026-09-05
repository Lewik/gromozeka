package com.gromozeka.worker.runtime

import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.time.Instant

@Serializable
data class WorkerLocationConfiguration(
    val enabled: Boolean = false,
    val intervalSeconds: Int = 60,
    val minimumDistanceMeters: Int = 25,
) {
    init {
        require(intervalSeconds in 1..86_400) { "Location interval must be between 1 and 86400 seconds" }
        require(minimumDistanceMeters in 0..10_000) { "Location distance must be between 0 and 10000 meters" }
    }
}

@Serializable
data class WorkerLocationSample(val observedAt: Instant, val location: DeviceStateEvent.Location)

fun isWorkerLocationFresh(ageNanos: Long, elapsedRequestNanos: Long, maximumAgeSeconds: Int): Boolean {
    require(maximumAgeSeconds in 0..3600)
    return ageNanos >= 0 && ageNanos <= if (maximumAgeSeconds == 0) elapsedRequestNanos
    else maximumAgeSeconds * 1_000_000_000L
}

class WorkerLocationTool(private val locate: suspend (maximumAgeSeconds: Int) -> WorkerLocationSample) : WorkerTool {
    override val descriptor = AiToolDescriptor(
        definition = AiToolDefinition(
            name = "grz_get_current_location",
            description = "Get the selected Worker's location with local location-sharing opt-in. By default waits for a new measurement; never silently substitutes an old position. max_age_seconds optionally permits a cached measurement up to that age (maximum 3600). timeout_seconds bounds acquisition, default 30, maximum 120. Returns measurement time and accuracy, not a guarantee of exact position. Disabled sharing, denied permission, unavailable sensor or no fix fail explicitly. Delivery TTL and execution timeout belong to the Worker request, separately from acquisition timeout. Use get_device_state or query_state_history for already reported positions without activating a sensor.",
            inputSchema = """{"type":"object","properties":{"max_age_seconds":{"type":"integer","minimum":0,"maximum":3600,"default":0},"timeout_seconds":{"type":"integer","minimum":1,"maximum":120,"default":30}},"additionalProperties":false}""",
        ),
        metadata = AiToolMetadata(
            executionScope = AiToolExecutionScope.WORKER,
            requiredRuntimeCapabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
            visibleToMemoryPipeline = false,
        ),
    )

    override suspend fun execute(arguments: JsonElement): JsonElement {
        require(arguments is JsonObject && arguments.keys.all { it in setOf("max_age_seconds", "timeout_seconds") }) {
            "Location expects only max_age_seconds and timeout_seconds"
        }
        fun integer(name: String, default: Int, range: IntRange): Int {
            if (name !in arguments) return default
            val value = arguments[name] as? JsonPrimitive
            require(value != null && !value.isString && value.intOrNull in range) { "$name must be an integer in $range" }
            return requireNotNull(value.intOrNull)
        }
        val age = integer("max_age_seconds", 0, 0..3600)
        val timeout = integer("timeout_seconds", 30, 1..120)
        val sample = withTimeoutOrNull(timeout * 1000L) { locate(age) }
        require(sample != null) { "No location meeting the requested freshness was acquired before timeout" }
        return Json.encodeToJsonElement(sample)
    }
}
