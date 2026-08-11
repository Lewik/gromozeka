package com.gromozeka.presentation.services

import com.gromozeka.domain.model.QuickTextAction
import com.gromozeka.domain.service.QuickTextActionService
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

internal class DesktopQuickTextActionExecutor(
    private val quickTextActionService: QuickTextActionService,
    private val uiFeedbackController: UiFeedbackController,
    private val notificationService: DesktopNotificationService,
) {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()

    suspend fun run(actionId: QuickTextAction.Id) {
        mutex.withLock {
            val actionName = actionName(actionId)
            log.info("Quick text action started: actionId=${actionId.value}")
            notificationService.show("Gromozeka", "$actionName started")
            try {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val inputText = withContext(Dispatchers.IO) {
                    clipboard.readText()
                }
                if (inputText.isNullOrBlank()) {
                    notificationService.show("Gromozeka", "$actionName skipped: clipboard has no text")
                    log.info("Quick text action skipped: clipboard has no text")
                    return
                }

                val result = quickTextActionService.runAction(actionId, inputText)
                withContext(Dispatchers.IO) {
                    clipboard.writeText(result.text)
                }
                log.info("Quick text action complete: actionId=${actionId.value}")
                notificationService.show("Gromozeka", "$actionName complete: result copied to clipboard")
            } catch (error: Throwable) {
                uiFeedbackController.notifyError()
                notificationService.show("Gromozeka", "$actionName failed: ${error.userFacingMessage()}")
                log.warn(error) { "Quick text action failed: ${error.message}" }
            }
        }
    }

    private fun Clipboard.readText(): String? =
        runCatching {
            val contents = getContents(null)
            if (contents?.isDataFlavorSupported(DataFlavor.stringFlavor) == true) {
                contents.getTransferData(DataFlavor.stringFlavor) as? String
            } else {
                null
            }
        }.getOrNull()

    private fun Clipboard.writeText(text: String) {
        setContents(StringSelection(text), null)
    }

    private fun actionName(actionId: QuickTextAction.Id): String =
        when (actionId) {
            QuickTextAction.FIX_TEXT_ID -> "Fix text"
            QuickTextAction.TRANSLATE_RU_EN_ID -> "Translate"
            else -> "Quick text action"
        }

    private fun Throwable.userFacingMessage(): String =
        message?.takeIf(String::isNotBlank)?.lineSequence()?.firstOrNull()?.take(160)
            ?: this::class.simpleName
            ?: "unknown error"
}
