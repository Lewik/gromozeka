package com.gromozeka.server

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.PersonalAccessTokenService
import kotlinx.coroutines.runBlocking
import org.springframework.boot.test.context.TestComponent
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GromozekaMcpAuthenticationTest {
    private fun fixture() = SpringHttpTestFixture(McpAuthenticationTestController(),
        authentication = TestAuthenticationService, tokens = TestPersonalAccessTokenService)

    @Test
    fun `MCP endpoints reject requests without authentication`() = runBlocking<Unit> {
        fixture().use { http ->
            val response = http.perform(get("/mcp").secureTestRequest())
            assertEquals(401, response.status)
            assertNotNull(response.getHeader("WWW-Authenticate"))
            assertEquals(401, http.perform(get("/mcp/control").secureTestRequest()).status)
        }
    }

    @Test
    fun `unified MCP requires existing control scope and never upgrades memory-only tokens`() = runBlocking<Unit> {
        fixture().use { http ->
            assertEquals(401, http.perform(get("/mcp").secureTestRequest().header("Authorization", "Bearer memory-token")).status)
            assertEquals(200, http.perform(get("/mcp").secureTestRequest().header("Authorization", "Bearer control-token")).status)
            assertEquals(401, http.perform(get("/mcp").secureTestRequest().header("Authorization", "Bearer revoked-or-invalid")).status)
            assertEquals(404, http.perform(get("/mcp/control").secureTestRequest().header("Authorization", "Bearer control-token")).status)
        }
    }

    @Test
    fun `authenticated browser session uses unified MCP without weakening HTTPS`() = runBlocking<Unit> {
        fixture().use { http ->
            assertEquals(200, http.perform(get("/mcp").secureTestRequest("session-token")).status)
            val insecure = get("/mcp").secureTestRequest().secure(false).with { it.scheme = "http"; it }
                .header("Authorization", "Bearer control-token")
            assertEquals(426, http.perform(insecure).status)
        }
    }
}

@TestComponent
@RestController
private class McpAuthenticationTestController {
    @GetMapping("/mcp") fun tools(): String = "tools"
}

private object TestAuthenticationService : AuthenticationService {
    override suspend fun hasUsers(): Boolean = true

    override suspend fun createFirstUser(
        bootstrapToken: String,
        username: String,
        displayName: String,
        password: CharArray,
        clientLabel: String?,
    ): IssuedUserSession = unsupported()

    override suspend fun login(
        username: String,
        password: CharArray,
        clientLabel: String?,
    ): IssuedUserSession = unsupported()

    override suspend fun authenticate(sessionToken: String): AuthenticatedUser? =
        testUser.takeIf { sessionToken == "session-token" }
            ?.let { AuthenticatedUser(it, UserSession.Id("session")) }

    override suspend fun logout(sessionToken: String) = Unit

    override suspend fun revokeAllSessions(userId: User.Id) = Unit
}

private object TestPersonalAccessTokenService : PersonalAccessTokenService {
    override suspend fun issue(
        userId: User.Id,
        name: String,
        scopes: Set<PersonalAccessToken.Scope>,
        expiresAt: Instant?,
    ): IssuedPersonalAccessToken = unsupported()

    override suspend fun list(userId: User.Id): List<PersonalAccessToken> = emptyList()

    override suspend fun revoke(
        userId: User.Id,
        tokenId: PersonalAccessToken.Id,
    ): Boolean = false

    override suspend fun authenticate(
        rawToken: String,
        requiredScope: PersonalAccessToken.Scope,
    ): AuthenticatedPersonalAccessToken? {
        val expectedToken = when (requiredScope) {
            PersonalAccessToken.Scope.MCP_MEMORY -> "memory-token"
            PersonalAccessToken.Scope.MCP_CONTROL -> "control-token"
        }
        if (rawToken != expectedToken) return null
        return AuthenticatedPersonalAccessToken(
            user = testUser,
            token = testPersonalAccessToken(requiredScope),
        )
    }
}

private val testUser = User(
    id = User.Id("user"),
    identities = listOf(com.gromozeka.domain.model.UserIdentity.LocalLogin("user")),
    displayName = "User",
    status = User.Status.ACTIVE,
    createdAt = Instant.DISTANT_PAST,
    updatedAt = Instant.DISTANT_PAST,
)

private fun testPersonalAccessToken(scope: PersonalAccessToken.Scope) =
    PersonalAccessToken(
        id = PersonalAccessToken.Id(scope.name.lowercase()),
        userId = testUser.id,
        name = scope.name,
        tokenHash = "hash",
        tokenPrefix = "prefix",
        scopes = setOf(scope),
        createdAt = Instant.DISTANT_PAST,
        expiresAt = null,
        lastUsedAt = null,
        revokedAt = null,
    )

private fun <T> unsupported(): T = error("Unsupported test operation")

private const val MCP_URL = "https://localhost/mcp"
private const val OLD_CONTROL_MCP_URL = "https://localhost/mcp/control"
