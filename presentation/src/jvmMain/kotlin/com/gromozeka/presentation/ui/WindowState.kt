package com.gromozeka.presentation.ui

import kotlinx.serialization.Serializable

@Serializable
data class UiWindowState(
    val x: Int = -1,     // -1 means not set (use system default)
    val y: Int = -1,     // -1 means not set (use system default)  
    val width: Int = 1200,
    val height: Int = 800,
    val isMaximized: Boolean = false,
)