package com.gromozeka.presentation.ui

import androidx.compose.runtime.Composable
import com.gromozeka.presentation.services.translation.data.Translation

@Composable
actual fun PlatformFontFallback(translation: Translation, content: @Composable () -> Unit) = content()
