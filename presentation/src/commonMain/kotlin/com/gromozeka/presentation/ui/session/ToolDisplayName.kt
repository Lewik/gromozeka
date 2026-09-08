package com.gromozeka.presentation.ui.session

import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.shared.localization.ToolDisplayText

internal fun toolDisplayName(toolName: String, translation: Translation.RuntimeTranslation): String =
    ToolDisplayText.name(toolName, translation::text)
