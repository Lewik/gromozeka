package com.gromozeka.server

import com.gromozeka.application.service.ConversationPinnedPrompt
import com.gromozeka.application.service.SuggestedRepliesGenerationService
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationPin
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserProfile.SuggestedRepliesSettings
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.repository.ConversationPinRepository
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.infrastructure.ai.runtime.AiInputValidatingRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SuggestedRepliesTextOnlyRuntimeTest {
    @Test
    fun `full thread media is projected before a text only runtime validates the request`() = runBlocking {
        val now = Instant.fromEpochMilliseconds(0)
        val conversation = Conversation(
            id = Conversation.Id("conversation"),
            projectId = Project.Id("project"),
            participants = setOf(
                Conversation.Participant.User(User.Id("user")),
                Conversation.Participant.Agent(AgentDefinition.Id("agent")),
            ),
            currentThread = Conversation.Thread.Id("thread"),
            createdAt = now,
            updatedAt = now,
        )
        val attachment = Artifact.Reference(
            id = Artifact.Id("attachment"),
            fileName = "report.pdf",
            mediaType = "application/pdf",
            sizeBytes = 3,
            purpose = Artifact.Purpose.USER_ATTACHMENT,
            kind = Artifact.Kind.DOCUMENT,
        )
        val source = Conversation.Message(
            id = Conversation.Message.Id("source"),
            conversationId = conversation.id,
            role = Conversation.Message.Role.USER,
            content = listOf(
                Conversation.Message.ContentItem.UserMessage("Read this report"),
                Conversation.Message.ContentItem.ArtifactItem(attachment),
                Conversation.Message.ContentItem.DocumentItem(
                    Conversation.Message.DocumentSource.Base64DocumentSource(
                        data = "cGRm",
                        mediaType = "application/pdf",
                        fileName = "materialized.pdf",
                    )
                ),
            ),
            createdAt = now,
        )
        val answer = Conversation.Message(
            id = Conversation.Message.Id("answer"),
            conversationId = conversation.id,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(
                Conversation.Message.ContentItem.AssistantMessage(
                    Conversation.Message.StructuredText("The report is ready")
                )
            ),
            createdAt = now,
        )
        val captured = mutableListOf<AiRuntimeRequest>()
        val delegate = object : AiRuntime {
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                captured += request
                return AiRuntimeResponse(
                    listOf(
                        AiAssistantMessage(
                            listOf(
                                Conversation.Message.ContentItem.AssistantMessage(
                                    Conversation.Message.StructuredText("", suggestedReplies = listOf("Next"))
                                )
                            )
                        )
                    )
                )
            }

            override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = error("Unexpected stream")
        }
        val connection = AiConnection.OpenAiApi(AiConnection.Id("connection"), "OpenAI", enabled = true)
        val model = AiModelConfiguration(
            id = AiModelConfiguration.Id("model"),
            connectionId = connection.id,
            providerModelId = "text-only",
            displayName = "Text only",
        )
        val runtime = AiInputValidatingRuntime(
            delegate = delegate,
            runtime = ResolvedAiRuntime(
                connection = connection,
                modelConfiguration = model,
                modelSpec = AiModelSpec(
                    id = model.providerModelId,
                    provider = AiProvider.OPENAI,
                    capabilities = setOf(AiModelCapability.TEXT_GENERATION),
                    limits = AiModelSpec.Limits(
                        textGeneration = AiModelSpec.Limits.TextGeneration(contextWindowTokens = 16_000)
                    ),
                ),
            ),
        )
        val provider = object : AiRuntimeProvider {
            override fun getRuntime(selection: AiRuntimeSelection, workspaceRootPath: String?): AiRuntime = runtime
        }
        val pinRepository = Mockito.mock(ConversationPinRepository::class.java) { invocation ->
            if (invocation.method.name.substringBefore('-') == "list") emptyList<ConversationPin>()
            else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        val service = SuggestedRepliesGenerationService(
            provider,
            ConversationPinnedPrompt(pinRepository),
            Mockito.mock(AiConfigurationProvider::class.java),
        )

        assertEquals(
            listOf("Next"),
            service.generate(
                conversation = conversation,
                context = SuggestedRepliesGenerationService.Context.Full(listOf(source, answer)),
                sourceMessage = answer,
                agentDefinitionId = AgentDefinition.Id("agent"),
                runtimeSelection = AiRuntimeSelection(model.id),
                actorUserId = User.Id("user"),
                usageId = "suggestions",
                settings = SuggestedRepliesSettings(contextMode = SuggestedRepliesSettings.ContextMode.FULL_THREAD),
            ),
        )

        val request = captured.single()
        assertEquals("Read this report", (request.messages[0].content[0] as Conversation.Message.ContentItem.UserMessage).text)
        assertContains((request.messages[0].content[1] as Conversation.Message.ContentItem.UserMessage).text, "report.pdf")
        assertContains((request.messages[0].content[2] as Conversation.Message.ContentItem.UserMessage).text, "materialized.pdf")
        assertTrue(request.messages.flatMap { it.content }.none {
            it is Conversation.Message.ContentItem.ImageItem ||
                it is Conversation.Message.ContentItem.DocumentItem ||
                it is Conversation.Message.ContentItem.ArtifactItem
        })
    }
}
