package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalSerializationApi::class)
internal object MemoryReadSelectorCandidateRenderer {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    fun render(
        hits: List<MemoryStore.SearchHit>,
        snapshot: MemoryNamespaceSnapshot?,
        query: String,
        safetyRefs: Set<MemoryItemRef> = emptySet(),
    ): String =
        if (hits.isEmpty()) {
            "none"
        } else {
            hits.mapIndexed { index, hit ->
                val candidate = hit.toCandidateView(snapshot, query).copy(
                    safetyCandidate = hit.toSelectorItemRef() in safetyRefs,
                )
                "[$index] ${json.encodeToString(candidate)}"
            }.joinToString("\n")
        }

    private fun MemoryStore.SearchHit.toCandidateView(snapshot: MemoryNamespaceSnapshot?, query: String): CandidateView =
        when (this) {
            is MemoryStore.SearchHit.SourceHit -> source.toSourceCandidateView(score, snapshot, query)
            is MemoryStore.SearchHit.EntityHit -> CandidateView(
                type = "entity",
                id = entity.id.value,
                score = score,
                lifecycleState = entity.status.name.lowercase(),
                entityType = entity.entityType.name,
                name = entity.canonicalName,
                text = entity.summary.orEmpty().limitForSelectorView(500),
            )

            is MemoryStore.SearchHit.ClaimHit -> claim.toClaimCandidateView(score, snapshot)
            is MemoryStore.SearchHit.NoteHit -> note.toNoteCandidateView(score, snapshot)
            is MemoryStore.SearchHit.ActionItemHit -> actionItem.toTaskCandidateView(score)
            is MemoryStore.SearchHit.ProfileHit -> CandidateView(
                type = "profile",
                id = profile.id.value,
                score = score,
                lifecycleState = "projection",
                ownerEntityId = profile.ownerEntityId.value,
                text = profile.profileText.limitForSelectorView(1_000),
            )

            is MemoryStore.SearchHit.EpisodeHit -> episode.toEpisodeCandidateView(score)
            is MemoryStore.SearchHit.RunHit -> CandidateView(
                type = "run",
                id = run.id.value,
                score = score,
                status = run.status.name,
                lifecycleState = run.status.name.lowercase(),
                text = run.summary.limitForSelectorView(700),
            )
        }

    private fun MemorySource.toSourceCandidateView(
        score: Double,
        snapshot: MemoryNamespaceSnapshot?,
        query: String,
    ): CandidateView {
        val supports = snapshot?.typedRefsSupportedBy(id).orEmpty()
        val overriddenBy = snapshot?.activeTypedReplacementsForSource(id).orEmpty()
        val supportsActive = supports.any { it.lifecycleState == "current" }
        val lifecycleState = when {
            supportsActive -> "evidence_for_active_memory"
            overriddenBy.isNotEmpty() -> "overridden_evidence"
            else -> "raw_evidence"
        }

        return CandidateView(
            type = "source",
            id = id.value,
            score = score,
            sourceType = sourceTypeForSelectorView(),
            actorRole = actorRoleForSelectorView(),
            retentionClass = retentionClass.name,
            lifecycleState = lifecycleState,
            text = sourceTextForSelectorView(query),
            supports = supports,
            overriddenBy = overriddenBy,
        )
    }

    private fun MemorySource.sourceTextForSelectorView(query: String): String =
        listOfNotNull(
            searchText
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.limitForSelectorView(MAX_SELECTOR_SOURCE_SEARCH_TEXT_CHARS)
                ?.let { "search_text:\n$it" },
            contentText
                .trim()
                .let { RuntimeMemorySourceExcerpt.queryFocused(text = it, query = query, maxChars = MAX_SELECTOR_SOURCE_EXCERPT_CHARS) }
                .let { "source_text:\n$it" },
        ).distinct().joinToString("\n\n")

    private fun MemoryClaim.toClaimCandidateView(
        score: Double,
        snapshot: MemoryNamespaceSnapshot?,
    ): CandidateView {
        val overriddenBy = snapshot?.activeClaimReplacementsFor(this).orEmpty()
        val replaces = supersedesClaimId?.let { snapshot?.claimRefById(it) ?: TypedMemoryRef("claim", it.value) }
            ?.let(::listOf)
            .orEmpty()
        val lifecycleState = when (status) {
            MemoryClaim.Status.ACTIVE -> "current"
            else -> "non_current"
        }

        return CandidateView(
            type = "claim",
            id = id.value,
            score = score,
            status = status.name,
            lifecycleState = lifecycleState,
            predicate = predicate,
            predicateFamily = predicateFamily,
            predicateSemanticKinds = predicatePolicy?.semanticKinds?.map { it.name },
            subjectEntityId = subjectEntityId.value,
            objectEntityId = objectEntityId?.value,
            text = normalizedText.limitForSelectorView(900),
            context = contextText?.limitForSelectorView(500),
            qualifiersJson = qualifiers.takeIf { it.isNotEmpty() },
            confidence = confidence,
            importance = importance,
            validFrom = validFrom?.toString(),
            validTo = validTo?.toString(),
            supersedes = replaces,
            overriddenBy = overriddenBy,
            evidence = evidenceRefs.toEvidenceViews(),
        )
    }

    private fun MemoryNote.toNoteCandidateView(
        score: Double,
        snapshot: MemoryNamespaceSnapshot?,
    ): CandidateView {
        val overriddenBy = snapshot?.activeNoteReplacementsFor(this).orEmpty()
        val replaces = supersedesNoteId?.let { snapshot?.noteRefById(it) ?: TypedMemoryRef("note", it.value) }
            ?.let(::listOf)
            .orEmpty()
        val lifecycleState = when (status) {
            MemoryNote.Status.ACTIVE -> "current"
            else -> "non_current"
        }

        return CandidateView(
            type = "note",
            id = id.value,
            score = score,
            status = status.name,
            lifecycleState = lifecycleState,
            noteType = noteType.name,
            title = title.limitForSelectorView(300),
            text = summary.limitForSelectorView(900),
            confidence = confidence,
            importance = importance,
            validFrom = validFrom?.toString(),
            validTo = validTo?.toString(),
            supersedes = replaces,
            overriddenBy = overriddenBy,
            evidence = evidenceRefs.toEvidenceViews(),
        )
    }

    private fun MemoryActionItem.toTaskCandidateView(score: Double): CandidateView =
        CandidateView(
            type = "action_item",
            id = id.value,
            score = score,
            status = status.name,
            lifecycleState = status.name.lowercase(),
            title = title.limitForSelectorView(300),
            text = description.orEmpty().limitForSelectorView(700),
            evidence = evidenceRefs.toEvidenceViews(),
        )

    private fun MemoryEpisode.toEpisodeCandidateView(score: Double): CandidateView =
        CandidateView(
            type = "episode",
            id = id.value,
            score = score,
            lifecycleState = "experience",
            text = "situation=${situation.limitForSelectorView(350)} action=${action.limitForSelectorView(350)} result=${result.limitForSelectorView(350)} lesson=${lesson.limitForSelectorView(700)}",
            evidence = evidenceRefs.toEvidenceViews(),
        )

    private fun MemoryNamespaceSnapshot.typedRefsSupportedBy(sourceId: MemorySource.Id): List<TypedMemoryRef> =
        buildList {
            claims
                .filter { claim -> claim.evidenceRefs.any { it.sourceId == sourceId } }
                .mapTo(this) { it.toTypedMemoryRef() }
            notes
                .filter { note -> note.evidenceRefs.any { it.sourceId == sourceId } }
                .mapTo(this) { it.toTypedMemoryRef() }
            actionItems
                .filter { actionItem -> actionItem.evidenceRefs.any { it.sourceId == sourceId } }
                .mapTo(this) { it.toTypedMemoryRef() }
            episodes
                .filter { episode -> episode.evidenceRefs.any { it.sourceId == sourceId } }
                .mapTo(this) { it.toTypedMemoryRef() }
        }.distinctBy { "${it.type}:${it.id}" }

    private fun MemoryNamespaceSnapshot.activeTypedReplacementsForSource(sourceId: MemorySource.Id): List<TypedMemoryRef> =
        typedRefsSupportedBy(sourceId)
            .flatMap { ref ->
                when (ref.type) {
                    "claim" -> claims
                        .firstOrNull { it.id.value == ref.id }
                        ?.let { activeClaimReplacementsFor(it) }
                        .orEmpty()

                    "note" -> notes
                        .firstOrNull { it.id.value == ref.id }
                        ?.let { activeNoteReplacementsFor(it) }
                        .orEmpty()

                    else -> emptyList()
                }
            }
            .distinctBy { "${it.type}:${it.id}" }

    private fun MemoryNamespaceSnapshot.activeClaimReplacementsFor(claim: MemoryClaim): List<TypedMemoryRef> =
        if (claim.status == MemoryClaim.Status.ACTIVE) {
            emptyList()
        } else {
            claims
                .filter { active ->
                    active.status == MemoryClaim.Status.ACTIVE &&
                        (active.supersedesClaimId == claim.id || claim.retractedByClaimId == active.id)
                }
                .map { it.toTypedMemoryRef() }
        }

    private fun MemoryNamespaceSnapshot.activeNoteReplacementsFor(note: MemoryNote): List<TypedMemoryRef> =
        if (note.status == MemoryNote.Status.ACTIVE) {
            emptyList()
        } else {
            notes
                .filter { active -> active.status == MemoryNote.Status.ACTIVE && active.supersedesNoteId == note.id }
                .map { it.toTypedMemoryRef() }
        }

    private fun MemoryNamespaceSnapshot.claimRefById(id: MemoryClaim.Id): TypedMemoryRef? =
        claims.firstOrNull { it.id == id }?.toTypedMemoryRef()

    private fun MemoryNamespaceSnapshot.noteRefById(id: MemoryNote.Id): TypedMemoryRef? =
        notes.firstOrNull { it.id == id }?.toTypedMemoryRef()

    private fun MemoryClaim.toTypedMemoryRef(): TypedMemoryRef =
        TypedMemoryRef(
            type = "claim",
            id = id.value,
            status = status.name,
            lifecycleState = if (status == MemoryClaim.Status.ACTIVE) "current" else "non_current",
            predicate = predicate,
            predicateSemanticKinds = predicatePolicy?.semanticKinds?.map { it.name },
        )

    private fun MemoryNote.toTypedMemoryRef(): TypedMemoryRef =
        TypedMemoryRef(
            type = "note",
            id = id.value,
            status = status.name,
            lifecycleState = if (status == MemoryNote.Status.ACTIVE) "current" else "non_current",
        )

    private fun MemoryActionItem.toTypedMemoryRef(): TypedMemoryRef =
        TypedMemoryRef(
            type = "action_item",
            id = id.value,
            status = status.name,
            lifecycleState = status.name.lowercase(),
        )

    private fun MemoryEpisode.toTypedMemoryRef(): TypedMemoryRef =
        TypedMemoryRef(
            type = "episode",
            id = id.value,
            lifecycleState = "experience",
        )

    private fun List<MemoryEvidenceRef>.toEvidenceViews(): List<EvidenceView> =
        take(6).map { ref ->
            EvidenceView(
                sourceId = ref.sourceId.value,
                kind = ref.kind.name,
                quote = ref.cachedQuote?.limitForSelectorView(300),
            )
        }

    private fun MemorySource.sourceTypeForSelectorView(): String =
        when (this) {
            is MemorySource.ChatTurn -> "chat_turn"
            is MemorySource.ToolOutput -> "tool_output"
            is MemorySource.ImportedNote -> "imported_note"
            is MemorySource.ExternalRecord -> "external_record"
        }

    private fun MemorySource.actorRoleForSelectorView(): String? =
        when (this) {
            is MemorySource.ChatTurn -> speakerRole.name
            is MemorySource.ImportedNote -> authorLabel
            is MemorySource.ExternalRecord -> authorLabel
            is MemorySource.ToolOutput -> null
        }

}

@Serializable
private data class CandidateView(
    val type: String,
    val id: String,
    val score: Double,
    val status: String? = null,
    @SerialName("lifecycle_state")
    val lifecycleState: String? = null,
    @SerialName("safety_candidate")
    val safetyCandidate: Boolean = false,
    @SerialName("source_type")
    val sourceType: String? = null,
    @SerialName("actor_role")
    val actorRole: String? = null,
    @SerialName("observed_at")
    val observedAt: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("retention_class")
    val retentionClass: String? = null,
    @SerialName("entity_type")
    val entityType: String? = null,
    val name: String? = null,
    val predicate: String? = null,
    @SerialName("predicate_family")
    val predicateFamily: String? = null,
    @SerialName("predicate_semantic_kinds")
    val predicateSemanticKinds: List<String>? = null,
    @SerialName("note_type")
    val noteType: String? = null,
    val title: String? = null,
    @SerialName("subject_entity_id")
    val subjectEntityId: String? = null,
    @SerialName("object_entity_id")
    val objectEntityId: String? = null,
    @SerialName("owner_entity_id")
    val ownerEntityId: String? = null,
    val text: String? = null,
    val context: String? = null,
    @SerialName("qualifiers_json")
    val qualifiersJson: JsonObject? = null,
    val confidence: Double? = null,
    val importance: Int? = null,
    @SerialName("valid_from")
    val validFrom: String? = null,
    @SerialName("valid_to")
    val validTo: String? = null,
    val evidence: List<EvidenceView> = emptyList(),
    val supports: List<TypedMemoryRef> = emptyList(),
    val supersedes: List<TypedMemoryRef> = emptyList(),
    @SerialName("overridden_by")
    val overriddenBy: List<TypedMemoryRef> = emptyList(),
)

@Serializable
private data class TypedMemoryRef(
    val type: String,
    val id: String,
    val status: String? = null,
    @SerialName("lifecycle_state")
    val lifecycleState: String? = null,
    val predicate: String? = null,
    @SerialName("predicate_semantic_kinds")
    val predicateSemanticKinds: List<String>? = null,
)

@Serializable
private data class EvidenceView(
    @SerialName("source_id")
    val sourceId: String,
    val kind: String,
    val quote: String? = null,
)

private fun MemoryStore.SearchHit.toSelectorItemRef(): MemoryItemRef =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> MemoryItemRef(MemoryItemRef.Type.SOURCE, source.id.value)
        is MemoryStore.SearchHit.EntityHit -> MemoryItemRef(MemoryItemRef.Type.ENTITY, entity.id.value)
        is MemoryStore.SearchHit.ClaimHit -> MemoryItemRef(MemoryItemRef.Type.CLAIM, claim.id.value)
        is MemoryStore.SearchHit.NoteHit -> MemoryItemRef(MemoryItemRef.Type.NOTE, note.id.value)
        is MemoryStore.SearchHit.ActionItemHit -> MemoryItemRef(MemoryItemRef.Type.ACTION_ITEM, actionItem.id.value)
        is MemoryStore.SearchHit.ProfileHit -> MemoryItemRef(MemoryItemRef.Type.PROFILE, profile.id.value)
        is MemoryStore.SearchHit.EpisodeHit -> MemoryItemRef(MemoryItemRef.Type.EPISODE, episode.id.value)
        is MemoryStore.SearchHit.RunHit -> MemoryItemRef(MemoryItemRef.Type.RUN, run.id.value)
    }

private fun String.limitForSelectorView(maxChars: Int): String {
    val normalized = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    if (normalized.length <= maxChars) return normalized
    return normalized.take(maxChars) + "...[truncated ${normalized.length - maxChars} chars]"
}

private const val MAX_SELECTOR_SOURCE_SEARCH_TEXT_CHARS = 600
private const val MAX_SELECTOR_SOURCE_EXCERPT_CHARS = 1_200
