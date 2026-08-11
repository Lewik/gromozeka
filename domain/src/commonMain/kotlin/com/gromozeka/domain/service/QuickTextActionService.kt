package com.gromozeka.domain.service

import com.gromozeka.domain.model.QuickTextAction
import com.gromozeka.domain.model.QuickTextActionResult

interface QuickTextActionService {
    suspend fun listActions(): List<QuickTextAction>

    suspend fun runAction(
        actionId: QuickTextAction.Id,
        text: String,
    ): QuickTextActionResult
}
