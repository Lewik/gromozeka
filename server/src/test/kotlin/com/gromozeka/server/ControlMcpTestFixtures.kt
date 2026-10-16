package com.gromozeka.server

import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.infrastructure.ai.config.mcp.McpConfigurationService
import com.gromozeka.infrastructure.ai.tool.DefaultAiToolProvider
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import org.springframework.context.support.StaticApplicationContext
import kotlin.time.Instant

internal fun testControlMcpCaller(role: User.Role = User.Role.OWNER): AuthenticatedMcpCaller =
    AuthenticatedMcpCaller.UserSession(
        AuthenticatedUser(
            user = User(
                id = User.Id(if (role == User.Role.OWNER) "control-mcp-test-user" else "control-mcp-test-${role.name.lowercase()}"),
                identities = listOf(com.gromozeka.domain.model.UserIdentity.LocalLogin("control-mcp-test-${role.name.lowercase()}")),
                displayName = "Control MCP Test User",
                status = User.Status.ACTIVE,
                role = role,
                createdAt = Instant.fromEpochMilliseconds(1),
                updatedAt = Instant.fromEpochMilliseconds(1),
            ),
            sessionId = UserSession.Id("control-mcp-test-session"),
        )
    )

internal fun testControlMcpContext(role: User.Role = User.Role.OWNER): ServerToolContext =
    ServerToolContext(testControlMcpCaller(role).user)

internal fun testServerToolFactory(
    users: UserDirectoryService = object : UserDirectoryService {
        private val values = User.Role.entries.map { testControlMcpCaller(it).user }
        override suspend fun findActiveById(id: User.Id): User? = values.find { it.id == id }
        override suspend fun listActive(): List<User> = values
    },
): ServerJsonToolFactory = ServerJsonToolFactory(users)

internal fun testNativeToolProvider(contributors: List<AiToolCallbackContributor>): AiToolProvider {
    val context = StaticApplicationContext()
    contributors.forEachIndexed { index, contributor ->
        context.beanFactory.registerSingleton("tools$index", contributor)
    }
    return DefaultAiToolProvider(
        applicationContext = context,
        callbackContributors = context.getBeanProvider(AiToolCallbackContributor::class.java),
        mcpConfigurationService = context.getBeanProvider(McpConfigurationService::class.java),
    )
}

internal fun ServerToolContext.toExecutionContext(): ToolExecutionContext = ToolExecutionContext(
    buildMap {
        put(TOOL_CONTEXT_USER_ID, user.id.value)
        callingAgentId?.let { put(TOOL_CONTEXT_AGENT_DEFINITION_ID, it.value) }
        conversationId?.let { put(TOOL_CONTEXT_CONVERSATION_ID, it.value) }
    },
    cancellationSignal,
)

internal suspend fun AiToolCallback.invokeStructured(context: ServerToolContext, arguments: JsonObject): JsonObject =
    checkNotNull(callResult(arguments.toString(), context.toExecutionContext()).structuredContent)

internal suspend fun AiToolCallback.invoke(context: ServerToolContext, arguments: JsonObject): CallToolResult =
    callResult(arguments.toString(), context.toExecutionContext()).toMcpResult()
