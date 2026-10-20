package com.gromozeka.server

import com.gromozeka.domain.model.AuthenticatedPersonalAccessToken
import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.IssuedPersonalAccessToken
import com.gromozeka.domain.model.IssuedUserSession
import com.gromozeka.domain.model.PersonalAccessToken
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.PersonalAccessTokenService
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GromozekaMcpAuthenticationTest {
    @Test
    fun `MCP endpoints reject requests without authentication`() = testApplication {
        application {
            installTestMcpRoutes()
        }

        val memoryResponse = client.get(MCP_URL)
        val controlResponse = client.get(OLD_CONTROL_MCP_URL)

        assertEquals(HttpStatusCode.Unauthorized, memoryResponse.status)
        assertEquals(HttpStatusCode.Unauthorized, controlResponse.status)
        assertNotNull(memoryResponse.headers[HttpHeaders.WWWAuthenticate])
    }

    @Test
    fun `unified MCP requires existing control scope and never upgrades memory-only tokens`() = testApplication {
        application { installTestMcpRoutes() }
        assertEquals(HttpStatusCode.Unauthorized, client.get(MCP_URL) {
            header(HttpHeaders.Authorization, "Bearer memory-token")
        }.status)
        assertEquals(HttpStatusCode.OK, client.get(MCP_URL) {
            header(HttpHeaders.Authorization, "Bearer control-token")
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(MCP_URL) {
            header(HttpHeaders.Authorization, "Bearer revoked-or-invalid")
        }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(OLD_CONTROL_MCP_URL) {
            header(HttpHeaders.Authorization, "Bearer control-token")
        }.status)
    }

    @Test
    fun `authenticated browser session uses unified MCP without weakening HTTPS`() = testApplication {
        application { installTestMcpRoutes() }
        assertEquals(HttpStatusCode.OK, client.get(MCP_URL) { cookie(SESSION_COOKIE_NAME, "session-token") }.status)
        assertEquals(HttpStatusCode.UpgradeRequired, client.get("http://localhost/mcp") {
            header(HttpHeaders.Authorization, "Bearer control-token")
        }.status)
    }

}

private fun io.ktor.server.application.Application.installTestMcpRoutes() {
    installHttpAuthenticationErrors()
    installMcpAuthentication(
        authenticationService = TestAuthenticationService,
        personalAccessTokenService = TestPersonalAccessTokenService,
    )
    routing {
        get("/mcp") {
            call.respondText("tools")
        }
    }
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
