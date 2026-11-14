package com.gromozeka.presentation.ui.session

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

internal actual fun horizontalResizePointerIcon(): PointerIcon = PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))
