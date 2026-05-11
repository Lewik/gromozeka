package com.gromozeka.presentation.ui

import androidx.compose.ui.Modifier

internal expect fun Modifier.onTabHover(
    onEnter: () -> Unit,
    onExit: () -> Unit,
): Modifier
