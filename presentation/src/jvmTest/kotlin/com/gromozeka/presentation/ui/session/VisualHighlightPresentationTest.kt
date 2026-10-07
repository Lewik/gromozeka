package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.CompactButton
import com.gromozeka.presentation.ui.GromozekaTheme
import kotlin.test.*

class VisualHighlightPresentationTest {
    @Test fun badgeDismissalDoesNotClickTheButtonUnderneathOrChangeItsBounds() = runComposeUiTest {
        var highlighted by mutableStateOf(false)
        var clicks = 0
        setContent {
            GromozekaTheme {
                Box(Modifier.padding(20.dp)) {
                    VisualHighlightContainer(highlighted, "action", { highlighted = false }, shape = MaterialTheme.shapes.small) {
                        CompactButton({ clicks++ }, Modifier.testTag("action")) { Text("Action") }
                    }
                }
            }
        }
        val original = onNodeWithTag("action").fetchSemanticsNode().boundsInRoot
        runOnIdle { highlighted = true }
        onNodeWithTag("visual-highlight-action").assertWidthIsEqualTo(18.dp).assertHeightIsEqualTo(18.dp)
        assertEquals(original, onNodeWithTag("action").fetchSemanticsNode().boundsInRoot)
        onNodeWithTag("visual-highlight-action").performTouchInput { click(center) }
        runOnIdle { assertFalse(highlighted); assertEquals(0, clicks) }
        onNodeWithTag("visual-highlight-action").assertDoesNotExist()
        onNodeWithTag("action").performTouchInput { click(center) }
        runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun tabPointerDoesNotMoveTheTitleOrSelectOrCloseTheTab() = runComposeUiTest {
        var highlighted by mutableStateOf(false)
        var selections = 0
        var closes = 0
        setContent {
            GromozekaTheme {
                Box(Modifier.padding(20.dp).width(420.dp)) {
                    SecondaryScrollableTabRow(selectedTabIndex = 0, edgePadding = 0.dp) {
                        Tab(selected = true, onClick = {}, text = { Text("Runtime") })
                        VisualTab("preview", "Preview", selected = false, dirty = true, highlighted = highlighted,
                            onSelect = { selections++ }, onClose = { closes++ },
                            onClearHighlight = { highlighted = false })
                    }
                }
            }
        }
        val title = onNodeWithTag("visual-title-preview", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val close = onNodeWithTag("visual-close-preview").fetchSemanticsNode().boundsInRoot
        val tab = onNodeWithTag("visual-tab-preview").fetchSemanticsNode().boundsInRoot
        val unlit = onNodeWithTag("visual-tab-preview").captureToImage().toPixelMap()
        runOnIdle { highlighted = true }
        onNodeWithTag("visual-highlight-tab-preview").assertWidthIsEqualTo(18.dp).assertHeightIsEqualTo(18.dp)
        assertEquals(title, onNodeWithTag("visual-title-preview", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        assertEquals(close, onNodeWithTag("visual-close-preview").fetchSemanticsNode().boundsInRoot)
        val pointer = onNodeWithTag("visual-highlight-tab-preview").fetchSemanticsNode().boundsInRoot
        assertEquals(tab, onNodeWithTag("visual-tab-preview").fetchSemanticsNode().boundsInRoot)
        val lit = onNodeWithTag("visual-tab-preview").captureToImage().toPixelMap()
        for (y in listOf(with(density) { 1.dp.roundToPx() }, lit.height - with(density) { 3.dp.roundToPx() })) {
            assertTrue(lit[lit.width / 2, y].red > unlit[unlit.width / 2, y].red,
                "The native tab row must not clip away the halo at the top or bottom")
        }
        assertEquals(tab, onNodeWithTag("visual-tab-container-preview", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot,
            "The halo must surround the whole tab, including its horizontal padding and close button")
        assertTrue(pointer.left >= tab.right - with(density) { 20.dp.toPx() } && pointer.right <= tab.right + 1f,
            "Pointer must sit at the tab's outer corner, not at the title's end")
        onNodeWithTag("visual-highlight-tab-preview").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(pointer.width - 2f, pointer.height - 2f))
        }
        runOnIdle { assertFalse(highlighted); assertEquals(0, selections); assertEquals(0, closes) }
        onNodeWithTag("visual-dirty-preview", useUnmergedTree = true).assertExists().assertHasNoClickAction()
        onNodeWithTag("visual-close-preview").performTouchInput { click(center) }
        runOnIdle { assertEquals(1, closes); assertEquals(0, selections) }
    }

    @Test fun glowUsesTheProvidedShapeForBothShadowAndInteriorCutout() = runComposeUiTest {
        val shape = RoundedCornerShape(16.dp)
        val background = Color(0xFF112233)
        setContent {
            GromozekaTheme {
                Box(Modifier.size(140.dp, 100.dp).background(Color.Black).testTag("scene")) {
                    Box(Modifier.padding(20.dp)) {
                        VisualHighlightContainer(true, "rounded", {}, shape = shape) {
                            Box(Modifier.size(80.dp, 40.dp).background(background, shape))
                        }
                    }
                }
            }
        }
        val pixels = onNodeWithTag("scene").captureToImage().toPixelMap()
        val corner = with(density) { 22.dp.roundToPx() }
        val centerX = with(density) { 60.dp.roundToPx() }
        val centerY = with(density) { 40.dp.roundToPx() }
        // This point lies outside the rounded outline, but within its bounding rectangle.
        assertTrue(pixels[corner, corner].red in 0.01f..0.3f, "Rounded corner must receive only the soft outer glow")
        assertEquals(background.toArgb(), pixels[centerX, centerY].toArgb(), "Glow must not repaint the element interior")
    }

    @Test fun inlineTextBadgeDismissesWithoutFollowingTheLink() = runComposeUiTest {
        var highlighted by mutableStateOf(true)
        var links = 0
        setContent {
            GromozekaTheme {
                val text = buildAnnotatedString {
                    append("Read ")
                    if (highlighted) pushStringAnnotation(VISUAL_INLINE_HIGHLIGHT, "term")
                    pushLink(LinkAnnotation.Clickable("link", linkInteractionListener = LinkInteractionListener { links++ }))
                    append("this part")
                    pop()
                    if (highlighted) pop()
                    append(" carefully")
                }
                Box(Modifier.padding(20.dp)) {
                    VisualAnnotatedText(text, { highlighted = false }, Modifier.width(280.dp))
                }
            }
        }
        onNodeWithTag("visual-highlight-term").assertExists().performTouchInput { click(center) }
        runOnIdle { assertFalse(highlighted); assertEquals(0, links) }
        onNodeWithTag("visual-highlight-term").assertDoesNotExist()
    }
}
