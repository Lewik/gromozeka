package com.gromozeka.server

import com.gromozeka.application.service.ServerAiCallAdmission
import com.gromozeka.domain.model.ai.AiAdmissionSnapshot
import com.gromozeka.domain.tool.AiToolAccessPolicy
import com.gromozeka.domain.tool.AiToolCallbackContributor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service
import kotlin.time.Clock

@Service
internal class AiAdmissionToolContributor(
    toolFactory: ServerJsonToolFactory,
    admission: ServerAiCallAdmission,
) : AiToolCallbackContributor {
    override val callbacks = listOf(toolFactory.create(
        CONTROL_TOOL_SOURCE,
        name = "grz_ai_admission_status",
        description = "Read Server-wide waiting AI operations and active concurrency leases, including disconnected Worker owners. Contains routing metadata only, never prompts or responses. A disconnected Worker lease remains active until execution completion or journal recovery is confirmed.",
        readOnly = true,
        accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
    ) {
        buildJsonObject {
            put("readAt", Clock.System.now().toString())
            put("snapshot", Json.encodeToJsonElement(AiAdmissionSnapshot.serializer(), admission.snapshot))
        }
    })
}
