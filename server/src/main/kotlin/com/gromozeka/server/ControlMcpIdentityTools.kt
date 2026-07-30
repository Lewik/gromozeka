package com.gromozeka.server

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
internal class ControlMcpIdentityTools(
    private val userAdministrationService: UserAdministrationService,
    private val userDirectoryService: UserDirectoryService,
    private val securityAuditService: SecurityAuditService,
) : ControlMcpToolProvider {
    override val tools: List<ControlMcpTool> = listOf(
        controlMcpTool(
            name = "grz_user_directory",
            description = "List active users who can be assigned to projects and workers in this Runtime.",
            readOnly = true,
        ) {
            buildJsonObject {
                put(
                    "users",
                    controlMcpJson.encodeToJsonElement(
                        ListSerializer(User.serializer()),
                        userDirectoryService.listActive(),
                    )
                )
            }
        },
        controlMcpTool(
            name = "grz_user_list",
            description = "List users of this isolated Gromozeka Runtime.",
            readOnly = true,
            accessPolicy = ControlMcpAccessPolicy.SERVER_OWNER,
        ) {
            buildJsonObject {
                put(
                    "users",
                    controlMcpJson.encodeToJsonElement(
                        ListSerializer(User.serializer()),
                        userAdministrationService.list(user),
                    )
                )
            }
        },
        controlMcpTool(
            name = "grz_security_audit_list",
            description = "List recent successful identity and access changes in this isolated Runtime.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "limit" to ControlMcpSchemas.integer(
                        description = "Maximum number of newest events to return, from 1 to 500.",
                        minimum = 1,
                    ),
                ),
            ),
            readOnly = true,
            accessPolicy = ControlMcpAccessPolicy.SERVER_OWNER,
        ) { input ->
            val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: 100
            buildJsonObject {
                put(
                    "events",
                    controlMcpJson.encodeToJsonElement(
                        ListSerializer(SecurityAuditEvent.serializer()),
                        securityAuditService.listRecent(user, limit),
                    )
                )
            }
        },
        controlMcpTool(
            name = "grz_user_create",
            description = "Create an active local user in this Runtime.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "username" to ControlMcpSchemas.string("Unique local username."),
                    "displayName" to ControlMcpSchemas.string("User-facing display name."),
                    "password" to ControlMcpSchemas.string("Initial password with at least 12 characters."),
                    "role" to ControlMcpSchemas.string(
                        description = "Runtime role.",
                        enum = User.Role.entries.map { it.name },
                    ),
                ),
                required = listOf("username", "displayName", "password", "role"),
            ),
            readOnly = false,
            accessPolicy = ControlMcpAccessPolicy.SERVER_OWNER,
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
        controlMcpTool(
            name = "grz_user_update",
            description = "Replace a user's display name, active status, and Runtime role.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "userId" to ControlMcpSchemas.string("User id."),
                    "displayName" to ControlMcpSchemas.string("User-facing display name."),
                    "status" to ControlMcpSchemas.string(
                        description = "Account status.",
                        enum = User.Status.entries.map { it.name },
                    ),
                    "role" to ControlMcpSchemas.string(
                        description = "Runtime role.",
                        enum = User.Role.entries.map { it.name },
                    ),
                ),
                required = listOf("userId", "displayName", "status", "role"),
            ),
            readOnly = false,
            idempotent = true,
            accessPolicy = ControlMcpAccessPolicy.SERVER_OWNER,
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
                ),
            )
        },
        controlMcpTool(
            name = "grz_user_password_reset",
            description = "Replace a local user's password and revoke every existing session and personal access token.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "userId" to ControlMcpSchemas.string("User id."),
                    "password" to ControlMcpSchemas.string("New password with at least 12 characters."),
                ),
                required = listOf("userId", "password"),
            ),
            readOnly = false,
            destructive = true,
            accessPolicy = ControlMcpAccessPolicy.SERVER_OWNER,
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
