package com.gromozeka.server

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.domain.tool.AiToolAccessPolicy

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiTpmGroup
import com.gromozeka.domain.model.ai.AiConcurrencyGroup
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiWebToolConfiguration
import com.gromozeka.domain.model.mcp.BrowserUseMcpPreset
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
internal class AiSettingsToolContributor(
    private val toolFactory: ServerJsonToolFactory,
    private val aiConfigurationService: AiConfigurationService,
    private val aiCatalogManagementService: AiCatalogManagementService,
    private val settingsService: SettingsService,
) : AiToolCallbackContributor {
    override val callbacks: List<AiToolCallback> = listOf(
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_control_help",
            description = "Explain Gromozeka configuration ownership, safe Control MCP workflows, and Browser Use self-setup.",
            readOnly = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) {
            val browserUseArguments = BrowserUseMcpPreset.arguments.joinToString(
                prefix = "[",
                postfix = "]",
            ) { argument -> "\"$argument\"" }
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
                    AI concurrency groups are Server-wide across execution targets. Each model configuration uses one concurrencyGroupId (default: default). Create or update groups with grz_ai_concurrency_group_upsert; null maxConcurrentCalls means unlimited. The default group cannot be deleted.
                    Model configuration tpm policies select a group by fixed id, request JSON Pointer value matching a group key, or Server command stdout. Commands receive JSON on stdin, use separate executable/arguments and run as the Server OS user. They are trusted owner configuration, not sandboxed model input. Set calculator and mandatory feedback extraction explicitly. TPM is disabled when tpm is null.
                    TPM serialized policy uses type tags: group={type:"fixed",groupId} or {type:"request_value",pointer} or {type:"command",command}. Commands are {executable,arguments:[],timeoutSeconds}; selector stdout is a plain key and calculator stdout is an integer. Calculator={type:"builtin",preset,charactersPerUnit,multiplier,fixedOverhead,outputReserve?} or {type:"command",command}; presets are CHARACTERS, INPUT_ONLY, INPUT_PLUS_OUTPUT, OPENAI_MAXIMUM, and are estimates, not tokenizers. Feedback={type:"fields",remaining,retryDelay?,retryFormat} or {type:"command",command}. Value sources are {type:"header",name,jsonPointer?} or {type:"body",pointer}; retryFormat is SECONDS, MILLISECONDS, HTTP_DATE or RETRY_AFTER. Feedback-command stdin is {status,headers,body,bodyUnavailable}; stdout is {remaining?,retryAfterMillis?}. fallbackGroupId applies only to group selection. Header names and quota constants require installation-specific configuration. Missing required success feedback fails the call; it never resets the budget or retries inference.
                    Conversation compaction belongs to AiModelConfiguration.compaction: thresholdTokens is an absolute positive integer (null disables auto-compaction); summaryModelConfigurationId is optional (null uses the current agent model). Percentages are UI input only. Replace these settings through grz_ai_model_configuration_upsert; compaction={} resets both to defaults. Auto-compaction is blocking SUMMARIZE; DISTILL is manual only.
                    Web tool configuration is part of the central AI catalog and is shared by all Workers.
                    External MCP servers are assigned to one exact Worker. Create, update, refresh, and delete are explicit operations against its current live Worker session and are never retried automatically after execution starts.
                    External MCP environment and HTTP header values are never returned. Read configuredTransportValues for their names; update sends only add/replace values plus explicit removal lists.
                    MCP tools/list_changed notifications only set refreshAvailable; call grz_mcp_server_refresh explicitly to accept a changed tool snapshot.
                    Browser Use is a Worker-owned MCP definition backed by the Browser MCP bundled with supported Worker distributions. First read grz_worker_list and grz_mcp_server_list. Select the only online Worker automatically; if several online Workers are plausible, ask the user which machine owns the browser profile. Never guess or reroute.
                    A canonical Browser Use connection has a unique lowercase snake_case id beginning with ${BrowserUseMcpPreset.SERVER_ID_PREFIX}, namespace "${BrowserUseMcpPreset.TOOL_NAMESPACE.value}", displayName "Browser · <workerId>", the exact workerId, timeoutMs ${BrowserUseMcpPreset.OPERATION_TIMEOUT_MS}, and this bundled_stdio transport: runtime "browser_use"; arguments $browserUseArguments; environment ${BrowserUseMcpPreset.EXTENSION_ID_ENV}="${BrowserUseMcpPreset.EXTENSION_ID}"; ephemeralWorkingDirectory true. Create it when absent. If it exists but differs, update it using its latest revision; omitted environment values preserve stored secrets.
                    Never ask the user to paste ${BrowserUseMcpPreset.EXTENSION_TOKEN_ENV} into the conversation or a Control MCP call. Tell the user to download Gromozeka Browser Bridge under Settings > Downloads, extract it permanently, and load its folder from chrome://extensions with Developer mode enabled in the browser profile on the selected Worker's machine. It can coexist with the official Playwright Extension and exposes every ordinary tab in that profile. The user may then copy its token into Settings > Tools > Browser Use > Extension token to avoid repeated approval dialogs. Without a token, the user approves each connection in the bridge. Once the MCP connection is created, use its browser tools to verify the profile, normally with browser_take_screenshot.
                    Inline secrets are returned as null with configuredInlineSecretPaths. Keep them null and use the mutation's preserve-existing-secret option to retain their values.
                    Destructive operations never guess replacements and return dependency errors when an entity is still referenced.
                    Personal UI translations are available to every authenticated User through grz_translation_list/source/get/validate/save/customize/delete/select/synchronize, independently of Projects. Start with the list for current revisions and client IDs. Read the paginated source and semantic guide before translating. Save requires a complete language package; customize copies an existing translation within its locale for small terminology changes. Saving never selects a package: select explicitly. MCP has no implicit current client, so per-client changes require an exact listed client ID. Other device UI settings remain outside this control surface; grz_user_profile_update changes only shared user behavior.
                    """.trimIndent()
                )
            }
        },
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_ai_catalog_get",
            description = "Read the complete AI catalog and optimistic revision. Inline secret values are always redacted.",
            readOnly = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) {
            aiConfigurationService.refreshIfChanged()
            redactedEntityResult(
                "catalogSnapshot",
                AiCatalogSnapshot.serializer(),
                aiConfigurationService.snapshot,
            )
        },
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_ai_concurrency_group_upsert",
            description = "Create or replace a Server-wide AI concurrency group. Fields: id, key (unique; defaults to id), displayName, maxConcurrentCalls (1-64, default 4; null means unlimited). Model configurations select one group by concurrencyGroupId; configurations across connections and Workers share its capacity. Existing operations are not cancelled when lowering a limit.",
            inputSchema = aiEntityMutationSchema("group", "Serialized AiConcurrencyGroup object."),
            readOnly = false,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) { input ->
            val group = input.decodeRequired("group", AiConcurrencyGroup.serializer())
            val snapshot = aiCatalogManagementService.upsertConcurrencyGroup(group, input.requiredLong("expectedRevision"))
            aiMutationResult(snapshot, "group", AiConcurrencyGroup.serializer(), snapshot.catalog.concurrencyGroups.single { it.id == group.id })
        },
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_ai_concurrency_group_delete",
            description = "Delete an unused AI concurrency group. The default group and groups referenced by model configurations or active admissions cannot be deleted.",
            inputSchema = aiDeleteSchema("groupId", "AI concurrency group id."),
            readOnly = false,
            destructive = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) { input ->
            val id = AiConcurrencyGroup.Id(input.requiredString("groupId"))
            aiDeleteResult(aiCatalogManagementService.deleteConcurrencyGroup(id, input.requiredLong("expectedRevision")),
                "ai_concurrency_group", id.value)
        },
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_ai_tpm_group_upsert",
            description = "Create or replace a Server-wide TPM group: id, unique human-readable key, displayName, tokenLimit, renewalPeriodSeconds (default 60), allowParallelRequests (default false), quotaUsageFraction (greater than 0, at most 1; default 1), missingRetryDelaySeconds (default 10). Model configurations opt in through tpm policy. No groups are created automatically.",
            inputSchema = aiEntityMutationSchema("group", "Serialized AiTpmGroup object."),
            readOnly = false,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) { input ->
            val group = input.decodeRequired("group", AiTpmGroup.serializer())
            val snapshot = aiCatalogManagementService.upsertTpmGroup(group, input.requiredLong("expectedRevision"))
            aiMutationResult(snapshot, "group", AiTpmGroup.serializer(), snapshot.catalog.tpmGroups.single { it.id == group.id })
        },
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_ai_tpm_group_delete",
            description = "Delete a TPM group not referenced by a fixed/fallback policy or active admission. Dynamic key selectors are not rewritten; their configured error/fallback policy applies.",
            inputSchema = aiDeleteSchema("groupId", "TPM group id."),
            readOnly = false,
            destructive = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) { input ->
            val id = AiTpmGroup.Id(input.requiredString("groupId"))
            aiDeleteResult(aiCatalogManagementService.deleteTpmGroup(id, input.requiredLong("expectedRevision")), "ai_tpm_group", id.value)
        },
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_ai_connection_upsert",
            description = "Create or replace one AI connection using the latest AI catalog revision. Missing API keys preserve the existing secret by default. SDK connections support httpSettings for Bearer/native auth, additional CA, proxy/noProxy, non-secret static headers and maxAttempts (1-5, default 3 including the first attempt; only complete HTTP 429 rejections may retry). Token env/file sources and CA paths belong to the selected execution target; Authorization must not be supplied in extraHeaders.",
            inputSchema = aiEntityMutationSchema(
                entityField = "connection",
                description = "Serialized AiConnection object with connectionKind discriminator.",
                extra = mapOf(
                    "preserveExistingSecret" to ServerToolSchemas.boolean(
                        "Preserve an existing API key when the supplied connection has no key. Defaults to true."
                    )
                ),
            ),
            readOnly = false,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
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
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_ai_connection_delete",
            description = "Delete one unreferenced AI connection using the latest AI catalog revision.",
            inputSchema = aiDeleteSchema("connectionId", "AI connection id."),
            readOnly = false,
            destructive = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
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
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_ai_model_spec_upsert",
            description = "Create or replace capabilities, limits and explicit request behavior for one exact model route. Behavior is not inferred from the model ID; preserve it when copying a spec to a gateway ID.",
            inputSchema = aiEntityMutationSchema(
                entityField = "modelSpec",
                description = "Serialized AiModelSpec object.",
            ),
            readOnly = false,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
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
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_ai_model_spec_delete",
            description = "Delete one unreferenced provider model specification.",
            inputSchema = ServerToolSchemas.objectSchema(
                properties = mapOf(
                    "provider" to ServerToolSchemas.string(
                        "AiProvider enum value.",
                        AiProvider.entries.map(AiProvider::name),
                    ),
                    "modelId" to ServerToolSchemas.string("Provider model id."),
                    "expectedRevision" to ServerToolSchemas.integer("Latest AI catalog revision.", minimum = 0),
                ),
                required = listOf("provider", "modelId", "expectedRevision"),
            ),
            readOnly = false,
            destructive = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
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
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_ai_model_configuration_upsert",
            description = "Create or replace one concrete model configuration, including compaction.thresholdTokens (null disables) and compaction.summaryModelConfigurationId (null uses current model). Omitted compaction or {} resets it.",
            inputSchema = aiEntityMutationSchema(
                entityField = "modelConfiguration",
                description = "Serialized AiModelConfiguration object. compaction: {thresholdTokens: positive integer or null, summaryModelConfigurationId: configuration ID or null}. Defaults: both null.",
            ),
            readOnly = false,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
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
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_ai_model_configuration_delete",
            description = "Delete one model configuration that is not referenced by assignments or Agents.",
            inputSchema = aiDeleteSchema("modelConfigurationId", "AI model configuration id."),
            readOnly = false,
            destructive = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
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
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_ai_runtime_assignment_set",
            description = "Set the model selection for one runtime purpose using the latest AI catalog revision.",
            inputSchema = aiEntityMutationSchema(
                entityField = "assignment",
                description = "Serialized AiRuntimeAssignment object containing purpose and selection.",
            ),
            readOnly = false,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
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
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_default_agent_set",
            description = "Select an existing global Agent as the default Agent.",
            inputSchema = ServerToolSchemas.objectSchema(
                properties = mapOf(
                    "agentId" to ServerToolSchemas.string("Global Agent id."),
                    "expectedRevision" to ServerToolSchemas.integer("Latest AI catalog revision.", minimum = 0),
                ),
                required = listOf("agentId", "expectedRevision"),
            ),
            readOnly = false,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
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
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_web_tools_update",
            description = "Replace central web tool configuration. Missing Brave and Jina API keys preserve their existing values by default.",
            inputSchema = ServerToolSchemas.objectSchema(
                properties = mapOf(
                    "configuration" to ServerToolSchemas.objectValue(
                        "Serialized AiWebToolConfiguration object."
                    ),
                    "expectedRevision" to ServerToolSchemas.integer(
                        "Latest AI catalog revision.",
                        minimum = 0,
                    ),
                    "preserveExistingSecrets" to ServerToolSchemas.boolean(
                        "Preserve existing Brave and Jina API keys when omitted. Defaults to true."
                    ),
                ),
                required = listOf("configuration", "expectedRevision"),
            ),
            readOnly = false,
            idempotent = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) { input ->
            val configuration = input.decodeRequired(
                "configuration",
                AiWebToolConfiguration.serializer(),
            )
            val snapshot = aiCatalogManagementService.setWebToolConfiguration(
                configuration = configuration,
                expectedRevision = input.requiredLong("expectedRevision"),
                preserveExistingSecrets = input.optionalBoolean("preserveExistingSecrets", true),
            )
            aiMutationResult(
                snapshot = snapshot,
                name = "configuration",
                serializer = AiWebToolConfiguration.serializer(),
                value = snapshot.catalog.webTools,
            )
        },
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_user_profile_get",
            description = "Read shared user behavior settings. Device-specific UI settings are not included.",
            readOnly = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) {
            redactedEntityResult(
                "userProfile",
                UserProfile.serializer(),
                settingsService.userProfile,
            )
        },
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_user_profile_update",
            description = "Replace shared user behavior settings while preserving the profile id and leaving device-specific settings unchanged.",
            inputSchema = ServerToolSchemas.objectSchema(
                properties = mapOf(
                    "userProfile" to ServerToolSchemas.objectValue("Serialized UserProfile object."),
                ),
                required = listOf("userProfile"),
            ),
            readOnly = false,
            idempotent = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) { input ->
            val existing = settingsService.userProfile
            val supplied = input.decodeRequired("userProfile", UserProfile.serializer())
            val updated = supplied.copy(id = existing.id)
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
    val redacted = serverToolJson.encodeToJsonElement(serializer, value).redactInlineSecrets()
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
): JsonObject =
    ServerToolSchemas.objectSchema(
        properties = mapOf(
            entityField to ServerToolSchemas.objectValue(description),
            "expectedRevision" to ServerToolSchemas.integer("Latest AI catalog revision.", minimum = 0),
        ) + extra,
        required = listOf(entityField, "expectedRevision"),
    )

private fun aiDeleteSchema(
    idField: String,
    description: String,
): JsonObject =
    ServerToolSchemas.objectSchema(
        properties = mapOf(
            idField to ServerToolSchemas.string(description),
            "expectedRevision" to ServerToolSchemas.integer("Latest AI catalog revision.", minimum = 0),
        ),
        required = listOf(idField, "expectedRevision"),
    )

private fun <T> JsonObject.decodeRequired(name: String, serializer: KSerializer<T>): T =
    serverToolJson.decodeFromJsonElement(serializer, requiredObject(name))

private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(
    name: String,
    values: Iterable<T>,
): T {
    val raw = requiredString(name)
    return values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: throw ServerToolException(
            "invalid_argument",
            "'$name' must be one of ${values.joinToString { it.name }}",
        )
}

private fun <T> redactedEntityResult(
    name: String,
    serializer: KSerializer<T>,
    value: T,
): JsonObject {
    val redacted = serverToolJson.encodeToJsonElement(serializer, value).redactInlineSecrets()
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
