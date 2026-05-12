package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryTask
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
internal object MemoryReadSelectorCandidateRenderer {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    fun render(
        hits: List<MemoryStore.SearchHit>,
        snapshot: MemoryNamespaceSnapshot?,
    ): String =
        if (hits.isEmpty()) {
            "none"
        } else {
            hits.mapIndexed { index, hit ->
                "${index + 1}. ${json.encodeToString(hit.toCandidateView(snapshot))}"
            }.joinToString("\n")
        }

    private fun MemoryStore.SearchHit.toCandidateView(snapshot: MemoryNamespaceSnapshot?): CandidateView =
        when (this) {
            is MemoryStore.SearchHit.SourceHit -> source.toSourceCandidateView(score, snapshot)
            is MemoryStore.SearchHit.EntityHit -> CandidateView(
                type = "entity",
                id = entity.id.value,
                score = score,
                lifecycleState = entity.status.name.lowercase(),
                entityType = entity.entityType.name,
                name = entity.canonicalName,
                text = entity.summary.orEmpty().limitForSelectorView(500),
                selectionHint = "Entity labels are anchors only; select them only as supporting context for selected typed memory.",
            )

            is MemoryStore.SearchHit.ClaimHit -> claim.toClaimCandidateView(score, snapshot)
            is MemoryStore.SearchHit.NoteHit -> note.toNoteCandidateView(score, snapshot)
            is MemoryStore.SearchHit.TaskHit -> task.toTaskCandidateView(score)
            is MemoryStore.SearchHit.ProfileHit -> CandidateView(
                type = "profile",
                id = profile.id.value,
                score = score,
                lifecycleState = "projection",
                ownerEntityId = profile.ownerEntityId.value,
                text = profile.profileText.limitForSelectorView(1_000),
                selectionHint = "Profile is a compact projection/cache. Use as context, but do not treat it as stronger than relevant ACTIVE claims or notes.",
            )

            is MemoryStore.SearchHit.EpisodeHit -> episode.toEpisodeCandidateView(score)
            is MemoryStore.SearchHit.RunHit -> CandidateView(
                type = "run",
                id = run.id.value,
                score = score,
                status = run.status.name,
                lifecycleState = run.status.name.lowercase(),
                text = run.summary.limitForSelectorView(700),
                selectionHint = "Run records explain memory operations; select only for debugging or maintenance questions.",
            )
        }

    private fun MemorySource.toSourceCandidateView(
        score: Double,
        snapshot: MemoryNamespaceSnapshot?,
    ): CandidateView {
        val supports = snapshot?.typedRefsSupportedBy(id).orEmpty()
        val overriddenBy = snapshot?.activeTypedReplacementsForSource(id).orEmpty()
        val supportsActive = supports.any { it.lifecycleState == "current" }
        val lifecycleState = when {
            overriddenBy.isNotEmpty() -> "overridden_evidence"
            supportsActive -> "evidence_for_active_memory"
            else -> "raw_evidence"
        }

        return CandidateView(
            type = "source",
            id = id.value,
            score = score,
            sourceType = sourceTypeForSelectorView(),
            actorRole = actorRoleForSelectorView(),
            observedAt = observedAt.toString(),
            createdAt = createdAt.toString(),
            retentionClass = retentionClass.name,
            usagePolicy = usagePolicyForSelectorView(),
            lifecycleState = lifecycleState,
            text = sourceTextForSelectorView(),
            supports = supports,
            overriddenBy = overriddenBy,
            selectionHint = when {
                overriddenBy.isNotEmpty() ->
                    "Historical evidence only. Do not select as current truth; prefer the ACTIVE overridden_by item for factual answers."
                supportsActive ->
                    "Evidence only. Select only when exact wording/provenance is required; typed ACTIVE memory carries the current truth."
                else ->
                    "Raw source. Select only when exact wording/provenance is required or no typed memory answers the target."
            },
        )
    }

    private fun MemorySource.sourceTextForSelectorView(): String =
        listOfNotNull(
            searchText?.trim()?.takeIf { it.isNotBlank() }?.let { "search_text:\n$it" },
            "source_text:\n${contentText.trim()}",
        ).joinToString("\n\n")

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
            subjectEntityId = subjectEntityId.value,
            objectEntityId = objectEntityId?.value,
            text = normalizedText.limitForSelectorView(900),
            context = contextText?.limitForSelectorView(500),
            confidence = confidence,
            importance = importance,
            validFrom = validFrom?.toString(),
            validTo = validTo?.toString(),
            supersedes = replaces,
            overriddenBy = overriddenBy,
            evidence = evidenceRefs.toEvidenceViews(),
            selectionHint = when {
                status == MemoryClaim.Status.ACTIVE ->
                    "Current typed fact. Prefer this for relevant factual answers over raw sources."
                overriddenBy.isNotEmpty() ->
                    "Non-current typed fact. Do not use as current truth; prefer the ACTIVE overridden_by item."
                else ->
                    "Non-current typed fact. Use only when the target asks about historical, expired, retracted, or candidate memory."
            },
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
            selectionHint = when {
                status == MemoryNote.Status.ACTIVE ->
                    "Current typed context. Prefer for rationale, decisions, plans, and contextual meaning."
                overriddenBy.isNotEmpty() ->
                    "Non-current note. Do not use as current context; prefer the ACTIVE overridden_by item."
                else ->
                    "Non-current note. Use only when the target asks about historical or stale context."
            },
        )
    }

    private fun MemoryTask.toTaskCandidateView(score: Double): CandidateView =
        CandidateView(
            type = "task",
            id = id.value,
            score = score,
            status = status.name,
            lifecycleState = status.name.lowercase(),
            title = title.limitForSelectorView(300),
            text = description.orEmpty().limitForSelectorView(700),
            evidence = evidenceRefs.toEvidenceViews(),
            selectionHint = "Task memory is for open commitments/workflow state; select only when the target asks about work to do or task status.",
        )

    private fun MemoryEpisode.toEpisodeCandidateView(score: Double): CandidateView =
        CandidateView(
            type = "episode",
            id = id.value,
            score = score,
            lifecycleState = "experience",
            text = "situation=${situation.limitForSelectorView(350)} action=${action.limitForSelectorView(350)} result=${result.limitForSelectorView(350)} lesson=${lesson.limitForSelectorView(700)}",
            evidence = evidenceRefs.toEvidenceViews(),
            selectionHint = "Episode memory is reusable experience; prefer for lessons, patterns, and what-worked questions.",
        )

    private fun MemoryNamespaceSnapshot.typedRefsSupportedBy(sourceId: MemorySource.Id): List<TypedMemoryRef> =
        buildList {
            claims
                .filter { claim -> claim.evidenceRefs.any { it.sourceId == sourceId } }
                .mapTo(this) { it.toTypedMemoryRef(this@typedRefsSupportedBy) }
            notes
                .filter { note -> note.evidenceRefs.any { it.sourceId == sourceId } }
                .mapTo(this) { it.toTypedMemoryRef(this@typedRefsSupportedBy) }
            tasks
                .filter { task -> task.evidenceRefs.any { it.sourceId == sourceId } }
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
                .map { it.toTypedMemoryRef(this) }
        }

    private fun MemoryNamespaceSnapshot.activeNoteReplacementsFor(note: MemoryNote): List<TypedMemoryRef> =
        if (note.status == MemoryNote.Status.ACTIVE) {
            emptyList()
        } else {
            notes
                .filter { active -> active.status == MemoryNote.Status.ACTIVE && active.supersedesNoteId == note.id }
                .map { it.toTypedMemoryRef(this) }
        }

    private fun MemoryNamespaceSnapshot.claimRefById(id: MemoryClaim.Id): TypedMemoryRef? =
        claims.firstOrNull { it.id == id }?.toTypedMemoryRef(this)

    private fun MemoryNamespaceSnapshot.noteRefById(id: MemoryNote.Id): TypedMemoryRef? =
        notes.firstOrNull { it.id == id }?.toTypedMemoryRef(this)

    private fun MemoryClaim.toTypedMemoryRef(snapshot: MemoryNamespaceSnapshot): TypedMemoryRef =
        TypedMemoryRef(
            type = "claim",
            id = id.value,
            status = status.name,
            lifecycleState = if (status == MemoryClaim.Status.ACTIVE) "current" else "non_current",
            predicate = predicate,
            text = normalizedText.limitForSelectorView(300),
            evidenceSourceIds = evidenceRefs.map { it.sourceId.value }.distinct(),
            overriddenByIds = snapshot.activeClaimReplacementsFor(this).map { it.id },
        )

    private fun MemoryNote.toTypedMemoryRef(snapshot: MemoryNamespaceSnapshot): TypedMemoryRef =
        TypedMemoryRef(
            type = "note",
            id = id.value,
            status = status.name,
            lifecycleState = if (status == MemoryNote.Status.ACTIVE) "current" else "non_current",
            title = title.limitForSelectorView(200),
            text = summary.limitForSelectorView(300),
            evidenceSourceIds = evidenceRefs.map { it.sourceId.value }.distinct(),
            overriddenByIds = snapshot.activeNoteReplacementsFor(this).map { it.id },
        )

    private fun MemoryTask.toTypedMemoryRef(): TypedMemoryRef =
        TypedMemoryRef(
            type = "task",
            id = id.value,
            status = status.name,
            lifecycleState = status.name.lowercase(),
            title = title.limitForSelectorView(200),
            text = description?.limitForSelectorView(300),
            evidenceSourceIds = evidenceRefs.map { it.sourceId.value }.distinct(),
        )

    private fun MemoryEpisode.toTypedMemoryRef(): TypedMemoryRef =
        TypedMemoryRef(
            type = "episode",
            id = id.value,
            lifecycleState = "experience",
            text = lesson.limitForSelectorView(300),
            evidenceSourceIds = evidenceRefs.map { it.sourceId.value }.distinct(),
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

    private fun MemorySource.usagePolicyForSelectorView(): String =
        "recall=${usagePolicy.allowRecall},evidence=${usagePolicy.allowEvidenceHydration},extract=${usagePolicy.allowStructuredExtraction},reason=${usagePolicy.reason.limitForSelectorView(120)}"
}

@Serializable
private data class CandidateView(
    val type: String,
    val id: String,
    val score: Double,
    val status: String? = null,
    @SerialName("lifecycle_state")
    val lifecycleState: String? = null,
    @SerialName("selection_hint")
    val selectionHint: String,
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
    @SerialName("usage_policy")
    val usagePolicy: String? = null,
    @SerialName("entity_type")
    val entityType: String? = null,
    val name: String? = null,
    val predicate: String? = null,
    @SerialName("predicate_family")
    val predicateFamily: String? = null,
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
    val title: String? = null,
    val text: String? = null,
    @SerialName("evidence_source_ids")
    val evidenceSourceIds: List<String> = emptyList(),
    @SerialName("overridden_by_ids")
    val overriddenByIds: List<String> = emptyList(),
)

@Serializable
private data class EvidenceView(
    @SerialName("source_id")
    val sourceId: String,
    val kind: String,
    val quote: String? = null,
)

private fun String.limitForSelectorView(maxChars: Int): String {
    val normalized = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    if (normalized.length <= maxChars) return normalized
    return normalized.take(maxChars) + "...[truncated ${normalized.length - maxChars} chars]"
}
