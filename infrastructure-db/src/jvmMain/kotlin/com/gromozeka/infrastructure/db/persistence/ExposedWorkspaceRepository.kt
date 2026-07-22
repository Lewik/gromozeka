package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.repository.WorkspaceRepository
import com.gromozeka.infrastructure.db.persistence.tables.WorkspaceMounts
import com.gromozeka.infrastructure.db.persistence.tables.Workspaces
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedWorkspaceRepository : WorkspaceRepository {
    override suspend fun save(workspace: Workspace): Workspace = dbQuery {
        val exists = Workspaces.selectAll().where { Workspaces.id eq workspace.id.value }.count() > 0
        if (exists) {
            Workspaces.update({ Workspaces.id eq workspace.id.value }) {
                it[projectId] = workspace.projectId.value
                it[name] = workspace.name
                it[kind] = workspace.kind.name
                it[updatedAt] = workspace.updatedAt.toKotlin()
            }
        } else {
            Workspaces.insert {
                it[id] = workspace.id.value
                it[projectId] = workspace.projectId.value
                it[name] = workspace.name
                it[kind] = workspace.kind.name
                it[createdAt] = workspace.createdAt.toKotlin()
                it[updatedAt] = workspace.updatedAt.toKotlin()
            }
        }
        workspace
    }

    override suspend fun findById(id: Workspace.Id): Workspace? = dbQuery {
        Workspaces.selectAll()
            .where { Workspaces.id eq id.value }
            .singleOrNull()
            ?.toWorkspace()
    }

    override suspend fun findByProject(projectId: Project.Id): List<Workspace> = dbQuery {
        Workspaces.selectAll()
            .where { Workspaces.projectId eq projectId.value }
            .map { it.toWorkspace() }
    }

    override suspend fun delete(id: Workspace.Id): Unit = dbQuery {
        Workspaces.deleteWhere { Workspaces.id eq id.value }
    }

    override suspend fun saveMount(mount: WorkspaceMount): WorkspaceMount = dbQuery {
        val predicate = WorkspaceMounts.id eq mount.id.value
        val exists = WorkspaceMounts.selectAll().where { predicate }.count() > 0
        if (exists) {
            WorkspaceMounts.update({ predicate }) {
                it[workspaceId] = mount.workspaceId.value
                it[workerId] = mount.workerId
                it[rootPath] = mount.rootPath
                it[updatedAt] = mount.updatedAt.toKotlin()
            }
        } else {
            WorkspaceMounts.insert {
                it[id] = mount.id.value
                it[workspaceId] = mount.workspaceId.value
                it[workerId] = mount.workerId
                it[rootPath] = mount.rootPath
                it[createdAt] = mount.createdAt.toKotlin()
                it[updatedAt] = mount.updatedAt.toKotlin()
            }
        }
        mount
    }

    override suspend fun findMount(id: WorkspaceMount.Id): WorkspaceMount? = dbQuery {
        WorkspaceMounts.selectAll()
            .where { WorkspaceMounts.id eq id.value }
            .singleOrNull()
            ?.toWorkspaceMount()
    }

    override suspend fun findMount(workspaceId: Workspace.Id, workerId: String): WorkspaceMount? = dbQuery {
        WorkspaceMounts.selectAll()
            .where {
                (WorkspaceMounts.workspaceId eq workspaceId.value) and
                    (WorkspaceMounts.workerId eq workerId)
            }
            .singleOrNull()
            ?.toWorkspaceMount()
    }

    override suspend fun findMountByPath(workerId: String, rootPath: String): WorkspaceMount? = dbQuery {
        WorkspaceMounts.selectAll()
            .where {
                (WorkspaceMounts.workerId eq workerId) and
                    (WorkspaceMounts.rootPath eq rootPath)
            }
            .singleOrNull()
            ?.toWorkspaceMount()
    }

    override suspend fun findMounts(workspaceId: Workspace.Id): List<WorkspaceMount> = dbQuery {
        WorkspaceMounts.selectAll()
            .where { WorkspaceMounts.workspaceId eq workspaceId.value }
            .map { it.toWorkspaceMount() }
    }

    override suspend fun findMountsByWorker(workerId: String): List<WorkspaceMount> = dbQuery {
        WorkspaceMounts.selectAll()
            .where { WorkspaceMounts.workerId eq workerId }
            .map { it.toWorkspaceMount() }
    }

    override suspend fun deleteMount(id: WorkspaceMount.Id): Unit = dbQuery {
        WorkspaceMounts.deleteWhere { WorkspaceMounts.id eq id.value }
    }

    private fun ResultRow.toWorkspace(): Workspace =
        Workspace(
            id = Workspace.Id(this[Workspaces.id]),
            projectId = Project.Id(this[Workspaces.projectId]),
            name = this[Workspaces.name],
            kind = Workspace.Kind.valueOf(this[Workspaces.kind]),
            createdAt = this[Workspaces.createdAt].toKotlinx(),
            updatedAt = this[Workspaces.updatedAt].toKotlinx(),
        )

    private fun ResultRow.toWorkspaceMount(): WorkspaceMount =
        WorkspaceMount(
            id = WorkspaceMount.Id(this[WorkspaceMounts.id]),
            workspaceId = Workspace.Id(this[WorkspaceMounts.workspaceId]),
            workerId = this[WorkspaceMounts.workerId],
            rootPath = this[WorkspaceMounts.rootPath],
            createdAt = this[WorkspaceMounts.createdAt].toKotlinx(),
            updatedAt = this[WorkspaceMounts.updatedAt].toKotlinx(),
        )
}
