package com.gromozeka.bot.services.theming.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
@SerialName("light")
data class LightTheme(
    override val themeId: String = THEME_ID,
    override val themeName: String = "Light",
    
    override val primary: HexColor = HexColor("#6200EE"),
    override val onPrimary: HexColor = HexColor("#FFFFFF"),
    override val primaryContainer: HexColor = HexColor("#BB86FC"),
    override val onPrimaryContainer: HexColor = HexColor("#000000"),
    
    override val secondary: HexColor = HexColor("#018786"),
    override val onSecondary: HexColor = HexColor("#FFFFFF"),
    override val secondaryContainer: HexColor = HexColor("#03DAC6"),
    override val onSecondaryContainer: HexColor = HexColor("#000000"),
    
    override val error: HexColor = HexColor("#B00020"),
    override val onError: HexColor = HexColor("#FFFFFF"),
    override val errorContainer: HexColor = HexColor("#CF6679"),
    override val onErrorContainer: HexColor = HexColor("#000000"),
    
    override val background: HexColor = HexColor("#FFFFFF"),
    override val onBackground: HexColor = HexColor("#000000"),
    override val surface: HexColor = HexColor("#FFFFFF"),
    override val onSurface: HexColor = HexColor("#000000"),
    override val surfaceVariant: HexColor = HexColor("#F5F5F5"),
    override val onSurfaceVariant: HexColor = HexColor("#424242"),
    override val outline: HexColor = HexColor("#E0E0E0"),
    
    // Optional colors with null defaults
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
        const val THEME_ID = "light"
    }
}