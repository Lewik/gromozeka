package com.gromozeka.infrastructure.db.memory

import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

class MemorySearchScorerTest {

    @Test
    fun episodeScorePrefersTargetLessonOverSimilarButDifferentEpisode() {
        val target = episode(
            id = "target",
            ownerEntityId = MemoryEntity.Id("entity-no-memory-verifier"),
            situation = "ReadTimeRetrievalPlanner returned no-memory for ambiguous recall questions.",
            action = "Run a short model-based verification call before accepting the no-memory plan.",
            result = "The pipeline recovers recall intent without brittle substring checks.",
            lesson = "For ambiguous no-memory recall decisions, prefer model verification over hard-coded lexical matching.",
            tags = listOf("memory", "recall", "planner"),
        )
        val competitor = episode(
            id = "competitor",
            ownerEntityId = MemoryEntity.Id("entity-source-hydrator"),
            situation = "Evidence hydration had to attach source quotes for factual answers.",
            action = "Fetch a bounded number of source snippets when exact grounding is requested.",
            result = "The answer can cite raw evidence without treating every source as a claim.",
            lesson = "For source hydration, keep evidence bounded and separate from interpreted memory.",
            tags = listOf("memory", "evidence", "source"),
        )

        val targetScore = MemorySearchScorer.episodeScore(
            query = "What reusable lesson did we record about ambiguous no-memory recall decisions?",
            episode = target,
            ownerEntity = noMemoryVerifierEntity(),
        )
        val competitorScore = MemorySearchScorer.episodeScore(
            query = "What reusable lesson did we record about ambiguous no-memory recall decisions?",
            episode = competitor,
            ownerEntity = sourceHydratorEntity(),
        )

        assertTrue(targetScore > competitorScore * 2, "target=$targetScore competitor=$competitorScore")
    }

    @Test
    fun episodeScoreUsesCamelCaseOwnerEntityNames() {
        val target = episode(
            id = "target",
            ownerEntityId = MemoryEntity.Id("entity-no-memory-verifier"),
            situation = "A planner fallback needed a model check.",
            action = "Verify the no-memory decision.",
            result = "Recall intent was recovered.",
            lesson = "Use a short verification call.",
            tags = emptyList(),
        )
        val unrelated = episode(
            id = "unrelated",
            ownerEntityId = MemoryEntity.Id("entity-source-hydrator"),
            situation = "Source snippets were attached to an answer.",
            action = "Hydrate evidence.",
            result = "Grounding improved.",
            lesson = "Keep evidence bounded.",
            tags = emptyList(),
        )

        val targetScore = MemorySearchScorer.episodeScore("NoMemoryVerifier lesson", target, noMemoryVerifierEntity())
        val unrelatedScore = MemorySearchScorer.episodeScore("NoMemoryVerifier lesson", unrelated, sourceHydratorEntity())

        assertTrue(targetScore > unrelatedScore, "target=$targetScore unrelated=$unrelatedScore")
    }

    @Test
    fun typedEpisodeWithoutQueryOverlapGetsZeroRelevance() {
        val episode = episode(
            id = "unrelated",
            ownerEntityId = MemoryEntity.Id("entity-source-hydrator"),
            situation = "Source snippets were attached to an answer.",
            action = "Hydrate evidence.",
            result = "Grounding improved.",
            lesson = "Keep evidence bounded.",
            tags = emptyList(),
        )

        assertEquals(
            expected = 0.0,
            actual = MemorySearchScorer.episodeScore("Toyota parking preference", episode, sourceHydratorEntity()),
        )
    }

    private fun noMemoryVerifierEntity(): MemoryEntity =
        entity(
            id = "entity-no-memory-verifier",
            canonicalName = "NoMemoryVerifier",
            normalizedName = "nomemoryverifier",
            summary = "Verifier for no-memory read planner decisions.",
        )

    private fun sourceHydratorEntity(): MemoryEntity =
        entity(
            id = "entity-source-hydrator",
            canonicalName = "SourceHydrator",
            normalizedName = "sourcehydrator",
            summary = "Evidence hydration component for runtime memory.",
        )

    private fun entity(
        id: String,
        canonicalName: String,
        normalizedName: String,
        summary: String,
    ): MemoryEntity =
        MemoryEntity(
            id = MemoryEntity.Id(id),
            namespace = TEST_NAMESPACE,
            entityType = MemoryEntity.Type.SERVICE,
            canonicalName = canonicalName,
            normalizedName = normalizedName,
            summary = summary,
            firstSeenAt = NOW,
            lastSeenAt = NOW,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun episode(
        id: String,
        ownerEntityId: MemoryEntity.Id,
        situation: String,
        action: String,
        result: String,
        lesson: String,
        tags: List<String>,
    ): MemoryEpisode =
        MemoryEpisode(
            id = MemoryEpisode.Id(id),
            namespace = TEST_NAMESPACE,
            ownerEntityId = ownerEntityId,
            situation = situation,
            action = action,
            result = result,
            lesson = lesson,
            tags = tags,
            successScore = 0.8,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private companion object {
        val TEST_NAMESPACE = MemoryNamespace("memory-search-scorer-test")
        val NOW: Instant = Instant.parse("2026-01-02T03:04:05Z")
    }
}
