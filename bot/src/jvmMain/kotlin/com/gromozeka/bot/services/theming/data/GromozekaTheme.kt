package com.gromozeka.bot.services.theming.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
@SerialName("gromozeka")
data class GromozekaTheme(
    override val themeId: String = THEME_ID,
    override val themeName: String = "Gromozeka",
    
    // Кастомные цвета для фирменной темы Gromozeka
    override val primary: Long = 0xFF2196F3,
    override val onPrimary: Long = 0xFFFFFFFF,
    override val primaryContainer: Long = 0xFF1976D2,
    override val onPrimaryContainer: Long = 0xFFFFFFFF,
    
    override val secondary: Long = 0xFF4CAF50,
    override val onSecondary: Long = 0xFFFFFFFF,
    override val secondaryContainer: Long = 0xFF388E3C,
    override val onSecondaryContainer: Long = 0xFFFFFFFF,
    
    override val error: Long = 0xFFE91E63,
    override val onError: Long = 0xFFFFFFFF,
    override val errorContainer: Long = 0xFFC2185B,
    override val onErrorContainer: Long = 0xFFFFFFFF,
    
    override val background: Long = 0xFF0D1117,
    override val onBackground: Long = 0xFFF0F6FC,
    override val surface: Long = 0xFF161B22,
    override val onSurface: Long = 0xFFF0F6FC,
    override val surfaceVariant: Long = 0xFF21262D,
    override val onSurfaceVariant: Long = 0xFF8B949E,
    override val outline: Long = 0xFF30363D,
    
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
        const val THEME_ID = "gromozeka"
    }
}