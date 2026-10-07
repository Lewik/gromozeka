package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.gromozeka.presentation.ui.LocalTranslation

/**
 * Native overlay, not a sibling/gutter: showing a dirty dot must not resize its anchor.
 * The empty Material badge sits at the top-end corner, inside the anchor bounds. It
 * has neither a click handler nor a tooltip and cannot obscure a separate close button.
 */
@Composable
internal fun VisualDirtyBadge(
    dirty: Boolean,
    badgeTag: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val description = LocalTranslation.current.text("visuals.localChanges")
    BadgedBox(
        // Grid/weighted rows can supply a nonzero minimum width. Do not let that force
        // the badge slot to the field's width; the actual control still sees maxWidth.
        modifier = modifier.wrapContentSize(Alignment.TopStart),
        badge = {
            if (dirty) Badge(
                modifier = Modifier.testTag(badgeTag).semantics { stateDescription = description },
                containerColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        content = content,
    )
}
