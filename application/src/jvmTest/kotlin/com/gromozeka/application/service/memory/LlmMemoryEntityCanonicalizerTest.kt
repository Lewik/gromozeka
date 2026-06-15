package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import com.gromozeka.domain.model.memory.isValidMemoryEntityId
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LlmMemoryEntityCanonicalizerTest {

    @Test
    fun createNewIgnoresBareEntityIdFromModel() = runBlocking {
        val canonicalizer = LlmMemoryEntityCanonicalizer(
            runtime = FixedJsonRuntime(
                """
                {
                  "operations": [
                    {
                      "mention": "token rotation",
                      "action": "create_new",
                      "entity_id": "0cdfb4d3913a9344",
                      "new_entity": {
                        "entity_type": "concept",
                        "canonical_name": "token rotation",
                        "summary": "Token rotation responsibility."
                      },
                      "confidence": 0.9,
                      "reason": "Concrete project concept."
                    }
                  ]
                }
                """.trimIndent()
            ),
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = canonicalizer.canonicalize(
            request = request("Mira owns the token rotation."),
            retrievalPlan = MemoryWriteRetrievalPlan(),
            retrievedHits = emptyList(),
        )

        assertEquals(1, ops.size)
        val entityId = ops.single().entityId
        assertTrue(entityId != null)
        assertNotEquals("0cdfb4d3913a9344", entityId.value)
        assertTrue(entityId.value.isValidMemoryEntityId())
    }

    @Test
    fun createNewUsesCanonicalNameAsIdentityAcrossEntityTypes() = runBlocking {
        val personId = canonicalizedNewEntityId(
            entityType = "person",
            canonicalName = "Mira",
        )
        val serviceId = canonicalizedNewEntityId(
            entityType = "service",
            canonicalName = "Mira",
        )

        assertEquals(personId, serviceId)
    }

    @Test
    fun linkExistingRestoresStrippedEntityPrefixFromCandidateId() = runBlocking {
        val candidateId = MemoryEntity.Id("entity:0cdfb4d3913a9344")
        val canonicalizer = LlmMemoryEntityCanonicalizer(
            runtime = FixedJsonRuntime(
                """
                {
                  "operations": [
                    {
                      "mention": "Mira",
                      "action": "link_existing",
                      "entity_id": "0cdfb4d3913a9344",
                      "confidence": 0.9,
                      "reason": "Matches the existing candidate."
                    }
                  ]
                }
                """.trimIndent()
            ),
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = canonicalizer.canonicalize(
            request = request("Mira owns the token rotation."),
            retrievalPlan = MemoryWriteRetrievalPlan(),
            retrievedHits = listOf(MemoryStore.SearchHit.EntityHit(entity(candidateId, "Mira"), score = 1.0)),
        )

        assertEquals(candidateId, ops.single().entityId)
    }

    @Test
    fun createNewLinksSafeSameSurfaceExistingCandidate() = runBlocking {
        val candidateId = MemoryEntity.Id("entity:0000000000000001")
        val canonicalizer = LlmMemoryEntityCanonicalizer(
            runtime = FixedJsonRuntime(
                """
                {
                  "operations": [
                    {
                      "mention": "Mira",
                      "action": "create_new",
                      "entity_id": null,
                      "new_entity": {
                        "entity_type": "person",
                        "canonical_name": "Mira",
                        "summary": "Person named Mira."
                      },
                      "confidence": 0.8,
                      "reason": "Reusable person mention."
                    }
                  ]
                }
                """.trimIndent()
            ),
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = canonicalizer.canonicalize(
            request = request("Mira owns the deployment checklist."),
            retrievalPlan = MemoryWriteRetrievalPlan(entityQueries = listOf("Mira")),
            retrievedHits = listOf(MemoryStore.SearchHit.EntityHit(entity(candidateId, "Mira"), score = 1.0)),
        )

        val op = ops.single()
        assertEquals(MemoryEntityCanonicalizationOp.Action.LINK_EXISTING, op.action)
        assertEquals(candidateId, op.entityId)
        assertEquals(null, op.newEntity)
    }

    @Test
    fun selfIdentityPersonMentionCreatesStableUserAlias() = runBlocking {
        val canonicalizer = LlmMemoryEntityCanonicalizer(
            runtime = FixedJsonRuntime(
                """
                {
                  "operations": [
                    {
                      "mention": "Lev Lewik",
                      "action": "create_new",
                      "entity_id": null,
                      "new_entity": {
                        "entity_type": "person",
                        "canonical_name": "Lev Lewik",
                        "summary": "Person named Lev Lewik."
                      },
                      "confidence": 0.9,
                      "reason": "The user stated a personal name."
                    }
                  ]
                }
                """.trimIndent()
            ),
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = canonicalizer.canonicalize(
            request = request("My name is Lev Lewik, and I prefer Kotlin."),
            retrievalPlan = MemoryWriteRetrievalPlan(entityQueries = listOf("Lev Lewik")),
            retrievedHits = emptyList(),
        )

        val op = ops.single()
        assertEquals(MemoryEntityCanonicalizationOp.Action.CREATE_NEW, op.action)
        assertEquals(MemoryEntity.Type.USER, op.newEntity?.entityType)
        assertEquals("User", op.newEntity?.canonicalName)
        assertEquals("Lev Lewik", op.aliasText)
    }

    @Test
    fun preferredNameCandidateRedirectsNamedPersonToExistingUser() = runBlocking {
        val user = entity(
            id = MemoryEntity.Id("entity:0000000000000002"),
            name = "User",
            type = MemoryEntity.Type.USER,
        )
        val preferredNameClaim = claim(
            id = "claim-preferred-name",
            subjectEntityId = user.id,
            predicate = "preferred_name",
            objectValue = JsonPrimitive("Lev"),
            normalizedText = "The user's preferred name is Lev.",
        )
        val canonicalizer = LlmMemoryEntityCanonicalizer(
            runtime = FixedJsonRuntime(
                """
                {
                  "operations": [
                    {
                      "mention": "Lev",
                      "action": "create_new",
                      "entity_id": null,
                      "new_entity": {
                        "entity_type": "person",
                        "canonical_name": "Lev",
                        "summary": "Person named Lev."
                      },
                      "confidence": 0.8,
                      "reason": "Named person mention."
                    }
                  ]
                }
                """.trimIndent()
            ),
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = canonicalizer.canonicalize(
            request = documentRequest("Lev prefers concise direct engineering discussion."),
            retrievalPlan = MemoryWriteRetrievalPlan(entityQueries = listOf("Lev")),
            retrievedHits = listOf(
                MemoryStore.SearchHit.EntityHit(user, score = 1.0),
                MemoryStore.SearchHit.ClaimHit(preferredNameClaim, score = 1.0),
            ),
        )

        val op = ops.single()
        assertEquals(MemoryEntityCanonicalizationOp.Action.ADD_ALIAS, op.action)
        assertEquals(user.id, op.entityId)
        assertEquals(null, op.newEntity)
        assertEquals("Lev", op.aliasText)
    }

    @Test
    fun existingNamedPersonCandidateMatchingUserIdentityRedirectsToUser() = runBlocking {
        val user = entity(
            id = MemoryEntity.Id("entity:0000000000000002"),
            name = "User",
            type = MemoryEntity.Type.USER,
            aliases = listOf(alias("Lev Lewik")),
        )
        val existingPerson = entity(
            id = MemoryEntity.Id("entity:0000000000000003"),
            name = "Lev Lewik",
            type = MemoryEntity.Type.PERSON,
        )
        val canonicalizer = LlmMemoryEntityCanonicalizer(
            runtime = FixedJsonRuntime(
                """
                {
                  "operations": [
                    {
                      "mention": "Lev Lewik",
                      "action": "link_existing",
                      "entity_id": "entity:0000000000000003",
                      "confidence": 0.8,
                      "reason": "The named person candidate matches the mention."
                    }
                  ]
                }
                """.trimIndent()
            ),
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = canonicalizer.canonicalize(
            request = documentRequest("Lev Lewik prefers concise direct engineering discussion."),
            retrievalPlan = MemoryWriteRetrievalPlan(entityQueries = listOf("Lev Lewik")),
            retrievedHits = listOf(
                MemoryStore.SearchHit.EntityHit(user, score = 1.0),
                MemoryStore.SearchHit.EntityHit(existingPerson, score = 1.0),
            ),
        )

        val op = ops.single()
        assertEquals(MemoryEntityCanonicalizationOp.Action.ADD_ALIAS, op.action)
        assertEquals(user.id, op.entityId)
        assertEquals(null, op.newEntity)
        assertEquals("Lev Lewik", op.aliasText)
    }

    @Test
    fun firstNamePersonWithoutExplicitShortUserAliasStaysPerson() = runBlocking {
        val user = entity(
            id = MemoryEntity.Id("entity:0000000000000002"),
            name = "User",
            type = MemoryEntity.Type.USER,
            aliases = listOf(alias("Lev Lewik")),
        )
        val canonicalizer = LlmMemoryEntityCanonicalizer(
            runtime = FixedJsonRuntime(
                """
                {
                  "operations": [
                    {
                      "mention": "Lev",
                      "action": "create_new",
                      "entity_id": null,
                      "new_entity": {
                        "entity_type": "person",
                        "canonical_name": "Lev",
                        "summary": "Person named Lev."
                      },
                      "confidence": 0.8,
                      "reason": "Named person mention."
                    }
                  ]
                }
                """.trimIndent()
            ),
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = canonicalizer.canonicalize(
            request = documentRequest("Lev owns a separate deployment checklist."),
            retrievalPlan = MemoryWriteRetrievalPlan(entityQueries = listOf("Lev")),
            retrievedHits = listOf(MemoryStore.SearchHit.EntityHit(user, score = 1.0)),
        )

        val op = ops.single()
        assertEquals(MemoryEntityCanonicalizationOp.Action.CREATE_NEW, op.action)
        assertEquals(MemoryEntity.Type.PERSON, op.newEntity?.entityType)
        assertEquals("Lev", op.newEntity?.canonicalName)
    }

    @Test
    fun thirdPersonPersonMentionWithoutUserIdentitySignalStaysPerson() = runBlocking {
        val canonicalizer = LlmMemoryEntityCanonicalizer(
            runtime = FixedJsonRuntime(
                """
                {
                  "operations": [
                    {
                      "mention": "Lev",
                      "action": "create_new",
                      "entity_id": null,
                      "new_entity": {
                        "entity_type": "person",
                        "canonical_name": "Lev",
                        "summary": "Person named Lev."
                      },
                      "confidence": 0.8,
                      "reason": "Named person mention."
                    }
                  ]
                }
                """.trimIndent()
            ),
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = canonicalizer.canonicalize(
            request = documentRequest("Lev owns a separate deployment checklist."),
            retrievalPlan = MemoryWriteRetrievalPlan(entityQueries = listOf("Lev")),
            retrievedHits = emptyList(),
        )

        val op = ops.single()
        assertEquals(MemoryEntityCanonicalizationOp.Action.CREATE_NEW, op.action)
        assertEquals(MemoryEntity.Type.PERSON, op.newEntity?.entityType)
        assertEquals("Lev", op.newEntity?.canonicalName)
    }

    @Test
    fun candidateEntityPromptDoesNotDependOnRetrievedHitOrder() = runBlocking {
        val entityCandidate = entity(
            id = MemoryEntity.Id("entity:0000000000000001"),
            name = "Entity",
            type = MemoryEntity.Type.CONCEPT,
            aliases = listOf(
                MemoryEntity.Alias(text = "entity model", normalizedText = "entity model", createdAt = NOW),
                MemoryEntity.Alias(text = "Entity", normalizedText = "entity", createdAt = NOW),
            ),
        )
        val profileCandidate = entity(
            id = MemoryEntity.Id("entity:0000000000000002"),
            name = "Profile",
            type = MemoryEntity.Type.CONCEPT,
        )

        val firstPrompt = captureEntityCanonicalizerPrompt(
            retrievedHits = listOf(
                MemoryStore.SearchHit.EntityHit(profileCandidate, score = 1.0),
                MemoryStore.SearchHit.EntityHit(entityCandidate, score = 1.0),
            ),
        )
        val secondPrompt = captureEntityCanonicalizerPrompt(
            retrievedHits = listOf(
                MemoryStore.SearchHit.EntityHit(entityCandidate, score = 1.0),
                MemoryStore.SearchHit.EntityHit(profileCandidate, score = 1.0),
            ),
        )

        assertEquals(firstPrompt.candidateExistingEntitiesBlock(), secondPrompt.candidateExistingEntitiesBlock())
        assertTrue(
            firstPrompt.candidateExistingEntitiesBlock().indexOf("name=Profile") <
                firstPrompt.candidateExistingEntitiesBlock().indexOf("name=Entity")
        )
        assertTrue(
            firstPrompt.candidateExistingEntitiesBlock().indexOf("aliases=Entity, entity model") >= 0
        )
    }

    @Test
    fun repairsDocumentFileEntityWithoutAboutFileAssertion() = runBlocking {
        val runtime = SequencedJsonRuntime(
            responses = ArrayDeque(
                listOf(
                    """
                    {
                      "operations": [
                        {
                          "mention": "MemoryToolSupport.kt",
                          "action": "create_new",
                          "entity_id": null,
                          "new_entity": {
                            "entity_type": "file",
                            "canonical_name": "MemoryToolSupport.kt",
                            "summary": "File named MemoryToolSupport.kt."
                          },
                          "about_file_assertion": false,
                          "alias_text": null,
                          "confidence": 0.8,
                          "reason": "The document mentions this Kotlin file."
                        }
                      ]
                    }
                    """.trimIndent(),
                    """
                    {
                      "operations": [
                        {
                          "mention": "MemoryToolSupport.kt",
                          "action": "noop",
                          "entity_id": null,
                          "new_entity": null,
                          "about_file_assertion": false,
                          "alias_text": null,
                          "confidence": 0.9,
                          "reason": "This is an incidental code reference, not a durable assertion about the file itself."
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            )
        )
        val canonicalizer = LlmMemoryEntityCanonicalizer(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = canonicalizer.canonicalize(
            request = documentRequest(
                """
                Document source: docs/lessons.md
                Document section: Memory lessons

                The lesson mentions MemoryToolSupport.kt while explaining MCP tool status output.
                """.trimIndent()
            ),
            retrievalPlan = MemoryWriteRetrievalPlan(),
            retrievedHits = emptyList(),
        )

        assertEquals(1, ops.size)
        assertEquals(MemoryEntityCanonicalizationOp.Action.NOOP, ops.single().action)
        assertEquals(2, runtime.requests.size)
        assertEquals(true, runtime.requests.last().options.toolContext["memoryStageRepair"])
    }

    private suspend fun canonicalizedNewEntityId(
        entityType: String,
        canonicalName: String,
    ): MemoryEntity.Id {
        val canonicalizer = LlmMemoryEntityCanonicalizer(
            runtime = FixedJsonRuntime(
                """
                {
                  "operations": [
                    {
                      "mention": "$canonicalName",
                      "action": "create_new",
                      "entity_id": null,
                      "new_entity": {
                        "entity_type": "$entityType",
                        "canonical_name": "$canonicalName",
                        "summary": "A test entity."
                      },
                      "confidence": 0.9,
                      "reason": "Concrete reusable referent."
                    }
                  ]
                }
                """.trimIndent()
            ),
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = canonicalizer.canonicalize(
            request = request("$canonicalName owns the token rotation."),
            retrievalPlan = MemoryWriteRetrievalPlan(),
            retrievedHits = emptyList(),
        )

        return requireNotNull(ops.single().entityId)
    }

    private suspend fun captureEntityCanonicalizerPrompt(
        retrievedHits: List<MemoryStore.SearchHit>,
    ): String {
        val runtime = RecordingJsonRuntime()
        LlmMemoryEntityCanonicalizer(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).canonicalize(
            request = request("The data model has Entity and Profile concepts."),
            retrievalPlan = MemoryWriteRetrievalPlan(entityQueries = listOf("Profile", "Entity")),
            retrievedHits = retrievedHits,
        )
        return runtime.prompt()
    }

    private class FixedJsonRuntime(
        private val responseText: String,
    ) : AiRuntime {
        override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse =
            AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                structured = Conversation.Message.StructuredText(responseText),
                            )
                        )
                    )
                )
            )

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()
    }

    private class RecordingJsonRuntime : AiRuntime {
        private var request: AiRuntimeRequest? = null
        override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            this.request = request
            return AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                structured = Conversation.Message.StructuredText("""{"operations":[]}"""),
                            )
                        )
                    )
                )
            )
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()

        fun prompt(): String {
            val message = request?.messages?.single()
                ?: error("Entity canonicalizer did not call runtime")
            return message.content
                .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
                .single()
                .text
        }
    }

    private class SequencedJsonRuntime(
        private val responses: ArrayDeque<String>,
    ) : AiRuntime {
        val requests = mutableListOf<AiRuntimeRequest>()
        override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            requests += request
            return AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                structured = Conversation.Message.StructuredText(responses.removeFirst()),
                            )
                        )
                    )
                )
            )
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()
    }

    private companion object {
        val TEST_NAMESPACE = MemoryNamespace("entity-canonicalizer-test")
        val NOW: Instant = Instant.parse("2026-01-02T03:04:05Z")

        fun request(text: String): DirectStructuredMemoryWriteRequest =
            DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = MemorySource.ChatTurn(
                    id = MemorySource.Id("source"),
                    namespace = TEST_NAMESPACE,
                    conversationId = Conversation.Id("conversation"),
                    threadId = Conversation.Thread.Id("thread"),
                    sourceMessageId = Conversation.Message.Id("message"),
                    speakerRole = MemorySource.ActorRole.USER,
                    contentText = text,
                    contentHash = "source-hash",
                    observedAt = NOW,
                    createdAt = NOW,
                ),
            )

        fun documentRequest(text: String): DirectStructuredMemoryWriteRequest =
            DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = MemorySource.ExternalRecord(
                    id = MemorySource.Id("external:document-section"),
                    namespace = TEST_NAMESPACE,
                    recordRef = "docs/lessons.md#section:1",
                    authorLabel = "document section",
                    contentText = text,
                    contentPayload = buildJsonObject {
                        put("memoryToolOrigin", "provided_document_section")
                        put("sourceKind", "document")
                        put("documentType", "MARKDOWN")
                        put("sourceRef", "docs/lessons.md")
                        put("heading", "Memory lessons")
                    },
                    contentHash = "source-hash",
                    observedAt = NOW,
                    createdAt = NOW,
                ),
            )

        fun entity(
            id: MemoryEntity.Id,
            name: String,
            type: MemoryEntity.Type = MemoryEntity.Type.PERSON,
            aliases: List<MemoryEntity.Alias> = emptyList(),
        ): MemoryEntity =
            MemoryEntity(
                id = id,
                namespace = TEST_NAMESPACE,
                entityType = type,
                canonicalName = name,
                normalizedName = name.lowercase(),
                aliases = aliases,
                attributes = JsonObject(emptyMap()),
                firstSeenAt = NOW,
                lastSeenAt = NOW,
                createdAt = NOW,
                updatedAt = NOW,
            )

        fun alias(text: String): MemoryEntity.Alias =
            MemoryEntity.Alias(
                text = text,
                normalizedText = text.lowercase(),
                createdAt = NOW,
            )

        fun claim(
            id: String,
            subjectEntityId: MemoryEntity.Id,
            predicate: String,
            objectValue: JsonPrimitive,
            normalizedText: String,
        ): MemoryClaim =
            MemoryClaim(
                id = MemoryClaim.Id(id),
                namespace = TEST_NAMESPACE,
                subjectEntityId = subjectEntityId,
                predicate = predicate,
                objectValue = objectValue,
                normalizedText = normalizedText,
                scope = MemoryScope.Global("Test user identity"),
                confidence = 0.9,
                importance = 8,
                firstSeenAt = NOW,
                lastSeenAt = NOW,
                createdAt = NOW,
                updatedAt = NOW,
            )
    }
}

private fun String.candidateExistingEntitiesBlock(): String =
    Regex("""Candidate existing entities:\s*\n(.*?)\n\s*Relevant retrieval context""", RegexOption.DOT_MATCHES_ALL)
        .find(this)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: error("Candidate existing entities block not found")
