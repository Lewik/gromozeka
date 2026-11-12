package com.gromozeka.domain.service

interface WorkerUpdateParticipant {
    suspend fun prepareForUpdate(): Boolean
    suspend fun resumeAfterUpdate()
}
