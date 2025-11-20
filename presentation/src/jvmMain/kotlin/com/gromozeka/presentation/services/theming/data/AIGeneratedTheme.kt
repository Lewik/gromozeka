package com.gromozeka.presentation.services.theming.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("ai_generated")
data class AIGeneratedTheme(
    override val themeId: String,
    override val themeName: String,

    override val primary: HexColor,
    override val onPrimary: HexColor,
    override val primaryContainer: HexColor,
    override val onPrimaryContainer: HexColor,

    override val secondary: HexColor,
    override val onSecondary: HexColor,
    override val secondaryContainer: HexColor,
    override val onSecondaryContainer: HexColor,

    override val error: HexColor,
    override val onError: HexColor,
    override val errorContainer: HexColor,
    override val onErrorContainer: HexColor,

    override val background: HexColor,
    override val onBackground: HexColor,
    override val surface: HexColor,
    override val onSurface: HexColor,
    override val surfaceVariant: HexColor,
    override val onSurfaceVariant: HexColor,
    override val outline: HexColor,

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
) : Theme()