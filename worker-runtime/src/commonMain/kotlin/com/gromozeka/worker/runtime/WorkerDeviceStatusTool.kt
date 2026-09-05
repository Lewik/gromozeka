package com.gromozeka.worker.runtime

import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.time.Instant

@Serializable
data class WorkerDeviceStatus(
    val observedAt: Instant,
    val device: DeviceStateEvent.DeviceInfo,
    val battery: DeviceStateEvent.Battery?,
    val airplaneMode: Boolean,
    val bluetoothEnabled: Boolean?,
    val availableStorageBytes: Long,
    val pendingEventCount: Int,
)

class WorkerDeviceStatusTool(private val inspect: suspend () -> WorkerDeviceStatus) : WorkerTool {
    override val descriptor = AiToolDescriptor(
        definition = AiToolDefinition(
            name = "grz_get_device_status",
            description = "Read current device information, battery, airplane mode, Bluetooth availability, app storage capacity and pending observation count on the selected Worker. Does not collect location, activate sensors or change device settings.",
            inputSchema = """{"type":"object","properties":{},"additionalProperties":false}""",
        ),
        metadata = AiToolMetadata(
            executionScope = AiToolExecutionScope.WORKER,
            requiredRuntimeCapabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
        ),
    )

    override suspend fun execute(arguments: JsonElement): JsonElement {
        require(arguments is JsonObject && arguments.isEmpty()) { "Device status expects an empty argument object" }
        return Json.encodeToJsonElement(inspect())
    }
}
