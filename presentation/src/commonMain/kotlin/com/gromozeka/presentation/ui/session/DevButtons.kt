package com.gromozeka.presentation.ui.session

import com.gromozeka.presentation.ui.LocalTranslation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.CompactButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun DevButtons(
    onSendMessage: suspend (String) -> Unit,
    coroutineScope: CoroutineScope,
) {
    val localization = LocalTranslation.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CompactButton(onClick = {
            coroutineScope.launch {
                onSendMessage(localization.text("chat.developerPrompt.tongueTwister"))
            }
        }) {
            Text(localization.text("quickActionTongueTwister"))
        }

        CompactButton(onClick = {
            coroutineScope.launch {
                onSendMessage(localization.text("chat.developerPrompt.dataTypesTable"))
            }
        }) {
            Text(localization.text("quickActionTable"))
        }

        CompactButton(onClick = {
            coroutineScope.launch {
                onSendMessage(localization.text("chat.developerPrompt.googleNews"))
            }
        }) {
            Text(localization.text("quickActionGoogleSearch"))
        }

        CompactButton(onClick = {
            coroutineScope.launch {
                onSendMessage(localization.text("chat.developerPrompt.listFiles"))
            }
        }) {
            Text(localization.text("chat.developerPrompt.listFiles"))
        }
    }
}
