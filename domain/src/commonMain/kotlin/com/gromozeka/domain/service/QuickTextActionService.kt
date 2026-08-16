package com.gromozeka.domain.service

import com.gromozeka.domain.model.QuickTextAction
import com.gromozeka.domain.model.QuickTextActionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface QuickTextActionService {
    suspend fun listActions(): List<QuickTextAction>

    fun observeActions(): Flow<List<QuickTextAction>> = flow {
        emit(listActions())
    }

    suspend fun runAction(
        actionId: QuickTextAction.Id,
        text: String,
    ): QuickTextActionResult
}
