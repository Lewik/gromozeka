package com.gromozeka.presentation.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.gromozeka.presentation.services.AttachmentAcquisition
import com.gromozeka.presentation.services.AttachmentAcquisitionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** The callback owns a particular draft before any asynchronous clipboard or drop read starts. */
internal class ComposerAttachmentReceiver(
    val controller: AttachmentAcquisitionController,
    val receive: (AttachmentAcquisition) -> Job,
) {
    fun paste(): Boolean = receivePrepared(controller::prepareClipboardAttachments)

    fun receivePrepared(prepare: () -> AttachmentAcquisition?): Boolean {
        val acquire = try {
            prepare()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            receive { throw error }
            return true
        } ?: return false
        receive(acquire)
        return true
    }
}

@Composable
internal expect fun Modifier.composerAttachmentInput(
    receiver: ComposerAttachmentReceiver?,
    pasteLabel: String,
): Modifier

@Composable
internal expect fun ComposerAttachmentContextMenu(
    receiver: ComposerAttachmentReceiver?,
    pasteLabel: String,
    content: @Composable () -> Unit,
)

internal fun Modifier.attachmentPasteShortcuts(
    receiver: ComposerAttachmentReceiver,
    useMeta: Boolean,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown || event.isAltPressed) return@onPreviewKeyEvent false
    val paste = event.key == Key.V && !event.isShiftPressed &&
        if (useMeta) event.isMetaPressed && !event.isCtrlPressed else event.isCtrlPressed && !event.isMetaPressed
    val insertPaste = !useMeta && event.key == Key.Insert && event.isShiftPressed &&
        !event.isCtrlPressed && !event.isMetaPressed
    (paste || insertPaste) && receiver.paste()
}

internal data object PasteAttachmentsMenuKey
