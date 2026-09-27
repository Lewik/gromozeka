package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.gromozeka.presentation.ui.DockedPanel

@Composable
internal fun ComposerPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DockedPanel(
        dividerAtTop = true,
        modifier = modifier.testTag("conversation-composer-panel"),
        content = content,
    )
}
