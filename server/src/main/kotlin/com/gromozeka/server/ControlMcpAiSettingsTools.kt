package com.gromozeka.server

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.AiCatalogManagementService
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.SettingsService
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
internal class ControlMcpAiSettingsTools(
    private val aiConfigurationService: AiConfigurationService,
    private val aiCatalogManagementService: AiCatalogManagementService,
    private val settingsService: SettingsService,
) : ControlMcpToolProvider {
    override val tools: List<ControlMcpTool> = listOf(
        controlMcpTool(
            name = "grz_control_help",
            description = "Explain Gromozeka configuration ownership and safe Control MCP workflows.",
            readOnly = true,
        ) {
            buildJsonObject {
                put(
                    "guide",
                    """
                    Gromozeka Server is the source of truth for Projects, Workspaces, Agents, Prompts, Agent Skills, AI configuration, and external MCP definitions.
                    A Project is logical. A Workspace is a project resource. A Workspace Mount binds one Workspace to one Worker-local root path.
                    Agents are model and behavior configurations, not execution workers.
                    Bundled templates are blueprints only; copy their values into mutable server entities when needed.
                    Read current entities before changing references. Create Prompts and Skills before Agents that reference them.
                    Prompt and Skill imports accept exact inline content. Read client-local files with the caller's filesystem tools before invoking Control MCP; the Server does not guess which Worker's filesystem owns an arbitrary path.
                    AI catalog mutations require the latest expectedRevision. Read grz_ai_catalog_get again after a revision conflict.
                    External MCP servers are assigned to one exact Worker. Create, update, refresh, and delete are explicit operations against its current live Worker session and are never retried automatically after execution starts.
                    MCP tools/list_changed notifications only set refreshAvailable; call grz_mcp_server_refresh explicitly to accept a changed tool snapshot.
                    Inline secrets are returned as null with configuredInlineSecretPaths. Keep them null and preserveExistingSecret=true to retain their values.
                    Destructive operations never guess replacements and return dependency errors when an entity is still referenced.
                    Device UI settings are intentionally outside this control surface. grz_user_profile_update changes only shared user behavior.
                    """.trimIndent()
                )
            }
        },
        controlMcpTool(
            name = "grz_ai_catalog_get",
            description = "Read the complete AI catalog and optimistic revision. Inline secret values are always redacted.",
            readOnly = true,
        ) {
            aiConfigurationService.refreshIfChanged()
            redactedEntityResult(
                "catalogSnapshot",
                AiCatalogSnapshot.serializer(),
                aiConfigurationService.snapshot,
            )
        },
        controlMcpTool(
            name = "grz_ai_connection_upsert",
            description = "Create or replace one AI connection using the latest AI catalog revision. Missing API keys preserve the existing secret by default.",
            inputSchema = aiEntityMutationSchema(
                entityField = "connection",
                description = "Serialized AiConnection object with connectionKind discriminator.",
                extra = mapOf(
                    "preserveExistingSecret" to ControlMcpSchemas.boolean(
                        "Preserve an existing API key when the supplied connection has no key. Defaults to true."
                    )
                ),
            ),
            readOnly = false,
        ) { input ->
            val connection = input.decodeRequired("connection", AiConnection.serializer())
            val snapshot = aiCatalogManagementService.upsertConnection(
                connection = connection,
                expectedRevision = input.requiredLong("expectedRevision"),
                preserveExistingSecret = input.optionalBoolean("preserveExistingSecret", true),
            )
            aiMutationResult(
                snapshot = snapshot,
                name = "connection",
                serializer = AiConnection.serializer(),
                value = snapshot.catalog.connections.single { it.id == connection.id },
            )
        },
        controlMcpTool(
            name = "grz_ai_connection_delete",
            description = "Delete one unreferenced AI connection using the latest AI catalog revision.",
            inputSchema = aiDeleteSchema("connectionId", "AI connection id."),
            readOnly = false,
            destructive = true,
        ) { input ->
            val connectionId = AiConnection.Id(input.requiredString("connectionId"))
            aiDeleteResult(
                snapshot = aiCatalogManagementService.deleteConnection(
                    connectionId = connectionId,
                    expectedRevision = input.requiredLong("expectedRevision"),
                ),
                entity = "ai_connection",
                id = connectionId.value,
            )
        },
        controlMcpTool(
            name = "grz_ai_model_spec_upsert",
            description = "Create or replace static capability and limit metadata for one provider model.",
            inputSchema = aiEntityMutationSchema(
                entityField = "modelSpec",
                description = "Serialized AiModelSpec object.",
            ),
            readOnly = false,
        ) { input ->
            val modelSpec = input.decodeRequired("modelSpec", AiModelSpec.serializer())
            val snapshot = aiCatalogManagementService.upsertModelSpec(
                modelSpec = modelSpec,
                expectedRevision = input.requiredLong("expectedRevision"),
            )
            aiMutationResult(
                snapshot = snapshot,
                name = "modelSpec",
                serializer = AiModelSpec.serializer(),
                value = snapshot.catalog.modelSpecs.single {
                    it.provider == modelSpec.provider && it.id == modelSpec.id
                },
            )
        },
        controlMcpTool(
            name = "grz_ai_model_spec_delete",
            description = "Delete one unreferenced provider model specification.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "provider" to ControlMcpSchemas.string(
                        "AiProvider enum value.",
                        AiProvider.entries.map(AiProvider::name),
                    ),
                    "modelId" to ControlMcpSchemas.string("Provider model id."),
                    "expectedRevision" to ControlMcpSchemas.integer("Latest AI catalog revision.", minimum = 0),
                ),
                required = listOf("provider", "modelId", "expectedRevision"),
            ),
            readOnly = false,
            destructive = true,
        ) { input ->
            val provider = input.requiredEnum("provider", AiProvider.entries)
            val modelId = input.requiredString("modelId")
            aiDeleteResult(
                snapshot = aiCatalogManagementService.deleteModelSpec(
                    provider = provider,
                    modelId = modelId,
                    expectedRevision = input.requiredLong("expectedRevision"),
                ),
                entity = "ai_model_spec",
                id = "${provider.name}/$modelId",
            )
        },
        controlMcpTool(
            name = "grz_ai_model_configuration_upsert",
            description = "Create or replace one concrete model configuration attached to an AI connection.",
            inputSchema = aiEntityMutationSchema(
                entityField = "modelConfiguration",
                description = "Serialized AiModelConfiguration object.",
            ),
            readOnly = false,
        ) { input ->
            val configuration = input.decodeRequired(
                "modelConfiguration",
                AiModelConfiguration.serializer(),
            )
            val snapshot = aiCatalogManagementService.upsertModelConfiguration(
                configuration = configuration,
                expectedRevision = input.requiredLong("expectedRevision"),
            )
            aiMutationResult(
                snapshot = snapshot,
                name = "modelConfiguration",
                serializer = AiModelConfiguration.serializer(),
                value = snapshot.catalog.modelConfigurations.single { it.id == configuration.id },
            )
        },
        controlMcpTool(
            name = "grz_ai_model_configuration_delete",
            description = "Delete one model configuration that is not referenced by assignments or Agents.",
            inputSchema = aiDeleteSchema("modelConfigurationId", "AI model configuration id."),
            readOnly = false,
            destructive = true,
        ) { input ->
            val configurationId = AiModelConfiguration.Id(input.requiredString("modelConfigurationId"))
            aiDeleteResult(
                snapshot = aiCatalogManagementService.deleteModelConfiguration(
                    configurationId = configurationId,
                    expectedRevision = input.requiredLong("expectedRevision"),
                ),
                entity = "ai_model_configuration",
                id = configurationId.value,
            )
        },
        controlMcpTool(
            name = "grz_ai_runtime_assignment_set",
            description = "Set the model selection for one runtime purpose using the latest AI catalog revision.",
            inputSchema = aiEntityMutationSchema(
                entityField = "assignment",
                description = "Serialized AiRuntimeAssignment object containing purpose and selection.",
            ),
            readOnly = false,
        ) { input ->
            val assignment = input.decodeRequired(
                "assignment",
                AiRuntimeAssignment.serializer(),
            )
            val snapshot = aiCatalogManagementService.setRuntimeAssignment(
                assignment = assignment,
                expectedRevision = input.requiredLong("expectedRevision"),
            )
            aiMutationResult(
                snapshot = snapshot,
                name = "assignment",
                serializer = AiRuntimeAssignment.serializer(),
                value = snapshot.catalog.runtimeAssignments.single { it.purpose == assignment.purpose },
            )
        },
        controlMcpTool(
            name = "grz_default_agent_set",
            description = "Select an existing global Agent as the default Agent.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "agentId" to ControlMcpSchemas.string("Global Agent id."),
                    "expectedRevision" to ControlMcpSchemas.integer("Latest AI catalog revision.", minimum = 0),
                ),
                required = listOf("agentId", "expectedRevision"),
            ),
            readOnly = false,
        ) { input ->
            val snapshot = aiCatalogManagementService.setDefaultAgent(
                agentId = AgentDefinition.Id(input.requiredString("agentId")),
                expectedRevision = input.requiredLong("expectedRevision"),
            )
            buildJsonObject {
                put("revision", snapshot.revision)
                put("defaultAgentId", snapshot.catalog.defaultAgentId.value)
            }
        },
        controlMcpTool(
            name = "grz_user_profile_get",
            description = "Read shared user behavior settings. Device-specific UI settings are not included. Inline secrets are redacted.",
            readOnly = true,
        ) {
            redactedEntityResult(
                "userProfile",
                UserProfile.serializer(),
                settingsService.userProfile,
            )
        },
        controlMcpTool(
            name = "grz_user_profile_update",
            description = "Replace shared user behavior settings while preserving the profile id and leaving device-specific settings unchanged. Missing tool API keys preserve existing secrets by default.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "userProfile" to ControlMcpSchemas.objectValue("Serialized UserProfile object."),
                    "preserveExistingSecrets" to ControlMcpSchemas.boolean(
                        "Preserve existing tool API keys when omitted. Defaults to true."
                    ),
                ),
                required = listOf("userProfile"),
            ),
            readOnly = false,
            idempotent = true,
        ) { input ->
            val existing = settingsService.userProfile
            val supplied = input.decodeRequired("userProfile", UserProfile.serializer())
            val updated = supplied.preserveSecretsFrom(
                existing,
                input.optionalBoolean("preserveExistingSecrets", true),
            ).copy(id = existing.id)
            settingsService.saveSettings {
                copy(userProfile = updated)
            }
            redactedEntityResult("userProfile", UserProfile.serializer(), updated)
        },
    )

}

private fun <T> aiMutationResult(
    snapshot: AiCatalogSnapshot,
    name: String,
    serializer: KSerializer<T>,
    value: T,
): JsonObject {
    val redacted = controlMcpJson.encodeToJsonElement(serializer, value).redactInlineSecrets()
    return buildJsonObject {
        put("revision", snapshot.revision)
        put(name, redacted.value)
        if (redacted.configuredInlineSecretPaths.isNotEmpty()) {
            put(
                "configuredInlineSecretPaths",
                JsonArray(redacted.configuredInlineSecretPaths.map(::JsonPrimitive)),
            )
        }
    }
}

private fun aiDeleteResult(
    snapshot: AiCatalogSnapshot,
    entity: String,
    id: String,
): JsonObject = buildJsonObject {
    put("revision", snapshot.revision)
    put("deleted", true)
    put("entity", entity)
    put("id", id)
}

private fun aiEntityMutationSchema(
    entityField: String,
    description: String,
    extra: Map<String, JsonElement> = emptyMap(),
): io.modelcontextprotocol.kotlin.sdk.types.ToolSchema =
    ControlMcpSchemas.objectSchema(
        properties = mapOf(
            entityField to ControlMcpSchemas.objectValue(description),
            "expectedRevision" to ControlMcpSchemas.integer("Latest AI catalog revision.", minimum = 0),
        ) + extra,
        required = listOf(entityField, "expectedRevision"),
    )

private fun aiDeleteSchema(
    idField: String,
    description: String,
): io.modelcontextprotocol.kotlin.sdk.types.ToolSchema =
    ControlMcpSchemas.objectSchema(
        properties = mapOf(
            idField to ControlMcpSchemas.string(description),
            "expectedRevision" to ControlMcpSchemas.integer("Latest AI catalog revision.", minimum = 0),
        ),
        required = listOf(idField, "expectedRevision"),
    )

private fun <T> JsonObject.decodeRequired(name: String, serializer: KSerializer<T>): T =
    controlMcpJson.decodeFromJsonElement(serializer, requiredObject(name))

private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(
    name: String,
    values: Iterable<T>,
): T {
    val raw = requiredString(name)
    return values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: throw ControlMcpToolException(
            "invalid_argument",
            "'$name' must be one of ${values.joinToString { it.name }}",
        )
}

private fun <T> redactedEntityResult(
    name: String,
    serializer: KSerializer<T>,
    value: T,
): JsonObject {
    val redacted = controlMcpJson.encodeToJsonElement(serializer, value).redactInlineSecrets()
    return buildJsonObject {
        put(name, redacted.value)
        if (redacted.configuredInlineSecretPaths.isNotEmpty()) {
            put(
                "configuredInlineSecretPaths",
                JsonArray(redacted.configuredInlineSecretPaths.map(::JsonPrimitive)),
            )
        }
    }
}

internal data class RedactedControlJson(
    val value: JsonElement,
    val configuredInlineSecretPaths: List<String>,
)

internal fun JsonElement.redactInlineSecrets(): RedactedControlJson {
    val paths = mutableListOf<String>()
    return RedactedControlJson(
        value = redactInlineSecrets(path = "", configuredPaths = paths),
        configuredInlineSecretPaths = paths,
    )
}

private fun JsonElement.redactInlineSecrets(
    path: String,
    configuredPaths: MutableList<String>,
): JsonElement =
    when (this) {
        is JsonArray -> JsonArray(
            mapIndexed { index, value ->
                value.redactInlineSecrets("$path/$index", configuredPaths)
            }
        )
        is JsonObject -> {
            if (this["secretType"]?.jsonPrimitive?.content == "inline") {
                configuredPaths += path.ifEmpty { "/" }
                JsonNull
            } else {
                JsonObject(
                    mapValues { (key, value) ->
                        value.redactInlineSecrets(
                            "$path/${key.toJsonPointerSegment()}",
                            configuredPaths,
                        )
                    }
                )
            }
        }
        else -> this
    }

private fun String.toJsonPointerSegment(): String =
    replace("~", "~0").replace("/", "~1")

private fun UserProfile.preserveSecretsFrom(
    existing: UserProfile,
    preserveExistingSecrets: Boolean,
): UserProfile {
    if (!preserveExistingSecrets) return this
    return copy(
        toolSettings = toolSettings.copy(
            braveSearch = toolSettings.braveSearch.copy(
                apiKey = toolSettings.braveSearch.apiKey ?: existing.toolSettings.braveSearch.apiKey,
            ),
            jinaReader = toolSettings.jinaReader.copy(
                apiKey = toolSettings.jinaReader.apiKey ?: existing.toolSettings.jinaReader.apiKey,
            ),
        )
    )
}
