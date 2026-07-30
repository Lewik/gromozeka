package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerProjectGrant
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.model.WorkerUserGrant
import com.gromozeka.domain.repository.WorkerAccessRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.infrastructure.db.persistence.tables.WorkerProjectGrants
import com.gromozeka.infrastructure.db.persistence.tables.WorkerUserGrants
import com.gromozeka.infrastructure.db.persistence.tables.Workers
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedWorkerAccessRepository : WorkerAccessRepository {
    override suspend fun findWorker(workerId: ConversationRuntimeWorkerId): WorkerResource? = dbQuery {
        Workers.selectAll()
            .where { Workers.id eq workerId.value }
            .singleOrNull()
            ?.toWorker()
    }

    override suspend fun listWorkers(): List<WorkerResource> = dbQuery {
        Workers.selectAll()
            .orderBy(Workers.displayName)
            .map { it.toWorker() }
    }

    override suspend fun saveWorker(worker: WorkerResource): WorkerResource = dbQuery {
        val updated = Workers.update({ Workers.id eq worker.id.value }) {
            it[displayName] = worker.displayName
            it[ownerUserId] = worker.ownerUserId.value
            it[runtimeWideAccess] = worker.runtimeWideAccess
            it[status] = worker.status.name
            it[updatedAt] = worker.updatedAt.toKotlin()
        }
        if (updated == 0) {
            Workers.insert {
                it[id] = worker.id.value
                it[displayName] = worker.displayName
                it[ownerUserId] = worker.ownerUserId.value
                it[runtimeWideAccess] = worker.runtimeWideAccess
                it[status] = worker.status.name
                it[createdAt] = worker.createdAt.toKotlin()
                it[updatedAt] = worker.updatedAt.toKotlin()
            }
        }
        worker
    }

    override suspend fun findUserGrant(
        workerId: ConversationRuntimeWorkerId,
        userId: User.Id,
    ): WorkerUserGrant? = dbQuery {
        WorkerUserGrants.selectAll()
            .where {
                (WorkerUserGrants.workerId eq workerId.value) and
                    (WorkerUserGrants.userId eq userId.value)
            }
            .singleOrNull()
            ?.toUserGrant()
    }

    override suspend fun listUserGrants(workerId: ConversationRuntimeWorkerId): List<WorkerUserGrant> = dbQuery {
        WorkerUserGrants.selectAll()
            .where { WorkerUserGrants.workerId eq workerId.value }
            .map { it.toUserGrant() }
    }

    override suspend fun findWorkerIdsGrantedToUser(
        userId: User.Id,
    ): Set<ConversationRuntimeWorkerId> = dbQuery {
        WorkerUserGrants.selectAll()
            .where { WorkerUserGrants.userId eq userId.value }
            .mapTo(mutableSetOf()) { ConversationRuntimeWorkerId(it[WorkerUserGrants.workerId]) }
    }

    override suspend fun saveUserGrant(grant: WorkerUserGrant): WorkerUserGrant = dbQuery {
        val existing = WorkerUserGrants.selectAll()
            .where {
                (WorkerUserGrants.workerId eq grant.workerId.value) and
                    (WorkerUserGrants.userId eq grant.userId.value)
            }
            .singleOrNull()
        if (existing == null) {
            WorkerUserGrants.insert {
                it[workerId] = grant.workerId.value
                it[userId] = grant.userId.value
                it[createdAt] = grant.createdAt.toKotlin()
                it[createdByUserId] = grant.createdByUserId.value
            }
        }
        existing?.toUserGrant() ?: grant
    }

    override suspend fun deleteUserGrant(
        workerId: ConversationRuntimeWorkerId,
        userId: User.Id,
    ): Boolean = dbQuery {
        WorkerUserGrants.deleteWhere {
            (WorkerUserGrants.workerId eq workerId.value) and
                (WorkerUserGrants.userId eq userId.value)
        } == 1
    }

    override suspend fun findProjectGrant(
        workerId: ConversationRuntimeWorkerId,
        projectId: Project.Id,
    ): WorkerProjectGrant? = dbQuery {
        WorkerProjectGrants.selectAll()
            .where {
                (WorkerProjectGrants.workerId eq workerId.value) and
                    (WorkerProjectGrants.projectId eq projectId.value)
            }
            .singleOrNull()
            ?.toProjectGrant()
    }

    override suspend fun listProjectGrants(
        workerId: ConversationRuntimeWorkerId,
    ): List<WorkerProjectGrant> = dbQuery {
        WorkerProjectGrants.selectAll()
            .where { WorkerProjectGrants.workerId eq workerId.value }
            .map { it.toProjectGrant() }
    }

    override suspend fun findWorkerIdsGrantedToProjects(
        projectIds: Set<Project.Id>,
    ): Set<ConversationRuntimeWorkerId> {
        if (projectIds.isEmpty()) {
            return emptySet()
        }
        return dbQuery {
            WorkerProjectGrants.selectAll()
                .where { WorkerProjectGrants.projectId inList projectIds.map(Project.Id::value) }
                .mapTo(mutableSetOf()) { ConversationRuntimeWorkerId(it[WorkerProjectGrants.workerId]) }
        }
    }

    override suspend fun saveProjectGrant(grant: WorkerProjectGrant): WorkerProjectGrant = dbQuery {
        val existing = WorkerProjectGrants.selectAll()
            .where {
                (WorkerProjectGrants.workerId eq grant.workerId.value) and
                    (WorkerProjectGrants.projectId eq grant.projectId.value)
            }
            .singleOrNull()
        if (existing == null) {
            WorkerProjectGrants.insert {
                it[workerId] = grant.workerId.value
                it[projectId] = grant.projectId.value
                it[createdAt] = grant.createdAt.toKotlin()
                it[createdByUserId] = grant.createdByUserId.value
            }
        }
        existing?.toProjectGrant() ?: grant
    }

    override suspend fun deleteProjectGrant(
        workerId: ConversationRuntimeWorkerId,
        projectId: Project.Id,
    ): Boolean = dbQuery {
        WorkerProjectGrants.deleteWhere {
            (WorkerProjectGrants.workerId eq workerId.value) and
                (WorkerProjectGrants.projectId eq projectId.value)
        } == 1
    }

    private fun ResultRow.toWorker(): WorkerResource =
        WorkerResource(
            id = ConversationRuntimeWorkerId(this[Workers.id]),
            displayName = this[Workers.displayName],
            ownerUserId = User.Id(this[Workers.ownerUserId]),
            runtimeWideAccess = this[Workers.runtimeWideAccess],
            status = WorkerResource.Status.valueOf(this[Workers.status]),
            createdAt = this[Workers.createdAt].toKotlinx(),
            updatedAt = this[Workers.updatedAt].toKotlinx(),
        )

    private fun ResultRow.toUserGrant(): WorkerUserGrant =
        WorkerUserGrant(
            workerId = ConversationRuntimeWorkerId(this[WorkerUserGrants.workerId]),
            userId = User.Id(this[WorkerUserGrants.userId]),
            createdAt = this[WorkerUserGrants.createdAt].toKotlinx(),
            createdByUserId = User.Id(this[WorkerUserGrants.createdByUserId]),
        )

    private fun ResultRow.toProjectGrant(): WorkerProjectGrant =
        WorkerProjectGrant(
            workerId = ConversationRuntimeWorkerId(this[WorkerProjectGrants.workerId]),
            projectId = Project.Id(this[WorkerProjectGrants.projectId]),
            createdAt = this[WorkerProjectGrants.createdAt].toKotlinx(),
            createdByUserId = User.Id(this[WorkerProjectGrants.createdByUserId]),
        )
}
