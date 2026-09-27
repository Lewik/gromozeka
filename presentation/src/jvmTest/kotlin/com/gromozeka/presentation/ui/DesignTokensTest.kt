package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.services.theming.data.DarkTheme
import com.gromozeka.presentation.services.theming.data.LightTheme
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import com.gromozeka.presentation.ui.session.ComposerPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignTokensTest {
    @Test
    fun compactControlsAndFieldShareFortyDpHeight() = verifyDensity(UiDensity.COMPACT)

    @Test
    fun touchControlsAndFieldShareFortyEightDpHeight() = verifyDensity(UiDensity.TOUCH)

    private fun verifyDensity(density: UiDensity) = runComposeUiTest {
        val expectedSize = if (density == UiDensity.TOUCH) 48.dp else 40.dp
        setContent {
            GromozekaTheme(density = density) {
                Column(Modifier.width(600.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(GromozekaTheme.spacing.controlGap)) {
                        CompactButton({}, Modifier.testTag("text-button")) { Text("Action") }
                        CompactIconButton({}, Icons.Default.Add, "Add", Modifier.testTag("icon-button"))
                        CompactTextField(TextFieldValue("Text"), {}, Modifier.weight(1f).testTag("field"))
                    }
                    ComposerPanel {
                        Box(Modifier.fillMaxWidth().height(20.dp).testTag("panel-content"))
                    }
                }
            }
        }
        for (tag in listOf("text-button", "icon-button", "field")) {
            onNodeWithTag(tag).assertHeightIsEqualTo(expectedSize)
        }
        onNodeWithTag("icon-button").assertWidthIsEqualTo(expectedSize)
        val panel = onNodeWithTag("conversation-composer-panel").fetchSemanticsNode().boundsInRoot
        val content = onNodeWithTag("panel-content").fetchSemanticsNode().boundsInRoot
        assertEquals(8f, content.left - panel.left)
        assertEquals(8f, panel.right - content.right)
        assertEquals(8f, panel.bottom - content.bottom)
    }

    @Test
    fun densityChangesDoNotResetDraftOrSelectionAndColorsDoNotChangeMetrics() = runComposeUiTest {
        var density by mutableStateOf(UiDensity.COMPACT)
        var dark by mutableStateOf(true)
        var value by mutableStateOf(TextFieldValue("Before after", TextRange(7)))
        setContent {
            GromozekaTheme(currentTheme = if (dark) DarkTheme() else LightTheme(), density = density) {
                CompactTextField(value, { value = it }, Modifier.width(400.dp).testTag("field"))
            }
        }
        onNodeWithTag("field").assertHeightIsEqualTo(40.dp)
        runOnIdle { density = UiDensity.TOUCH }
        onNodeWithTag("field").assertHeightIsEqualTo(48.dp)
        runOnIdle {
            assertEquals(TextFieldValue("Before after", TextRange(7)), value)
            dark = false
        }
        onNodeWithTag("field").assertHeightIsEqualTo(48.dp)
        onNodeWithTag("field").performTextInputSelection(TextRange(0, 6))
        onNodeWithTag("field").performTextInput("After")
        runOnIdle { assertEquals("After after", value.text) }
    }

    @Test
    fun contentOutsideSurfacesUsesTheThemeForeground() = runComposeUiTest {
        var dark by mutableStateOf(true)
        var observed: Color? = null
        var expected: Color? = null
        setContent {
            GromozekaTheme(currentTheme = if (dark) DarkTheme() else LightTheme()) {
                val contentColor = LocalContentColor.current
                val foreground = MaterialTheme.colorScheme.onBackground
                SideEffect { observed = contentColor; expected = foreground }
            }
        }
        runOnIdle { assertEquals(expected, observed); dark = false }
        runOnIdle { assertEquals(expected, observed) }
    }

    @Test
    fun iconsUseThemeSizeButExplicitSizesArePreserved() = runComposeUiTest {
        setContent {
            GromozekaTheme {
                Column {
                    Icon(Icons.Default.Add, null, Modifier.testTag("default-icon"))
                    Icon(Icons.Default.Add, null, Modifier.size(32.dp).testTag("explicit-icon"))
                }
            }
        }
        onNodeWithTag("default-icon").assertWidthIsEqualTo(20.dp).assertHeightIsEqualTo(20.dp)
        onNodeWithTag("explicit-icon").assertWidthIsEqualTo(32.dp).assertHeightIsEqualTo(32.dp)
    }

    @Test
    fun focusKeepsTheInputBorderNeutralAndTheSameThickness() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            GromozekaTheme {
                CompactTextField(TextFieldValue("draft"), {}, Modifier.width(240.dp).testTag("field"))
            }
        }
        val field = onNodeWithTag("field")
        field.assertIsNotFocused()
        val before = field.captureToImage().toPixelMap()
        field.performClick()
        mainClock.advanceTimeBy(300)
        field.assertIsFocused().assertIsEnabled()
        val after = field.captureToImage().toPixelMap()
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        for (y in before.height / 2 - 3..before.height / 2 + 3) {
            for (x in listOf(0, 1, 2, before.width - 3, before.width - 2, before.width - 1)) {
                assertEquals(before[x, y], after[x, y], "Outline must not brighten or thicken on focus")
            }
        }
    }

    @Test
    fun topDockedPanelUsesTheSameInsetsAndRowGapAsComposer() = runComposeUiTest {
        var density by mutableStateOf(UiDensity.COMPACT)
        setContent {
            GromozekaTheme(density = density) {
                DockedPanel(dividerAtTop = false, modifier = Modifier.width(400.dp).testTag("header")) {
                    Row(Modifier.fillMaxWidth().height(GromozekaTheme.controls.minHeight).testTag("first-row")) {
                        CompactIconButton({}, Icons.Default.Add, "Add")
                    }
                    Row(Modifier.fillMaxWidth().height(GromozekaTheme.controls.minHeight).testTag("second-row")) {
                        ToggleButtonGroup(listOf(ToggleButtonOption(Icons.Default.Person, "People")), emptySet(), {})
                    }
                }
            }
        }
        for (profile in UiDensity.entries) {
            runOnIdle { density = profile }
            val expected = if (profile == UiDensity.COMPACT) 40.dp else 48.dp
            val panel = onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
            val first = onNodeWithTag("first-row").fetchSemanticsNode().boundsInRoot
            val second = onNodeWithTag("second-row").fetchSemanticsNode().boundsInRoot
            onNodeWithContentDescription("Add").assertWidthIsEqualTo(expected).assertHeightIsEqualTo(expected)
            onNodeWithContentDescription("People").assertWidthIsEqualTo(expected).assertHeightIsEqualTo(expected)
            assertEquals(8f, first.left - panel.left)
            assertEquals(8f, panel.right - first.right)
            assertEquals(8f, first.top - panel.top)
            assertEquals(8f, second.top - first.bottom)
            assertEquals(8f, panel.bottom - second.bottom - 1f)
        }
    }

    @Test
    fun compactFieldGrowsForLargeFontsInsteadOfClippingToTokenHeight() = runComposeUiTest {
        setContent {
            GromozekaTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    CompactTextField(TextFieldValue("Large text"), {}, Modifier.width(400.dp).testTag("field"))
                }
            }
        }
        val bounds = onNodeWithTag("field").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height > 40f)
    }
}
