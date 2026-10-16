package com.gromozeka.server

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.domain.tool.AiToolAccessPolicy

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.service.SecurityAuditService
import com.gromozeka.domain.service.UserAdministrationService
import com.gromozeka.domain.service.UserDirectoryService
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
internal class IdentityToolContributor(
    private val toolFactory: ServerJsonToolFactory,
    private val userAdministrationService: UserAdministrationService,
    private val userDirectoryService: UserDirectoryService,
    private val securityAuditService: SecurityAuditService,
) : AiToolCallbackContributor {
    override val callbacks: List<AiToolCallback> = listOf(
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_user_directory",
            description = "List active users who can be assigned to projects and workers in this Runtime.",
            readOnly = true,
        ) {
            buildJsonObject {
                put(
                    "users",
                    serverToolJson.encodeToJsonElement(
                        ListSerializer(User.serializer()),
                        userDirectoryService.listActive(),
                    )
                )
            }
        },
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_user_list",
            description = "List users of this isolated Gromozeka Runtime.",
            readOnly = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) {
            buildJsonObject {
                put(
                    "users",
                    serverToolJson.encodeToJsonElement(
                        ListSerializer(User.serializer()),
                        userAdministrationService.list(user),
                    )
                )
            }
        },
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_security_audit_list",
            description = "List recent successful identity and access changes in this isolated Runtime.",
            inputSchema = ServerToolSchemas.objectSchema(
                properties = mapOf(
                    "limit" to ServerToolSchemas.integer(
                        description = "Maximum number of newest events to return, from 1 to 500.",
                        minimum = 1,
                    ),
                ),
            ),
            readOnly = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) { input ->
            val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: 100
            buildJsonObject {
                put(
                    "events",
                    serverToolJson.encodeToJsonElement(
                        ListSerializer(SecurityAuditEvent.serializer()),
                        securityAuditService.listRecent(user, limit),
                    )
                )
            }
        },
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_user_create",
            description = "Create an active local user in this Runtime.",
            inputSchema = ServerToolSchemas.objectSchema(
                properties = mapOf(
                    "username" to ServerToolSchemas.string("Unique local username."),
                    "displayName" to ServerToolSchemas.string("User-facing display name."),
                    "password" to ServerToolSchemas.string("Initial password with at least 12 characters."),
                    "role" to ServerToolSchemas.string(
                        description = "Runtime role.",
                        enum = User.Role.entries.map { it.name },
                    ),
                ),
                required = listOf("username", "displayName", "password", "role"),
            ),
            readOnly = false,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) { input ->
            val created = input.requiredString("password").usePasswordChars { password ->
                userAdministrationService.create(
                    actor = user,
                    username = input.requiredString("username"),
                    displayName = input.requiredString("displayName"),
                    password = password,
                    role = User.Role.valueOf(input.requiredString("role")),
                )
            }
            entityResult("user", User.serializer(), created)
        },
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_user_update",
            description = "Update a user's profile, Runtime role, login permission and AI permission. Omitted permissions stay unchanged. An observed Telegram identity does not enable login.",
            inputSchema = ServerToolSchemas.objectSchema(
                properties = mapOf(
                    "userId" to ServerToolSchemas.string("User id."),
                    "displayName" to ServerToolSchemas.string("User-facing display name."),
                    "loginAllowed" to ServerToolSchemas.boolean("Allow login and personal access tokens. Requires a configured login identity."),
                    "aiAllowed" to ServerToolSchemas.boolean("Allow this user to initiate AI work. Does not grant project or channel access."),
                    "status" to ServerToolSchemas.string(
                        description = "Account status.",
                        enum = User.Status.entries.map { it.name },
                    ),
                    "role" to ServerToolSchemas.string(
                        description = "Runtime role.",
                        enum = User.Role.entries.map { it.name },
                    ),
                ),
                required = listOf("userId", "displayName", "status", "role"),
            ),
            readOnly = false,
            idempotent = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) { input ->
            entityResult(
                "user",
                User.serializer(),
                userAdministrationService.update(
                    actor = user,
                    userId = User.Id(input.requiredString("userId")),
                    displayName = input.requiredString("displayName"),
                    status = User.Status.valueOf(input.requiredString("status")),
                    role = User.Role.valueOf(input.requiredString("role")),
                    loginAllowed = if ("loginAllowed" in input) input.optionalBoolean("loginAllowed", false) else null,
                    aiAllowed = if ("aiAllowed" in input) input.optionalBoolean("aiAllowed", false) else null,
                ),
            )
        },
        toolFactory.create(CONTROL_TOOL_SOURCE,
            name = "grz_user_password_reset",
            description = "Replace a local user's password and revoke every existing session and personal access token.",
            inputSchema = ServerToolSchemas.objectSchema(
                properties = mapOf(
                    "userId" to ServerToolSchemas.string("User id."),
                    "password" to ServerToolSchemas.string("New password with at least 12 characters."),
                ),
                required = listOf("userId", "password"),
            ),
            readOnly = false,
            destructive = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) { input ->
            input.requiredString("password").usePasswordChars { password ->
                userAdministrationService.resetPassword(
                    actor = user,
                    userId = User.Id(input.requiredString("userId")),
                    password = password,
                )
            }
            buildJsonObject { put("reset", true) }
        },
    )
}
