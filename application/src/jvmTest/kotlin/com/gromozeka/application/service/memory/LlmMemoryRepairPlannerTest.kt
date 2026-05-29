package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryMaintenanceRequest
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryProfile
import com.gromozeka.domain.model.memory.MemoryRepairCandidateCluster
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive

class LlmMemoryRepairPlannerTest {
    @Test
    fun repairPromptEvidenceDoesNotDependOnSnapshotCollectionOrder() = runBlocking {
        val olderSource = source("source-older", "Gromozeka uses gpt-5.3-codex.")
        val newerSource = source("source-newer", "Gromozeka uses gpt-5.5.")
        val olderClaim = claim("claim-older", olderSource, "gpt-5.3-codex", OLDER)
        val newerClaim = claim("claim-newer", newerSource, "gpt-5.5", NEWER)
        val cluster = MemoryRepairCandidateCluster(
            id = "repair-cluster-1",
            kind = MemoryRepairCandidateCluster.Kind.CONFLICTING_CLAIMS,
            hits = listOf(
                MemoryStore.SearchHit.ClaimHit(newerClaim, score = 0.95),
                MemoryStore.SearchHit.ClaimHit(olderClaim, score = 0.95),
            ),
            reason = "Claims disagree.",
        )

        val firstPrompt = captureRepairPrompt(
            cluster = cluster,
            snapshot = snapshot(
                sources = listOf(newerSource, olderSource),
                claims = listOf(newerClaim, olderClaim),
                profiles = listOf(profile("profile-b"), profile("profile-a")),
            ),
        )
        val secondPrompt = captureRepairPrompt(
            cluster = cluster,
            snapshot = snapshot(
                sources = listOf(olderSource, newerSource),
                claims = listOf(olderClaim, newerClaim),
                profiles = listOf(profile("profile-a"), profile("profile-b")),
            ),
        )

        assertEquals(firstPrompt.supportingEvidenceBlock(), secondPrompt.supportingEvidenceBlock())
        assertEquals(firstPrompt.candidateClustersBlock(), secondPrompt.candidateClustersBlock())
        assertTrue(firstPrompt.supportingEvidenceBlock().indexOf("source source-older") < firstPrompt.supportingEvidenceBlock().indexOf("source source-newer"))
        assertTrue(firstPrompt.supportingEvidenceBlock().indexOf("profile profile-a") < firstPrompt.supportingEvidenceBlock().indexOf("profile profile-b"))
    }

    private suspend fun captureRepairPrompt(
        cluster: MemoryRepairCandidateCluster,
        snapshot: MemoryNamespaceSnapshot,
    ): String {
        val runtime = RecordingRuntime()
        LlmMemoryRepairPlanner(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).plan(
            request = MemoryMaintenanceRequest(NAMESPACE),
            candidateClusters = listOf(cluster),
            snapshot = snapshot,
        )
        return runtime.prompt()
    }

    private class RecordingRuntime : AiRuntime {
        private var request: AiRuntimeRequest? = null

        override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            this.request = request
            return AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                Conversation.Message.StructuredText("""{"repair_actions":[],"summary":"noop"}""")
                            )
                        )
                    )
                )
            )
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()

        fun prompt(): String {
            val message = request?.messages?.single()
                ?: error("Repair planner did not call runtime")
            return message.content
                .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
                .single()
                .text
        }
    }
}

private fun String.supportingEvidenceBlock(): String =
    Regex("""Supporting evidence:\s*\n(.*?)\n\s*Return JSON:""", RegexOption.DOT_MATCHES_ALL)
        .find(this)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: error("Supporting evidence block not found")

private fun String.candidateClustersBlock(): String =
    Regex("""Candidate clusters:\s*\n(.*?)\n\s*Supporting evidence:""", RegexOption.DOT_MATCHES_ALL)
        .find(this)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: error("Candidate clusters block not found")

private val NAMESPACE = MemoryNamespace("memory-repair-planner-test")
private val OLDER = Instant.parse("2026-01-01T00:00:00Z")
private val NEWER = Instant.parse("2026-01-02T00:00:00Z")
private val SUBJECT_ID = MemoryEntity.Id("entity-gromozeka")

private fun snapshot(
    sources: List<MemorySource>,
    claims: List<MemoryClaim>,
    profiles: List<MemoryProfile>,
): MemoryNamespaceSnapshot =
    MemoryNamespaceSnapshot(
        sources = sources,
        entities = listOf(
            MemoryEntity(
                id = SUBJECT_ID,
                namespace = NAMESPACE,
                entityType = MemoryEntity.Type.PROJECT,
                canonicalName = "Gromozeka",
                normalizedName = "gromozeka",
                firstSeenAt = OLDER,
                lastSeenAt = NEWER,
                createdAt = OLDER,
                updatedAt = NEWER,
            )
        ),
        claims = claims,
        profiles = profiles,
    )

private fun source(id: String, text: String): MemorySource =
    MemorySource.ChatTurn(
        id = MemorySource.Id(id),
        namespace = NAMESPACE,
        conversationId = Conversation.Id("conversation"),
        threadId = Conversation.Thread.Id("thread"),
        sourceMessageId = Conversation.Message.Id("message-$id"),
        speakerRole = MemorySource.ActorRole.USER,
        contentText = text,
        searchText = text,
        contentHash = "$id-hash",
        observedAt = if (id.endsWith("older")) OLDER else NEWER,
        createdAt = if (id.endsWith("older")) OLDER else NEWER,
    )

private fun claim(id: String, source: MemorySource, value: String, updatedAt: Instant): MemoryClaim =
    MemoryClaim(
        id = MemoryClaim.Id(id),
        namespace = NAMESPACE,
        subjectEntityId = SUBJECT_ID,
        predicate = "uses_primary_model",
        objectValue = JsonPrimitive(value),
        normalizedText = source.contentText,
        scope = MemoryScope.Global("Project-level setting"),
        confidence = 0.99,
        importance = 9,
        evidenceRefs = listOf(
            MemoryEvidenceRef(
                sourceId = source.id,
                kind = MemoryEvidenceRef.Kind.DIRECT,
                cachedQuote = source.contentText,
            )
        ),
        firstSeenAt = updatedAt,
        lastSeenAt = updatedAt,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

private fun profile(id: String): MemoryProfile =
    MemoryProfile(
        id = MemoryProfile.Id(id),
        namespace = NAMESPACE,
        ownerEntityId = SUBJECT_ID,
        profileText = "$id profile",
        version = 1,
        createdAt = OLDER,
        updatedAt = NEWER,
    )
