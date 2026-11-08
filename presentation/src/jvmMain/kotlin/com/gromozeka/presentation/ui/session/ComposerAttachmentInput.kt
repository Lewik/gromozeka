@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import com.gromozeka.presentation.services.DesktopAttachmentAcquisitionController

@Composable
internal actual fun Modifier.composerAttachmentInput(
    receiver: ComposerAttachmentReceiver?,
    pasteLabel: String,
): Modifier {
    if (receiver == null) return this
    val useMeta = remember { System.getProperty("os.name").contains("mac", ignoreCase = true) }
    val paste = attachmentPasteShortcuts(receiver, useMeta)
    val controller = receiver.controller as? DesktopAttachmentAcquisitionController ?: return paste
    val target = remember(receiver) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean =
                receiver.receivePrepared { controller.prepareTransferable(event.awtTransferable) }
        }
    }
    return paste.dragAndDropTarget(
        shouldStartDragAndDrop = { controller.canReceive(it.awtTransferable) },
        target = target,
    )
}

@Composable
internal actual fun ComposerAttachmentContextMenu(
    receiver: ComposerAttachmentReceiver?,
    pasteLabel: String,
    content: @Composable () -> Unit,
) {
    ContextMenuDataProvider(items = {
        if (receiver?.controller?.capabilities?.clipboardPaste == true)
            listOf(ContextMenuItem(pasteLabel) { receiver.paste() })
        else emptyList()
    }, content = content)
}
