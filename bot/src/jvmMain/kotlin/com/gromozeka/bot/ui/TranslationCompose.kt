package com.gromozeka.bot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.gromozeka.bot.services.translation.TranslationService
import com.gromozeka.bot.services.translation.data.Translation
import com.gromozeka.bot.services.translation.data.EnglishTranslation

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