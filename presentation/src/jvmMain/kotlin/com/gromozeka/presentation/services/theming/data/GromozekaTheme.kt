package com.gromozeka.presentation.services.theming.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("gromozeka")
data class GromozekaTheme(
    override val themeId: String = THEME_ID,
    override val themeName: String = "Gromozeka",

    // Кастомные цвета для фирменной темы Gromozeka
    override val primary: HexColor = HexColor("#2196F3"),
    override val onPrimary: HexColor = HexColor("#FFFFFF"),
    override val primaryContainer: HexColor = HexColor("#1976D2"),
    override val onPrimaryContainer: HexColor = HexColor("#FFFFFF"),

    override val secondary: HexColor = HexColor("#4CAF50"),
    override val onSecondary: HexColor = HexColor("#FFFFFF"),
    override val secondaryContainer: HexColor = HexColor("#388E3C"),
    override val onSecondaryContainer: HexColor = HexColor("#FFFFFF"),

    override val error: HexColor = HexColor("#E91E63"),
    override val onError: HexColor = HexColor("#FFFFFF"),
    override val errorContainer: HexColor = HexColor("#C2185B"),
    override val onErrorContainer: HexColor = HexColor("#FFFFFF"),

    override val background: HexColor = HexColor("#0D1117"),
    override val onBackground: HexColor = HexColor("#F0F6FC"),
    override val surface: HexColor = HexColor("#161B22"),
    override val onSurface: HexColor = HexColor("#F0F6FC"),
    override val surfaceVariant: HexColor = HexColor("#21262D"),
    override val onSurfaceVariant: HexColor = HexColor("#8B949E"),
    override val outline: HexColor = HexColor("#30363D"),

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
        const val THEME_ID = "gromozeka"
    }
}