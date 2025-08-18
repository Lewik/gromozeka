package com.gromozeka.bot.services.theming.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
@SerialName("light")
data class LightTheme(
    override val themeId: String = THEME_ID,
    override val themeName: String = "Light",
    
    override val primary: HexColor = HexColor("#4FC3F7"),
    override val onPrimary: HexColor = HexColor("#000000"),
    override val primaryContainer: HexColor = HexColor("#E1F5FE"),
    override val onPrimaryContainer: HexColor = HexColor("#01579B"),
    
    override val secondary: HexColor = HexColor("#81C784"),
    override val onSecondary: HexColor = HexColor("#000000"),
    override val secondaryContainer: HexColor = HexColor("#E8F5E8"),
    override val onSecondaryContainer: HexColor = HexColor("#1B5E20"),
    
    override val error: HexColor = HexColor("#F44336"),
    override val onError: HexColor = HexColor("#FFFFFF"),
    override val errorContainer: HexColor = HexColor("#FFEBEE"),
    override val onErrorContainer: HexColor = HexColor("#B71C1C"),
    
    override val background: HexColor = HexColor("#F5F5F5"),
    override val onBackground: HexColor = HexColor("#2E2E2E"),
    override val surface: HexColor = HexColor("#E8E8E8"),
    override val onSurface: HexColor = HexColor("#2E2E2E"),
    override val surfaceVariant: HexColor = HexColor("#D0D0D0"),
    override val onSurfaceVariant: HexColor = HexColor("#666666"),
    override val outline: HexColor = HexColor("#BDBDBD"),
    
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