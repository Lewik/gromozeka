package com.gromozeka.domain.repository

import com.gromozeka.domain.model.WorkerContactObservation

interface WorkerContactRepository {
    suspend fun record(observation: WorkerContactObservation)
}
