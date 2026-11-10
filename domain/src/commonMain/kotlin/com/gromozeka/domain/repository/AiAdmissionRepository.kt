package com.gromozeka.domain.repository

import com.gromozeka.domain.model.ai.AiAdmissionLease

interface AiAdmissionRepository {
    /** A new Server process cannot still own a local operation. Remote operations remain charged. */
    suspend fun restoreRemoteLeases(): List<AiAdmissionLease>
    suspend fun create(lease: AiAdmissionLease)
    suspend fun accept(id: String, workerId: String)
    suspend fun finish(id: String, workerId: String? = null)
    suspend fun finishWorkerRequest(workerId: String, requestId: String)
}
