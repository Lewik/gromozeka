package com.gromozeka.application.service

import com.gromozeka.domain.model.LocalPasswordCredential
import com.gromozeka.domain.model.PersonalAccessToken
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectMembership
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.service.LastActiveRuntimeOwnerException
import com.gromozeka.domain.service.PasswordHasher
import com.gromozeka.domain.service.SoleProjectOwnerException
import com.gromozeka.domain.service.UserAdministrationDeniedException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class UserAdministrationApplicationServiceTest {
    private val identityRepository = FakeIdentityRepository()
    private val projectMembershipRepository = FakeProjectMembershipRepository()
    private val service = UserAdministrationApplicationService(
        identityRepository = identityRepository,
        projectMembershipRepository = projectMembershipRepository,
        passwordHasher = UserAdministrationPasswordHasher(),
    )
    private val owner = user("owner", User.Role.OWNER)

    init {
        identityRepository.users += owner
        identityRepository.credentials += credential(owner)
    }

    @Test
    fun `runtime owner creates normalized user`() = runUserAdministrationTest {
        val created = service.create(
            actor = owner,
            username = " Developer ",
            displayName = "Developer",
            password = "sufficiently long password".toCharArray(),
            role = User.Role.MEMBER,
        )

        assertEquals("developer", created.username)
        assertEquals(User.Role.MEMBER, created.role)
        assertNotNull(identityRepository.findPasswordCredential(created.id))
    }

    @Test
    fun `member cannot administer users`() = runUserAdministrationTest {
        val member = service.create(
            actor = owner,
            username = "member",
            displayName = "Member",
            password = "sufficiently long password".toCharArray(),
            role = User.Role.MEMBER,
        )

        assertFailsWith<UserAdministrationDeniedException> {
            service.list(member)
        }
    }

    @Test
    fun `last active runtime owner cannot be demoted`() = runUserAdministrationTest {
        assertFailsWith<LastActiveRuntimeOwnerException> {
            service.update(
                actor = owner,
                userId = owner.id,
                displayName = owner.displayName,
                status = User.Status.ACTIVE,
                role = User.Role.MEMBER,
            )
        }
    }

    @Test
    fun `role change revokes sessions and disabling revokes personal access tokens`() =
        runUserAdministrationTest {
        val secondOwner = service.create(
            actor = owner,
            username = "second-owner",
            displayName = "Second Owner",
            password = "sufficiently long password".toCharArray(),
            role = User.Role.OWNER,
        )
        identityRepository.sessions += session(secondOwner)
        identityRepository.personalAccessTokens += personalAccessToken(secondOwner)

        service.update(
            actor = owner,
            userId = secondOwner.id,
            displayName = secondOwner.displayName,
            status = User.Status.DISABLED,
            role = User.Role.MEMBER,
        )

        assertTrue(identityRepository.sessions.single().isRevoked)
        assertTrue(identityRepository.personalAccessTokens.single().isRevoked)
    }

    @Test
    fun `sole project owner cannot be disabled`() = runUserAdministrationTest {
        val projectOwner = service.create(
            actor = owner,
            username = "project-owner",
            displayName = "Project Owner",
            password = "sufficiently long password".toCharArray(),
            role = User.Role.MEMBER,
        )
        projectMembershipRepository.save(
            ProjectMembership(
                projectId = Project.Id("project"),
                userId = projectOwner.id,
                role = ProjectMembership.Role.OWNER,
                createdAt = Clock.System.now(),
                createdByUserId = owner.id,
            )
        )

        assertFailsWith<SoleProjectOwnerException> {
            service.update(
                actor = owner,
                userId = projectOwner.id,
                displayName = projectOwner.displayName,
                status = User.Status.DISABLED,
                role = projectOwner.role,
            )
        }
    }

    @Test
    fun `password reset revokes sessions and personal access tokens`() = runUserAdministrationTest {
        val member = service.create(
            actor = owner,
            username = "member-reset",
            displayName = "Member Reset",
            password = "sufficiently long password".toCharArray(),
            role = User.Role.MEMBER,
        )
        identityRepository.sessions += session(member)
        identityRepository.personalAccessTokens += personalAccessToken(member)

        service.resetPassword(
            actor = owner,
            userId = member.id,
            password = "another sufficiently long password".toCharArray(),
        )

        assertTrue(identityRepository.sessions.single().isRevoked)
        assertTrue(identityRepository.personalAccessTokens.single().isRevoked)
        assertEquals(
            "hash:another sufficiently long password",
            identityRepository.findPasswordCredential(member.id)?.passwordHash,
        )
    }

    private fun user(
        username: String,
        role: User.Role,
    ): User {
        val now = Clock.System.now()
        return User(
            id = User.Id(username),
            username = username,
            displayName = username,
            status = User.Status.ACTIVE,
            role = role,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun credential(user: User) =
        LocalPasswordCredential(
            userId = user.id,
            passwordHash = "hash:password",
            passwordChangedAt = Clock.System.now(),
        )

    private fun session(user: User): UserSession {
        val now = Clock.System.now()
        return UserSession(
            id = UserSession.Id("session-${user.id.value}"),
            userId = user.id,
            tokenHash = "session-hash",
            createdAt = now,
            lastSeenAt = now,
            expiresAt = now + 1.days,
            revokedAt = null,
            clientLabel = null,
        )
    }

    private fun personalAccessToken(user: User): PersonalAccessToken {
        val now = Clock.System.now()
        return PersonalAccessToken(
            id = PersonalAccessToken.Id("token-${user.id.value}"),
            userId = user.id,
            name = "token",
            tokenHash = "token-hash",
            tokenPrefix = "grz_",
            scopes = setOf(PersonalAccessToken.Scope.MCP_CONTROL),
            createdAt = now,
            expiresAt = null,
            lastUsedAt = null,
            revokedAt = null,
        )
    }
}

private class UserAdministrationPasswordHasher : PasswordHasher {
    override fun hash(password: CharArray): String = "hash:${password.concatToString()}"
    override fun verify(password: CharArray, passwordHash: String): Boolean = hash(password) == passwordHash
    override fun needsRehash(passwordHash: String): Boolean = false
}

private fun runUserAdministrationTest(block: suspend () -> Unit) =
    runBlocking { block() }
