package com.gromozeka.presentation.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

/** Unlabelled outlined input with themed padding; selection, IME and undo remain BasicTextField's. */
@Composable
fun CompactTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    placeholder: @Composable (() -> Unit)? = null,
    errorMessage: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    shape: Shape = MaterialTheme.shapes.small,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val controls = GromozekaTheme.controls
    val minHeight = if (!singleLine && minLines > 1) {
        // BasicTextField's minLines is not reflected in every intrinsic measurement.
        // Auto-sized Grid rows need the same minimum before their final tight measure.
        // Measure font metrics rather than assuming pixels/sp or a fixed line height.
        val measurer = rememberTextMeasurer()
        val oneLine = measurer.measure("H", style = textStyle, softWrap = false).size.height
        val twoLines = measurer.measure("H\nH", style = textStyle, softWrap = false).size.height
        val textHeight = with(LocalDensity.current) { (oneLine + (twoLines - oneLine) * (minLines - 1)).toDp() }
        maxOf(controls.minHeight, textHeight + controls.verticalPadding * 2)
    } else controls.minHeight
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val defaults = OutlinedTextFieldDefaults.colors()
    // Keep the outline quiet and stable while focused; caret and selection still indicate focus.
    val colors = defaults.copy(focusedIndicatorColor = defaults.unfocusedIndicatorColor)
    val isError = errorMessage != null
    val textColor = when {
        !enabled -> colors.disabledTextColor
        isError -> colors.errorTextColor
        focused -> colors.focusedTextColor
        else -> colors.unfocusedTextColor
    }
    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.defaultMinSize(minHeight = minHeight)
                .semantics { errorMessage?.let { error(it) } },
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle.copy(color = if (!enabled || isError) textColor else textStyle.color.takeOrElse { textColor }),
            cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value.text,
                    innerTextField = innerTextField,
                    enabled = enabled,
                    singleLine = singleLine,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interactionSource,
                    isError = isError,
                    placeholder = placeholder,
                    colors = colors,
                    contentPadding = PaddingValues(
                        horizontal = controls.horizontalPadding,
                        vertical = controls.verticalPadding,
                    ),
                    container = {
                        OutlinedTextFieldDefaults.Container(
                            enabled = enabled,
                            isError = isError,
                            interactionSource = interactionSource,
                            colors = colors,
                            shape = shape,
                            focusedBorderThickness = OutlinedTextFieldDefaults.UnfocusedBorderThickness,
                            unfocusedBorderThickness = OutlinedTextFieldDefaults.UnfocusedBorderThickness,
                        )
                    },
                )
            },
        )
    }
}
