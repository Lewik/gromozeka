package com.gromozeka.application.service

import com.gromozeka.domain.tool.AiToolContextField.*
import com.gromozeka.domain.tool.withContextRequirements

import com.gromozeka.domain.model.NamedSecret
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.PreloadedServerToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredUserId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Component

@Component
class ListNamedSecretsToolCallback(
    private val namedSecretService: NamedSecretApplicationService,
) : AiToolCallback {
    override val metadata = PreloadedServerToolMetadata.withContextRequirements(USER)
    override val definition = AiToolDefinition(
        name = "grz_list_secrets",
        description = "List the current user's durable named secrets without revealing their values. Use returned secret:// references as exact tool argument values, or as values in grz_execute_command.secret_env. Never put them directly in shell command text.",
        inputSchema = """{"type":"object","additionalProperties":false,"properties":{}}""",
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        buildJsonArray {
            namedSecretService.list(context.requiredUserId()).forEach { secret ->
                add(buildJsonObject {
                    put("name", secret.name)
                    put("description", secret.description)
                    put("reference", secret.reference)
                    put("updated_at", secret.updatedAt.toString())
                })
            }
        }.toString()
    }
}
