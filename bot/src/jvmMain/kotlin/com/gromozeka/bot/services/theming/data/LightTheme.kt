package com.gromozeka.bot.services.theming.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
@SerialName("light")
data class LightTheme(
    override val themeId: String = THEME_ID,
    override val themeName: String = "Light",
    
    override val primary: Long = 0xFF6200EE,
    override val onPrimary: Long = 0xFFFFFFFF,
    override val primaryContainer: Long = 0xFFBB86FC,
    override val onPrimaryContainer: Long = 0xFF000000,
    
    override val secondary: Long = 0xFF018786,
    override val onSecondary: Long = 0xFFFFFFFF,
    override val secondaryContainer: Long = 0xFF03DAC6,
    override val onSecondaryContainer: Long = 0xFF000000,
    
    override val error: Long = 0xFFB00020,
    override val onError: Long = 0xFFFFFFFF,
    override val errorContainer: Long = 0xFFCF6679,
    override val onErrorContainer: Long = 0xFF000000,
    
    override val background: Long = 0xFFFFFFFF,
    override val onBackground: Long = 0xFF000000,
    override val surface: Long = 0xFFFFFFFF,
    override val onSurface: Long = 0xFF000000,
    override val surfaceVariant: Long = 0xFFF5F5F5,
    override val onSurfaceVariant: Long = 0xFF424242,
    override val outline: Long = 0xFFE0E0E0,
    
    // Optional colors with null defaults
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
        const val THEME_ID = "light"
    }
}