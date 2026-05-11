package com.gromozeka.presentation.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.gromozeka.presentation.services.translation.data.EnglishTranslation
import com.gromozeka.presentation.services.translation.data.Translation

val LocalTranslation = compositionLocalOf<Translation> { Translation.builtIn[EnglishTranslation.LANGUAGE_CODE]!! }

@Composable
fun TranslationProvider(currentTranslation: Translation, content: @Composable () -> Unit) {
    val layoutDirection = when (currentTranslation.textDirection) {
        Translation.TextDirection.RTL -> LayoutDirection.Rtl
        Translation.TextDirection.LTR -> LayoutDirection.Ltr
    }

    CompositionLocalProvider(
        LocalTranslation provides currentTranslation,
        LocalLayoutDirection provides layoutDirection
    ) {
        content()
    }
}
