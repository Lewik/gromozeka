package com.gromozeka.server

import com.gromozeka.application.service.McpServerManagementService
import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpServerTransport
import com.gromozeka.domain.model.mcp.McpTransportValueRemovals
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
internal class ControlMcpServerCatalogTools(
    private val managementService: McpServerManagementService,
) : ControlMcpToolProvider {
    override val tools: List<ControlMcpTool> = listOf(
        controlMcpTool(
            name = "grz_mcp_server_list",
            description = "List centrally managed external MCP servers, their exact Workers, revisions, snapshots, and refresh state.",
            readOnly = true,
        ) {
            buildJsonObject {
                put(
                    "servers",
                    JsonArray(managementService.list().map(McpServer::toRedactedJson)),
                )
            }
        },
        controlMcpTool(
            name = "grz_mcp_server_get",
            description = "Read one centrally managed external MCP server by stable id.",
            inputSchema = serverIdSchema(),
            readOnly = true,
        ) { input ->
            val id = McpServerId(input.requiredString("serverId"))
            val server = managementService.get(id)
                ?: notFound("MCP server", id.value)
            buildJsonObject {
                put("server", server.toRedactedJson())
            }
        },
        controlMcpTool(
            name = "grz_mcp_server_create",
            description = "Connect and fully validate an external MCP server on one exact online Worker, then persist and activate it. No retry occurs after execution starts.",
            inputSchema = configMutationSchema(requireRevision = false),
            readOnly = false,
        ) { input ->
            val server = managementService.create(
                input.decodeRequired("config", McpServerConfig.serializer())
            )
            buildJsonObject {
                put("server", server.toRedactedJson())
            }
        },
        controlMcpTool(
            name = "grz_mcp_server_update",
            description = "Update one MCP configuration after validation on its exact Worker. " +
                "Stored environment and HTTP header values are preserved unless replaced in config or explicitly removed.",
            inputSchema = configMutationSchema(requireRevision = true),
            readOnly = false,
        ) { input ->
            val server = managementService.update(
                config = input.decodeRequired("config", McpServerConfig.serializer()),
                expectedRevision = input.requiredLong("expectedRevision"),
                transportValueRemovals = McpTransportValueRemovals(
                    environmentVariables = input.optionalStringList(
                        "removeEnvironmentVariables"
                    ).toSet(),
                    httpHeaders = input.optionalStringList("removeHttpHeaders").toSet(),
                ),
            )
            buildJsonObject {
                put("server", server.toRedactedJson())
            }
        },
        controlMcpTool(
            name = "grz_mcp_server_refresh",
            description = "Explicitly fetch and validate the current MCP tool list, replace its stored snapshot, and clear refreshAvailable.",
            inputSchema = serverIdSchema(requireRevision = true),
            readOnly = false,
        ) { input ->
            val server = managementService.refresh(
                serverId = McpServerId(input.requiredString("serverId")),
                expectedRevision = input.requiredLong("expectedRevision"),
            )
            buildJsonObject {
                put("server", server.toRedactedJson())
            }
        },
        controlMcpTool(
            name = "grz_mcp_server_delete",
            description = "Delete and disconnect one external MCP server from its exact online Worker.",
            inputSchema = serverIdSchema(requireRevision = true),
            readOnly = false,
            destructive = true,
        ) { input ->
            val id = McpServerId(input.requiredString("serverId"))
            managementService.delete(
                serverId = id,
                expectedRevision = input.requiredLong("expectedRevision"),
            )
            buildJsonObject {
                put("deletedServerId", id.value)
            }
        },
    )
}

private fun configMutationSchema(requireRevision: Boolean) =
    ControlMcpSchemas.objectSchema(
        properties = buildMap {
            put(
                "config",
                mcpServerConfigSchema(isUpdate = requireRevision),
            )
            if (requireRevision) {
                put(
                    "expectedRevision",
                    ControlMcpSchemas.integer("Current MCP server revision.", minimum = 1),
                )
                put(
                    "removeEnvironmentVariables",
                    nonBlankStringArray(
                        "Exact stdio environment variable names to remove. " +
                            "Every omitted stored variable is preserved."
                    ),
                )
                put(
                    "removeHttpHeaders",
                    nonBlankStringArray(
                        "Case-insensitive Streamable HTTP header names to remove. " +
                            "Every omitted stored header is preserved."
                    ),
                )
            }
        },
        required = buildList {
            add("config")
            if (requireRevision) {
                add("expectedRevision")
            }
        },
    )

private fun mcpServerConfigSchema(isUpdate: Boolean): JsonObject =
    strictObjectSchema(
        description = "Complete external MCP configuration. The stable id determines generated tool names. " +
            "workerId must identify one exact online Worker; Gromozeka never guesses or retries on another Worker.",
        properties = mapOf(
            "id" to buildJsonObject {
                put("type", "string")
                put("description", "Stable lowercase snake_case id used in generated tool names.")
                put("pattern", "^[a-z][a-z0-9_]{0,63}$")
            },
            "displayName" to nonBlankString("Human-readable MCP server name."),
            "workerId" to nonBlankString(
                "Exact id of the online Worker that will own the MCP process or connection."
            ),
            "transport" to buildJsonObject {
                put("description", "How the selected Worker connects to the external MCP server.")
                put(
                    "oneOf",
                    JsonArray(
                        listOf(
                            stdioTransportSchema(isUpdate),
                            streamableHttpTransportSchema(isUpdate),
                        )
                    ),
                )
            },
            "timeoutMs" to buildJsonObject {
                put("type", "integer")
                put("description", "Per-operation timeout in milliseconds. Defaults to 40000.")
                put("minimum", 1)
            },
            "allowedTools" to nonBlankStringArray(
                "Optional allowlist of remote tool names. Omit it to allow every tool except excludedTools.",
                minimumItems = 1,
            ),
            "excludedTools" to nonBlankStringArray(
                "Remote tool names to exclude. Defaults to an empty list."
            ),
            "forwardGrzConversationContext" to ControlMcpSchemas.boolean(
                "Whether Gromozeka conversation context is injected into calls to this MCP. Defaults to false."
            ),
        ),
        required = listOf("id", "displayName", "workerId", "transport"),
    )

private fun stdioTransportSchema(isUpdate: Boolean): JsonObject =
    strictObjectSchema(
        description = "Start and communicate with an MCP server through stdio on the selected Worker.",
        properties = mapOf(
            "type" to constantString("stdio"),
            "command" to nonBlankString("Executable available on the selected Worker."),
            "arguments" to nonBlankStringArray("Command arguments. Defaults to an empty list."),
            "environment" to stringMap(
                if (isUpdate) {
                    "Environment variables to add or replace. Omitted stored variables are preserved."
                } else {
                    "Environment variables added to the MCP process. Defaults to an empty object."
                }
            ),
        ),
        required = listOf("type", "command"),
    )

private fun streamableHttpTransportSchema(isUpdate: Boolean): JsonObject =
    strictObjectSchema(
        description = "Connect to a Streamable HTTP MCP endpoint from the selected Worker.",
        properties = mapOf(
            "type" to constantString("streamable_http"),
            "url" to buildJsonObject {
                put("type", "string")
                put("description", "MCP endpoint URL using http or https.")
                put("pattern", "^https?://")
            },
            "headers" to stringMap(
                if (isUpdate) {
                    "HTTP headers to add or replace. Omitted stored headers are preserved."
                } else {
                    "HTTP headers sent to the MCP endpoint. Defaults to an empty object."
                }
            ),
        ),
        required = listOf("type", "url"),
    )

private fun strictObjectSchema(
    description: String,
    properties: Map<String, JsonElement>,
    required: List<String>,
): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("description", description)
        put("additionalProperties", false)
        put("properties", JsonObject(properties))
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }

private fun constantString(value: String): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("const", value)
    }

private fun nonBlankString(description: String): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
        put("minLength", 1)
    }

private fun nonBlankStringArray(
    description: String,
    minimumItems: Int = 0,
): JsonObject =
    buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject {
            put("type", "string")
            put("minLength", 1)
        })
        put("uniqueItems", true)
        if (minimumItems > 0) {
            put("minItems", minimumItems)
        }
    }

private fun stringMap(description: String): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("description", description)
        put("propertyNames", buildJsonObject { put("minLength", 1) })
        put("additionalProperties", buildJsonObject { put("type", "string") })
    }

private fun serverIdSchema(requireRevision: Boolean = false) =
    ControlMcpSchemas.objectSchema(
        properties = buildMap {
            put("serverId", ControlMcpSchemas.string("Stable MCP server id."))
            if (requireRevision) {
                put(
                    "expectedRevision",
                    ControlMcpSchemas.integer("Current MCP server revision.", minimum = 1),
                )
            }
        },
        required = buildList {
            add("serverId")
            if (requireRevision) {
                add("expectedRevision")
            }
        },
    )

internal fun McpServer.toRedactedJson(): JsonObject {
    val encoded = controlMcpJson.parseToJsonElement(
        controlMcpJson.encodeToString(McpServer.serializer(), this)
    ).jsonObject
    val encodedConfig = encoded.getValue("config").jsonObject
    val encodedTransport = encodedConfig.getValue("transport").jsonObject
    val redactedTransport = when (config.transport) {
        is McpServerTransport.Stdio -> JsonObject(encodedTransport - "environment")
        is McpServerTransport.StreamableHttp -> JsonObject(encodedTransport - "headers")
    }
    val configuredTransportValues = buildJsonObject {
        val environmentVariables = (config.transport as? McpServerTransport.Stdio)
            ?.environment
            ?.keys
            .orEmpty()
            .sorted()
        val httpHeaders = (config.transport as? McpServerTransport.StreamableHttp)
            ?.headers
            ?.keys
            .orEmpty()
            .sortedBy(String::lowercase)
        put(
            "environmentVariables",
            JsonArray(environmentVariables.map(::JsonPrimitive)),
        )
        put(
            "httpHeaders",
            JsonArray(httpHeaders.map(::JsonPrimitive)),
        )
    }
    return JsonObject(
        encoded + mapOf(
            "config" to JsonObject(
                encodedConfig + mapOf("transport" to redactedTransport)
            ),
            "configuredTransportValues" to configuredTransportValues,
        )
    )
}

private fun <T> JsonObject.decodeRequired(
    name: String,
    serializer: KSerializer<T>,
): T =
    controlMcpJson.decodeFromString(
        serializer,
        controlMcpJson.encodeToString(JsonObject.serializer(), requiredObject(name)),
    )
