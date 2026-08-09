package com.gromozeka.domain.repository

import com.gromozeka.domain.model.MobileWorkerContactObservation

interface MobileWorkerContactRepository {
    suspend fun record(observation: MobileWorkerContactObservation)
}
