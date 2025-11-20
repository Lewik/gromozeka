package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.CompactButton
import kotlinx.coroutines.runBlocking

@Composable
fun DevButtons(
    onSendMessage: suspend (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CompactButton(onClick = {
            runBlocking {
                onSendMessage("Расскажи скороговорку")
            }
        }) {
            Text("🗣 Скороговорка")
        }

        CompactButton(onClick = {
            runBlocking {
                onSendMessage("Создай таблицу с примерами разных типов данных в программировании")
            }
        }) {
            Text("📊 Таблица")
        }

        CompactButton(onClick = {
            runBlocking {
                onSendMessage("Загугли последние новости про Google")
            }
        }) {
            Text("🔍 Загугли про гугл")
        }

        CompactButton(onClick = {
            runBlocking {
                onSendMessage("Выполни ls")
            }
        }) {
            Text("📁 выполни ls")
        }
    }
}