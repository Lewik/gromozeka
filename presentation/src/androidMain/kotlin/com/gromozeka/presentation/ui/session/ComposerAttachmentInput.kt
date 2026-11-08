@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.gromozeka.presentation.ui.session

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import com.gromozeka.presentation.services.AndroidAttachmentAcquisitionController

@Composable
internal actual fun Modifier.composerAttachmentInput(
    receiver: ComposerAttachmentReceiver?,
    pasteLabel: String,
): Modifier {
    if (receiver == null) return this
    val paste = attachmentPasteShortcuts(receiver, useMeta = false).appendTextContextMenuComponents {
        if (receiver.controller.capabilities.clipboardPaste) {
            separator()
            item(PasteAttachmentsMenuKey, pasteLabel) { close(); receiver.paste() }
        }
    }
    val controller = receiver.controller as? AndroidAttachmentAcquisitionController ?: return paste
    val context = LocalContext.current
    val activity = remember(context) {
        checkNotNull(generateSequence(context) { current ->
            (current as? ContextWrapper)?.baseContext?.takeIf { it !== current }
        }.filterIsInstance<Activity>().firstOrNull()) { "Composer drag-and-drop requires an Activity" }
    }
    val target = remember(receiver, activity) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val native = event.toAndroidDragEvent()
                val acquire = native.clipData?.let(controller::prepareClipData) ?: return false
                val permission = activity.requestDragAndDropPermissions(native)
                try {
                    receiver.receive(acquire).invokeOnCompletion { permission?.release() }
                } catch (error: Throwable) {
                    permission?.release()
                    throw error
                }
                return true
            }
        }
    }
    return paste.dragAndDropTarget(
        shouldStartDragAndDrop = { it.toAndroidDragEvent().clipDescription != null },
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
