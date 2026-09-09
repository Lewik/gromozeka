package com.gromozeka.domain.repository

import com.gromozeka.domain.model.TranslationState
import com.gromozeka.domain.model.User

interface TranslationRepository {
    suspend fun load(userId: User.Id): TranslationState

    suspend fun save(
        userId: User.Id,
        state: TranslationState,
        expectedRevision: Long,
    ): TranslationState
}
