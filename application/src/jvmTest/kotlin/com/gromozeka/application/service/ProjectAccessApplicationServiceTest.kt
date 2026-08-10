package com.gromozeka.application.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectMembership
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.ProjectAccessDeniedException
import com.gromozeka.domain.service.ProjectDomainService
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectAccessApplicationServiceTest {
    private val owner = activeUser("owner")
    private val editor = activeUser("editor")
    private val viewer = activeUser("viewer")
    private val outsider = activeUser("outsider")
    private val identityRepository = FakeIdentityRepository().apply {
        users += listOf(owner, editor, viewer, outsider)
    }
    private val membershipRepository = FakeProjectMembershipRepository()
    private val projectService = FakeProjectDomainService()
    private val securityAuditRecorder = FakeSecurityAuditRecorder()
    private val service = ProjectAccessApplicationService(
        projectService = projectService,
        membershipRepository = membershipRepository,
        identityRepository = identityRepository,
        securityAuditRecorder = securityAuditRecorder,
    )

    @Test
    fun `project creator becomes its owner`() = runSuspend {
        val project = service.create(owner.id, "Secure project")

        assertEquals(project, service.findById(owner.id, project.id))
        assertEquals(
            ProjectMembership.Role.OWNER,
            membershipRepository.find(project.id, owner.id)?.role,
        )
        assertEquals(null, service.findById(outsider.id, project.id))
        assertEquals(SecurityAuditEvent.Action.PROJECT_CREATED, securityAuditRecorder.records.single().action)
    }

    @Test
    fun `roles grant only their declared permissions`() = runSuspend {
        val project = service.create(owner.id, "Shared project")
        service.setMembership(owner.id, project.id, editor.id, ProjectMembership.Role.EDITOR)
        service.setMembership(owner.id, project.id, viewer.id, ProjectMembership.Role.VIEWER)

        assertTrue(service.can(editor.id, project.id, ProjectPermission.READ))
        assertTrue(service.can(editor.id, project.id, ProjectPermission.WRITE))
        assertFalse(service.can(editor.id, project.id, ProjectPermission.ADMIN))
        assertTrue(service.can(viewer.id, project.id, ProjectPermission.READ))
        assertFalse(service.can(viewer.id, project.id, ProjectPermission.WRITE))
        assertFalse(service.can(outsider.id, project.id, ProjectPermission.READ))

        service.update(editor.id, project.id, "Edited", null)
        assertFailsWith<ProjectAccessDeniedException> {
            service.update(viewer.id, project.id, "Rejected", null)
        }
    }

    @Test
    fun `last project owner cannot be removed or demoted`() = runSuspend {
        val project = service.create(owner.id, "Protected project")

        assertFailsWith<IllegalStateException> {
            service.removeMembership(owner.id, project.id, owner.id)
        }
        assertFailsWith<IllegalStateException> {
            service.setMembership(
                owner.id,
                project.id,
                owner.id,
                ProjectMembership.Role.EDITOR,
            )
        }
    }

    @Test
    fun `project listings contain only memberships`() = runSuspend {
        val owned = service.create(owner.id, "Owned")
        val shared = service.create(outsider.id, "Shared")
        service.setMembership(outsider.id, shared.id, owner.id, ProjectMembership.Role.VIEWER)
        service.create(viewer.id, "Hidden")

        assertEquals(
            setOf(owned.id, shared.id),
            service.findAll(owner.id).mapTo(mutableSetOf(), Project::id),
        )
    }
}

private class FakeProjectDomainService : ProjectDomainService {
    private val projects = linkedMapOf<Project.Id, Project>()

    override suspend fun create(
        name: String,
        description: String?,
        id: Project.Id?,
    ): Project {
        val now = Clock.System.now()
        val project = Project(
            id = id ?: Project.Id("project-${projects.size + 1}"),
            name = name,
            description = description,
            createdAt = now,
            lastUsedAt = now,
        )
        check(projects.putIfAbsent(project.id, project) == null)
        return project
    }

    override suspend fun findById(id: Project.Id): Project? = projects[id]

    override suspend fun findRecent(limit: Int): List<Project> =
        projects.values.take(limit)

    override suspend fun findAll(): List<Project> = projects.values.toList()

    override suspend fun update(
        id: Project.Id,
        name: String,
        description: String?,
    ): Project =
        projects.getValue(id)
            .copy(name = name, description = description)
            .also { projects[id] = it }

    override suspend fun delete(id: Project.Id) {
        check(projects.remove(id) != null)
    }

    override suspend fun updateLastUsed(id: Project.Id): Project? =
        projects[id]
            ?.copy(lastUsedAt = Clock.System.now())
            ?.also { projects[id] = it }
}

private fun activeUser(id: String): User {
    val now = Clock.System.now()
    return User(
        id = User.Id(id),
        username = id,
        displayName = id,
        status = User.Status.ACTIVE,
        createdAt = now,
        updatedAt = now,
    )
}

private fun runSuspend(block: suspend () -> Unit) =
    kotlinx.coroutines.runBlocking { block() }
