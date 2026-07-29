package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerProjectGrant
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.model.WorkerUserGrant
import com.gromozeka.domain.service.ConversationRuntimeWorkerId

interface WorkerAccessRepository {
    suspend fun findWorker(workerId: ConversationRuntimeWorkerId): WorkerResource?

    suspend fun listWorkers(): List<WorkerResource>

    suspend fun saveWorker(worker: WorkerResource): WorkerResource

    suspend fun findUserGrant(
        workerId: ConversationRuntimeWorkerId,
        userId: User.Id,
    ): WorkerUserGrant?

    suspend fun listUserGrants(workerId: ConversationRuntimeWorkerId): List<WorkerUserGrant>

    suspend fun findWorkerIdsGrantedToUser(userId: User.Id): Set<ConversationRuntimeWorkerId>

    suspend fun saveUserGrant(grant: WorkerUserGrant): WorkerUserGrant

    suspend fun deleteUserGrant(
        workerId: ConversationRuntimeWorkerId,
        userId: User.Id,
    ): Boolean

    suspend fun findProjectGrant(
        workerId: ConversationRuntimeWorkerId,
        projectId: Project.Id,
    ): WorkerProjectGrant?

    suspend fun listProjectGrants(workerId: ConversationRuntimeWorkerId): List<WorkerProjectGrant>

    suspend fun findWorkerIdsGrantedToProjects(
        projectIds: Set<Project.Id>,
    ): Set<ConversationRuntimeWorkerId>

    suspend fun saveProjectGrant(grant: WorkerProjectGrant): WorkerProjectGrant

    suspend fun deleteProjectGrant(
        workerId: ConversationRuntimeWorkerId,
        projectId: Project.Id,
    ): Boolean
}
