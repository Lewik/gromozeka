package com.gromozeka.presentation

import com.gromozeka.presentation.ui.state.UIState

fun getTabDisplayName(tabUiState: UIState.Tab, index: Int): String =
    tabUiState.customName?.takeIf { it.isNotBlank() } ?: tabUiState.agent.name
