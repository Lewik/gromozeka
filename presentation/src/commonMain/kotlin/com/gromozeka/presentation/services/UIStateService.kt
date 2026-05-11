package com.gromozeka.presentation.services

import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import kotlinx.coroutines.CoroutineScope

class UIStateService(
    private val scope: CoroutineScope,
) {
    suspend fun initialize(appViewModel: AppViewModel) = Unit
    fun forceSave() = Unit
    fun disableAutoSave() = Unit
}
