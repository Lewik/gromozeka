package com.gromozeka.bot.services

import com.gromozeka.bot.ui.viewmodel.AppViewModel
import com.gromozeka.bot.ui.state.ConversationInitiator
import klog.KLoggers
import com.gromozeka.shared.domain.Conversation
import kotlinx.coroutines.flow.first
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.time.Clock

@Service
class ContextExtractionService(
    private val applicationContext: ApplicationContext,
) {
    private val log = KLoggers.logger(this)

    suspend fun extractContextsFromTab(tabId: String) {
        val appViewModel = applicationContext.getBean(AppViewModel::class.java)

        val sourceTab = findTabById(appViewModel, tabId) ?: throw IllegalStateException("Tab not found: $tabId")

        val conversationId = sourceTab.uiState.first().conversationId
        val projectPath = sourceTab.projectPath

        log.info("Extracting contexts from tab: $tabId")
        log.info("Source conversation: ${conversationId.value}")
        log.info("Project path: $projectPath")

        val instructions = loadContextExtractionInstructions()

        // Create Conversation.Message with instructions for context extraction
        val chatMessage = Conversation.Message(
            id = Conversation.Message.Id(UUID.randomUUID().toString()),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(instructions)),
            createdAt = Clock.System.now(),
            instructions = listOf(
                Conversation.Message.Instruction.UserInstruction(
                    "thinking_ultrathink",
                    "Ultrathink",
                    "Use ultrathink mode"
                ),
                Conversation.Message.Instruction.Source.User
            )
        )

        val backgroundTabIndex = appViewModel.createTab(
            projectPath = projectPath,
            conversationId = conversationId,
            initialMessage = chatMessage,
            setAsCurrent = false,  // Работаем в фоне
            initiator = ConversationInitiator.System
        )

        log.info("Created background tab $backgroundTabIndex for context extraction")
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