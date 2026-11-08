@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.gromozeka.presentation.services.BrowserAttachmentAcquisitionController
import kotlin.JsFun

@Composable
internal actual fun Modifier.composerAttachmentInput(
    receiver: ComposerAttachmentReceiver?,
    pasteLabel: String,
): Modifier {
    if (receiver == null) return this
    val paste = appendTextContextMenuComponents {
        if (receiver.controller.capabilities.clipboardPaste) {
            separator()
            item(PasteAttachmentsMenuKey, pasteLabel) { close(); receiver.paste() }
        }
    }
    if (receiver.controller !is BrowserAttachmentAcquisitionController) return paste
    val latestReceiver = rememberUpdatedState(receiver)
    val region = remember { createComposerAttachmentRegion() }
    val density = LocalDensity.current.density
    DisposableEffect(region) {
        installComposerAttachmentEvents(region) { files ->
            val target = latestReceiver.value
            val controller = target.controller as BrowserAttachmentAcquisitionController
            target.receivePrepared { controller.prepareExternalFiles(files) }
        }
        onDispose { removeComposerAttachmentEvents(region) }
    }
    return paste.onFocusChanged { setComposerAttachmentFocus(region, it.isFocused) }
        .onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            setComposerAttachmentBounds(region, bounds.left / density, bounds.top / density,
                bounds.right / density, bounds.bottom / density)
        }
}

@JsFun("() => ({ focused: false, bounds: null })")
internal external fun createComposerAttachmentRegion(): JsAny

@JsFun("(state, focused) => { state.focused = focused; }")
internal external fun setComposerAttachmentFocus(state: JsAny, focused: Boolean)

@JsFun("(state, left, top, right, bottom) => { state.bounds = { left, top, right, bottom }; }")
internal external fun setComposerAttachmentBounds(state: JsAny, left: Float, top: Float, right: Float, bottom: Float)

@JsFun(
    """
    (state, receive) => {
        const inside = event => {
            const b = state.bounds;
            return b && b.right > b.left && b.bottom > b.top &&
                event.clientX >= b.left && event.clientX <= b.right &&
                event.clientY >= b.top && event.clientY <= b.bottom;
        };
        const hasFiles = transfer => Array.from(transfer?.types || []).includes("Files");
        state.dragover = event => {
            if (!inside(event) || !hasFiles(event.dataTransfer)) return;
            event.preventDefault();
            event.dataTransfer.dropEffect = "copy";
        };
        state.drop = event => {
            if (!inside(event) || !event.dataTransfer?.files?.length) return;
            const files = Array.from(event.dataTransfer.files);
            event.preventDefault();
            event.stopPropagation();
            receive(files);
        };
        state.paste = event => {
            if (!state.focused || !event.clipboardData?.files?.length) return;
            const files = Array.from(event.clipboardData.files);
            event.preventDefault();
            event.stopPropagation();
            receive(files);
        };
        document.addEventListener("dragover", state.dragover, true);
        document.addEventListener("drop", state.drop, true);
        document.addEventListener("paste", state.paste, true);
    }
    """
)
internal external fun installComposerAttachmentEvents(state: JsAny, receive: (JsAny) -> Unit)

@JsFun(
    """
    state => {
        document.removeEventListener("dragover", state.dragover, true);
        document.removeEventListener("drop", state.drop, true);
        document.removeEventListener("paste", state.paste, true);
        state.focused = false;
        state.bounds = null;
    }
    """
)
internal external fun removeComposerAttachmentEvents(state: JsAny)

@Composable
internal actual fun ComposerAttachmentContextMenu(
    receiver: ComposerAttachmentReceiver?,
    pasteLabel: String,
    content: @Composable () -> Unit,
) {
    content()
}
