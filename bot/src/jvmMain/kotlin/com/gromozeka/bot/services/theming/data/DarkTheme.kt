package com.gromozeka.bot.services.theming.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
@SerialName("dark")
data class DarkTheme(
    override val themeId: String = THEME_ID,
    override val themeName: String = "Dark",
    
    override val primary: HexColor = HexColor("#BB86FC"),
    override val onPrimary: HexColor = HexColor("#000000"),
    override val primaryContainer: HexColor = HexColor("#3700B3"),
    override val onPrimaryContainer: HexColor = HexColor("#FFFFFF"),
    
    override val secondary: HexColor = HexColor("#03DAC6"),
    override val onSecondary: HexColor = HexColor("#000000"),
    override val secondaryContainer: HexColor = HexColor("#018786"),
    override val onSecondaryContainer: HexColor = HexColor("#FFFFFF"),
    
    override val error: HexColor = HexColor("#CF6679"),
    override val onError: HexColor = HexColor("#000000"),
    override val errorContainer: HexColor = HexColor("#B00020"),
    override val onErrorContainer: HexColor = HexColor("#FFFFFF"),
    
    override val background: HexColor = HexColor("#121212"),
    override val onBackground: HexColor = HexColor("#FFFFFF"),
    override val surface: HexColor = HexColor("#121212"),
    override val onSurface: HexColor = HexColor("#FFFFFF"),
    override val surfaceVariant: HexColor = HexColor("#1E1E1E"),
    override val onSurfaceVariant: HexColor = HexColor("#E0E0E0"),
    override val outline: HexColor = HexColor("#333333"),
    
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