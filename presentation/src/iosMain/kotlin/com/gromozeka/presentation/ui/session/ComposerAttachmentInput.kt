@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import com.gromozeka.presentation.services.IosAttachmentAcquisitionController

@Composable
internal actual fun Modifier.composerAttachmentInput(
    receiver: ComposerAttachmentReceiver?,
    pasteLabel: String,
): Modifier {
    if (receiver == null) return this
    val paste = attachmentPasteShortcuts(receiver, useMeta = true)
    val controller = receiver.controller as? IosAttachmentAcquisitionController ?: return paste
    val target = remember(receiver) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = receiver.receivePrepared {
                controller.prepareProviders(event.items.map { it.itemProvider })
            }
        }
    }
    return paste.dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.items.any { controller.canReceiveProvider(it.itemProvider) } },
        target = target,
    )
}

@Composable
internal actual fun ComposerAttachmentContextMenu(
    receiver: ComposerAttachmentReceiver?,
    pasteLabel: String,
    content: @Composable () -> Unit,
) {
    content()
}
