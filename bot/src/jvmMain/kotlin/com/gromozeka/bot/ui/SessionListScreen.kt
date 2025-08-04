package com.gromozeka.bot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.model.ProjectGroup
import com.gromozeka.bot.model.Session
import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
@Composable
fun SessionListScreen(
    onSessionSelected: (ChatSession, List<ChatMessage>, Session) -> Unit,
    coroutineScope: CoroutineScope,
    onNewSession: (String) -> Unit,
) {
    // Временно отключаем загрузку существующих сессий - будет реализовано в отдельной таске
    // var projectGroups by remember { mutableStateOf<List<ProjectGroup>>(emptyList()) }
    // var expandedProjects by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(false) } // Сразу показываем UI

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("Выберите беседу", style = MaterialTheme.typography.h5)
        Spacer(modifier = Modifier.height(16.dp))

        // Временная упрощенная версия - только кнопка создания новой сессии
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Streaming Session Test",
                style = MaterialTheme.typography.h6,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Button(
                onClick = { 
                    // Используем тестовую папку gromozeka для новых сессий
                    onNewSession("/Users/lewik/code/gromozeka/dev")
                },
                modifier = Modifier.padding(8.dp)
            ) {
                Text("Новая сессия (gromozeka/dev)")
            }
            
            Text(
                text = "Загрузка существующих сессий будет добавлена в отдельной таске",
                style = MaterialTheme.typography.caption,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun ProjectGroupHeader(
    group: ProjectGroup,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { onToggleExpanded() },
        backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.1f),
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Свернуть" else "Развернуть"
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = group.displayName(),
                style = MaterialTheme.typography.h6
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${group.sessionCount()} сессий",
                style = MaterialTheme.typography.caption
            )
        }
    }
}

@Composable
private fun SessionItem(
    session: ChatSession,
    onSessionClick: (ChatSession) -> Unit,
    isGrouped: Boolean = false,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isGrouped) 24.dp else 0.dp,
                top = 2.dp,
                bottom = 2.dp,
                end = 0.dp
            )
            .clickable { onSessionClick(session) },
        elevation = if (isGrouped) 1.dp else 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = session.displayPreview(),
                style = MaterialTheme.typography.body1
            )
            if (!isGrouped) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Проект: ${session.displayProject()}",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.primary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text(
                    text = session.displayTime(),
                    style = MaterialTheme.typography.caption
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${session.messageCount} сообщений",
                    style = MaterialTheme.typography.caption
                )
            }
        }
    }
}

@Composable
private fun NewSessionButton(
    projectPath: String,
    onNewSessionClick: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 4.dp, bottom = 4.dp)
            .clickable { onNewSessionClick(projectPath) },
        backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "+ Новая сессия",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.primary
            )
        }
    }
}

// Временно закомментировано - будет восстановлено когда добавим загрузку истории
/*
private fun CoroutineScope.handleSessionClick(
    clickedSession: ChatSession,
    onSessionSelected: (ChatSession, List<ChatMessage>, Session) -> Unit
) {
    launch {
        try {
            // TODO: Create Session and load existing history from files
            val claudeWrapper = ClaudeCodeStreamingWrapper()
            val session = Session(clickedSession.projectPath, claudeWrapper)
            
            // Pass session to parent - it will handle Claude process management  
            onSessionSelected(clickedSession, emptyList(), session)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
*/