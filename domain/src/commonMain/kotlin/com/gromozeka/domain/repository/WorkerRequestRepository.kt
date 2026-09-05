package com.gromozeka.domain.repository

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.StoredWorkerRequest
import com.gromozeka.domain.service.PendingWorkerRequest
import com.gromozeka.domain.service.WorkerRequestProgress
import kotlin.time.Instant

interface WorkerRequestRepository {
    suspend fun create(request: StoredWorkerRequest)
    suspend fun find(id: String): StoredWorkerRequest?
    suspend fun progress(id: String): WorkerRequestProgress?
    suspend fun pending(workerId: ConversationRuntimeWorkerId, limit: Int): List<PendingWorkerRequest>
    suspend fun markDispatched(id: String, at: Instant): Boolean
    suspend fun cancel(id: String, at: Instant)
    suspend fun complete(workerId: ConversationRuntimeWorkerId, id: String, response: ByteArray, at: Instant, onlyIfUndispatched: Boolean = false): Boolean
}
