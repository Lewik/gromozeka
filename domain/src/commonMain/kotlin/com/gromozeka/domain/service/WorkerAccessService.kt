package com.gromozeka.domain.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.model.WorkerProjectGrant
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.model.WorkerUserGrant

interface WorkerAccessService {
    suspend fun findAccessible(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
        projectId: Project.Id? = null,
    ): WorkerResource?

    suspend fun listAccessible(actor: User): List<WorkerResource>

    suspend fun listAvailableToProject(projectId: Project.Id): List<WorkerResource>

    suspend fun requirePermission(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
        permission: WorkerPermission,
        projectId: Project.Id? = null,
    ): WorkerResource

    suspend fun requireProjectAccess(
        workerId: ConversationRuntimeWorkerId,
        projectId: Project.Id,
    ): WorkerResource

    suspend fun listUserGrants(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
    ): List<WorkerUserGrant>

    suspend fun grantUser(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
        userId: User.Id,
    ): WorkerUserGrant

    suspend fun revokeUser(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
        userId: User.Id,
    ): Boolean

    suspend fun listProjectGrants(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
    ): List<WorkerProjectGrant>

    suspend fun grantProject(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
        projectId: Project.Id,
    ): WorkerProjectGrant

    suspend fun revokeProject(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
        projectId: Project.Id,
    ): Boolean

    suspend fun setOrganizationAccess(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
        enabled: Boolean,
    ): WorkerResource

    suspend fun revokeWorker(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
    ): WorkerResource
}

class WorkerAccessDeniedException :
    IllegalStateException("Worker is unavailable or access is denied")
