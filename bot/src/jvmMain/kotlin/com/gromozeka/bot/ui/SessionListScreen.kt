package com.gromozeka.bot.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.model.ChatMessage
import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.services.ClaudeCodeSessionMapper
import com.gromozeka.bot.services.ClaudeCodeStreamingWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SessionListScreen(
    availableSessions: List<ChatSession>,
    onSessionSelected: (ChatSession, List<ChatMessage>) -> Unit,
    claudeCodeStreamingWrapper: ClaudeCodeStreamingWrapper,
    coroutineScope: CoroutineScope
) {
    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("Выберите беседу", style = MaterialTheme.typography.h5)
        Spacer(modifier = Modifier.height(16.dp))

        if (availableSessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Нет доступных бесед")
            }
        } else {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth()) {
                availableSessions.forEach { session ->
                    SessionItem(
                        session = session,
                        onSessionClick = { clickedSession ->
                            coroutineScope.launch {
                                try {
                                    val encodedPath = clickedSession.projectPath.replace("/", "-")
                                    val sessionFile = File(
                                        System.getProperty("user.home"),
                                        ".claude/projects/$encodedPath/${clickedSession.sessionId}.jsonl"
                                    )

                                    val messages = ClaudeCodeSessionMapper.loadSessionAsChatMessages(sessionFile)

                                    claudeCodeStreamingWrapper.start(
                                        sessionId = clickedSession.sessionId, 
                                        projectPath = clickedSession.projectPath
                                    )

                                    println("Loaded ${messages.size} messages from session ${clickedSession.sessionId}")
                                    println("Started streaming wrapper for project: ${clickedSession.projectPath}")
                                    
                                    onSessionSelected(clickedSession, messages)

                                } catch (e: Exception) {
                                    println("Error loading session: ${e.message}")
                                    e.printStackTrace()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionItem(
    session: ChatSession,
    onSessionClick: (ChatSession) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pointerInput(Unit) {
                detectTapGestures {
                    onSessionClick(session)
                }
            },
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = session.displayPreview(),
                style = MaterialTheme.typography.body1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Проект: ${session.displayProject()}",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.primary
            )
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