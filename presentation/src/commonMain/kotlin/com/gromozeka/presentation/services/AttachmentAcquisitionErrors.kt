package com.gromozeka.presentation.services

import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedText

internal fun Throwable.asAttachmentFailure(fallbackKey: String = "client.attachment.failed"): LocalizedText =
    if (this is LocalizedTextException) text else attachmentFailureText(localizedText(fallbackKey), message)

internal fun attachmentFailureText(reason: LocalizedText, diagnostic: String?): LocalizedText =
    diagnostic?.takeIf { it.isNotBlank() }?.let {
        LocalizedText.Joined(listOf(reason, LocalizedText.Literal(it)), "\n")
    } ?: reason
