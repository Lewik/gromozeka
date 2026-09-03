package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationSearchHit
import com.gromozeka.domain.model.ConversationSearchPage
import com.gromozeka.domain.model.ConversationSearchRequest
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.service.ConversationSearchService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSearchViewModelTest {
    @Test
    fun `query is debounced and stale query is never requested`() = runTest {
        val service = RecordingConversationSearchService(
            pages = mutableListOf(ConversationSearchPage(listOf(hit("message-2"))))
        )
        val viewModel = ConversationSearchViewModel(service, backgroundScope)

        viewModel.updateSearchQuery("old")
        advanceTimeBy(200)
        viewModel.updateSearchQuery("new")
        advanceTimeBy(299)
        runCurrent()

        assertTrue(service.requests.isEmpty())

        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf("new"), service.requests.map { it.query })
        assertEquals(listOf("message-2"), viewModel.searchResults.value.mapNotNull { it.messageId?.value })
        assertFalse(viewModel.isSearching.value)
    }

    @Test
    fun `next cursor appends unique results`() = runTest {
        val firstHit = hit("message-1")
        val secondHit = hit("message-2")
        val service = RecordingConversationSearchService(
            pages = mutableListOf(
                ConversationSearchPage(listOf(firstHit), nextCursor = "next-page"),
                ConversationSearchPage(listOf(firstHit, secondHit)),
            )
        )
        val viewModel = ConversationSearchViewModel(service, backgroundScope)

        viewModel.updateSearchQuery("needle")
        advanceTimeBy(300)
        runCurrent()

        assertTrue(viewModel.hasMoreResults.value)
        viewModel.loadMore()
        runCurrent()

        assertEquals(listOf(null, "next-page"), service.requests.map { it.cursor })
        assertEquals(
            listOf("message-1", "message-2"),
            viewModel.searchResults.value.mapNotNull { it.messageId?.value },
        )
        assertFalse(viewModel.hasMoreResults.value)
        assertFalse(viewModel.isLoadingMore.value)
    }

    private class RecordingConversationSearchService(
        private val pages: MutableList<ConversationSearchPage>,
    ) : ConversationSearchService {
        val requests = mutableListOf<ConversationSearchRequest>()

        override suspend fun search(request: ConversationSearchRequest): ConversationSearchPage {
            requests += request
            return pages.removeAt(0)
        }
    }

    private fun hit(messageId: String): ConversationSearchHit {
        val timestamp = Instant.parse("2026-08-19T10:00:00Z")
        val project = Project(
            id = Project.Id("project-1"),
            name = "Project",
            createdAt = timestamp,
            lastUsedAt = timestamp,
        )
        val conversation = Conversation(
            id = Conversation.Id("conversation-1"),
            projectId = project.id,
            participants = setOf(Conversation.Participant.Agent(AgentDefinition.Id("agent-1"))),
            displayName = "Conversation",
            currentThread = Conversation.Thread.Id("thread-1"),
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        return ConversationSearchHit(
            project = project,
            conversation = conversation,
            matchKind = ConversationSearchHit.MatchKind.MESSAGE,
            threadId = conversation.currentThread,
            messageId = Conversation.Message.Id(messageId),
            role = Conversation.Message.Role.USER,
            matchedAt = timestamp,
            excerpt = "Matching excerpt",
        )
    }
}
