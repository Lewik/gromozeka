package com.gromozeka.server

import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.repository.WorkerAccessRepository
import com.gromozeka.domain.repository.ProjectRepository
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.StoredWorkerRequest
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.WorkerAccessDeniedException
import com.gromozeka.domain.service.ProjectAccessDeniedException
import org.springframework.stereotype.Service

fun interface WorkerRequestAuthorization {
    suspend fun requireAccess(request: StoredWorkerRequest)
}

@Service
class DefaultWorkerRequestAuthorization(
    private val workers: WorkerAccessRepository,
    private val workerAccess: WorkerAccessService,
    private val users: UserDirectoryService,
    private val projectAccess: ProjectAccessService,
    private val projects: ProjectRepository,
) : WorkerRequestAuthorization {
    override suspend fun requireAccess(request: StoredWorkerRequest) {
        workers.findWorker(request.workerId)
            ?.takeIf { it.status == WorkerResource.Status.ACTIVE } ?: throw WorkerAccessDeniedException()
        request.projectId?.let {
            if (projects.findById(it) == null) throw ProjectAccessDeniedException()
            workerAccess.requireProjectAccess(request.workerId, it)
        }
        request.actorUserId?.let { userId ->
            val user = users.findActiveById(userId) ?: throw WorkerAccessDeniedException()
            request.projectId?.let { projectAccess.requirePermission(userId, it, ProjectPermission.READ) }
            workerAccess.requirePermission(user, request.workerId, WorkerPermission.USE, request.projectId)
        }
    }
}
