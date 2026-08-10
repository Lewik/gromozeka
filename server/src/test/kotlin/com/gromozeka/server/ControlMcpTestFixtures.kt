package com.gromozeka.server

import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import kotlin.time.Instant

internal fun testControlMcpCaller(
    role: User.Role = User.Role.OWNER,
): AuthenticatedMcpCaller =
    AuthenticatedMcpCaller.UserSession(
        AuthenticatedUser(
            user = User(
                id = User.Id("control-mcp-test-user"),
                username = "control-mcp-test-user",
                displayName = "Control MCP Test User",
                status = User.Status.ACTIVE,
                role = role,
                createdAt = Instant.fromEpochMilliseconds(1),
                updatedAt = Instant.fromEpochMilliseconds(1),
            ),
            sessionId = UserSession.Id("control-mcp-test-session"),
        )
    )

internal fun testControlMcpContext(
    role: User.Role = User.Role.OWNER,
): ControlMcpCallContext =
    ControlMcpCallContext(testControlMcpCaller(role).user)
