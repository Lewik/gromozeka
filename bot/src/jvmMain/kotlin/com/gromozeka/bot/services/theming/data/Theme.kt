package com.gromozeka.bot.services.theming.data

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

@Serializable
sealed class Theme {
    abstract val themeId: String
    abstract val themeName: String

    // Core Material Design colors as HexColor values
    abstract val primary: HexColor
    abstract val onPrimary: HexColor
    abstract val primaryContainer: HexColor
    abstract val onPrimaryContainer: HexColor

    abstract val secondary: HexColor
    abstract val onSecondary: HexColor
    abstract val secondaryContainer: HexColor
    abstract val onSecondaryContainer: HexColor

    abstract val error: HexColor
    abstract val onError: HexColor
    abstract val errorContainer: HexColor
    abstract val onErrorContainer: HexColor

    abstract val background: HexColor
    abstract val onBackground: HexColor
    abstract val surface: HexColor
    abstract val onSurface: HexColor
    abstract val surfaceVariant: HexColor
    abstract val onSurfaceVariant: HexColor
    abstract val outline: HexColor

    // Optional colors with defaults
    abstract val tertiary: HexColor?
    abstract val onTertiary: HexColor?
    abstract val tertiaryContainer: HexColor?
    abstract val onTertiaryContainer: HexColor?
    abstract val outlineVariant: HexColor?
    abstract val scrim: HexColor?
    abstract val inverseSurface: HexColor?
    abstract val inverseOnSurface: HexColor?
    abstract val inversePrimary: HexColor?

    // Utility methods for Compose Color conversion
    fun getPrimaryColor(): Color = primary.toComposeColor()
    fun getOnPrimaryColor(): Color = onPrimary.toComposeColor()
    fun getBackgroundColor(): Color = background.toComposeColor()
    fun getOnBackgroundColor(): Color = onBackground.toComposeColor()
    fun getSurfaceColor(): Color = surface.toComposeColor()
    fun getOnSurfaceColor(): Color = onSurface.toComposeColor()
    fun getSecondaryColor(): Color = secondary.toComposeColor()
    fun getOnSecondaryColor(): Color = onSecondary.toComposeColor()
    fun getErrorColor(): Color = error.toComposeColor()
    fun getOnErrorColor(): Color = onError.toComposeColor()

    companion object {
        val builtIn = listOf(
            DarkTheme(),
            LightTheme(),
            GromozekaTheme(),
        ).associateBy { it.themeId }

        fun getThemeNameTranslated(
            themeId: String,
            translation: com.gromozeka.bot.services.translation.data.Translation,
        ): String {
            return when (themeId) {
                DarkTheme.THEME_ID -> translation.settings.themeNameDark
                LightTheme.THEME_ID -> translation.settings.themeNameLight
                GromozekaTheme.THEME_ID -> translation.settings.themeNameGromozeka
                else -> builtIn[themeId]?.themeName ?: themeId
            }
        }
    }
}