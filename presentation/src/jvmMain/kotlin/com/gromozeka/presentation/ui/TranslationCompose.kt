package com.gromozeka.presentation.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.gromozeka.presentation.services.translation.TranslationService
import com.gromozeka.presentation.services.translation.data.EnglishTranslation
import com.gromozeka.presentation.services.translation.data.Translation

val LocalTranslation = compositionLocalOf<Translation> { Translation.builtIn[EnglishTranslation.LANGUAGE_CODE]!! }

@Composable
fun TranslationProvider(translationService: TranslationService, content: @Composable () -> Unit) {
    val currentTranslation by translationService.currentTranslation.collectAsState()

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