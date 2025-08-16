package com.gromozeka.bot.services.theming.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
@SerialName("dark")
data class DarkTheme(
    override val themeId: String = THEME_ID,
    override val themeName: String = "Dark",
    
    override val primary: Long = 0xFFBB86FC,
    override val onPrimary: Long = 0xFF000000,
    override val primaryContainer: Long = 0xFF3700B3,
    override val onPrimaryContainer: Long = 0xFFFFFFFF,
    
    override val secondary: Long = 0xFF03DAC6,
    override val onSecondary: Long = 0xFF000000,
    override val secondaryContainer: Long = 0xFF018786,
    override val onSecondaryContainer: Long = 0xFFFFFFFF,
    
    override val error: Long = 0xFFCF6679,
    override val onError: Long = 0xFF000000,
    override val errorContainer: Long = 0xFFB00020,
    override val onErrorContainer: Long = 0xFFFFFFFF,
    
    override val background: Long = 0xFF121212,
    override val onBackground: Long = 0xFFFFFFFF,
    override val surface: Long = 0xFF121212,
    override val onSurface: Long = 0xFFFFFFFF,
    override val surfaceVariant: Long = 0xFF1E1E1E,
    override val onSurfaceVariant: Long = 0xFFE0E0E0,
    override val outline: Long = 0xFF333333,
    
    // Optional colors with null defaults (will use Material defaults)
    override val tertiary: Long? = null,
    override val onTertiary: Long? = null,
    override val tertiaryContainer: Long? = null,
    override val onTertiaryContainer: Long? = null,
    override val outlineVariant: Long? = null,
    override val scrim: Long? = null,
    override val inverseSurface: Long? = null,
    override val inverseOnSurface: Long? = null,
    override val inversePrimary: Long? = null,
) : Theme() {
    companion object {
        const val THEME_ID = "dark"
    }
}