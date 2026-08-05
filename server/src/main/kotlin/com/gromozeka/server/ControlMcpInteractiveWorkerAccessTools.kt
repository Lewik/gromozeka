package com.gromozeka.server

import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerAccessDeniedException
import com.gromozeka.domain.service.WorkerAccessService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
internal class ControlMcpInteractiveWorkerAccessTools(
    private val interactiveAccessService: InteractiveWorkerAccessService,
    private val workerAccessService: WorkerAccessService,
) : ControlMcpToolProvider {
    override val tools: List<ControlMcpTool> = interactiveAccessService.configuredWorkerId
        ?.let { listOf(interactiveAccessTool()) }
        ?: emptyList()

    private fun interactiveAccessTool(): ControlMcpTool = controlMcpTool(
        name = "grz_worker_interactive_access_get",
        description = "Get a reusable user-facing link for an interactive desktop attached to one Worker. " +
            "Present openUrl as a clickable link and do not open it for the user. The Server authorizes the user " +
            "and creates one-time desktop credentials only when the link is opened.",
        inputSchema = ControlMcpSchemas.objectSchema(
            properties = mapOf(
                "workerId" to ControlMcpSchemas.string("Exact Worker id."),
            ),
            required = listOf("workerId"),
        ),
        readOnly = true,
    ) { input ->
        val workerId = ConversationRuntimeWorkerId(input.requiredString("workerId"))
        val openUrl = interactiveAccessService.openUrl(workerId)
            ?: notFound("Interactive access", workerId.value)
        val worker = try {
            workerAccessService.requirePermission(
                actor = user,
                workerId = workerId,
                permission = WorkerPermission.USE,
            )
        } catch (_: WorkerAccessDeniedException) {
            throw ControlMcpToolException("forbidden", "Worker is unavailable or access is denied")
        }
        buildJsonObject {
            put("workerId", worker.id.value)
            put("displayName", worker.displayName)
            put("type", "interactive_desktop")
            put("openUrl", openUrl)
        }
    }
}
