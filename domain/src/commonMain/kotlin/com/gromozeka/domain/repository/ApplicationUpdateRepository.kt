package com.gromozeka.domain.repository

import com.gromozeka.domain.model.ApplicationInstallation

interface ApplicationUpdateRepository {
    suspend fun list(): List<ApplicationInstallation>
    suspend fun find(id: String): ApplicationInstallation?
    suspend fun modify(
        id: String,
        initial: ApplicationInstallation? = null,
        transform: (ApplicationInstallation) -> ApplicationInstallation,
    ): ApplicationInstallation
}
