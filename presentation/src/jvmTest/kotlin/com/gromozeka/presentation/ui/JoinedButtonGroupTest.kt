package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.icons.Icons
import kotlin.test.Test
import kotlin.test.assertEquals

class JoinedButtonGroupTest {
    @Test
    fun onlyOuterCornersAreRoundedAndTheyFollowTheTheme() = runComposeUiTest {
        var shapes = emptyList<CornerBasedShape>()
        setContent {
            MaterialTheme(shapes = Shapes(small = RoundedCornerShape(13.dp))) {
                val current = listOf(joinedButtonShape(0, 1)) + (0..2).map { joinedButtonShape(it, 3) }
                SideEffect { shapes = current }
            }
        }
        runOnIdle {
            val corners = shapes.map { shape ->
                listOf(shape.topStart, shape.bottomStart, shape.topEnd, shape.bottomEnd)
                    .map { it.toPx(Size(44f, 44f), Density(1f)) }
            }
            assertEquals(listOf(
                listOf(13f, 13f, 13f, 13f),
                listOf(13f, 13f, 0f, 0f),
                listOf(0f, 0f, 0f, 0f),
                listOf(0f, 0f, 13f, 13f),
            ), corners)
        }
    }

    @Test
    fun existingRadioAndIndependentToggleGroupsKeepTheirBehavior() = runComposeUiTest {
        val radio = mutableStateOf(0)
        val toggles = mutableStateOf(emptySet<Int>())
        setContent {
            GromozekaTheme {
                Column {
                    CustomSegmentedButtonGroup(
                        listOf(SegmentedButtonOption("One"), SegmentedButtonOption("Two")),
                        selectedIndex = radio.value,
                        onSelectionChange = { radio.value = it },
                    )
                    ToggleButtonGroup(
                        listOf(ToggleButtonOption(Icons.Default.Mic, "Microphone"), ToggleButtonOption(Icons.Default.Send, "Sending")),
                        selectedIndices = toggles.value,
                        onToggle = { index -> toggles.value = if (index in toggles.value) toggles.value - index else toggles.value + index },
                    )
                }
            }
        }
        // Test selection callbacks independently of tooltip popup placement.
        onNodeWithText("Two").performSemanticsAction(SemanticsActions.OnClick) { it() }
        onNodeWithContentDescription("Microphone").performSemanticsAction(SemanticsActions.OnClick) { it() }
        onNodeWithContentDescription("Sending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        runOnIdle { assertEquals(1, radio.value); assertEquals(setOf(0, 1), toggles.value) }
        onNodeWithText("One").performSemanticsAction(SemanticsActions.OnClick) { it() }
        onNodeWithContentDescription("Microphone").performSemanticsAction(SemanticsActions.OnClick) { it() }
        runOnIdle { assertEquals(0, radio.value); assertEquals(setOf(1), toggles.value) }
    }
}
