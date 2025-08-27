package com.gromozeka.bot.services

import com.gromozeka.bot.ui.viewmodel.AppViewModel
import com.gromozeka.bot.ui.state.ConversationInitiator
import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.coroutines.flow.first
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service
import kotlin.time.Clock

@Service
class ContextExtractionService(
    private val applicationContext: ApplicationContext,
) {

    suspend fun extractContextsFromTab(tabId: String) {
        val appViewModel = applicationContext.getBean(AppViewModel::class.java)

        val sourceTab = findTabById(appViewModel, tabId) ?: throw IllegalStateException("Tab not found: $tabId")

        val claudeSessionId = sourceTab.claudeSessionId.first()
        val projectPath = sourceTab.projectPath

        println("[ContextExtractionService] Extracting contexts from tab: $tabId")
        println("[ContextExtractionService] Source session: ${claudeSessionId.value}")
        println("[ContextExtractionService] Project path: $projectPath")

        val instructions = loadContextExtractionInstructions()

        // Create ChatMessage with instructions for context extraction
        val chatMessage = ChatMessage(
            role = ChatMessage.Role.USER,
            content = listOf(ChatMessage.ContentItem.UserMessage(instructions)),
            instructions = listOf(
                ChatMessage.Instruction.UserInstruction(
                    "thinking_ultrathink",
                    "Ultrathink",
                    "Use ultrathink mode"
                ),
                ChatMessage.Instruction.Source.User
            ),
            uuid = java.util.UUID.randomUUID().toString(),
            timestamp = Clock.System.now(),
            llmSpecificMetadata = null
        )

        val backgroundTabIndex = appViewModel.createTab(
            projectPath = projectPath,
            resumeSessionId = claudeSessionId.value,
            initialMessage = chatMessage,
            setAsCurrent = false,  // Работаем в фоне
            initiator = ConversationInitiator.System
        )

        println("[ContextExtractionService] Created background tab $backgroundTabIndex for context extraction")
    }

    private suspend fun findTabById(appViewModel: AppViewModel, tabId: String) =
        appViewModel.tabs.first().find { tab ->
            val uiState = tab.uiState.first()
            uiState.tabId == tabId
        }

    private fun loadContextExtractionInstructions(): String {
        return this::class.java.classLoader
            .getResourceAsStream("ai-instructions/context-extraction-instructions.md")
            ?.bufferedReader()
            ?.readText()
            ?: throw IllegalStateException("Can't find context extraction instruction from $this")

    }
}