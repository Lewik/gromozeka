package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun NameEditDialog(
    isOpen: Boolean,
    currentName: String,
    title: String,
    label: String,
    maxLength: Int? = null,
    onRename: suspend (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isOpen) return

    val localization = LocalTranslation.current
    var editingText by remember(currentName) {
        mutableStateOf(TextFieldValue(currentName, selection = TextRange(0, currentName.length)))
    }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val canSave = !isSaving && editingText.text.trim() != currentName.trim() &&
        (maxLength == null || editingText.text.length <= maxLength)

    fun dismiss() {
        if (!isSaving) onDismiss()
    }

    fun confirmRename() {
        if (!canSave || isSaving) return
        val newName = editingText.text.trim()
        isSaving = true
        saveError = null
        scope.launch {
            try {
                onRename(newName)
                onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                saveError = error.message ?: localization.text("search.renameConversationFailed")
            } finally {
                isSaving = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        modifier = Modifier.widthIn(max = 480.dp).testTag(UiTestTag.NameEditDialog.value),
        icon = { Icon(Icons.Default.Edit, contentDescription = null) },
        title = { Text(title) },
        text = {
            LaunchedEffect(focusRequester) {
                focusRequester.requestFocus()
            }
            OutlinedTextField(
                value = editingText,
                onValueChange = { value ->
                    if (maxLength == null || value.text.length <= maxLength) {
                        editingText = value
                        saveError = null
                    }
                },
                enabled = !isSaving,
                label = { Text(label) },
                isError = saveError != null,
                supportingText = {
                    val error = saveError
                    when {
                        error != null -> Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(UiTestTag.NameEditError.value),
                        )
                        maxLength != null -> Text("${editingText.text.length}/$maxLength")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTestTag.NameEditInput.value)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        when {
                            event.key == Key.Enter && event.type == KeyEventType.KeyDown -> {
                                confirmRename()
                                true
                            }
                            event.key == Key.Escape && event.type == KeyEventType.KeyDown -> {
                                dismiss()
                                true
                            }
                            else -> false
                        }
                    },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirmRename() }),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = ::confirmRename,
                enabled = canSave,
                modifier = Modifier.testTag(UiTestTag.NameEditSave.value),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(localization.saveButton)
            }
        },
        dismissButton = {
            TextButton(
                onClick = ::dismiss,
                enabled = !isSaving,
                modifier = Modifier.testTag(UiTestTag.NameEditCancel.value),
            ) {
                Text(localization.cancelButton)
            }
        },
    )
}
