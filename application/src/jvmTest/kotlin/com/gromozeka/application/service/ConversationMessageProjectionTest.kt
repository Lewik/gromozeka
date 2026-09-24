package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.PositionedConversationMessage
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class ConversationMessageProjectionTest {
    @Test
    fun providerReplayStateStaysOnTheServer() {
        val original = message(listOf(
            Conversation.Message.ContentItem.Thinking("Readable reasoning", "signature-secret"),
            Conversation.Message.ContentItem.ContextCompactionResult(
                payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState(buildJsonObject { put("encrypted", "opaque-secret") }),
                origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO,
                coverage = Conversation.Message.ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                sourceMessageIds = listOf(Conversation.Message.Id("source")),
            ),
        )).copy(providerMetadata = buildJsonObject {
            put("providerReplay", "replay-secret")
            put("compactionBoundary", buildJsonObject { put("trigger", "automatic") })
        })
        val view = ConversationMessageProjection.project(PositionedConversationMessage(7, original))
        val encoded = Json.encodeToString(view)
        assertFalse(encoded.contains("signature-secret"))
        assertFalse(encoded.contains("opaque-secret"))
        assertFalse(encoded.contains("replay-secret"))
        assertTrue(encoded.contains("Readable reasoning"))
        assertTrue(encoded.contains("automatic"))
        assertFalse(view.hasMoreContent)
        val checkpoint = view.message.content.filterIsInstance<Conversation.Message.ContentItem.ContextCompactionResult>().single()
        assertTrue(checkpoint.coversAllPrevious)
        assertEquals(listOf(Conversation.Message.Id("source")), checkpoint.sourceMessageIds)
        assertEquals("signature-secret", (original.content.first() as Conversation.Message.ContentItem.Thinking).signature)
    }

    @Test
    fun visiblePreviewsAreBoundedAndExplicitlyMarkedIncomplete() {
        val original = message(listOf(Conversation.Message.ContentItem.UserMessage("z".repeat(300_000))))
        val view = ConversationMessageProjection.project(PositionedConversationMessage(5, original))
        assertTrue(view.hasMoreContent)
        assertTrue(Json.encodeToString(view).encodeToByteArray().size < 32 * 1024)
        assertEquals(300_000, (original.content.single() as Conversation.Message.ContentItem.UserMessage).text.length)
    }

    @Test
    fun embeddedImagesAreNotIncludedInTimelineEvents() {
        val original = message(listOf(Conversation.Message.ContentItem.ImageItem(Conversation.Message.ImageSource.Base64ImageSource("a".repeat(500_000), "image/png"))))
        val view = ConversationMessageProjection.project(PositionedConversationMessage(3, original))
        assertTrue(view.hasMoreContent)
        assertTrue(Json.encodeToString(view).length < 2_000)
        assertEquals(500_000, ((original.content.single() as Conversation.Message.ContentItem.ImageItem).source as Conversation.Message.ImageSource.Base64ImageSource).data.length)
    }

    private fun message(content: List<Conversation.Message.ContentItem>) = Conversation.Message(
        id = Conversation.Message.Id("message"), conversationId = Conversation.Id("conversation"),
        role = Conversation.Message.Role.ASSISTANT, content = content,
        createdAt = Instant.parse("2026-09-16T00:00:00Z"),
    )
}
