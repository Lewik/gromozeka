package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gromozeka.presentation.ui.CompactButton
import com.gromozeka.presentation.ui.LocalTranslation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Composable
fun JsonDialog(
    json: String,
    onDismiss: () -> Unit,
) {
    BasicGromozekaDialog(
        onDismissRequest = onDismiss,
    ) {
        Card {
            Column {
                // Header with title and close button
                Row {
                    Text(LocalTranslation.current.viewOriginalJson)
                    CompactButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Divider()

                // Scrollable content - Dialog is outside main SelectionContainer
                SelectionContainer {
                    Text(
                        text = jsonPrettyPrint(json),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private val prettyJson = Json {
    prettyPrint = true
    isLenient = true
}

private fun jsonPrettyPrint(json: String): String =
    jsonPrettyPrintOrNull(json) ?: json

internal fun jsonPrettyPrintOrNull(json: String): String? =
    runCatching {
        prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(json))
    }.getOrNull()
