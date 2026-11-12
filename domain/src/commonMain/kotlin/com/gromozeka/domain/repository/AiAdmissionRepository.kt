package com.gromozeka.domain.repository

import com.gromozeka.domain.model.ai.AiAdmissionLease
import com.gromozeka.domain.model.ai.AiTpmState
import com.gromozeka.domain.model.ai.AiAdmissionCompletionResult

interface AiAdmissionRepository {
    /** A new Server process cannot still own a local operation. Remote operations remain charged. */
    suspend fun restoreRemoteLeases(): List<AiAdmissionLease>
    suspend fun findCompletion(id: String, workerId: String?): AiAdmissionCompletionResult?
    suspend fun loadTpmStates(): List<AiTpmState>
    suspend fun create(lease: AiAdmissionLease, tpmStates: List<AiTpmState> = emptyList())
    suspend fun accept(id: String, workerId: String)
    suspend fun finish(id: String, workerId: String? = null, tpmStates: List<AiTpmState> = emptyList(), feedbackError: String? = null)
    suspend fun finishWorkerRequest(workerId: String, requestId: String)
}
