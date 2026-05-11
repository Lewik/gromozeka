package com.gromozeka.presentation.ui

import androidx.compose.ui.Modifier

internal actual fun Modifier.onTabHover(
    onEnter: () -> Unit,
    onExit: () -> Unit,
): Modifier = this
