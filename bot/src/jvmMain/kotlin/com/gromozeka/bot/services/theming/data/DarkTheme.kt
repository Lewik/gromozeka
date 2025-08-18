package com.gromozeka.bot.services.theming.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
@SerialName("dark")
data class DarkTheme(
    override val themeId: String = THEME_ID,
    override val themeName: String = "Dark",
    
    override val primary: HexColor = HexColor("#187EEC"),
    override val onPrimary: HexColor = HexColor("#FFFFFF"),
    override val primaryContainer: HexColor = HexColor("#0A4BA0"),
    override val onPrimaryContainer: HexColor = HexColor("#FFFFFF"),
    
    override val secondary: HexColor = HexColor("#60A962"),
    override val onSecondary: HexColor = HexColor("#FFFFFF"),
    override val secondaryContainer: HexColor = HexColor("#2E5930"),
    override val onSecondaryContainer: HexColor = HexColor("#FFFFFF"),
    
    override val error: HexColor = HexColor("#FF4444"),
    override val onError: HexColor = HexColor("#FFFFFF"),
    override val errorContainer: HexColor = HexColor("#8B0000"),
    override val onErrorContainer: HexColor = HexColor("#FFFFFF"),
    
    override val background: HexColor = HexColor("#1C1C1C"),
    override val onBackground: HexColor = HexColor("#FFFFFF"),
    override val surface: HexColor = HexColor("#2A2A2A"),
    override val onSurface: HexColor = HexColor("#FFFFFF"),
    override val surfaceVariant: HexColor = HexColor("#323232"),
    override val onSurfaceVariant: HexColor = HexColor("#CCCCCC"),
    override val outline: HexColor = HexColor("#555555"),
    
    // Optional colors with null defaults (will use Material defaults)
    override val tertiary: HexColor? = null,
    override val onTertiary: HexColor? = null,
    override val tertiaryContainer: HexColor? = null,
    override val onTertiaryContainer: HexColor? = null,
    override val outlineVariant: HexColor? = null,
    override val scrim: HexColor? = null,
    override val inverseSurface: HexColor? = null,
    override val inverseOnSurface: HexColor? = null,
    override val inversePrimary: HexColor? = null,
) : Theme() {
    companion object {
        const val THEME_ID = "dark"
    }
}