package com.gromozeka.presentation.services

import com.gromozeka.presentation.services.translation.data.Translation
import kotlinx.coroutines.CancellationException
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
    private val currentTranslation: () -> Translation,
) : QuickTextActionRunner {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()

    override suspend fun run(actionId: QuickTextAction.Id) {
        mutex.withLock {
            var actionName = actionId.value
            val notificationId = "quick-text-action:${actionId.value}"
            log.info("Quick text action started: actionId=${actionId.value}")
            notificationService.show(notificationId, "Gromozeka", currentTranslation().executingStatus)
            try {
                actionName = actionName(actionId)
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val inputText = withContext(Dispatchers.IO) {
                    clipboard.readText()
                }
                if (inputText.isNullOrBlank()) {
                    notificationService.show(notificationId, "Gromozeka", currentTranslation().text("quickText.noInput", "action" to actionName))
                    log.info("Quick text action skipped: clipboard has no text")
                    return
                }

                val result = quickTextActionService.runAction(actionId, inputText, currentTranslation().languageCode)
                withContext(Dispatchers.IO) {
                    clipboard.writeText(result.text)
                }
                log.info("Quick text action complete: actionId=${actionId.value}")
                notificationService.show(notificationId, "Gromozeka", currentTranslation().text("native.copiedToClipboard", "action" to actionName))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                uiFeedbackController.notifyError()
                notificationService.show(notificationId, "Gromozeka", currentTranslation().text("quickText.failed", "action" to actionName, "error" to error.userFacingMessage()))
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

    private suspend fun actionName(actionId: QuickTextAction.Id): String {
        val action = quickTextActionService.listActions().firstOrNull { it.id == actionId }
        val default = QuickTextAction.defaults().firstOrNull { it.id == actionId }
        if (action != null && action.title != default?.title) return action.title
        return when (actionId) {
            QuickTextAction.FIX_TEXT_ID -> currentTranslation().text("quickText.fix")
            QuickTextAction.TRANSLATE_INTERFACE_LANGUAGE_ID -> currentTranslation().text("quickText.translate")
            else -> action?.title ?: actionId.value
        }
    }

    private fun Throwable.userFacingMessage(): String =
        message?.takeIf(String::isNotBlank)?.lineSequence()?.firstOrNull()?.take(160)
            ?: this::class.simpleName
            ?: currentTranslation().text("common.unknownError")
}
