package com.gromozeka.bot.services.theming.data

import kotlinx.serialization.Serializable

@Serializable
sealed class Theme {
    abstract val themeId: String
    abstract val themeName: String
    
    // Core Material Design colors as Long values (0xFFRRGGBB)
    abstract val primary: Long
    abstract val onPrimary: Long
    abstract val primaryContainer: Long
    abstract val onPrimaryContainer: Long
    
    abstract val secondary: Long
    abstract val onSecondary: Long
    abstract val secondaryContainer: Long
    abstract val onSecondaryContainer: Long
    
    abstract val error: Long
    abstract val onError: Long
    abstract val errorContainer: Long
    abstract val onErrorContainer: Long
    
    abstract val background: Long
    abstract val onBackground: Long
    abstract val surface: Long
    abstract val onSurface: Long
    abstract val surfaceVariant: Long
    abstract val onSurfaceVariant: Long
    abstract val outline: Long
    
    // Optional colors with defaults
    abstract val tertiary: Long?
    abstract val onTertiary: Long?
    abstract val tertiaryContainer: Long?
    abstract val onTertiaryContainer: Long?
    abstract val outlineVariant: Long?
    abstract val scrim: Long?
    abstract val inverseSurface: Long?
    abstract val inverseOnSurface: Long?
    abstract val inversePrimary: Long?
    
    companion object {
        val builtIn = listOf(
            DarkTheme(),
            LightTheme(),
            GromozekaTheme(),
        ).associateBy { it.themeId }
        
        fun getThemeNameTranslated(themeId: String, translation: com.gromozeka.bot.services.translation.data.Translation): String {
            return when (themeId) {
                DarkTheme.THEME_ID -> translation.settings.themeNameDark
                LightTheme.THEME_ID -> translation.settings.themeNameLight
                GromozekaTheme.THEME_ID -> translation.settings.themeNameGromozeka
                else -> builtIn[themeId]?.themeName ?: themeId
            }
        }
    }
}