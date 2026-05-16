package com.gromozeka.domain.repository

import com.gromozeka.domain.model.UserProfile

interface UserProfileRepository {
    suspend fun getCurrent(): UserProfile

    suspend fun save(profile: UserProfile)
}
