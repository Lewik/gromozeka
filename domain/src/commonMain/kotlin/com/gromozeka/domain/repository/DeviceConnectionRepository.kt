package com.gromozeka.domain.repository

import com.gromozeka.domain.model.DeviceConnection
import com.gromozeka.domain.model.DeviceConnectionConsumption
import com.gromozeka.domain.model.DeviceConnectionDecision
import com.gromozeka.domain.model.DeviceConnectionSessionCredential
import com.gromozeka.domain.model.User
import kotlinx.datetime.Instant

interface DeviceConnectionRepository {
    suspend fun create(connection: DeviceConnection): Boolean

    suspend fun findPendingByUserCode(
        userCode: String,
        now: Instant,
    ): DeviceConnection?

    suspend fun approve(
        userCode: String,
        userId: User.Id,
        decidedAt: Instant,
    ): DeviceConnectionDecision?

    suspend fun deny(
        userCode: String,
        userId: User.Id,
        decidedAt: Instant,
    ): DeviceConnectionDecision?

    suspend fun findBySecretHash(secretHash: String): DeviceConnection?

    suspend fun consume(
        secretHash: String,
        consumedAt: Instant,
        sessionCredential: DeviceConnectionSessionCredential?,
        workerCredentialHash: String?,
    ): DeviceConnectionConsumption?
}
