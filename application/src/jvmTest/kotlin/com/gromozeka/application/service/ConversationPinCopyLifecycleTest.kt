package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import kotlin.test.*
import kotlin.time.Instant

/** Verifies lifecycle wiring, not transaction atomicity (covered by PostgresConversationPinRepositoryTest). */
class ConversationPinCopyLifecycleTest {
    private val now = Instant.fromEpochMilliseconds(0)
    private val user = User.Id("user")
    private val project = Project(Project.Id("project"), "Project", createdAt = now, lastUsedAt = now)
    private val oldAgent = AgentDefinition.Id("old-agent")
    private val defaultAgent = AgentDefinition(AgentDefinition.Id("new-default"), name = "Default", prompts = emptyList(),
        runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model")), type = AgentDefinition.Type.Global, createdAt = now, updatedAt = now)
    private val source = Conversation(Conversation.Id("source"), project.id,
        setOf(Conversation.Participant.User(user), Conversation.Participant.Agent(oldAgent)), currentThread = Conversation.Thread.Id("source-thread"),
        autoRespondAgentIds = setOf(oldAgent), createdAt = now, updatedAt = now)

    @Test fun `Restart creates empty history with requested default participants and uses atomic pin-copy seam`() = runBlocking<Unit> {
        val f = Fixture()
        val participants = setOf(Conversation.Participant.User(user), Conversation.Participant.Agent(defaultAgent.id))
        val result = f.service.createWithCopiedPins(project.id, participants, source.id)
        assertEquals(participants, result.participants)
        assertEquals(setOf(defaultAgent.id), result.autoRespondAgentIds)
        assertNotEquals(source.id, result.id); assertNotEquals(source.currentThread, result.currentThread)
        assertEquals(listOf(source.id.value), f.copiedFrom)
        assertEquals(listOf(result), f.created)
        assertTrue(f.ordinaryCreated.isEmpty())
        assertEquals(result.currentThread, f.threads.single().id)
        Mockito.verifyNoInteractions(f.messages, f.links, f.artifacts, f.suggestions)
        assertContains(f.events, DeclarativeStateKey.conversationPins(result.id))
    }

    @Test fun `ordinary create is unchanged while fork copies pins through the same seam`() = runBlocking<Unit> {
        val ordinary = Fixture()
        ordinary.service.create(project.id, setOf(Conversation.Participant.User(user)))
        assertEquals(1, ordinary.ordinaryCreated.size); assertTrue(ordinary.copiedFrom.isEmpty())
        assertTrue(ordinary.events.none { it.resource == DeclarativeStateResource.CONVERSATION_PINS })
        val fork = Fixture()
        val result = fork.service.fork(source.id)
        assertEquals(source.participants, result.participants)
        assertEquals(source.autoRespondAgentIds, result.autoRespondAgentIds)
        assertEquals(listOf(source.id.value), fork.copiedFrom)
        assertTrue(fork.ordinaryCreated.isEmpty())
        assertContains(fork.events, DeclarativeStateKey.conversationPins(result.id))
    }

    @Test fun `copy failure neither falls back to create nor publishes or starts history copying`() = runBlocking<Unit> {
        val f = Fixture(); f.copyFailure = IllegalStateException("PRIVATE_PIN_TEXT")
        val failure = assertFailsWith<ConversationPinOperationFailedException> {
            f.service.createWithCopiedPins(project.id, setOf(Conversation.Participant.User(user)), source.id)
        }
        assertFalse(failure.toString().contains("PRIVATE_")); assertNull(failure.cause)
        assertTrue(f.events.isEmpty()); assertTrue(f.ordinaryCreated.isEmpty()); assertTrue(f.threads.isEmpty())
        Mockito.verifyNoInteractions(f.messages, f.links, f.artifacts, f.suggestions)
    }

    private inner class Fixture {
        var copyFailure: Exception? = null
        val copiedFrom = mutableListOf<String>()
        val created = mutableListOf<Conversation>()
        val ordinaryCreated = mutableListOf<Conversation>()
        val threads = mutableListOf<Conversation.Thread>()
        val events = mutableListOf<DeclarativeStateKey>()
        val conversations = responding<ConversationRepository> { invocation ->
            when (invocation.method.name.substringBefore('-')) {
                "findById" -> source
                "create" -> (invocation.arguments[0] as Conversation).also { ordinaryCreated += it }
                "createWithCopiedPins" -> {
                    copyFailure?.let { throw it }
                    copiedFrom += invocation.arguments[1] as String
                    (invocation.arguments[0] as Conversation).also { created += it }
                }
                else -> error("Unexpected repository operation")
            }
        }
        val threadRepository = responding<ThreadRepository> { invocation ->
            if (invocation.method.name.substringBefore('-') == "save") (invocation.arguments[0] as Conversation.Thread).also { threads += it }
            else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        val messages = responding<MessageRepository> { emptyList<Conversation.Message>() }
        val links = responding<ThreadMessageRepository> { invocation ->
            if (invocation.method.name.substringBefore('-') == "getByThread") emptyList<ThreadMessageLink>() else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        val artifacts = responding<ConversationArtifactApplicationService> { invocation ->
            if (invocation.method.name.substringBefore('-') == "cloneReferences") invocation.arguments[2] else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        val suggestions = mock<SuggestedRepliesGenerationService>()
        val service = ConversationApplicationService(conversations, threadRepository, messages, links,
            responding { project }, mock(), responding { defaultAgent }, ToolCallPairingService(), mock(), mock(), artifacts,
            suggestions, mock(), DeclarativeStateChangePublisher { keys -> events += keys })
    }
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
    private inline fun <reified T> responding(noinline answer: (InvocationOnMock) -> Any?): T = Mockito.mock(T::class.java) { answer(it) }
}
