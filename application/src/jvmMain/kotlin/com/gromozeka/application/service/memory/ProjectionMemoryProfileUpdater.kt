package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryPredicateCatalogDefaults
import com.gromozeka.domain.model.memory.MemoryProfile
import com.gromozeka.domain.model.memory.MemoryProfileUpdater
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.model.memory.resolvePredicateDefinition
import klog.KLoggers
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

class ProjectionMemoryProfileUpdater(
    private val store: MemoryStore,
) : MemoryProfileUpdater {
    private val log = KLoggers.logger(this)

    override suspend fun update(
        request: DirectStructuredMemoryWriteRequest,
        appliedBatch: MemoryUpdateBatch,
        completedAt: Instant,
    ): MemoryUpdateBatch {
        return updateNamespaceProfiles(
            namespace = request.namespace,
            logSubject = "source=${request.source.id.value}",
            appliedBatch = appliedBatch,
            completedAt = completedAt,
        )
    }

    suspend fun updateNamespaceProfiles(
        namespace: MemoryNamespace,
        logSubject: String,
        appliedBatch: MemoryUpdateBatch,
        completedAt: Instant,
        force: Boolean = false,
    ): MemoryUpdateBatch {
        if (!appliedBatch.hasProfileRelevantChanges()) {
            if (!force) {
                log.info {
                    "Memory profile updater skipped: namespace=${namespace.value} $logSubject reason=no_profile_relevant_changes"
                }
                return MemoryUpdateBatch()
            }
        }

        if (appliedBatch.hasProfileRelevantChanges() || force) {
            log.info {
                "Memory profile updater run: namespace=${namespace.value} $logSubject " +
                    "force=$force batchClaims=${appliedBatch.claims.size} batchNotes=${appliedBatch.notes.size} batchTasks=${appliedBatch.actionItems.size}"
            }
        }

        val snapshot = store.loadNamespaceSnapshot(namespace)
        val profiles = rebuildProfiles(
            namespace = namespace,
            snapshot = snapshot,
            appliedBatch = appliedBatch,
            completedAt = completedAt,
        )

        log.info {
            "Memory profile updater result: namespace=${namespace.value} $logSubject " +
                "profiles=${profiles.size} owners=${profiles.joinToString("|") { "${it.ownerEntityId.value}:${it.profileText.oneLineForProfileLog(180)}" }.ifBlank { "none" }}"
        }

        return MemoryUpdateBatch(profiles = profiles)
    }

    private fun rebuildProfiles(
        namespace: MemoryNamespace,
        snapshot: MemoryNamespaceSnapshot,
        appliedBatch: MemoryUpdateBatch,
        completedAt: Instant,
    ): List<MemoryProfile> {
        val entitiesById = snapshot.entities.associateBy { it.id }
        val existingProfiles = snapshot.profiles.associateBy { it.ownerEntityId }
        val catalog = snapshot.predicateDefinitions
            .takeIf { it.isNotEmpty() }
            ?: MemoryPredicateCatalogDefaults.forNamespace(namespace)

        val profileClaims = snapshot.claims
            .filter { claim ->
                claim.status == MemoryClaim.Status.ACTIVE &&
                    claim.archivedAt == null &&
                    claim.isCurrentAt(completedAt) &&
                    claim.confidence >= MIN_PROFILE_CLAIM_CONFIDENCE &&
                    catalog.resolvePredicateDefinition(claim.predicate, claim.predicateFamily)?.profileSync == true
            }

        val profileNotes = snapshot.notes
            .filter { note ->
                note.status == MemoryNote.Status.ACTIVE &&
                    note.archivedAt == null &&
                    note.isCurrentAt(completedAt) &&
                    note.importance >= 8 &&
                    note.confidence >= 0.7 &&
                    note.maturity != MemoryNote.Maturity.FRESH
            }

        val profileTasks = snapshot.actionItems
            .filter { actionItem ->
                actionItem.archivedAt == null &&
                    actionItem.status in liveTaskStatuses &&
                    actionItem.importanceForProfile() >= 7
            }

        val ownerIds = buildSet {
            addAll(existingProfiles.keys)
            addAll(profileClaims.map { it.subjectEntityId })
            addAll(profileNotes.mapNotNull { it.profileOwnerEntityId() })
            addAll(profileTasks.mapNotNull { it.ownerEntityId })
        }

        return ownerIds.mapNotNull { ownerId ->
            val owner = entitiesById[ownerId]
            val claims = profileClaims
                .filter { it.subjectEntityId == ownerId }
                .sortedWith(compareByDescending<MemoryClaim> { it.importance }.thenByDescending { it.updatedAt })
                .take(MAX_PROFILE_CLAIMS)
            val notes = profileNotes
                .filter { it.profileOwnerEntityId() == ownerId }
                .sortedWith(compareByDescending<MemoryNote> { it.importance }.thenByDescending { it.updatedAt })
                .take(MAX_PROFILE_NOTES)
            val actionItems = profileTasks
                .filter { it.ownerEntityId == ownerId }
                .sortedWith(compareByDescending<MemoryActionItem> { it.priority.profileWeight() }.thenByDescending { it.updatedAt })
                .take(MAX_PROFILE_ACTION_ITEMS)

            buildProfile(
                namespace = namespace,
                ownerId = ownerId,
                owner = owner,
                existing = existingProfiles[ownerId],
                claims = claims,
                notes = notes,
                actionItems = actionItems,
                appliedBatch = appliedBatch,
                completedAt = completedAt,
            )
        }
    }

    private fun buildProfile(
        namespace: MemoryNamespace,
        ownerId: MemoryEntity.Id,
        owner: MemoryEntity?,
        existing: MemoryProfile?,
        claims: List<MemoryClaim>,
        notes: List<MemoryNote>,
        actionItems: List<MemoryActionItem>,
        appliedBatch: MemoryUpdateBatch,
        completedAt: Instant,
    ): MemoryProfile? {
        if (claims.isEmpty() && notes.isEmpty() && actionItems.isEmpty() && existing == null) {
            return null
        }

        val ownerName = owner?.canonicalName ?: ownerId.value
        val ownerType = owner?.entityType?.name ?: "UNKNOWN"
        val profileText = renderProfileText(ownerName, ownerType, claims, notes, actionItems)
        val profileJson = buildProfileJson(ownerId, owner, claims, notes, actionItems)
        if (existing?.profileText == profileText && existing.profileJson == profileJson) {
            return null
        }

        return MemoryProfile(
            id = existing?.id ?: MemoryProfile.Id("${namespace.value}:profile:${ownerId.value}"),
            namespace = namespace,
            ownerEntityId = ownerId,
            profileJson = profileJson,
            profileText = profileText,
            version = (existing?.version ?: 0L) + 1L,
            updatedFromRunId = appliedBatch.runs.lastOrNull()?.id ?: existing?.updatedFromRunId,
            lastCompactedAt = completedAt,
            createdAt = existing?.createdAt ?: completedAt,
            updatedAt = completedAt,
        )
    }

    private fun renderProfileText(
        ownerName: String,
        ownerType: String,
        claims: List<MemoryClaim>,
        notes: List<MemoryNote>,
        actionItems: List<MemoryActionItem>,
    ): String {
        val sections = buildList {
            add("Profile for $ownerName ($ownerType).")
            if (claims.isNotEmpty()) {
                add("Stable facts:")
                addAll(claims.map { "- ${it.normalizedText.ensureSentence()}" })
            }
            if (notes.isNotEmpty()) {
                add("Long-lived context:")
                addAll(notes.map { "- ${it.title.ensureSentence()} ${it.summary.ensureSentence()}" })
            }
            if (actionItems.isNotEmpty()) {
                add("Open commitments:")
                addAll(actionItems.map { "- ${it.status.name}: ${it.title.ensureSentence()}" })
            }
            if (claims.isEmpty() && notes.isEmpty() && actionItems.isEmpty()) {
                add("No active profile-synced memory.")
            }
        }

        return sections.joinToString("\n").take(MAX_PROFILE_TEXT_CHARS)
    }

    private fun buildProfileJson(
        ownerId: MemoryEntity.Id,
        owner: MemoryEntity?,
        claims: List<MemoryClaim>,
        notes: List<MemoryNote>,
        actionItems: List<MemoryActionItem>,
    ): JsonObject = buildJsonObject {
        put("owner_entity_id", JsonPrimitive(ownerId.value))
        put("owner_name", JsonPrimitive(owner?.canonicalName ?: ownerId.value))
        put("owner_type", JsonPrimitive(owner?.entityType?.name ?: "UNKNOWN"))
        put("facts", claims.mapToJsonArray { claim ->
            buildJsonObject {
                put("id", JsonPrimitive(claim.id.value))
                put("predicate", JsonPrimitive(claim.predicate))
                put("text", JsonPrimitive(claim.normalizedText))
                put("importance", JsonPrimitive(claim.importance))
                put("confidence", JsonPrimitive(claim.confidence))
            }
        })
        put("notes", notes.mapToJsonArray { note ->
            buildJsonObject {
                put("id", JsonPrimitive(note.id.value))
                put("type", JsonPrimitive(note.noteType.name))
                put("title", JsonPrimitive(note.title))
                put("summary", JsonPrimitive(note.summary))
                put("importance", JsonPrimitive(note.importance))
            }
        })
        put("actionItems", actionItems.mapToJsonArray { actionItem ->
            buildJsonObject {
                put("id", JsonPrimitive(actionItem.id.value))
                put("status", JsonPrimitive(actionItem.status.name))
                put("priority", JsonPrimitive(actionItem.priority.name))
                put("title", JsonPrimitive(actionItem.title))
            }
        })
    }

    private fun MemoryNote.profileOwnerEntityId(): MemoryEntity.Id? =
        anchorEntityId ?: entityRefs.firstOrNull {
            it.role == MemoryNote.EntityRef.Role.OWNER ||
                it.role == MemoryNote.EntityRef.Role.SUBJECT ||
                it.role == MemoryNote.EntityRef.Role.PRIMARY
        }?.entityId

    private fun MemoryActionItem.importanceForProfile(): Int =
        when (priority) {
            MemoryActionItem.Priority.HIGH -> 9
            MemoryActionItem.Priority.NORMAL -> 7
            MemoryActionItem.Priority.LOW -> 5
        }

    private fun MemoryActionItem.Priority.profileWeight(): Int =
        when (this) {
            MemoryActionItem.Priority.HIGH -> 3
            MemoryActionItem.Priority.NORMAL -> 2
            MemoryActionItem.Priority.LOW -> 1
        }

    private fun <T> List<T>.mapToJsonArray(transform: (T) -> JsonObject): JsonArray =
        buildJsonArray {
            this@mapToJsonArray.forEach { add(transform(it)) }
        }

    private fun MemoryUpdateBatch.hasProfileRelevantChanges(): Boolean =
        predicateDefinitions.isNotEmpty() ||
            entities.isNotEmpty() ||
            claims.isNotEmpty() ||
            notes.isNotEmpty() ||
            actionItems.isNotEmpty()

    private companion object {
        const val MAX_PROFILE_CLAIMS = 24
        const val MAX_PROFILE_NOTES = 6
        const val MAX_PROFILE_ACTION_ITEMS = 5
        const val MAX_PROFILE_TEXT_CHARS = 4_000
        const val MIN_PROFILE_CLAIM_CONFIDENCE = 0.7

        val liveTaskStatuses = setOf(
            MemoryActionItem.Status.OPEN,
            MemoryActionItem.Status.IN_PROGRESS,
            MemoryActionItem.Status.BLOCKED,
        )
    }
}

private fun MemoryClaim.isCurrentAt(instant: Instant): Boolean {
    val end = validTo ?: return true
    return end > instant
}

private fun MemoryNote.isCurrentAt(instant: Instant): Boolean {
    val end = validTo ?: return true
    return end > instant
}

private fun String.ensureSentence(): String {
    val trimmed = trim()
    if (trimmed.isBlank()) {
        return trimmed
    }
    return if (trimmed.last() in ".!?") trimmed else "$trimmed."
}

private fun String.oneLineForProfileLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
