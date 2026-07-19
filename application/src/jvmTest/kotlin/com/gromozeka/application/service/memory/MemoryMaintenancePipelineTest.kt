package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryClaimReconciliationOp
import com.gromozeka.domain.model.memory.MemoryClaimReconciler
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp
import com.gromozeka.domain.model.memory.MemoryEntityMaintenanceCandidateGroup
import com.gromozeka.domain.model.memory.MemoryEntityMaintenancePlan
import com.gromozeka.domain.model.memory.MemoryEntityMaintenancePlanner
import com.gromozeka.domain.model.memory.MemoryEpisodeCandidate
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryForgetPlan
import com.gromozeka.domain.model.memory.MemoryForgetPlanner
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryMaintenanceRequest
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryNoteConsolidator
import com.gromozeka.domain.model.memory.MemoryNoteCandidate
import com.gromozeka.domain.model.memory.MemoryPredicateDefinition
import com.gromozeka.domain.model.memory.MemoryProfile
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadPlanner
import com.gromozeka.domain.model.memory.MemoryReadRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionResult
import com.gromozeka.domain.model.memory.MemoryReadSelector
import com.gromozeka.domain.model.memory.MemoryReconciliationAction
import com.gromozeka.domain.model.memory.MemoryRepairCandidateCluster
import com.gromozeka.domain.model.memory.MemoryRepairPlan
import com.gromozeka.domain.model.memory.MemoryRepairPlanner
import com.gromozeka.domain.model.memory.MemoryRetrievalBudget
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemorySourceUsagePolicy
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.model.memory.MemoryActionItemUpdateOp
import com.gromozeka.domain.model.memory.MemoryThreadContext
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import com.gromozeka.domain.model.memory.NoteConsolidationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive

class MemoryMaintenancePipelineTest {

    @Test
    fun materializerAppliesAddAliasToExistingEntity() {
        val user = entity(
            id = MemoryEntity.Id("entity-current-user"),
            entityType = MemoryEntity.Type.USER,
            canonicalName = "User",
            normalizedName = "user",
        )
        val source = source("alias-source", "Call me Lev.")

        val batch = DefaultDirectStructuredMemoryWriteMaterializer(
            idFactory = SequentialMemoryIdFactory("alias-patch"),
        ).materialize(
            DirectStructuredMemoryWriteMaterialization(
                request = DirectStructuredMemoryWriteRequest(TEST_NAMESPACE, source),
                routeDecision = MemoryRouteDecision(
                    decision = MemoryRouteDecision.Decision.DIRECT_STRUCTURED_WRITE,
                    memoryTypes = setOf(MemorySemanticType.CLAIM),
                    reason = "Alias patch test.",
                ),
                retrievalPlan = MemoryWriteRetrievalPlan(),
                retrievedHits = listOf(MemoryStore.SearchHit.EntityHit(user, score = 1.0)),
                entityOps = listOf(
                    MemoryEntityCanonicalizationOp(
                        mention = "Lev",
                        action = MemoryEntityCanonicalizationOp.Action.ADD_ALIAS,
                        entityId = user.id,
                        aliasText = "Lev",
                        confidence = 0.95,
                        reason = "The user provided a preferred name alias.",
                    )
                ),
                noteCandidates = emptyList(),
                rawNoteOps = emptyList(),
                noteOps = emptyList(),
                claimCandidates = emptyList(),
                rawClaimOps = emptyList(),
                claimOps = emptyList(),
                rawActionItemOps = emptyList(),
                actionItemOps = emptyList(),
                predicateCatalog = emptyList(),
                startedAt = EARLIER,
                completedAt = NOW,
            )
        )

        val updatedUser = batch.entities.single()
        assertEquals(user.id, updatedUser.id)
        assertTrue(updatedUser.aliases.any { it.text == "Lev" && it.sourceId == source.id })
    }

    @Test
    fun noopQuestionSourcesBecomeAuditOnlyAndDoNotRecall() = runBlocking {
        val questionSource = source(
            "booknest-question-source",
            "I remember BookNest exported CSV. Is that still current?",
        )
        val store = InMemoryMemoryStore()

        val result = DirectStructuredMemoryWritePipeline(
            store = store,
            router = FixedMemoryWriteRouter(
                MemoryRouteDecision(
                    decision = MemoryRouteDecision.Decision.NOOP,
                    sourcePolicy = MemorySourceUsagePolicy.STANDARD,
                    sourceSearchText = "BookNest exported CSV still current question",
                    reason = "Question only.",
                )
            ),
            retrievalPlanner = FixedMemoryWriteRetrievalPlanner(),
            entityCanonicalizer = FixedMemoryEntityCanonicalizer(),
            noteConstructor = FixedMemoryNoteConstructor(),
            noteReconciler = InsertOnlyMemoryNoteReconciler,
            claimExtractor = FixedMemoryClaimExtractor(),
            claimReconciler = InsertOnlyMemoryClaimReconciler,
            actionItemUpdater = FixedMemoryActionItemUpdater(),
            materializer = DefaultDirectStructuredMemoryWriteMaterializer(
                idFactory = SequentialMemoryIdFactory("noop-question-policy"),
            ),
            clock = FixedMemoryClock(NOW),
        ).write(
            DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = questionSource,
            )
        )

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)
        val storedSource = snapshot.sourceById(questionSource.id.value)

        assertEquals(MemoryRouteDecision.Decision.NOOP, result.routeDecision.decision)
        assertTrue(!result.routeDecision.sourcePolicy.allowStructuredExtraction)
        assertTrue(!result.routeDecision.sourcePolicy.allowRecall)
        assertTrue(!result.routeDecision.sourcePolicy.allowEvidenceHydration)
        assertEquals("BookNest exported CSV still current question", storedSource.searchText)
        assertTrue(!storedSource.usagePolicy.allowStructuredExtraction)
        assertTrue(!storedSource.usagePolicy.allowRecall)
        assertTrue(!storedSource.usagePolicy.allowEvidenceHydration)

        val readResult = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    requireEvidenceFallback = true,
                    retrievalBudget = MemoryRetrievalBudget(sources = 3),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Question-only source should not be normal recall evidence.",
                            query = "BookNest exported CSV still current",
                            topK = 3,
                        )
                    ),
                )
            ),
        ).read(memoryReadRequest("booknest-question-read", "What did I ask about BookNest CSV?"))

        val prompt = assertNotNull(readResult.runtimePrompt)

        assertTrue(readResult.retrievedHits.none { it.toTestItemRef().id == questionSource.id.value })
        assertTrue(prompt.contains("No relevant persisted memory was retrieved"))
        assertTrue(!prompt.contains("BookNest exported CSV"))
        assertTrue(readResult.trace.searchSteps.any { step ->
            step.stage == "retrieval:SOURCE" && step.rawCount == 1 && step.selectedCount == 0
        })
    }

    @Test
    fun assistantChatTurnsBecomeAuditOnlySourceOnlyAndDoNotRecall() = runBlocking {
        val assistantSource = source(
            id = "assistant-source",
            text = "Запомнил: для HarborLens основной формат аналитического экспорта — ORC.",
            speakerRole = MemorySource.ActorRole.ASSISTANT,
        )
        val store = InMemoryMemoryStore()

        val result = DirectStructuredMemoryWritePipeline(
            store = store,
            router = FixedMemoryWriteRouter(
                MemoryRouteDecision(
                    decision = MemoryRouteDecision.Decision.DIRECT_STRUCTURED_WRITE,
                    memoryTypes = setOf(MemorySemanticType.CLAIM),
                    reason = "Should not be called for assistant turns.",
                )
            ),
            retrievalPlanner = FixedMemoryWriteRetrievalPlanner(),
            entityCanonicalizer = FixedMemoryEntityCanonicalizer(),
            noteConstructor = FixedMemoryNoteConstructor(),
            noteReconciler = InsertOnlyMemoryNoteReconciler,
            claimExtractor = FixedMemoryClaimExtractor(),
            claimReconciler = InsertOnlyMemoryClaimReconciler,
            actionItemUpdater = FixedMemoryActionItemUpdater(),
            materializer = DefaultDirectStructuredMemoryWriteMaterializer(
                idFactory = SequentialMemoryIdFactory("assistant-source-only"),
            ),
            clock = FixedMemoryClock(NOW),
        ).write(
            DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = assistantSource,
            )
        )

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)
        val storedSource = snapshot.sourceById(assistantSource.id.value)

        assertEquals(MemoryRouteDecision.Decision.NOOP, result.routeDecision.decision)
        assertTrue(!result.routeDecision.sourcePolicy.allowStructuredExtraction)
        assertTrue(!result.routeDecision.sourcePolicy.allowRecall)
        assertTrue(!result.routeDecision.sourcePolicy.allowEvidenceHydration)
        assertTrue(result.memoryBatch.claims.isEmpty())
        assertTrue(result.memoryBatch.notes.isEmpty())
        assertTrue(result.memoryBatch.actionItems.isEmpty())
        assertTrue(!storedSource.usagePolicy.allowStructuredExtraction)
        assertTrue(!storedSource.usagePolicy.allowRecall)
        assertTrue(!storedSource.usagePolicy.allowEvidenceHydration)
        assertTrue(storedSource.usagePolicy.reason.contains("Assistant chat turn"))

        val readResult = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    requireEvidenceFallback = true,
                    retrievalBudget = MemoryRetrievalBudget(sources = 3),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Assistant acknowledgements must not become recall evidence.",
                            query = "HarborLens ORC export format",
                            topK = 3,
                        )
                    ),
                )
            ),
        ).read(memoryReadRequest("assistant-source-read", "What format did HarborLens use?"))

        val prompt = assertNotNull(readResult.runtimePrompt)

        assertTrue(readResult.retrievedHits.none { it.toTestItemRef().id == assistantSource.id.value })
        assertTrue(prompt.contains("No relevant persisted memory was retrieved"))
        assertTrue(!prompt.contains("HarborLens"))
        assertTrue(readResult.trace.searchSteps.any { step ->
            step.stage == "retrieval:SOURCE" && step.rawCount == 1 && step.selectedCount == 0
        })
    }

    @Test
    fun explicitForgetArchivesSelectedMemoryAndKeepsCurrentForgetSource() = runBlocking {
        val oldSource = source("source-to-forget", "User prefers Toyota RunX for daily driving.")
        val forgetSource = source("forget-command", "Forget that I prefer Toyota.")
        val claim = claim(
            id = "claim-to-forget",
            sourceId = oldSource.id.value,
            normalizedText = "The user prefers Toyota RunX for daily driving.",
        )
        val backingStore = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(oldSource, forgetSource),
                entities = listOf(entity()),
                claims = listOf(claim),
            )
        )
        val store = SearchInterceptingMemoryStore(backingStore) { backingStore.search(it) }
        val embeddingIndexer = FixedSearchEmbeddingIndexer()
        val planner = FixedForgetPlanner(
            MemoryForgetPlan(
                forgetActions = listOf(
                    MemoryForgetPlan.Action(
                        action = MemoryForgetPlan.Action.Type.SOFT_DELETE_SOURCE,
                        targetType = MemoryItemRef.Type.SOURCE,
                        targetIds = listOf(oldSource.id.value),
                        reason = "Remove source evidence requested by the user.",
                    ),
                    MemoryForgetPlan.Action(
                        action = MemoryForgetPlan.Action.Type.SOFT_DELETE_SOURCE,
                        targetType = MemoryItemRef.Type.SOURCE,
                        targetIds = listOf(forgetSource.id.value),
                        reason = "Planner must not be able to delete the current forget command.",
                    ),
                    MemoryForgetPlan.Action(
                        action = MemoryForgetPlan.Action.Type.ARCHIVE_ITEM,
                        targetType = MemoryItemRef.Type.CLAIM,
                        targetIds = listOf(claim.id.value),
                        reason = "Remove interpreted memory from normal recall.",
                    ),
                ),
                summary = "Forgot Toyota preference.",
            )
        )

        val result = ExplicitMemoryForgetPipeline(
            store = store,
            planner = planner,
            idFactory = SequentialMemoryIdFactory("forget"),
            embeddingIndexer = embeddingIndexer,
            clock = FixedMemoryClock(NOW),
        ).run(
            request = DirectStructuredMemoryWriteRequest(TEST_NAMESPACE, forgetSource),
            routeDecision = MemoryRouteDecision(
                decision = MemoryRouteDecision.Decision.FORGET_REQUEST,
                memoryTypes = setOf(MemorySemanticType.SOURCE, MemorySemanticType.CLAIM),
                sourcePolicy = MemorySourceUsagePolicy(
                    allowStructuredExtraction = false,
                    allowRecall = false,
                    allowEvidenceHydration = false,
                    reason = "explicit forget command",
                ),
                sourceSearchText = "Toyota RunX preference",
                reason = "User asked to forget remembered information.",
            ),
        )

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertEquals(1, result.memoryBatch.sources.size)
        assertEquals(NOW, snapshot.sourceById(oldSource.id.value).deletedAt)
        assertNull(snapshot.sourceById(forgetSource.id.value).deletedAt)
        assertEquals(NOW, snapshot.claimById(claim.id.value).archivedAt)
        assertTrue(snapshot.runs.any { it.runType == MemoryRun.Type.FORGET_MEMORY })
        assertTrue(store.searchRequests.any { it.scopes == setOf(MemoryStore.SearchScope.ALL) && it.embedding != null })
        assertTrue("Toyota RunX preference" in embeddingIndexer.queries)
    }

    @Test
    fun retentionArchivesOnlyInactiveCandidates() = runBlocking {
        val activeClaim = claim("claim-active", status = MemoryClaim.Status.ACTIVE)
        val supersededClaim = claim("claim-superseded", status = MemoryClaim.Status.SUPERSEDED)
        val activeNote = note("note-active", status = MemoryNote.Status.ACTIVE)
        val resolvedNote = note("note-resolved", status = MemoryNote.Status.RESOLVED)
        val openTask = actionItem("actionItem-open", status = MemoryActionItem.Status.OPEN)
        val doneTask = actionItem("actionItem-done", status = MemoryActionItem.Status.DONE)
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(source()),
                entities = listOf(entity()),
                claims = listOf(activeClaim, supersededClaim),
                notes = listOf(activeNote, resolvedNote),
                actionItems = listOf(openTask, doneTask),
            )
        )

        MemoryRetentionPipeline(
            store = store,
            planner = PolicyMemoryRetentionPlanner(),
            idFactory = SequentialMemoryIdFactory("retention"),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertNull(snapshot.claimById(activeClaim.id.value).archivedAt)
        assertEquals(NOW, snapshot.claimById(supersededClaim.id.value).archivedAt)
        assertEquals(MemoryClaim.Status.SUPERSEDED, snapshot.claimById(supersededClaim.id.value).status)
        assertNull(snapshot.noteById(activeNote.id.value).archivedAt)
        assertEquals(NOW, snapshot.noteById(resolvedNote.id.value).archivedAt)
        assertNull(snapshot.taskById(openTask.id.value).archivedAt)
        assertEquals(NOW, snapshot.taskById(doneTask.id.value).archivedAt)
        assertTrue(snapshot.runs.any { it.runType == MemoryRun.Type.APPLY_RETENTION })
    }

    @Test
    fun repairMergesDuplicateClaimsAndArchivesLoser() = runBlocking {
        val firstSource = source("source-first", "User prefers Toyota.")
        val secondSource = source("source-second", "User still prefers Toyota.")
        val weakerClaim = claim(
            id = "claim-weaker",
            sourceId = firstSource.id.value,
            confidence = 0.55,
            importance = 5,
        )
        val strongerClaim = claim(
            id = "claim-stronger",
            sourceId = secondSource.id.value,
            confidence = 0.9,
            importance = 8,
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(firstSource, secondSource),
                entities = listOf(entity()),
                claims = listOf(weakerClaim, strongerClaim),
            )
        )

        val result = MemoryRepairPipeline(
            store = store,
            planner = FixedRepairPlanner(
                MemoryRepairPlan(
                    repairActions = listOf(
                        MemoryRepairPlan.Action(
                            action = MemoryRepairPlan.Action.Type.MERGE_DUPLICATES,
                            targetType = MemoryItemRef.Type.CLAIM,
                            targetIds = listOf(weakerClaim.id.value, strongerClaim.id.value),
                            reason = "Duplicate claim detected.",
                        )
                    ),
                    summary = "Merged duplicate Toyota preference claims.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("repair"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertEquals(2, result.suspiciousHits.filterIsInstance<MemoryStore.SearchHit.ClaimHit>().size)
        assertEquals(NOW, snapshot.claimById(weakerClaim.id.value).archivedAt)
        assertNull(snapshot.claimById(strongerClaim.id.value).archivedAt)
        assertEquals(2, snapshot.claimById(strongerClaim.id.value).evidenceRefs.size)
        assertTrue(snapshot.runs.any { it.runType == MemoryRun.Type.REPAIR_MEMORY })

        val normalSnapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE)
        val searchHits = store.search(
            MemoryStore.SearchRequest(
                namespace = TEST_NAMESPACE,
                scopes = setOf(MemoryStore.SearchScope.CLAIMS),
                query = "Toyota preference",
                limit = 10,
            )
        )
        assertTrue(normalSnapshot.claims.none { it.id == weakerClaim.id })
        assertTrue(normalSnapshot.claims.any { it.id == strongerClaim.id })
        assertTrue(searchHits.none { it.toTestItemRef().id == weakerClaim.id.value })
        assertTrue(searchHits.any { it.toTestItemRef().id == strongerClaim.id.value })
    }

    @Test
    fun repairMergesDuplicateTasksAndArchivesLoser() = runBlocking {
        val title = "Add selector trace report to memory e2e"
        val weakerTask = actionItem("actionItem-weaker-selector-trace", status = MemoryActionItem.Status.OPEN).copy(
            title = title,
            description = "Expose selector decisions in memory e2e reports.",
            confidence = 0.55,
            updatedAt = EARLIER,
        )
        val strongerTask = actionItem("actionItem-stronger-selector-trace", status = MemoryActionItem.Status.OPEN).copy(
            title = title,
            description = "Expose selector decisions in memory e2e reports.",
            priority = MemoryActionItem.Priority.HIGH,
            confidence = 0.95,
            updatedAt = NOW,
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(source()),
                entities = listOf(entity()),
                actionItems = listOf(weakerTask, strongerTask),
            )
        )

        val result = MemoryRepairPipeline(
            store = store,
            planner = FixedRepairPlanner(
                MemoryRepairPlan(
                    repairActions = listOf(
                        MemoryRepairPlan.Action(
                            action = MemoryRepairPlan.Action.Type.MERGE_DUPLICATES,
                            targetType = MemoryItemRef.Type.ACTION_ITEM,
                            targetIds = listOf(weakerTask.id.value, strongerTask.id.value),
                            reason = "Duplicate actionItem detected.",
                        )
                    ),
                    summary = "Merged duplicate selector trace actionItems.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("repair-actionItem"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertTrue(result.candidateClusters.any { it.kind == MemoryRepairCandidateCluster.Kind.DUPLICATE_ACTION_ITEMS })
        assertEquals(1, result.memoryBatch.actionItems.size)
        assertEquals(weakerTask.id, result.memoryBatch.actionItems.single().id)
        assertEquals(NOW, snapshot.taskById(weakerTask.id.value).archivedAt)
        assertNull(snapshot.taskById(strongerTask.id.value).archivedAt)

        val normalSnapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE)
        val searchHits = store.search(
            MemoryStore.SearchRequest(
                namespace = TEST_NAMESPACE,
                scopes = setOf(MemoryStore.SearchScope.ACTION_ITEMS),
                query = "selector trace report",
                limit = 10,
            )
        )
        assertTrue(normalSnapshot.actionItems.none { it.id == weakerTask.id })
        assertTrue(normalSnapshot.actionItems.any { it.id == strongerTask.id })
        assertTrue(searchHits.none { it.toTestItemRef().id == weakerTask.id.value })
        assertTrue(searchHits.any { it.toTestItemRef().id == strongerTask.id.value })
    }

    @Test
    fun repairDetectsConflictingReplacementClaimsAndSupersedesOlderClaim() = runBlocking {
        val modelPredicate = MemoryPredicateDefinition(
            namespace = TEST_NAMESPACE,
            predicate = "uses_primary_model",
            objectKind = MemoryPredicateDefinition.ObjectValueKind.STRING,
            cardinality = MemoryPredicateDefinition.Cardinality.SINGLE,
            conflictPolicy = MemoryPredicateDefinition.ConflictPolicy.REPLACE,
            semanticKinds = setOf(MemoryPredicateDefinition.SemanticKind.TECHNICAL_CONFIGURATION),
            profileSync = true,
        )
        val oldClaim = claim(
            id = "claim-old-model",
            predicate = "uses_primary_model",
            objectValue = JsonPrimitive("gpt-5.3-codex"),
            normalizedText = "Gromozeka uses gpt-5.3-codex as the primary model.",
        ).copy(
            predicatePolicy = modelPredicate,
            updatedAt = EARLIER,
        )
        val newClaim = claim(
            id = "claim-new-model",
            predicate = "uses_primary_model",
            objectValue = JsonPrimitive("gpt-5.5"),
            normalizedText = "Gromozeka uses gpt-5.5 as the primary model.",
        ).copy(
            predicatePolicy = modelPredicate,
            updatedAt = NOW,
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                predicateDefinitions = listOf(modelPredicate),
                sources = listOf(source()),
                entities = listOf(entity()),
                claims = listOf(oldClaim, newClaim),
            )
        )

        val result = MemoryRepairPipeline(
            store = store,
            planner = FixedRepairPlanner(
                MemoryRepairPlan(
                    repairActions = listOf(
                        MemoryRepairPlan.Action(
                            action = MemoryRepairPlan.Action.Type.SUPERSEDE_ITEM,
                            targetType = MemoryItemRef.Type.CLAIM,
                            targetIds = listOf(oldClaim.id.value),
                            reason = "Older replacement-style claim conflicts with newer claim.",
                        )
                    ),
                    summary = "Superseded old model claim.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("repair-conflict"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertTrue(result.candidateClusters.any { it.kind == MemoryRepairCandidateCluster.Kind.CONFLICTING_CLAIMS })
        assertEquals(2, result.suspiciousHits.filterIsInstance<MemoryStore.SearchHit.ClaimHit>().size)
        assertEquals(MemoryClaim.Status.SUPERSEDED, snapshot.claimById(oldClaim.id.value).status)
        assertEquals(MemoryClaim.Status.ACTIVE, snapshot.claimById(newClaim.id.value).status)
        assertNull(snapshot.claimById(oldClaim.id.value).archivedAt)
    }

    @Test
    fun repairRefreshesDriftedProfileProjection() = runBlocking {
        val preferencePredicate = MemoryPredicateDefinition(
            namespace = TEST_NAMESPACE,
            predicate = "prefers",
            objectKind = MemoryPredicateDefinition.ObjectValueKind.STRING,
            cardinality = MemoryPredicateDefinition.Cardinality.MULTI,
            conflictPolicy = MemoryPredicateDefinition.ConflictPolicy.COEXIST,
            semanticKinds = setOf(MemoryPredicateDefinition.SemanticKind.PREFERENCE),
            profileSync = true,
        )
        val profile = profile(
            id = "profile-user",
            ownerEntityId = USER_ENTITY_ID,
            profileText = "Profile for Lewik (USER).\nNo active profile-synced memory.",
            version = 1,
            updatedAt = EARLIER,
        )
        val freshClaim = claim(
            id = "claim-profile-toyota",
            predicate = "prefers",
            normalizedText = "The user prefers Toyota.",
        ).copy(
            predicatePolicy = preferencePredicate,
            updatedAt = NOW,
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                predicateDefinitions = listOf(preferencePredicate),
                sources = listOf(source()),
                entities = listOf(entity()),
                claims = listOf(freshClaim),
                profiles = listOf(profile),
            )
        )

        val result = MemoryRepairPipeline(
            store = store,
            planner = FixedRepairPlanner(
                MemoryRepairPlan(
                    repairActions = listOf(
                        MemoryRepairPlan.Action(
                            action = MemoryRepairPlan.Action.Type.REFRESH_PROFILE,
                            targetType = MemoryItemRef.Type.PROFILE,
                            targetIds = listOf(profile.id.value),
                            reason = "Profile is older than active profile-synced claim.",
                        )
                    ),
                    summary = "Refreshed drifted profile.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("repair-profile"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)
        val updatedProfile = snapshot.profiles.single()

        assertTrue(result.candidateClusters.any { it.kind == MemoryRepairCandidateCluster.Kind.PROFILE_DRIFT })
        assertEquals(1, result.memoryBatch.profiles.size)
        assertEquals(2, updatedProfile.version)
        assertTrue(updatedProfile.profileText.contains("The user prefers Toyota."))
        assertTrue(snapshot.runs.any { run ->
            run.runType == MemoryRun.Type.REPAIR_MEMORY &&
                run.appliedOps.any { it.toString().contains("refresh_profile") }
        })
    }

    @Test
    fun repairBatchesLargeCandidateClusterSets() = runBlocking {
        val duplicateClaims = (1..9).flatMap { index ->
            listOf(
                claim(
                    id = "claim-tool-$index-a",
                    normalizedText = "The user prefers Tool $index.",
                ),
                claim(
                    id = "claim-tool-$index-b",
                    normalizedText = "The user prefers Tool $index.",
                ),
            )
        }
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(source()),
                entities = listOf(entity()),
                claims = duplicateClaims,
            )
        )
        val planner = CapturingRepairPlanner()

        val result = MemoryRepairPipeline(
            store = store,
            planner = planner,
            idFactory = SequentialMemoryIdFactory("repair-batches"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        assertEquals(9, result.candidateClusters.size)
        assertEquals(listOf(8, 1), planner.batchSizes)
        assertEquals(0, result.repairPlan.repairActions.size)
    }

    @Test
    fun profileProjectionIgnoresExpiredAndLowConfidenceMemory() = runBlocking {
        val profile = profile(
            id = "profile-user",
            ownerEntityId = USER_ENTITY_ID,
            profileText = "Profile for Lewik (USER).\nStable facts:\n- The user prefers Obsidian.",
            version = 1,
            updatedAt = EARLIER,
        )
        val currentClaim = claim(
            id = "claim-current-logseq",
            predicate = "prefers",
            objectValue = JsonPrimitive("Logseq"),
            normalizedText = "The user prefers Logseq for personal notes.",
            confidence = 0.9,
        ).copy(updatedAt = NOW)
        val expiredClaim = claim(
            id = "claim-expired-obsidian",
            predicate = "prefers",
            objectValue = JsonPrimitive("Obsidian"),
            normalizedText = "The user prefers Obsidian for personal notes.",
            confidence = 0.95,
        ).copy(validTo = EARLIER, updatedAt = NOW)
        val weakClaim = claim(
            id = "claim-weak-vim",
            predicate = "prefers",
            objectValue = JsonPrimitive("Vim"),
            normalizedText = "The user may prefer Vim for notes.",
            confidence = 0.4,
        ).copy(updatedAt = NOW)
        val expiredNote = note(
            id = "note-expired-obsidian",
            title = "Old Obsidian context",
            summary = "The user used to prefer Obsidian for personal notes.",
            confidence = 0.95,
            importance = 9,
        ).copy(validTo = EARLIER, updatedAt = NOW)
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(source()),
                entities = listOf(entity()),
                claims = listOf(currentClaim, expiredClaim, weakClaim),
                notes = listOf(expiredNote),
                profiles = listOf(profile),
            )
        )

        val result = ProjectionMemoryProfileUpdater(store).updateNamespaceProfiles(
            namespace = TEST_NAMESPACE,
            logSubject = "test=profile_projection_safety",
            appliedBatch = MemoryUpdateBatch(
                claims = listOf(currentClaim, expiredClaim, weakClaim),
                notes = listOf(expiredNote),
            ),
            completedAt = NOW,
        )

        val updatedProfile = result.profiles.single()

        assertTrue(updatedProfile.profileText.contains("The user prefers Logseq for personal notes."))
        assertTrue(!updatedProfile.profileText.contains("Obsidian"))
        assertTrue(!updatedProfile.profileText.contains("Vim"))
        assertTrue(!updatedProfile.profileText.contains("Old Obsidian context"))
        assertTrue(updatedProfile.profileJson.toString().contains("claim-current-logseq"))
        assertTrue(!updatedProfile.profileJson.toString().contains("claim-expired-obsidian"))
        assertTrue(!updatedProfile.profileJson.toString().contains("claim-weak-vim"))
    }

    @Test
    fun profileProjectionUsesStableOrderingForEqualRankedFacts() = runBlocking {
        val laterClaim = claim(
            id = "claim-b",
            normalizedText = "The user has been experimenting with a French press.",
            confidence = 0.95,
            importance = 6,
        )
        val earlierClaim = claim(
            id = "claim-a",
            normalizedText = "The user has signed up for a coffee subscription service.",
            confidence = 0.95,
            importance = 6,
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(source()),
                entities = listOf(entity()),
                claims = listOf(laterClaim, earlierClaim),
            )
        )

        val result = ProjectionMemoryProfileUpdater(store).updateNamespaceProfiles(
            namespace = TEST_NAMESPACE,
            logSubject = "test=stable_profile_order",
            appliedBatch = MemoryUpdateBatch(claims = listOf(laterClaim, earlierClaim)),
            completedAt = NOW,
            force = true,
        )

        val profileText = result.profiles.single().profileText

        assertTrue(profileText.indexOf("coffee subscription service") < profileText.indexOf("French press"))
    }

    @Test
    fun directWriteMarksNotesSupportedBySupersededClaimsAsStaleEvenWhenNotesWereNotRetrieved() = runBlocking {
        val obsidian = entity(
            id = MemoryEntity.Id("entity-obsidian"),
            entityType = MemoryEntity.Type.PRODUCT,
            canonicalName = "Obsidian",
            normalizedName = "obsidian",
        )
        val logseq = entity(
            id = MemoryEntity.Id("entity-logseq"),
            entityType = MemoryEntity.Type.PRODUCT,
            canonicalName = "Logseq",
            normalizedName = "logseq",
        )
        val oldSource = source("source-obsidian", "For personal notes, remember that I prefer Obsidian.")
        val updateSource = source("source-logseq", "I now prefer Logseq instead of Obsidian for personal notes.")
        val oldClaim = claim(
            id = "claim-obsidian",
            sourceId = oldSource.id.value,
            objectEntityId = obsidian.id,
            objectValue = null,
            normalizedText = "The user prefers Obsidian for personal notes.",
        )
        val staleCandidateNote = note(
            id = "note-obsidian",
            title = "Obsidian personal notes preference",
            summary = "The user prefers Obsidian for personal notes because it keeps Markdown local.",
            anchorEntityId = USER_ENTITY_ID,
            entityRefs = listOf(
                MemoryNote.EntityRef(USER_ENTITY_ID, MemoryNote.EntityRef.Role.SUBJECT),
                MemoryNote.EntityRef(obsidian.id, MemoryNote.EntityRef.Role.PRIMARY),
            ),
            evidenceRefs = listOf(evidenceRef(oldSource.id.value, oldSource.contentText)),
        )
        val unrelatedNote = note(
            id = "note-local-first",
            title = "Local-first notes context",
            summary = "The user likes local-first personal notes workflows.",
            anchorEntityId = USER_ENTITY_ID,
            entityRefs = listOf(MemoryNote.EntityRef(USER_ENTITY_ID, MemoryNote.EntityRef.Role.SUBJECT)),
            evidenceRefs = listOf(evidenceRef(oldSource.id.value, "local-first personal notes workflow")),
        )
        val newClaimCandidate = MemoryClaimCandidate(
            subjectEntityId = USER_ENTITY_ID,
            predicate = "prefers",
            objectEntityId = logseq.id,
            normalizedText = "The user prefers Logseq for personal notes.",
            scope = MemoryScope.Global("User-level preference"),
            confidence = 0.98,
            importance = 7,
            evidenceQuote = "I now prefer Logseq instead of Obsidian",
            evidenceReason = "The target message explicitly replaces the previous note-taking preference.",
            reason = "Scripted replacement candidate.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(oldSource),
                entities = listOf(entity(), obsidian, logseq),
                claims = listOf(oldClaim),
                notes = listOf(staleCandidateNote, unrelatedNote),
            )
        )

        val result = DirectStructuredMemoryWritePipeline(
            store = store,
            router = FixedMemoryWriteRouter(),
            retrievalPlanner = FixedMemoryWriteRetrievalPlanner(
                MemoryWriteRetrievalPlan(
                    needRetrieval = true,
                    entityQueries = listOf("user", "Obsidian", "Logseq"),
                    textQueries = listOf("personal notes preference Obsidian Logseq"),
                    memoryTypes = setOf(MemorySemanticType.CLAIM, MemorySemanticType.ENTITY, MemorySemanticType.SOURCE),
                    retrievalBudget = MemoryRetrievalBudget(claims = 5, sources = 5),
                )
            ),
            entityCanonicalizer = FixedMemoryEntityCanonicalizer(),
            noteConstructor = FixedMemoryNoteConstructor(),
            noteReconciler = InsertOnlyMemoryNoteReconciler,
            claimExtractor = FixedMemoryClaimExtractor(listOf(newClaimCandidate)),
            claimReconciler = FixedMemoryClaimReconciler(
                listOf(
                    MemoryClaimReconciliationOp(
                        action = MemoryReconciliationAction.SUPERSEDE,
                        targetClaimId = oldClaim.id,
                        candidate = newClaimCandidate,
                        reason = "Scripted replacement supersedes old Obsidian preference.",
                    )
                )
            ),
            actionItemUpdater = FixedMemoryActionItemUpdater(),
            materializer = DefaultDirectStructuredMemoryWriteMaterializer(
                idFactory = SequentialMemoryIdFactory("claim-supersede-cascade"),
            ),
            clock = FixedMemoryClock(NOW),
        ).write(
            DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = updateSource,
            )
        )

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertTrue(result.retrievedHits.none { it is MemoryStore.SearchHit.NoteHit })
        assertEquals(MemoryClaim.Status.SUPERSEDED, snapshot.claimById(oldClaim.id.value).status)
        assertEquals(MemoryClaim.Status.ACTIVE, snapshot.claimById("claim-supersede-cascade-claim-2").status)
        assertEquals(MemoryNote.Status.STALE, snapshot.noteById(staleCandidateNote.id.value).status)
        assertEquals(NOW, snapshot.noteById(staleCandidateNote.id.value).validTo)
        assertEquals(NOW, snapshot.noteById(staleCandidateNote.id.value).updatedAt)
        assertEquals(MemoryNote.Status.ACTIVE, snapshot.noteById(unrelatedNote.id.value).status)
        assertTrue(result.memoryBatch.notes.any { it.id == staleCandidateNote.id && it.status == MemoryNote.Status.STALE })
    }

    @Test
    fun directWriteKeepsEntitySummariesIdentityOnlyWhenClaimsChange() = runBlocking {
        val bookNest = entity(
            id = MemoryEntity.Id("entity-booknest"),
            entityType = MemoryEntity.Type.PROJECT,
            canonicalName = "BookNest",
            normalizedName = "booknest",
            summary = "A project whose current primary export format is CSV.",
        )
        val csv = entity(
            id = MemoryEntity.Id("entity-csv"),
            entityType = MemoryEntity.Type.TECHNOLOGY,
            canonicalName = "CSV",
            normalizedName = "csv",
            summary = "Comma-Separated Values data format used as the primary export format in ShelfLog.",
        )
        val parquetId = MemoryEntity.Id("entity-parquet")
        val oldSource = source("booknest-csv-source", "For BookNest, the primary export format is CSV.")
        val updateSource = source("booknest-parquet-source", "BookNest primary export is now Parquet; CSV is old.")
        val oldClaim = claim(
            id = "booknest-csv-claim",
            sourceId = oldSource.id.value,
            subjectEntityId = bookNest.id,
            predicate = "primary_export_format",
            objectEntityId = csv.id,
            objectValue = null,
            normalizedText = "The current primary export format for BookNest is CSV.",
        )
        val newClaimCandidate = MemoryClaimCandidate(
            subjectEntityId = bookNest.id,
            predicate = "primary_export_format",
            predicateFamily = "primary_export_format",
            objectEntityId = parquetId,
            normalizedText = "The current primary export format for BookNest is Parquet.",
            contextText = "This replaces the old CSV value.",
            scope = MemoryScope.Entity("BookNest project", bookNest.id),
            confidence = 0.99,
            importance = 5,
            evidenceQuote = "primary export is now Parquet",
            evidenceReason = "The target message explicitly updates BookNest's current export format.",
            reason = "Scripted current export replacement.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(oldSource),
                entities = listOf(entity(), bookNest, csv),
                claims = listOf(oldClaim),
            )
        )

        DirectStructuredMemoryWritePipeline(
            store = store,
            router = FixedMemoryWriteRouter(),
            retrievalPlanner = FixedMemoryWriteRetrievalPlanner(
                MemoryWriteRetrievalPlan(
                    needRetrieval = true,
                    entityQueries = listOf("BookNest", "CSV", "Parquet"),
                    textQueries = listOf("BookNest primary export format"),
                    memoryTypes = setOf(MemorySemanticType.CLAIM, MemorySemanticType.ENTITY, MemorySemanticType.SOURCE),
                    retrievalBudget = MemoryRetrievalBudget(claims = 5, sources = 5),
                )
            ),
            entityCanonicalizer = FixedMemoryEntityCanonicalizer(
                listOf(
                    MemoryEntityCanonicalizationOp(
                        mention = "BookNest",
                        action = MemoryEntityCanonicalizationOp.Action.LINK_EXISTING,
                        entityId = bookNest.id,
                    ),
                    MemoryEntityCanonicalizationOp(
                        mention = "CSV",
                        action = MemoryEntityCanonicalizationOp.Action.LINK_EXISTING,
                        entityId = csv.id,
                    ),
                    MemoryEntityCanonicalizationOp(
                        mention = "Parquet",
                        action = MemoryEntityCanonicalizationOp.Action.CREATE_NEW,
                        entityId = parquetId,
                        newEntity = MemoryEntityCanonicalizationOp.NewEntity(
                            entityType = MemoryEntity.Type.TECHNOLOGY,
                            canonicalName = "Parquet",
                            summary = "Columnar format now used as the primary export format for BookNest.",
                        ),
                    ),
                )
            ),
            noteConstructor = FixedMemoryNoteConstructor(),
            noteReconciler = InsertOnlyMemoryNoteReconciler,
            claimExtractor = FixedMemoryClaimExtractor(listOf(newClaimCandidate)),
            claimReconciler = FixedMemoryClaimReconciler(
                listOf(
                    MemoryClaimReconciliationOp(
                        action = MemoryReconciliationAction.SUPERSEDE,
                        targetClaimId = oldClaim.id,
                        candidate = newClaimCandidate,
                    )
                )
            ),
            actionItemUpdater = FixedMemoryActionItemUpdater(),
            materializer = DefaultDirectStructuredMemoryWriteMaterializer(
                idFactory = SequentialMemoryIdFactory("entity-summary"),
            ),
            clock = FixedMemoryClock(NOW),
        ).write(
            DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = updateSource,
            )
        )

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertEquals("Project named BookNest.", snapshot.entityById(bookNest.id.value).summary)
        assertEquals("Technology named CSV.", snapshot.entityById(csv.id.value).summary)
        assertEquals("Technology named Parquet.", snapshot.entityById(parquetId.value).summary)
        assertEquals(MemoryClaim.Status.SUPERSEDED, snapshot.claimById(oldClaim.id.value).status)
        assertTrue(snapshot.claims.any { it.status == MemoryClaim.Status.ACTIVE && it.normalizedText.contains("Parquet") })
    }

    @Test
    fun directWriteDedupGuardNoopsDuplicateClaimInsert() = runBlocking {
        val toyota = entity(
            id = MemoryEntity.Id("entity-toyota"),
            entityType = MemoryEntity.Type.PRODUCT,
            canonicalName = "Toyota cars",
            normalizedName = "toyota cars",
        )
        val existingSource = source("source-existing", "For future recommendations, remember that I prefer Toyota cars.")
        val duplicateSource = source("source-duplicate", "Just to repeat the preference: I still prefer Toyota cars.")
        val existingClaim = claim(
            id = "claim-existing-toyota",
            sourceId = existingSource.id.value,
            objectEntityId = toyota.id,
            objectValue = null,
            normalizedText = "The user prefers Toyota cars.",
        )
        val duplicateCandidate = MemoryClaimCandidate(
            subjectEntityId = USER_ENTITY_ID,
            predicate = "prefers",
            objectEntityId = toyota.id,
            normalizedText = "The user prefers Toyota cars.",
            scope = MemoryScope.Global("User-level preference"),
            confidence = 0.9,
            importance = 7,
            evidenceQuote = "I still prefer Toyota cars",
            evidenceReason = "The target message repeats the same preference.",
            reason = "Scripted duplicate candidate",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(existingSource),
                entities = listOf(entity(), toyota),
                claims = listOf(existingClaim),
            )
        )

        val result = DirectStructuredMemoryWritePipeline(
            store = store,
            router = FixedMemoryWriteRouter(),
            retrievalPlanner = FixedMemoryWriteRetrievalPlanner(
                MemoryWriteRetrievalPlan(
                    needRetrieval = true,
                    memoryTypes = setOf(MemorySemanticType.CLAIM, MemorySemanticType.ENTITY, MemorySemanticType.SOURCE),
                    retrievalBudget = MemoryRetrievalBudget(claims = 5, sources = 5),
                )
            ),
            entityCanonicalizer = FixedMemoryEntityCanonicalizer(),
            noteConstructor = FixedMemoryNoteConstructor(),
            noteReconciler = InsertOnlyMemoryNoteReconciler,
            claimExtractor = FixedMemoryClaimExtractor(listOf(duplicateCandidate)),
            claimReconciler = InsertOnlyMemoryClaimReconciler,
            actionItemUpdater = FixedMemoryActionItemUpdater(),
            materializer = DefaultDirectStructuredMemoryWriteMaterializer(
                idFactory = SequentialMemoryIdFactory("dedup"),
            ),
            clock = FixedMemoryClock(NOW),
        ).write(
            DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = duplicateSource,
            )
        )

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertEquals(MemoryReconciliationAction.NOOP, result.claimOps.single().action)
        assertEquals(existingClaim.id, result.claimOps.single().targetClaimId)
        assertTrue(result.memoryBatch.claims.isEmpty())
        assertEquals(MemoryRun.Type.RECONCILE_CLAIMS, result.memoryBatch.runs.single().runType)
        assertTrue(result.memoryBatch.runs.single().summary.contains("no durable changes"))
        assertTrue(result.memoryBatch.runs.single().summary.contains("claims=1"))
        assertTrue(!result.memoryBatch.runs.single().summary.contains("1 claim ops"))
        assertEquals(listOf(existingClaim.id), snapshot.claims.map { it.id })
        assertTrue(snapshot.sources.any { it.id == duplicateSource.id })
    }

    @Test
    fun directWriteSkipsAlreadyProcessedSameSource() = runBlocking {
        val repeatedSource = source(
            "source-idempotent",
            "For recommendations, remember that I prefer Toyota.",
        )
        val candidate = MemoryClaimCandidate(
            subjectEntityId = USER_ENTITY_ID,
            predicate = "prefers",
            objectValue = JsonPrimitive("Toyota"),
            normalizedText = "The user prefers Toyota.",
            scope = MemoryScope.Global("User-level preference"),
            confidence = 0.9,
            importance = 7,
            evidenceQuote = "I prefer Toyota",
            evidenceReason = "The target message states the preference directly.",
            reason = "Scripted idempotent write candidate.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                entities = listOf(entity()),
            )
        )

        fun pipeline(prefix: String) = DirectStructuredMemoryWritePipeline(
            store = store,
            router = FixedMemoryWriteRouter(),
            retrievalPlanner = FixedMemoryWriteRetrievalPlanner(
                MemoryWriteRetrievalPlan(
                    needRetrieval = false,
                    memoryTypes = setOf(MemorySemanticType.CLAIM),
                    retrievalBudget = MemoryRetrievalBudget(claims = 0),
                )
            ),
            entityCanonicalizer = FixedMemoryEntityCanonicalizer(),
            noteConstructor = FixedMemoryNoteConstructor(),
            noteReconciler = InsertOnlyMemoryNoteReconciler,
            claimExtractor = FixedMemoryClaimExtractor(listOf(candidate)),
            claimReconciler = InsertOnlyMemoryClaimReconciler,
            actionItemUpdater = FixedMemoryActionItemUpdater(),
            materializer = DefaultDirectStructuredMemoryWriteMaterializer(
                idFactory = SequentialMemoryIdFactory(prefix),
            ),
            clock = FixedMemoryClock(NOW),
        )

        val first = pipeline("idempotent-first").write(
            DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = repeatedSource,
            )
        )
        val second = pipeline("idempotent-second").write(
            DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = repeatedSource,
            )
        )
        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)
        val structuredRuns = snapshot.runs
            .filter { repeatedSource.id in it.sourceIds }
            .filter { it.runType == MemoryRun.Type.RECONCILE_CLAIMS }

        assertEquals(1, first.memoryBatch.claims.size)
        assertEquals(MemoryRouteDecision.Decision.NOOP, second.routeDecision.decision)
        assertTrue(second.routeDecision.reason.contains("already processed"))
        assertTrue(second.sourceBatch.sources.isEmpty())
        assertTrue(second.memoryBatch.claims.isEmpty())
        assertEquals(1, snapshot.sources.count { it.id == repeatedSource.id })
        assertEquals(1, snapshot.claims.count { claim -> claim.evidenceRefs.any { it.sourceId == repeatedSource.id } })
        assertEquals(1, structuredRuns.size)
        assertTrue(!structuredRuns.single().inputHash.isNullOrBlank())
    }

    @Test
    fun directWriteDedupGuardNoopsDuplicateNoteInsert() = runBlocking {
        val existingSource = source("source-existing-note", "Memory recall logging should expose planner, selector, and prompt composition decisions.")
        val duplicateSource = source("source-duplicate-note", "Repeat the note: memory recall logging should expose planner, selector, and prompt composition decisions.")
        val existingNote = note(
            id = "note-existing-recall-logging",
            title = "Memory recall logging",
            summary = "Memory recall logging should expose planner, selector, and prompt composition decisions.",
            noteType = MemoryNote.Type.DECISION,
            evidenceRefs = listOf(evidenceRef(existingSource.id.value, existingSource.contentText)),
        )
        val duplicateCandidate = MemoryNoteCandidate(
            title = existingNote.title,
            summary = existingNote.summary,
            scope = existingNote.scope,
            noteType = existingNote.noteType,
            entityRefs = existingNote.entityRefs,
            confidence = 0.9,
            importance = 8,
            evidenceQuote = "memory recall logging should expose planner, selector, and prompt composition decisions",
            evidenceReason = "The target message repeats the same note.",
            rationale = "Scripted duplicate note candidate",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(existingSource),
                entities = listOf(entity()),
                notes = listOf(existingNote),
            )
        )

        val result = DirectStructuredMemoryWritePipeline(
            store = store,
            router = FixedMemoryWriteRouter(
                MemoryRouteDecision(
                    decision = MemoryRouteDecision.Decision.NOTE_WRITE,
                    memoryTypes = setOf(MemorySemanticType.NOTE, MemorySemanticType.ENTITY),
                    salience = 1.0,
                    reason = "Scripted note write",
                )
            ),
            retrievalPlanner = FixedMemoryWriteRetrievalPlanner(
                MemoryWriteRetrievalPlan(
                    needRetrieval = true,
                    memoryTypes = setOf(MemorySemanticType.NOTE, MemorySemanticType.ENTITY),
                    retrievalBudget = MemoryRetrievalBudget(notes = 5),
                )
            ),
            entityCanonicalizer = FixedMemoryEntityCanonicalizer(),
            noteConstructor = FixedMemoryNoteConstructor(listOf(duplicateCandidate)),
            noteReconciler = InsertOnlyMemoryNoteReconciler,
            claimExtractor = FixedMemoryClaimExtractor(),
            claimReconciler = InsertOnlyMemoryClaimReconciler,
            actionItemUpdater = FixedMemoryActionItemUpdater(),
            materializer = DefaultDirectStructuredMemoryWriteMaterializer(
                idFactory = SequentialMemoryIdFactory("note-dedup"),
            ),
            clock = FixedMemoryClock(NOW),
        ).write(
            DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = duplicateSource,
            )
        )

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertEquals(MemoryReconciliationAction.INSERT, result.rawNoteOps.single().action)
        assertEquals(MemoryReconciliationAction.NOOP, result.noteOps.single().action)
        assertEquals(existingNote.id, result.noteOps.single().targetNoteId)
        assertTrue(result.memoryBatch.notes.isEmpty())
        assertEquals(listOf(existingNote.id), snapshot.notes.map { it.id })
    }

    @Test
    fun directWriteDedupGuardConvertsDuplicateTaskInsertToUpdate() = runBlocking {
        val existingSource = source("source-existing-actionItem", "Remember follow-up: add write trace report to memory e2e.")
        val duplicateSource = source("source-duplicate-actionItem", "Update the same follow-up: add write trace report to memory e2e with raw and final ops.")
        val existingTask = actionItem(
            id = "actionItem-existing-write-trace",
            status = MemoryActionItem.Status.OPEN,
        ).copy(
            title = "Add write trace report to memory e2e",
            description = "Expose write pipeline stages in the real-model memory e2e report.",
            evidenceRefs = listOf(evidenceRef(existingSource.id.value, existingSource.contentText)),
        )
        val duplicateDraft = MemoryActionItemUpdateOp.Draft(
            title = existingTask.title,
            description = "Expose raw and final write operations in the real-model memory e2e report.",
            status = MemoryActionItem.Status.OPEN,
            scope = existingTask.scope,
            ownerEntityId = existingTask.ownerEntityId,
            evidenceQuote = "add write trace report to memory e2e with raw and final ops",
            evidenceReason = "The target message updates the same follow-up.",
            confidence = 0.9,
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(existingSource),
                entities = listOf(entity()),
                actionItems = listOf(existingTask),
            )
        )

        val result = DirectStructuredMemoryWritePipeline(
            store = store,
            router = FixedMemoryWriteRouter(
                MemoryRouteDecision(
                    decision = MemoryRouteDecision.Decision.MIXED,
                    memoryTypes = setOf(MemorySemanticType.ACTION_ITEM, MemorySemanticType.ENTITY),
                    salience = 1.0,
                    reason = "Scripted actionItem write",
                )
            ),
            retrievalPlanner = FixedMemoryWriteRetrievalPlanner(
                MemoryWriteRetrievalPlan(
                    needRetrieval = true,
                    memoryTypes = setOf(MemorySemanticType.ACTION_ITEM, MemorySemanticType.ENTITY),
                    retrievalBudget = MemoryRetrievalBudget(actionItems = 5),
                )
            ),
            entityCanonicalizer = FixedMemoryEntityCanonicalizer(),
            noteConstructor = FixedMemoryNoteConstructor(),
            noteReconciler = InsertOnlyMemoryNoteReconciler,
            claimExtractor = FixedMemoryClaimExtractor(),
            claimReconciler = InsertOnlyMemoryClaimReconciler,
            actionItemUpdater = FixedMemoryActionItemUpdater(
                listOf(
                    MemoryActionItemUpdateOp(
                        action = MemoryActionItemUpdateOp.Action.INSERT,
                        actionItem = duplicateDraft,
                        reason = "Scripted duplicate actionItem insert",
                    )
                )
            ),
            materializer = DefaultDirectStructuredMemoryWriteMaterializer(
                idFactory = SequentialMemoryIdFactory("actionItem-dedup"),
            ),
            clock = FixedMemoryClock(NOW),
        ).write(
            DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = duplicateSource,
            )
        )

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertEquals(MemoryActionItemUpdateOp.Action.INSERT, result.rawActionItemOps.single().action)
        assertEquals(MemoryActionItemUpdateOp.Action.UPDATE, result.actionItemOps.single().action)
        assertEquals(existingTask.id, result.actionItemOps.single().targetActionItemId)
        assertEquals(listOf(existingTask.id), snapshot.actionItems.map { it.id })
        assertTrue(snapshot.taskById(existingTask.id.value).description!!.contains("raw and final write operations"))
    }

    @Test
    fun entityMaintenanceMergesDuplicateEntitiesAndRelinksMemory() = runBlocking {
        val source = source("entity-source", "No Memory Verifier and NoMemoryVerifier refer to the same verifier service.")
        val winner = entity(
            id = MemoryEntity.Id("entity-no-memory-verifier"),
            entityType = MemoryEntity.Type.SERVICE,
            canonicalName = "NoMemoryVerifier",
            normalizedName = "no memory verifier",
            summary = "Service that verifies no-memory read decisions.",
        )
        val loser = entity(
            id = MemoryEntity.Id("entity-no-memory-verifier-spaced"),
            entityType = MemoryEntity.Type.SERVICE,
            canonicalName = "No Memory Verifier",
            normalizedName = "no memory verifier",
        )
        val claim = claim(
            id = "claim-verifier",
            sourceId = source.id.value,
            subjectEntityId = loser.id,
            predicate = "verifies_read_need",
            normalizedText = "No Memory Verifier checks whether a no-memory read plan still needs recall.",
        )
        val note = note(
            id = "note-verifier",
            title = "No Memory Verifier context",
            summary = "The verifier guards recall when the read planner says no memory is needed.",
            anchorEntityId = loser.id,
            entityRefs = listOf(MemoryNote.EntityRef(loser.id, MemoryNote.EntityRef.Role.PRIMARY)),
            evidenceRefs = listOf(evidenceRef(source.id.value, source.contentText)),
        )
        val actionItem = actionItem(
            id = "actionItem-verifier",
            status = MemoryActionItem.Status.OPEN,
            ownerEntityId = loser.id,
            relatedEntityIds = listOf(loser.id),
        )
        val episode = episode(
            id = "episode-verifier",
            ownerEntityId = loser.id,
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(source),
                entities = listOf(winner, loser),
                claims = listOf(claim),
                notes = listOf(note),
                actionItems = listOf(actionItem),
                episodes = listOf(episode),
            )
        )

        val result = MemoryEntityMaintenancePipeline(
            store = store,
            planner = FixedEntityMaintenancePlanner(
                MemoryEntityMaintenancePlan(
                    actions = listOf(
                        MemoryEntityMaintenancePlan.Action(
                            action = MemoryEntityMaintenancePlan.Action.Type.MERGE,
                            winnerEntityId = winner.id.value,
                            loserEntityIds = listOf(loser.id.value),
                            aliasTexts = listOf("No Memory Verifier"),
                            reason = "Both names refer to the same verifier service.",
                        )
                    ),
                    summary = "Merged duplicate verifier entities.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("entity-maintenance"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertEquals(1, result.candidateGroups.size)
        assertTrue(result.candidateGroups.single().entities.map { it.id }.containsAll(listOf(winner.id, loser.id)))
        assertEquals(MemoryEntity.Status.ACTIVE, snapshot.entityById(winner.id.value).status)
        assertEquals(MemoryEntity.Status.MERGED, snapshot.entityById(loser.id.value).status)
        assertEquals(winner.id, snapshot.entityById(loser.id.value).mergedIntoEntityId)
        assertTrue(snapshot.entityById(winner.id.value).aliases.any { it.text == "No Memory Verifier" })
        assertEquals(winner.id, snapshot.claimById(claim.id.value).subjectEntityId)
        assertEquals(winner.id, snapshot.noteById(note.id.value).anchorEntityId)
        assertEquals(winner.id, snapshot.noteById(note.id.value).entityRefs.single().entityId)
        assertEquals(winner.id, snapshot.taskById(actionItem.id.value).ownerEntityId)
        assertEquals(listOf(winner.id), snapshot.taskById(actionItem.id.value).relatedEntityIds)
        assertEquals(winner.id, snapshot.episodeById(episode.id.value).ownerEntityId)
        assertTrue(snapshot.runs.any { it.runType == MemoryRun.Type.MAINTAIN_ENTITIES })
    }

    @Test
    fun entityMaintenanceMergesCompatibleHumanTypesAndPreservesObservedTypes() = runBlocking {
        val user = entity(
            id = MemoryEntity.Id("entity-lewik-user"),
            entityType = MemoryEntity.Type.USER,
            canonicalName = "Lewik",
            normalizedName = "lewik",
        )
        val person = entity(
            id = MemoryEntity.Id("entity-lewik-person"),
            entityType = MemoryEntity.Type.PERSON,
            canonicalName = "Lewik",
            normalizedName = "lewik",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                entities = listOf(user, person),
            )
        )

        val result = MemoryEntityMaintenancePipeline(
            store = store,
            planner = FixedEntityMaintenancePlanner(
                MemoryEntityMaintenancePlan(
                    actions = listOf(
                        MemoryEntityMaintenancePlan.Action(
                            action = MemoryEntityMaintenancePlan.Action.Type.MERGE,
                            winnerEntityId = user.id.value,
                            loserEntityIds = listOf(person.id.value),
                            reason = "Both entities identify the same human.",
                        )
                    ),
                    summary = "Merged compatible human aliases.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("entity-maintenance"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)
        val updatedUser = snapshot.entityById(user.id.value)

        assertEquals(1, result.candidateGroups.size)
        assertEquals(MemoryEntity.Status.ACTIVE, updatedUser.status)
        assertEquals(MemoryEntity.Status.MERGED, snapshot.entityById(person.id.value).status)
        assertEquals(setOf(MemoryEntity.Type.USER, MemoryEntity.Type.PERSON), updatedUser.observedTypes)
    }

    @Test
    fun entityMaintenanceGroupsCurrentUserWithNamedPersonThroughPreferredName() = runBlocking {
        val user = entity(
            id = MemoryEntity.Id("entity-current-user"),
            entityType = MemoryEntity.Type.USER,
            canonicalName = "User",
            normalizedName = "user",
            aliases = listOf(alias("Lev Lewik")),
        )
        val fullNamePerson = entity(
            id = MemoryEntity.Id("entity-lev-lewik-person"),
            entityType = MemoryEntity.Type.PERSON,
            canonicalName = "Lev Lewik",
            normalizedName = "lev lewik",
        )
        val firstNamePerson = entity(
            id = MemoryEntity.Id("entity-lev-person"),
            entityType = MemoryEntity.Type.PERSON,
            canonicalName = "Lev",
            normalizedName = "lev",
        )
        val preferredName = claim(
            id = "claim-user-preferred-name",
            subjectEntityId = user.id,
            predicate = "preferred_name",
            objectValue = JsonPrimitive("Lev"),
            normalizedText = "The user's preferred name is Lev.",
        )
        val skill = claim(
            id = "claim-lev-kotlin",
            subjectEntityId = fullNamePerson.id,
            predicate = "works_with",
            objectValue = JsonPrimitive("Kotlin"),
            normalizedText = "Lev Lewik works with Kotlin.",
        )
        val taskContext = claim(
            id = "claim-lev-memory",
            subjectEntityId = firstNamePerson.id,
            predicate = "responsible_for",
            objectValue = JsonPrimitive("memory pipeline repair"),
            normalizedText = "Lev is responsible for memory pipeline repair.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                entities = listOf(user, fullNamePerson, firstNamePerson),
                claims = listOf(preferredName, skill, taskContext),
            )
        )

        val result = MemoryEntityMaintenancePipeline(
            store = store,
            planner = FixedEntityMaintenancePlanner(
                MemoryEntityMaintenancePlan(
                    actions = listOf(
                        MemoryEntityMaintenancePlan.Action(
                            action = MemoryEntityMaintenancePlan.Action.Type.MERGE,
                            winnerEntityId = user.id.value,
                            loserEntityIds = listOf(fullNamePerson.id.value, firstNamePerson.id.value),
                            aliasTexts = listOf("Lev Lewik", "Lev"),
                            reason = "The named PERSON entities are aliases of the current USER profile.",
                        )
                    ),
                    summary = "Merged current user identity split.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("entity-maintenance"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)
        val updatedUser = snapshot.entityById(user.id.value)

        assertEquals(1, result.candidateGroups.size)
        assertTrue(result.candidateGroups.single().reason.contains("current user"))
        assertEquals(MemoryEntity.Status.ACTIVE, updatedUser.status)
        assertEquals(MemoryEntity.Status.MERGED, snapshot.entityById(fullNamePerson.id.value).status)
        assertEquals(MemoryEntity.Status.MERGED, snapshot.entityById(firstNamePerson.id.value).status)
        assertEquals(user.id, snapshot.claimById(skill.id.value).subjectEntityId)
        assertEquals(user.id, snapshot.claimById(taskContext.id.value).subjectEntityId)
        assertTrue(updatedUser.aliases.any { it.text == "Lev Lewik" })
        assertTrue(updatedUser.aliases.any { it.text == "Lev" })
        assertEquals(setOf(MemoryEntity.Type.USER, MemoryEntity.Type.PERSON), updatedUser.observedTypes)
    }

    @Test
    fun entityMaintenanceGroupsCurrentUserWhenIdentitySignalWasStoredOnNamedPerson() = runBlocking {
        val selfIdentitySource = source(
            id = "source-self-identity",
            text = "My name is Lev Lewik, and my preferred name is Lev.",
        )
        val user = entity(
            id = MemoryEntity.Id("entity-current-user"),
            entityType = MemoryEntity.Type.USER,
            canonicalName = "User",
            normalizedName = "user",
        )
        val fullNamePerson = entity(
            id = MemoryEntity.Id("entity-lev-lewik-person"),
            entityType = MemoryEntity.Type.PERSON,
            canonicalName = "Lev Lewik",
            normalizedName = "lev lewik",
        )
        val firstNamePerson = entity(
            id = MemoryEntity.Id("entity-lev-person"),
            entityType = MemoryEntity.Type.PERSON,
            canonicalName = "Lev",
            normalizedName = "lev",
        )
        val preferredNameOnPerson = claim(
            id = "claim-person-preferred-name",
            sourceId = selfIdentitySource.id.value,
            subjectEntityId = fullNamePerson.id,
            predicate = "preferred_name",
            objectValue = JsonPrimitive("Lev"),
            normalizedText = "Lev Lewik's preferred name is Lev.",
        )
        val skill = claim(
            id = "claim-lev-kotlin",
            sourceId = selfIdentitySource.id.value,
            subjectEntityId = fullNamePerson.id,
            predicate = "works_with",
            objectValue = JsonPrimitive("Kotlin"),
            normalizedText = "Lev Lewik works with Kotlin.",
        )
        val taskContext = claim(
            id = "claim-lev-memory",
            sourceId = "source-task-context",
            subjectEntityId = firstNamePerson.id,
            predicate = "responsible_for",
            objectValue = JsonPrimitive("memory pipeline repair"),
            normalizedText = "Lev is responsible for memory pipeline repair.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(
                    selfIdentitySource,
                    source("source-task-context", "Lev is responsible for memory pipeline repair."),
                ),
                entities = listOf(user, fullNamePerson, firstNamePerson),
                claims = listOf(preferredNameOnPerson, skill, taskContext),
            )
        )

        val result = MemoryEntityMaintenancePipeline(
            store = store,
            planner = FixedEntityMaintenancePlanner(
                MemoryEntityMaintenancePlan(
                    actions = listOf(
                        MemoryEntityMaintenancePlan.Action(
                            action = MemoryEntityMaintenancePlan.Action.Type.MERGE,
                            winnerEntityId = user.id.value,
                            loserEntityIds = listOf(fullNamePerson.id.value, firstNamePerson.id.value),
                            aliasTexts = listOf("Lev Lewik", "Lev"),
                            reason = "Self-identity evidence shows the named PERSON entities are the current user.",
                        )
                    ),
                    summary = "Merged current user identity split.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("entity-maintenance"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)
        val updatedUser = snapshot.entityById(user.id.value)

        assertEquals(1, result.candidateGroups.size)
        assertTrue(result.candidateGroups.single().reason.contains("current user"))
        assertEquals(MemoryEntity.Status.ACTIVE, updatedUser.status)
        assertEquals(MemoryEntity.Status.MERGED, snapshot.entityById(fullNamePerson.id.value).status)
        assertEquals(MemoryEntity.Status.MERGED, snapshot.entityById(firstNamePerson.id.value).status)
        assertEquals(user.id, snapshot.claimById(preferredNameOnPerson.id.value).subjectEntityId)
        assertEquals(user.id, snapshot.claimById(skill.id.value).subjectEntityId)
        assertEquals(user.id, snapshot.claimById(taskContext.id.value).subjectEntityId)
        assertTrue(updatedUser.aliases.any { it.text == "Lev Lewik" })
        assertTrue(updatedUser.aliases.any { it.text == "Lev" })
    }

    @Test
    fun entityMaintenanceDoesNotGroupCurrentUserWithWeakFirstNameOnly() = runBlocking {
        val user = entity(
            id = MemoryEntity.Id("entity-current-user"),
            entityType = MemoryEntity.Type.USER,
            canonicalName = "User",
            normalizedName = "user",
            aliases = listOf(alias("Lev Lewik")),
        )
        val otherLev = entity(
            id = MemoryEntity.Id("entity-other-lev"),
            entityType = MemoryEntity.Type.PERSON,
            canonicalName = "Lev",
            normalizedName = "lev",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                entities = listOf(user, otherLev),
            )
        )

        val result = MemoryEntityMaintenancePipeline(
            store = store,
            planner = FixedEntityMaintenancePlanner(MemoryEntityMaintenancePlan(summary = "No action.")),
            idFactory = SequentialMemoryIdFactory("entity-maintenance"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        assertEquals(0, result.candidateGroups.size)
    }

    @Test
    fun entityMaintenanceCanMergeSameNameAcrossTypeFamiliesWhenPlannerConfirmsMisclassification() = runBlocking {
        val person = entity(
            id = MemoryEntity.Id("entity-mira-person"),
            entityType = MemoryEntity.Type.PERSON,
            canonicalName = "Mira",
            normalizedName = "mira",
            summary = "Person named Mira.",
        )
        val service = entity(
            id = MemoryEntity.Id("entity-mira-service"),
            entityType = MemoryEntity.Type.SERVICE,
            canonicalName = "Mira",
            normalizedName = "mira",
            summary = "Misclassified service emitted for Mira.",
        )
        val claim = claim(
            id = "claim-mira",
            subjectEntityId = service.id,
            predicate = "owns_component",
            normalizedText = "Mira owns the memory ingestion pipeline.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                entities = listOf(person, service),
                claims = listOf(claim),
            )
        )

        val result = MemoryEntityMaintenancePipeline(
            store = store,
            planner = FixedEntityMaintenancePlanner(
                MemoryEntityMaintenancePlan(
                    actions = listOf(
                        MemoryEntityMaintenancePlan.Action(
                            action = MemoryEntityMaintenancePlan.Action.Type.MERGE,
                            winnerEntityId = person.id.value,
                            loserEntityIds = listOf(service.id.value),
                            reason = "The service entity is a misclassified duplicate of the person.",
                        )
                    ),
                    summary = "Merged cross-family same-name misclassification.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("entity-maintenance"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)
        val updatedPerson = snapshot.entityById(person.id.value)

        assertEquals(1, result.candidateGroups.size)
        assertTrue(result.candidateGroups.single().reason.contains("across entity types"))
        assertEquals(MemoryEntity.Status.ACTIVE, updatedPerson.status)
        assertEquals(MemoryEntity.Status.MERGED, snapshot.entityById(service.id.value).status)
        assertEquals(person.id, snapshot.entityById(service.id.value).mergedIntoEntityId)
        assertEquals(person.id, snapshot.claimById(claim.id.value).subjectEntityId)
        assertEquals(setOf(MemoryEntity.Type.PERSON, MemoryEntity.Type.SERVICE), updatedPerson.observedTypes)
    }

    @Test
    fun entityMaintenanceMergesCompatibleTechnicalTypesAndPreservesObservedTypes() = runBlocking {
        val winner = entity(
            id = MemoryEntity.Id("entity-convention-plugins-tech"),
            entityType = MemoryEntity.Type.TECHNOLOGY,
            canonicalName = "convention-plugins",
            normalizedName = "convention plugins",
        )
        val loser = entity(
            id = MemoryEntity.Id("entity-convention-plugins-concept"),
            entityType = MemoryEntity.Type.CONCEPT,
            canonicalName = "Convention Plugins",
            normalizedName = "convention plugins",
        )
        val claim = claim(
            id = "claim-convention-plugins",
            subjectEntityId = loser.id,
            predicate = "has_constraint",
            normalizedText = "Convention plugins should keep shared Gradle build logic centralized.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                entities = listOf(winner, loser),
                claims = listOf(claim),
            )
        )

        val result = MemoryEntityMaintenancePipeline(
            store = store,
            planner = FixedEntityMaintenancePlanner(
                MemoryEntityMaintenancePlan(
                    actions = listOf(
                        MemoryEntityMaintenancePlan.Action(
                            action = MemoryEntityMaintenancePlan.Action.Type.MERGE,
                            winnerEntityId = winner.id.value,
                            loserEntityIds = listOf(loser.id.value),
                            aliasTexts = listOf("Convention Plugins"),
                            reason = "Both entities name the same Gradle convention plugins concept.",
                        )
                    ),
                    summary = "Merged duplicate convention plugins entities.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("entity-maintenance"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)
        val updatedWinner = snapshot.entityById(winner.id.value)

        assertEquals(1, result.candidateGroups.size)
        assertTrue(result.candidateGroups.single().entities.map { it.id }.containsAll(listOf(winner.id, loser.id)))
        assertEquals(MemoryEntity.Status.ACTIVE, updatedWinner.status)
        assertEquals(MemoryEntity.Status.MERGED, snapshot.entityById(loser.id.value).status)
        assertEquals(winner.id, snapshot.entityById(loser.id.value).mergedIntoEntityId)
        assertEquals(winner.id, snapshot.claimById(claim.id.value).subjectEntityId)
        assertEquals(setOf(MemoryEntity.Type.TECHNOLOGY, MemoryEntity.Type.CONCEPT), updatedWinner.observedTypes)
    }

    @Test
    fun entityMaintenanceMergesFileDocumentArtifactsAndPreservesObservedTypes() = runBlocking {
        val file = entity(
            id = MemoryEntity.Id("entity-readme-file"),
            entityType = MemoryEntity.Type.FILE,
            canonicalName = "README.md",
            normalizedName = "readme md",
        )
        val document = entity(
            id = MemoryEntity.Id("entity-readme-document"),
            entityType = MemoryEntity.Type.DOCUMENT,
            canonicalName = "README.md",
            normalizedName = "readme md",
        )
        val claim = claim(
            id = "claim-readme",
            subjectEntityId = document.id,
            predicate = "has_constraint",
            normalizedText = "README.md documents the local development workflow.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                entities = listOf(file, document),
                claims = listOf(claim),
            )
        )

        val result = MemoryEntityMaintenancePipeline(
            store = store,
            planner = FixedEntityMaintenancePlanner(
                MemoryEntityMaintenancePlan(
                    actions = listOf(
                        MemoryEntityMaintenancePlan.Action(
                            action = MemoryEntityMaintenancePlan.Action.Type.MERGE,
                            winnerEntityId = file.id.value,
                            loserEntityIds = listOf(document.id.value),
                            aliasTexts = listOf("README.md"),
                            reason = "Both entities identify the same stored markdown artifact.",
                        )
                    ),
                    summary = "Merged duplicate README artifact entities.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("entity-maintenance"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)
        val updatedFile = snapshot.entityById(file.id.value)

        assertEquals(1, result.candidateGroups.size)
        assertTrue(result.candidateGroups.single().entities.map { it.id }.containsAll(listOf(file.id, document.id)))
        assertEquals(MemoryEntity.Status.ACTIVE, updatedFile.status)
        assertEquals(MemoryEntity.Status.MERGED, snapshot.entityById(document.id.value).status)
        assertEquals(file.id, snapshot.entityById(document.id.value).mergedIntoEntityId)
        assertEquals(file.id, snapshot.claimById(claim.id.value).subjectEntityId)
        assertEquals(setOf(MemoryEntity.Type.FILE, MemoryEntity.Type.DOCUMENT), updatedFile.observedTypes)
    }

    @Test
    fun entityMaintenanceKeepsIncompatibleSameNameEntityTypesSeparate() = runBlocking {
        val project = entity(
            id = MemoryEntity.Id("entity-gromozeka-project"),
            entityType = MemoryEntity.Type.PROJECT,
            canonicalName = "Gromozeka",
            normalizedName = "gromozeka",
        )
        val document = entity(
            id = MemoryEntity.Id("entity-gromozeka-document"),
            entityType = MemoryEntity.Type.DOCUMENT,
            canonicalName = "Gromozeka",
            normalizedName = "gromozeka",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                entities = listOf(project, document),
            )
        )

        val result = MemoryEntityMaintenancePipeline(
            store = store,
            planner = FixedEntityMaintenancePlanner(MemoryEntityMaintenancePlan(summary = "No action.")),
            idFactory = SequentialMemoryIdFactory("entity-maintenance"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertEquals(1, result.candidateGroups.size)
        assertTrue(result.candidateGroups.single().reason.contains("across entity types"))
        assertEquals(MemoryEntity.Status.ACTIVE, snapshot.entityById(project.id.value).status)
        assertEquals(MemoryEntity.Status.ACTIVE, snapshot.entityById(document.id.value).status)
    }

    @Test
    fun entityMaintenanceBatchesLargeCandidateGroupSets() = runBlocking {
        val entities = (1..9).flatMap { index ->
            listOf(
                entity(
                    id = MemoryEntity.Id("entity-tool-$index-technology"),
                    entityType = MemoryEntity.Type.TECHNOLOGY,
                    canonicalName = "Tool $index",
                    normalizedName = "tool $index",
                ),
                entity(
                    id = MemoryEntity.Id("entity-tool-$index-concept"),
                    entityType = MemoryEntity.Type.CONCEPT,
                    canonicalName = "Tool $index",
                    normalizedName = "tool $index",
                ),
            )
        }
        val store = InMemoryMemoryStore(MemoryNamespaceSnapshot(entities = entities))
        val planner = CapturingEntityMaintenancePlanner()

        val result = MemoryEntityMaintenancePipeline(
            store = store,
            planner = planner,
            idFactory = SequentialMemoryIdFactory("entity-maintenance"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        assertEquals(9, result.candidateGroups.size)
        assertEquals(listOf(8, 1), planner.batchSizes)
        assertEquals(0, result.maintenancePlan.actions.size)
    }

    @Test
    fun entityMaintenanceRefreshesSummaryAfterReplacementClaim() = runBlocking {
        val pantryPilot = entity(
            id = MemoryEntity.Id("entity-pantry-pilot"),
            entityType = MemoryEntity.Type.PROJECT,
            canonicalName = "PantryPilot",
            normalizedName = "pantrypilot",
            summary = "A fictional card or project context in which the primary format for the weekly report is PDF.",
        )
        val pdf = entity(
            id = MemoryEntity.Id("entity-pdf"),
            entityType = MemoryEntity.Type.TECHNOLOGY,
            canonicalName = "PDF",
            normalizedName = "pdf",
        )
        val xlsx = entity(
            id = MemoryEntity.Id("entity-xlsx"),
            entityType = MemoryEntity.Type.TECHNOLOGY,
            canonicalName = "XLSX",
            normalizedName = "xlsx",
        )
        val oldClaim = claim(
            id = "claim-pantry-pilot-pdf",
            subjectEntityId = pantryPilot.id,
            predicate = "primary_export_format",
            objectEntityId = pdf.id,
            objectValue = null,
            normalizedText = "The current primary format for PantryPilot's weekly report is PDF.",
            status = MemoryClaim.Status.SUPERSEDED,
        )
        val activeClaim = claim(
            id = "claim-pantry-pilot-xlsx",
            subjectEntityId = pantryPilot.id,
            predicate = "primary_export_format",
            objectEntityId = xlsx.id,
            objectValue = null,
            normalizedText = "The current primary format for PantryPilot's weekly report is XLSX.",
        ).copy(supersedesClaimId = oldClaim.id)
        val claimLikeSummary = "PantryPilot is a fictional project whose current weekly report format is XLSX."
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                entities = listOf(pantryPilot, pdf, xlsx),
                claims = listOf(oldClaim, activeClaim),
            )
        )

        val result = MemoryEntityMaintenancePipeline(
            store = store,
            planner = FixedEntityMaintenancePlanner(
                MemoryEntityMaintenancePlan(
                    actions = listOf(
                        MemoryEntityMaintenancePlan.Action(
                            action = MemoryEntityMaintenancePlan.Action.Type.UPDATE_SUMMARY,
                            targetEntityIds = listOf(pantryPilot.id.value),
                            summaryText = claimLikeSummary,
                            reason = "Active replacement claim changed the current report format from PDF to XLSX.",
                        )
                    ),
                    summary = "Refreshed stale entity summary.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("entity-maintenance"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertEquals(1, result.candidateGroups.size)
        assertEquals(listOf(pantryPilot.id), result.candidateGroups.single().entities.map { it.id })
        assertTrue(result.candidateGroups.single().reason.contains("summary may be stale"))
        assertEquals("Project named PantryPilot.", snapshot.entityById(pantryPilot.id.value).summary)
        assertEquals("Technology named PDF.", snapshot.entityById(pdf.id.value).summary)
        assertEquals("Technology named XLSX.", snapshot.entityById(xlsx.id.value).summary)
        assertEquals(NOW, snapshot.entityById(pantryPilot.id.value).updatedAt)
        assertTrue(snapshot.runs.any { run ->
            run.runType == MemoryRun.Type.MAINTAIN_ENTITIES &&
                run.appliedOps.toString().contains("normalize_entity_identity_summary")
        })
    }

    @Test
    fun entityMaintenanceNormalizesAllActiveEntitySummariesWithoutCandidateGroups() = runBlocking {
        val bookNest = entity(
            id = MemoryEntity.Id("entity-book-nest"),
            entityType = MemoryEntity.Type.PROJECT,
            canonicalName = "BookNest",
            normalizedName = "booknest",
            summary = "A project whose current primary export format is CSV.",
        )
        val user = entity(
            id = MemoryEntity.Id("entity-user-memory"),
            entityType = MemoryEntity.Type.USER,
            canonicalName = "User",
            normalizedName = "user",
            summary = "The human user who currently prefers Toyota and iPad.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                entities = listOf(bookNest, user),
            )
        )

        val result = MemoryEntityMaintenancePipeline(
            store = store,
            planner = FixedEntityMaintenancePlanner(MemoryEntityMaintenancePlan(summary = "No action.")),
            idFactory = SequentialMemoryIdFactory("entity-maintenance"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertEquals(0, result.candidateGroups.size)
        assertEquals("Project named BookNest.", snapshot.entityById(bookNest.id.value).summary)
        assertEquals("The user interacting with this agent.", snapshot.entityById(user.id.value).summary)
        assertTrue(snapshot.runs.any { run ->
            run.runType == MemoryRun.Type.MAINTAIN_ENTITIES &&
                run.appliedOps.toString().contains("normalize_entity_identity_summary")
        })
    }

    @Test
    fun entityMaintenanceUsesEmbeddingsToFindHiddenDuplicateCandidates() = runBlocking {
        val first = entity(
            id = MemoryEntity.Id("entity-mercury-planner"),
            entityType = MemoryEntity.Type.CONCEPT,
            canonicalName = "Mercury Planner",
            normalizedName = "mercury planner",
            summary = "Concept for planning meetings from short agenda notes.",
        )
        val second = entity(
            id = MemoryEntity.Id("entity-quick-agenda-flow"),
            entityType = MemoryEntity.Type.CONCEPT,
            canonicalName = "Quick Agenda Flow",
            normalizedName = "quick agenda flow",
            summary = "Concept for planning meetings from short agenda notes.",
        )
        val delegate = InMemoryMemoryStore(MemoryNamespaceSnapshot(entities = listOf(first, second)))
        val store = SearchInterceptingMemoryStore(delegate) { request ->
            when {
                request.scopes == setOf(MemoryStore.SearchScope.ENTITIES) && request.query.contains("Mercury Planner") ->
                    listOf(MemoryStore.SearchHit.EntityHit(second, score = 0.92))

                request.scopes == setOf(MemoryStore.SearchScope.ENTITIES) && request.query.contains("Quick Agenda Flow") ->
                    listOf(MemoryStore.SearchHit.EntityHit(first, score = 0.92))

                else -> delegate.search(request)
            }
        }
        val embeddingIndexer = FixedSearchEmbeddingIndexer()

        val result = MemoryEntityMaintenancePipeline(
            store = store,
            planner = FixedEntityMaintenancePlanner(MemoryEntityMaintenancePlan(summary = "No action.")),
            idFactory = SequentialMemoryIdFactory("entity-maintenance"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            embeddingIndexer = embeddingIndexer,
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        assertEquals(1, result.candidateGroups.size)
        assertEquals(setOf(first.id, second.id), result.candidateGroups.single().entities.map { it.id }.toSet())
        assertTrue(result.candidateGroups.single().reason.contains("embedding-near"))
        assertTrue(store.searchRequests.any { it.embedding != null && it.scopes == setOf(MemoryStore.SearchScope.ENTITIES) })
        assertTrue(embeddingIndexer.queries.any { it.contains("Mercury Planner") })
    }

    @Test
    fun noteConsolidationMaterializesEpisodeAndConsolidatesOriginNote() = runBlocking {
        val source = source("lesson-source", "We tried lexical heuristics for recall and replaced them with a short LLM verification call.")
        val originNote = note(
            id = "note-lesson",
            title = "Recall planner fallback lesson",
            summary = "Lexical fallback heuristics were brittle; a short LLM verification is a better guard.",
            noteType = MemoryNote.Type.LESSON,
            maturity = MemoryNote.Maturity.STABILIZING,
            confidence = 0.85,
            importance = 9,
            evidenceRefs = listOf(evidenceRef(source.id.value, source.contentText)),
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(source),
                entities = listOf(entity()),
                notes = listOf(originNote),
            )
        )

        val result = MemoryNoteConsolidationPipeline(
            store = store,
            consolidator = FixedNoteConsolidator(
                NoteConsolidationResult(
                    episodeCandidates = listOf(
                        MemoryEpisodeCandidate(
                            ownerEntityId = USER_ENTITY_ID,
                            originNoteId = originNote.id,
                            situation = "Recall planner returned no-memory for ambiguous user questions.",
                            action = "Run a short LLM verification call instead of hard-coded substring heuristics.",
                            result = "The pipeline can recover recall intent without lexical hacks.",
                            lesson = "Use a cheap model check for ambiguous no-memory recall decisions instead of brittle text matching.",
                            tags = listOf("memory", "recall", "planner"),
                            successScore = 0.8,
                            reason = "Reusable implementation lesson.",
                        )
                    ),
                    summary = "Materialized one reusable recall lesson.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("episode"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)
        val episode = snapshot.episodes.single()
        val updatedNote = snapshot.noteById(originNote.id.value)

        assertEquals(1, result.memoryBatch.episodes.size)
        assertEquals(USER_ENTITY_ID, episode.ownerEntityId)
        assertEquals(originNote.id, episode.originNoteId)
        assertEquals(MemoryEvidenceRef.Kind.DERIVED_FROM_NOTE, episode.evidenceRefs.single().kind)
        assertEquals(MemoryNote.Status.RESOLVED, updatedNote.status)
        assertEquals(MemoryNote.Maturity.CONSOLIDATED, updatedNote.maturity)
        assertTrue(snapshot.runs.any { it.runType == MemoryRun.Type.CONSOLIDATE_NOTES })
    }

    @Test
    fun noteConsolidationUsesEmbeddingsForRelatedContext() = runBlocking {
        val originNote = note(
            id = "note-origin",
            title = "Audio pipeline lesson",
            summary = "Local transcription should keep bounded rolling context when stabilizing speech drafts.",
            maturity = MemoryNote.Maturity.STABILIZING,
            confidence = 0.8,
            importance = 9,
        )
        val semanticNeighbor = note(
            id = "note-semantic-neighbor",
            title = "Live interpreter draft stabilization",
            summary = "The live interpreter keeps unstable draft text separate until the stabilizer commits final deltas.",
        )
        val delegate = InMemoryMemoryStore(MemoryNamespaceSnapshot(notes = listOf(originNote)))
        val store = SearchInterceptingMemoryStore(delegate) { request ->
            when {
                request.scopes.contains(MemoryStore.SearchScope.NOTES) && request.embedding != null ->
                    listOf(MemoryStore.SearchHit.NoteHit(semanticNeighbor, score = 0.88))

                else -> delegate.search(request)
            }
        }
        val capturedRelatedHits = mutableListOf<MemoryStore.SearchHit>()

        MemoryNoteConsolidationPipeline(
            store = store,
            consolidator = object : MemoryNoteConsolidator {
                override suspend fun consolidate(
                    request: MemoryMaintenanceRequest,
                    selectedNotes: List<MemoryNote>,
                    relatedHits: List<MemoryStore.SearchHit>,
                    snapshot: MemoryNamespaceSnapshot,
                ): NoteConsolidationResult {
                    capturedRelatedHits += relatedHits
                    return NoteConsolidationResult(summary = "No action.")
                }
            },
            idFactory = SequentialMemoryIdFactory("note-consolidation"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            embeddingIndexer = FixedSearchEmbeddingIndexer(),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        assertTrue(capturedRelatedHits.any { it is MemoryStore.SearchHit.NoteHit && it.note.id == semanticNeighbor.id })
        assertTrue(store.searchRequests.any { it.embedding != null && it.scopes.contains(MemoryStore.SearchScope.NOTES) })
    }

    @Test
    fun noteConsolidationDedupGuardDropsDuplicateClaimAndConsolidatesOriginNote() = runBlocking {
        val source = source("duplicate-claim-source", "Repeatable preference note: the user prefers Toyota.")
        val originNote = note(
            id = "note-duplicate-toyota",
            title = "Toyota preference note",
            summary = "The user prefers Toyota.",
            noteType = MemoryNote.Type.CONTEXT,
            confidence = 0.9,
            importance = 9,
            evidenceRefs = listOf(evidenceRef(source.id.value, source.contentText)),
        )
        val existingClaim = claim(
            id = "claim-existing-toyota",
            sourceId = source.id.value,
            normalizedText = "The user prefers Toyota.",
        )
        val duplicateCandidate = MemoryClaimCandidate(
            subjectEntityId = USER_ENTITY_ID,
            predicate = "prefers",
            objectValue = JsonPrimitive("Toyota"),
            normalizedText = "The user prefers Toyota.",
            scope = MemoryScope.Global("User-level preference"),
            confidence = 0.9,
            importance = 8,
            originNoteId = originNote.id,
            reason = "Scripted duplicate claim candidate.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(source),
                entities = listOf(entity()),
                claims = listOf(existingClaim),
                notes = listOf(originNote),
            )
        )

        val result = MemoryNoteConsolidationPipeline(
            store = store,
            consolidator = FixedNoteConsolidator(
                NoteConsolidationResult(
                    claimCandidates = listOf(duplicateCandidate),
                    summary = "Scripted duplicate claim consolidation.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("claim-dedup"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)
        val updatedNote = snapshot.noteById(originNote.id.value)

        assertEquals(1, result.rawConsolidationResult.claimCandidates.size)
        assertTrue(result.consolidationResult.claimCandidates.isEmpty())
        assertTrue(result.memoryBatch.claims.isEmpty())
        assertEquals(listOf(existingClaim.id), snapshot.claims.map { it.id })
        assertEquals(MemoryNote.Status.RESOLVED, updatedNote.status)
        assertEquals(MemoryNote.Maturity.CONSOLIDATED, updatedNote.maturity)
    }

    @Test
    fun noteConsolidationDedupGuardConvertsDuplicateTaskInsertToUpdate() = runBlocking {
        val source = source("duplicate-actionItem-source", "Follow-up note: add maintenance trace report to memory e2e.")
        val originNote = note(
            id = "note-duplicate-actionItem",
            title = "Maintenance trace follow-up",
            summary = "Add maintenance trace report to memory e2e.",
            noteType = MemoryNote.Type.DECISION,
            confidence = 0.9,
            importance = 9,
            evidenceRefs = listOf(evidenceRef(source.id.value, source.contentText)),
        )
        val existingTask = actionItem(
            id = "actionItem-maintenance-trace",
            status = MemoryActionItem.Status.OPEN,
        ).copy(
            title = "Add maintenance trace report to memory e2e",
            description = "Expose maintenance pipeline actions in the e2e report.",
            evidenceRefs = listOf(evidenceRef(source.id.value, source.contentText)),
        )
        val duplicateDraft = MemoryActionItemUpdateOp.Draft(
            title = existingTask.title,
            description = "Expose raw and final maintenance pipeline actions in the e2e report.",
            status = MemoryActionItem.Status.OPEN,
            scope = existingTask.scope,
            ownerEntityId = existingTask.ownerEntityId,
            originNoteId = originNote.id,
            confidence = 0.9,
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(source),
                entities = listOf(entity()),
                notes = listOf(originNote),
                actionItems = listOf(existingTask),
            )
        )

        val result = MemoryNoteConsolidationPipeline(
            store = store,
            consolidator = FixedNoteConsolidator(
                NoteConsolidationResult(
                    actionItemActions = listOf(
                        MemoryActionItemUpdateOp(
                            action = MemoryActionItemUpdateOp.Action.INSERT,
                            actionItem = duplicateDraft,
                            reason = "Scripted duplicate actionItem insert.",
                        )
                    ),
                    summary = "Scripted duplicate actionItem consolidation.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("actionItem-dedup"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertEquals(MemoryActionItemUpdateOp.Action.INSERT, result.rawConsolidationResult.actionItemActions.single().action)
        assertEquals(MemoryActionItemUpdateOp.Action.UPDATE, result.consolidationResult.actionItemActions.single().action)
        assertEquals(existingTask.id, result.consolidationResult.actionItemActions.single().targetActionItemId)
        assertEquals(listOf(existingTask.id), snapshot.actionItems.map { it.id })
        assertTrue(snapshot.taskById(existingTask.id.value).description!!.contains("raw and final maintenance"))
        assertEquals(MemoryNote.Maturity.CONSOLIDATED, snapshot.noteById(originNote.id.value).maturity)
    }

    @Test
    fun noteConsolidationDedupGuardDropsDuplicateEpisodeAndConsolidatesOriginNote() = runBlocking {
        val source = source("duplicate-episode-source", "Lesson note: use model verification instead of substring matching.")
        val originNote = note(
            id = "note-duplicate-episode",
            title = "Recall verifier lesson",
            summary = "Use model verification instead of substring matching.",
            noteType = MemoryNote.Type.LESSON,
            confidence = 0.9,
            importance = 9,
            evidenceRefs = listOf(evidenceRef(source.id.value, source.contentText)),
        )
        val existingEpisode = episode("episode-existing-verifier")
        val duplicateCandidate = MemoryEpisodeCandidate(
            ownerEntityId = existingEpisode.ownerEntityId,
            originNoteId = originNote.id,
            situation = existingEpisode.situation,
            action = existingEpisode.action,
            result = existingEpisode.result,
            lesson = existingEpisode.lesson,
            tags = existingEpisode.tags,
            reason = "Scripted duplicate episode candidate.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(source),
                entities = listOf(entity()),
                notes = listOf(originNote),
                episodes = listOf(existingEpisode),
            )
        )

        val result = MemoryNoteConsolidationPipeline(
            store = store,
            consolidator = FixedNoteConsolidator(
                NoteConsolidationResult(
                    episodeCandidates = listOf(duplicateCandidate),
                    summary = "Scripted duplicate episode consolidation.",
                )
            ),
            idFactory = SequentialMemoryIdFactory("episode-dedup"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            clock = FixedMemoryClock(NOW),
        ).run(MemoryMaintenanceRequest(TEST_NAMESPACE))

        val snapshot = store.loadNamespaceSnapshot(TEST_NAMESPACE, includeArchived = true)

        assertEquals(1, result.rawConsolidationResult.episodeCandidates.size)
        assertTrue(result.consolidationResult.episodeCandidates.isEmpty())
        assertTrue(result.memoryBatch.episodes.isEmpty())
        assertEquals(listOf(existingEpisode.id), snapshot.episodes.map { it.id })
        assertEquals(MemoryNote.Maturity.CONSOLIDATED, snapshot.noteById(originNote.id.value).maturity)
    }

    @Test
    fun runtimeReadRetrievesAndRendersEpisodes() = runBlocking {
        val episode = MemoryEpisode(
            id = MemoryEpisode.Id("episode-recall-lesson"),
            namespace = TEST_NAMESPACE,
            ownerEntityId = USER_ENTITY_ID,
            situation = "Recall planner returned no-memory for ambiguous user questions.",
            action = "Run a short LLM verification call instead of hard-coded substring heuristics.",
            result = "The pipeline recovered recall intent without lexical hacks.",
            lesson = "Use a cheap model check for ambiguous no-memory recall decisions instead of brittle text matching.",
            tags = listOf("memory", "recall", "planner"),
            successScore = 0.8,
            evidenceRefs = listOf(evidenceRef("lesson-source", "Short LLM verification replaced lexical fallback heuristics.")),
            createdAt = EARLIER,
            updatedAt = EARLIER,
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(source("lesson-source", "Short LLM verification replaced lexical fallback heuristics.")),
                entities = listOf(entity()),
                episodes = listOf(episode),
            )
        )
        val targetMessage = userMessage(
            id = "target-message",
            text = "What did we learn about recall planner fallback?",
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.RATIONALE,
                    retrievalBudget = MemoryRetrievalBudget(episodes = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.EPISODE,
                            why = "The user asks for a reusable implementation lesson.",
                            query = "recall planner fallback lesson",
                            topK = 1,
                        )
                    ),
                )
            ),
        ).read(
            MemoryReadRequest(
                namespace = TEST_NAMESPACE,
                threadContext = MemoryThreadContext(
                    conversationId = Conversation.Id("conversation"),
                    threadId = Conversation.Thread.Id("read-thread"),
                    targetMessageId = targetMessage.id,
                    messages = listOf(targetMessage),
                ),
            )
        )

        val prompt = assertNotNull(result.runtimePrompt)

        assertTrue(result.retrievedHits.map { it.toTestItemRef() }.contains(MemoryItemRef(MemoryItemRef.Type.EPISODE, episode.id.value)))
        assertTrue(prompt.contains("Retrieved episodes:"))
        assertTrue(prompt.contains(episode.situation))
        assertTrue(prompt.contains(episode.action))
        assertTrue(prompt.contains(episode.result))
        assertTrue(prompt.contains(episode.lesson))
        assertTrue(result.trace.selectedHits.any { it.ref == MemoryItemRef(MemoryItemRef.Type.EPISODE, episode.id.value) })
    }

    @Test
    fun runtimeReadSelectorFiltersCandidatesBeforeHydrationAndPromptComposition() = runBlocking {
        val verifierSource = source("verifier-source", "NoMemoryVerifier should use model-based verification instead of substring checks.")
        val hydratorSource = source("hydrator-source", "SourceHydrator should use bounded quote hydration for evidence fallback.")
        val verifierEpisode = MemoryEpisode(
            id = MemoryEpisode.Id("episode-no-memory-verifier"),
            namespace = TEST_NAMESPACE,
            ownerEntityId = USER_ENTITY_ID,
            situation = "ReadTimeRetrievalPlanner returned no-memory for ambiguous recall.",
            action = "Run a short model-based verification call.",
            result = "Recall intent was recovered without hard-coded substring matching.",
            lesson = "NoMemoryVerifier should use model-based verification for ambiguous no-memory recall decisions.",
            tags = listOf("memory", "recall"),
            evidenceRefs = listOf(evidenceRef(verifierSource.id.value, verifierSource.contentText)),
            createdAt = EARLIER,
            updatedAt = EARLIER,
        )
        val hydratorEpisode = MemoryEpisode(
            id = MemoryEpisode.Id("episode-source-hydrator"),
            namespace = TEST_NAMESPACE,
            ownerEntityId = USER_ENTITY_ID,
            situation = "Factual answers needed raw source evidence.",
            action = "Fetch a bounded set of source quotes.",
            result = "Prompt stayed grounded without source flooding.",
            lesson = "SourceHydrator should use bounded quote hydration for source evidence.",
            tags = listOf("memory", "source"),
            evidenceRefs = listOf(evidenceRef(hydratorSource.id.value, hydratorSource.contentText)),
            createdAt = EARLIER,
            updatedAt = EARLIER,
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(verifierSource, hydratorSource),
                entities = listOf(entity()),
                episodes = listOf(verifierEpisode, hydratorEpisode),
            )
        )
        val targetMessage = userMessage(
            id = "target-message",
            text = "What reusable lesson did we record for NoMemoryVerifier about ambiguous no-memory recall decisions?",
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.RATIONALE,
                    retrievalBudget = MemoryRetrievalBudget(sources = 2, episodes = 2),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.EPISODE,
                            why = "The user asks for a reusable lesson.",
                            query = "memory lesson",
                            topK = 2,
                        )
                    ),
                )
            ),
            selector = FixedReadSelector(
                selectedRefs = listOf(MemoryItemRef(MemoryItemRef.Type.EPISODE, verifierEpisode.id.value))
            ),
        ).read(
            MemoryReadRequest(
                namespace = TEST_NAMESPACE,
                threadContext = MemoryThreadContext(
                    conversationId = Conversation.Id("conversation"),
                    threadId = Conversation.Thread.Id("read-thread"),
                    targetMessageId = targetMessage.id,
                    messages = listOf(targetMessage),
                ),
            )
        )

        val prompt = assertNotNull(result.runtimePrompt)

        assertTrue(result.retrievedHits.map { it.toTestItemRef() }.contains(MemoryItemRef(MemoryItemRef.Type.EPISODE, verifierEpisode.id.value)))
        assertTrue(result.retrievedHits.map { it.toTestItemRef() }.contains(MemoryItemRef(MemoryItemRef.Type.SOURCE, verifierSource.id.value)))
        assertTrue(result.retrievedHits.map { it.toTestItemRef() }.none { it.id == hydratorEpisode.id.value })
        assertTrue(result.retrievedHits.map { it.toTestItemRef() }.none { it.id == hydratorSource.id.value })
        assertTrue(prompt.contains("NoMemoryVerifier"))
        assertTrue(!prompt.contains("SourceHydrator"))
        assertTrue(result.trace.searchSteps.any { it.stage == "selector" && it.rawCount >= 2 && it.selectedCount == 1 })
        assertTrue(result.trace.selectorDecisions.any { it.selected && it.ref.id == verifierEpisode.id.value })
        assertTrue(result.trace.selectorDecisions.any { !it.selected && it.ref.id == hydratorEpisode.id.value })
    }

    @Test
    fun runtimeReadKeepsExplicitSelectorPicksWhenSafetyHitsCompeteForBudget() = runBlocking {
        val directClaim = claim(
            id = "direct-date-claim",
            predicate = "current_metric_value",
            objectValue = JsonPrimitive("February 1"),
            normalizedText = "ACL's submission date was February 1st.",
            importance = 1,
        )
        val safetyClaims = (1..4).map { index ->
            claim(
                id = "safety-claim-$index",
                predicate = "has_goal",
                objectValue = JsonPrimitive("Sentiment analysis context $index"),
                normalizedText = "The user has related sentiment analysis context $index.",
                importance = 10 - index,
            )
        }
        val directRef = MemoryItemRef(MemoryItemRef.Type.CLAIM, directClaim.id.value)
        val safetyRefs = safetyClaims.map { MemoryItemRef(MemoryItemRef.Type.CLAIM, it.id.value) }
        val targetMessage = userMessage(
            id = "target-message",
            text = "When did I submit my research paper on sentiment analysis?",
        )

        val result = RuntimeMemoryReadPipeline(
            store = InMemoryMemoryStore(
                MemoryNamespaceSnapshot(
                    entities = listOf(entity()),
                    claims = listOf(directClaim) + safetyClaims,
                )
            ),
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(claims = 4),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "Retrieve the complete set of submission timing facts.",
                            query = "research paper sentiment analysis submission date",
                            topK = 5,
                        )
                    ),
                )
            ),
            selector = FixedReadSelectorWithSafetyHits(
                selectedRefs = listOf(directRef),
                safetyRefs = safetyRefs,
            ),
        ).read(
            MemoryReadRequest(
                namespace = TEST_NAMESPACE,
                threadContext = MemoryThreadContext(
                    conversationId = Conversation.Id("conversation"),
                    threadId = Conversation.Thread.Id("read-thread"),
                    targetMessageId = targetMessage.id,
                    messages = listOf(targetMessage),
                ),
            )
        )

        val refs = result.retrievedHits.map { it.toTestItemRef() }
        val prompt = assertNotNull(result.runtimePrompt)

        assertTrue(refs.contains(directRef))
        assertEquals(4, refs.count { it.type == MemoryItemRef.Type.CLAIM })
        assertTrue(safetyRefs.any { it in refs })
        assertTrue(prompt.contains("ACL's submission date was February 1st."))
    }

    @Test
    fun runtimeReadKeepsExplicitSelectorSourcesWhenTypedMemoryWouldPruneRawEvidence() = runBlocking {
        val originalPriceSource = source(
            id = "favorite-author-list-price-source",
            text = "The new book from the user's favorite author had a list price of $30 before the discount.",
        )
        val discountedPriceSource = source(
            id = "favorite-author-discount-price-source",
            text = "The user bought the favorite author's book for $24 after the discount.",
        )
        val discountClaim = claim(
            id = "favorite-author-discount-claim",
            sourceId = discountedPriceSource.id.value,
            predicate = "current_metric_value",
            objectValue = JsonPrimitive("$24"),
            normalizedText = "The user bought the favorite author's book for $24 after a discount.",
            importance = 9,
        )
        val originalSourceRef = MemoryItemRef(MemoryItemRef.Type.SOURCE, originalPriceSource.id.value)
        val discountedSourceRef = MemoryItemRef(MemoryItemRef.Type.SOURCE, discountedPriceSource.id.value)
        val discountClaimRef = MemoryItemRef(MemoryItemRef.Type.CLAIM, discountClaim.id.value)

        val result = RuntimeMemoryReadPipeline(
            store = InMemoryMemoryStore(
                MemoryNamespaceSnapshot(
                    sources = listOf(originalPriceSource, discountedPriceSource),
                    entities = listOf(entity()),
                    claims = listOf(discountClaim),
                )
            ),
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(sources = 2, claims = 1),
                    retrievalRequests = emptyList(),
                )
            ),
            selector = FixedReadSelector(
                selectedRefs = listOf(originalSourceRef, discountedSourceRef, discountClaimRef),
            ),
        ).read(
            memoryReadRequest(
                targetMessageId = "target-message",
                targetText = "What percentage discount did I get on the book from my favorite author?",
            )
        )

        val refs = result.retrievedHits.map { it.toTestItemRef() }
        val prompt = assertNotNull(result.runtimePrompt)

        assertTrue(refs.contains(originalSourceRef))
        assertTrue(refs.contains(discountedSourceRef))
        assertTrue(refs.contains(discountClaimRef))
        assertTrue(prompt.contains("$30 before the discount"))
        assertTrue(prompt.contains("$24 after a discount"))
    }

    @Test
    fun runtimeReadKeepsPreferredClaimPredicatesAfterCandidateMerging() = runBlocking {
        val metricPolicy = MemoryPredicateDefinition(
            predicate = "current_metric_value",
            namespace = TEST_NAMESPACE,
            objectKind = MemoryPredicateDefinition.ObjectValueKind.STRING,
            cardinality = MemoryPredicateDefinition.Cardinality.SINGLE,
            temporalPolicy = MemoryPredicateDefinition.TemporalPolicy.STATUS_LIKE,
            conflictPolicy = MemoryPredicateDefinition.ConflictPolicy.REPLACE,
            semanticKinds = setOf(MemoryPredicateDefinition.SemanticKind.AGGREGATE_VALUE),
            aggregateEffect = MemoryPredicateDefinition.AggregateEffect.SET_CURRENT_VALUE,
        )
        val goalClaim = claim(
            id = "goal-claim",
            sourceId = "goal-source",
            predicate = "has_goal",
            objectValue = JsonPrimitive("Improve benchmark"),
            normalizedText = "The user wants to improve the benchmark value.",
            importance = 9,
        )
        val eventClaim = claim(
            id = "event-claim",
            sourceId = "event-source",
            predicate = "attended_event",
            objectValue = JsonPrimitive("Historical benchmark event"),
            normalizedText = "The user reported a benchmark value during a historical event.",
            importance = 9,
        )
        val metricClaim = claim(
            id = "metric-claim",
            sourceId = "metric-source",
            predicate = metricPolicy.predicate,
            objectValue = JsonPrimitive("42"),
            normalizedText = "The user's current benchmark value is 42.",
            importance = 1,
        ).copy(
            predicateFamily = metricPolicy.predicate,
            predicatePolicy = metricPolicy,
        )
        val selector = CapturingReadSelector(
            selectedRefs = listOf(MemoryItemRef(MemoryItemRef.Type.CLAIM, metricClaim.id.value))
        )
        val targetMessage = userMessage(
            id = "target-message",
            text = "What is my current benchmark value?",
        )

        RuntimeMemoryReadPipeline(
            store = InMemoryMemoryStore(
                MemoryNamespaceSnapshot(
                    sources = listOf(
                        source("goal-source", goalClaim.normalizedText),
                        source("event-source", eventClaim.normalizedText),
                        source("metric-source", metricClaim.normalizedText),
                    ),
                    entities = listOf(entity()),
                    claims = listOf(goalClaim, eventClaim, metricClaim),
                )
            ),
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(claims = 3),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "Retrieve the current benchmark value.",
                            query = "current benchmark value",
                            topK = 3,
                            preferredClaimPredicates = listOf(metricPolicy.predicate),
                            deprioritizedClaimPredicates = listOf("has_goal", "attended_event"),
                        )
                    ),
                )
            ),
            selector = selector,
        ).read(
            MemoryReadRequest(
                namespace = TEST_NAMESPACE,
                threadContext = MemoryThreadContext(
                    conversationId = Conversation.Id("conversation"),
                    threadId = Conversation.Thread.Id("read-thread"),
                    targetMessageId = targetMessage.id,
                    messages = listOf(targetMessage),
                ),
            )
        )

        assertEquals(MemoryItemRef(MemoryItemRef.Type.CLAIM, metricClaim.id.value), selector.capturedRefs.first())
    }

    @Test
    fun runtimeReadDoesNotRestoreRejectedCoreProfilesAfterSelector() = runBlocking {
        val noisyProfile = profile(
            id = "profile-noisy-user",
            ownerEntityId = USER_ENTITY_ID,
            profileText = "Irrelevant ShelfLog profile noise should not be injected into PantryPilot answers.",
        )
        val source = source("pantry-pilot-xlsx-source", "PantryPilot weekly report format is currently XLSX.")
        val claim = claim(
            id = "pantry-pilot-xlsx-claim",
            sourceId = source.id.value,
            predicate = "weekly_report_format",
            objectValue = JsonPrimitive("XLSX"),
            normalizedText = "PantryPilot weekly report format is currently XLSX.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(source),
                entities = listOf(entity()),
                profiles = listOf(noisyProfile),
                claims = listOf(claim),
            )
        )
        val targetMessage = userMessage(
            id = "target-message",
            text = "What do we know about PantryPilot?",
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    coreBlocks = setOf(MemoryReadPlan.CoreBlock.PROFILE),
                    retrievalBudget = MemoryRetrievalBudget(claims = 1, sources = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "The user asks about a project.",
                            query = "PantryPilot",
                            topK = 1,
                        )
                    ),
                )
            ),
            selector = FixedReadSelector(
                selectedRefs = listOf(MemoryItemRef(MemoryItemRef.Type.CLAIM, claim.id.value))
            ),
        ).read(
            MemoryReadRequest(
                namespace = TEST_NAMESPACE,
                threadContext = MemoryThreadContext(
                    conversationId = Conversation.Id("conversation"),
                    threadId = Conversation.Thread.Id("read-thread"),
                    targetMessageId = targetMessage.id,
                    messages = listOf(targetMessage),
                ),
            )
        )

        val prompt = assertNotNull(result.runtimePrompt)
        val refs = result.retrievedHits.map { it.toTestItemRef() }

        assertTrue(refs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, claim.id.value)))
        assertTrue(refs.none { it == MemoryItemRef(MemoryItemRef.Type.PROFILE, noisyProfile.id.value) })
        assertTrue(prompt.contains("PantryPilot weekly report format is currently XLSX."))
        assertTrue(!prompt.contains(noisyProfile.profileText))
        assertTrue(
            result.trace.selectorDecisions.any {
                !it.selected && it.ref == MemoryItemRef(MemoryItemRef.Type.PROFILE, noisyProfile.id.value)
            }
        )
    }

    @Test
    fun runtimeReadScopesCoreAndTypedRetrievalToResolvedTargetEntityBeforeSelector() = runBlocking {
        val pantryPilotEntityId = MemoryEntity.Id("entity-pantry-pilot")
        val shelfLogEntityId = MemoryEntity.Id("entity-shelflog")
        val ipadEntityId = MemoryEntity.Id("entity-ipad")
        val pantrySource = source("pantry-pilot-xlsx-source", "PantryPilot weekly report format is currently XLSX.")
        val ipadSource = source("ipad-source", "The user prefers iPad for reading technical books.")
        val shelfLogSource = source("shelflog-source", "ShelfLog reports should start with a short summary.")
        val pantryClaim = claim(
            id = "pantry-pilot-xlsx-claim",
            sourceId = pantrySource.id.value,
            subjectEntityId = pantryPilotEntityId,
            predicate = "weekly_report_format",
            objectValue = JsonPrimitive("XLSX"),
            normalizedText = "PantryPilot weekly report format is currently XLSX.",
        )
        val ipadClaim = claim(
            id = "ipad-claim",
            sourceId = ipadSource.id.value,
            subjectEntityId = USER_ENTITY_ID,
            objectEntityId = ipadEntityId,
            objectValue = null,
            predicate = "prefers",
            normalizedText = "The user prefers iPad for reading technical books.",
        )
        val shelfLogNote = note(
            id = "shelflog-note",
            title = "ShelfLog report output preference",
            summary = "ShelfLog reports should start with a short summary.",
            anchorEntityId = shelfLogEntityId,
            entityRefs = listOf(MemoryNote.EntityRef(shelfLogEntityId, MemoryNote.EntityRef.Role.PRIMARY)),
            evidenceRefs = listOf(evidenceRef(shelfLogSource.id.value, shelfLogSource.contentText)),
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(pantrySource, ipadSource, shelfLogSource),
                entities = listOf(
                    entity(),
                    entity(
                        id = pantryPilotEntityId,
                        entityType = MemoryEntity.Type.PROJECT,
                        canonicalName = "PantryPilot",
                        normalizedName = "pantrypilot",
                    ),
                    entity(
                        id = shelfLogEntityId,
                        entityType = MemoryEntity.Type.PROJECT,
                        canonicalName = "ShelfLog",
                        normalizedName = "shelflog",
                    ),
                    entity(
                        id = ipadEntityId,
                        entityType = MemoryEntity.Type.TECHNOLOGY,
                        canonicalName = "iPad",
                        normalizedName = "ipad",
                    ),
                ),
                profiles = listOf(
                    profile(
                        id = "profile-pantry-pilot",
                        ownerEntityId = pantryPilotEntityId,
                        profileText = "Profile for PantryPilot. Stable facts: weekly report format is XLSX.",
                    ),
                    profile(
                        id = "profile-user",
                        ownerEntityId = USER_ENTITY_ID,
                        profileText = "Profile for User. Stable facts: the user prefers iPad.",
                    ),
                    profile(
                        id = "profile-shelflog",
                        ownerEntityId = shelfLogEntityId,
                        profileText = "Profile for ShelfLog. Stable facts: home book catalog.",
                    ),
                ),
                claims = listOf(pantryClaim, ipadClaim),
                notes = listOf(shelfLogNote),
            )
        )
        val selector = CapturingReadSelector(
            selectedRefs = listOf(MemoryItemRef(MemoryItemRef.Type.CLAIM, pantryClaim.id.value))
        )
        val targetMessage = userMessage(
            id = "target-message",
            text = "What do we know about PantryPilot?",
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    coreBlocks = setOf(MemoryReadPlan.CoreBlock.PROFILE),
                    retrievalBudget = MemoryRetrievalBudget(claims = 5, notes = 2),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "Retrieve project-specific facts about PantryPilot.",
                            query = "PantryPilot project product repository app facts",
                            topK = 5,
                        ),
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.NOTE,
                            why = "Retrieve any PantryPilot summary context.",
                            query = "PantryPilot summary context",
                            topK = 2,
                        )
                    ),
                )
            ),
            selector = selector,
        ).read(
            MemoryReadRequest(
                namespace = TEST_NAMESPACE,
                threadContext = MemoryThreadContext(
                    conversationId = Conversation.Id("conversation"),
                    threadId = Conversation.Thread.Id("read-thread"),
                    targetMessageId = targetMessage.id,
                    messages = listOf(targetMessage),
                ),
            )
        )

        val capturedRefs = selector.capturedRefs.toSet()

        assertTrue(capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.ENTITY, pantryPilotEntityId.value)))
        assertTrue(capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.PROFILE, "profile-pantry-pilot")))
        assertTrue(capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, pantryClaim.id.value)))
        assertTrue(capturedRefs.none { it == MemoryItemRef(MemoryItemRef.Type.PROFILE, "profile-user") })
        assertTrue(capturedRefs.none { it == MemoryItemRef(MemoryItemRef.Type.PROFILE, "profile-shelflog") })
        assertTrue(capturedRefs.none { it == MemoryItemRef(MemoryItemRef.Type.CLAIM, ipadClaim.id.value) })
        assertTrue(capturedRefs.none { it == MemoryItemRef(MemoryItemRef.Type.NOTE, shelfLogNote.id.value) })
        assertTrue(result.trace.searchSteps.any { it.stage == "target_entities" && it.selectedCount == 1 })
    }

    @Test
    fun runtimeReadLetsSelectorSeeMoreNoteCandidatesThanPlannerTopK() = runBlocking {
        val atlasBridgeEntityId = MemoryEntity.Id("entity-atlasbridge")
        val documentEntityId = MemoryEntity.Id("entity-atlasbridge-contract")
        val nonGoalsSource = source(
            "atlasbridge-nongoals-source",
            "AtlasBridge does not define memory extraction, vector search, or mobile UI layout.",
        )
        val rolloutSource = source(
            "atlasbridge-rollout-source",
            "AtlasBridge rollout rules: Phase 1 uses snapshot replay, shadow mode compares deltas, and rollback disables live delta streaming.",
        )
        val nonGoalsNote = note(
            id = "atlasbridge-nongoals-note",
            title = "AtlasBridge non-goals",
            summary = "AtlasBridge does not define memory extraction, vector search, or mobile UI layout.",
            noteType = MemoryNote.Type.DIRECTION,
            anchorEntityId = atlasBridgeEntityId,
            entityRefs = listOf(
                MemoryNote.EntityRef(atlasBridgeEntityId, MemoryNote.EntityRef.Role.PRIMARY),
                MemoryNote.EntityRef(documentEntityId, MemoryNote.EntityRef.Role.MENTIONED),
            ),
            evidenceRefs = listOf(evidenceRef(nonGoalsSource.id.value, nonGoalsSource.contentText)),
        )
        val rolloutNote = note(
            id = "atlasbridge-rollout-note",
            title = "AtlasBridge rollout rules",
            summary = "AtlasBridge rollout rules require snapshot replay first, shadow mode divergence checks, and rollback by disabling live delta streaming.",
            noteType = MemoryNote.Type.PLAN,
            anchorEntityId = atlasBridgeEntityId,
            entityRefs = listOf(
                MemoryNote.EntityRef(atlasBridgeEntityId, MemoryNote.EntityRef.Role.PRIMARY),
                MemoryNote.EntityRef(documentEntityId, MemoryNote.EntityRef.Role.MENTIONED),
            ),
            evidenceRefs = listOf(evidenceRef(rolloutSource.id.value, rolloutSource.contentText)),
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(nonGoalsSource, rolloutSource),
                entities = listOf(
                    entity(
                        id = atlasBridgeEntityId,
                        entityType = MemoryEntity.Type.PROJECT,
                        canonicalName = "AtlasBridge",
                        normalizedName = "atlasbridge",
                    ),
                    entity(
                        id = documentEntityId,
                        entityType = MemoryEntity.Type.DOCUMENT,
                        canonicalName = "AtlasBridge Runtime Memory Contract",
                        normalizedName = "atlasbridge runtime memory contract",
                    ),
                ),
                notes = listOf(nonGoalsNote, rolloutNote),
            )
        )
        val selector = CapturingReadSelector(
            selectedRefs = listOf(MemoryItemRef(MemoryItemRef.Type.NOTE, rolloutNote.id.value))
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(notes = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.NOTE,
                            why = "Retrieve rollout rules from the document digest.",
                            query = "AtlasBridge rollout rules contract notes",
                            topK = 1,
                        )
                    ),
                )
            ),
            selector = selector,
        ).read(memoryReadRequest("atlasbridge-rollout-read", "What are the AtlasBridge rollout rules?"))

        val capturedRefs = selector.capturedRefs.toSet()
        val retrievedNoteRefs = result.retrievedHits.map { it.toTestItemRef() }
        val prompt = assertNotNull(result.runtimePrompt)

        assertTrue(capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.NOTE, nonGoalsNote.id.value)))
        assertTrue(capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.NOTE, rolloutNote.id.value)))
        assertEquals(listOf(MemoryItemRef(MemoryItemRef.Type.NOTE, rolloutNote.id.value)), retrievedNoteRefs.filter { it.type == MemoryItemRef.Type.NOTE })
        assertTrue(prompt.contains("snapshot replay"))
        assertTrue(prompt.contains("rollback"))
        assertTrue(!prompt.contains("mobile UI layout"))
    }

    @Test
    fun runtimeReadKeepsProjectEntitiesSeparateAcrossBackToBackQuestions() = runBlocking {
        val pantryPilotEntityId = MemoryEntity.Id("entity-pantry-pilot")
        val shelfLogEntityId = MemoryEntity.Id("entity-shelflog")
        val xlsxEntityId = MemoryEntity.Id("entity-xlsx")
        val jsonLinesEntityId = MemoryEntity.Id("entity-json-lines")
        val readingStateEntityId = MemoryEntity.Id("entity-reading-state")
        val pantrySource = source("pantry-pilot-xlsx-source", "PantryPilot weekly report primary format is XLSX.")
        val shelfPurposeSource = source("shelflog-purpose-source", "ShelfLog is used to maintain a home book catalog.")
        val shelfExportSource = source("shelflog-json-lines-source", "ShelfLog primary export format is JSON Lines.")
        val shelfFieldSource = source("shelflog-reading-state-source", "ShelfLog reading status is stored in reading_state.")
        val pantryClaim = claim(
            id = "pantry-pilot-xlsx-claim",
            sourceId = pantrySource.id.value,
            subjectEntityId = pantryPilotEntityId,
            objectEntityId = xlsxEntityId,
            objectValue = null,
            predicate = "primary_export_format",
            normalizedText = "The current primary format for PantryPilot's weekly report is XLSX.",
        )
        val shelfPurposeClaim = claim(
            id = "shelflog-purpose-claim",
            sourceId = shelfPurposeSource.id.value,
            subjectEntityId = shelfLogEntityId,
            objectValue = null,
            predicate = "has_goal",
            normalizedText = "ShelfLog is used to maintain a home book catalog.",
        )
        val shelfExportClaim = claim(
            id = "shelflog-json-lines-claim",
            sourceId = shelfExportSource.id.value,
            subjectEntityId = shelfLogEntityId,
            objectEntityId = jsonLinesEntityId,
            objectValue = null,
            predicate = "primary_export_format",
            normalizedText = "The primary export format for ShelfLog is currently JSON Lines.",
        )
        val shelfFieldClaim = claim(
            id = "shelflog-reading-state-claim",
            sourceId = shelfFieldSource.id.value,
            subjectEntityId = shelfLogEntityId,
            objectEntityId = readingStateEntityId,
            objectValue = null,
            predicate = "reading_status_field_name",
            normalizedText = "In ShelfLog, reading status is stored in the field reading_state.",
        )
        val shelfLogNote = note(
            id = "shelflog-report-note",
            title = "ShelfLog report output preference",
            summary = "ShelfLog reports should start with a short summary.",
            anchorEntityId = shelfLogEntityId,
            entityRefs = listOf(MemoryNote.EntityRef(shelfLogEntityId, MemoryNote.EntityRef.Role.PRIMARY)),
            evidenceRefs = listOf(evidenceRef(shelfPurposeSource.id.value, shelfPurposeSource.contentText)),
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(pantrySource, shelfPurposeSource, shelfExportSource, shelfFieldSource),
                entities = listOf(
                    entity(),
                    entity(
                        id = pantryPilotEntityId,
                        entityType = MemoryEntity.Type.PROJECT,
                        canonicalName = "PantryPilot",
                        normalizedName = "pantrypilot",
                    ),
                    entity(
                        id = shelfLogEntityId,
                        entityType = MemoryEntity.Type.PROJECT,
                        canonicalName = "ShelfLog",
                        normalizedName = "shelflog",
                    ),
                    entity(
                        id = xlsxEntityId,
                        entityType = MemoryEntity.Type.TECHNOLOGY,
                        canonicalName = "XLSX",
                        normalizedName = "xlsx",
                    ),
                    entity(
                        id = jsonLinesEntityId,
                        entityType = MemoryEntity.Type.TECHNOLOGY,
                        canonicalName = "JSON Lines",
                        normalizedName = "json lines",
                    ),
                    entity(
                        id = readingStateEntityId,
                        entityType = MemoryEntity.Type.CONCEPT,
                        canonicalName = "reading_state",
                        normalizedName = "reading_state",
                    ),
                ),
                profiles = listOf(
                    profile(
                        id = "profile-pantry-pilot",
                        ownerEntityId = pantryPilotEntityId,
                        profileText = "Profile for PantryPilot. Stable facts: weekly report format is XLSX.",
                    ),
                    profile(
                        id = "profile-shelflog",
                        ownerEntityId = shelfLogEntityId,
                        profileText = "Profile for ShelfLog. Stable facts: home book catalog.",
                    ),
                ),
                claims = listOf(pantryClaim, shelfPurposeClaim, shelfExportClaim, shelfFieldClaim),
                notes = listOf(shelfLogNote),
            )
        )

        val pantrySelector = CapturingReadSelector(
            selectedRefs = listOf(
                MemoryItemRef(MemoryItemRef.Type.PROFILE, "profile-pantry-pilot"),
                MemoryItemRef(MemoryItemRef.Type.CLAIM, pantryClaim.id.value),
            )
        )
        val pantryResult = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(projectFactReadPlan("PantryPilot", claims = 4, notes = 2)),
            selector = pantrySelector,
        ).read(memoryReadRequest("pantry-target", "What do we know about PantryPilot?"))
        val pantryPrompt = assertNotNull(pantryResult.runtimePrompt)
        val pantryCandidateRefs = pantrySelector.capturedRefs.toSet()

        assertTrue(pantryCandidateRefs.contains(MemoryItemRef(MemoryItemRef.Type.ENTITY, pantryPilotEntityId.value)))
        assertTrue(pantryCandidateRefs.contains(MemoryItemRef(MemoryItemRef.Type.PROFILE, "profile-pantry-pilot")))
        assertTrue(pantryCandidateRefs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, pantryClaim.id.value)))
        assertTrue(pantryCandidateRefs.none { it.id == shelfLogEntityId.value })
        assertTrue(pantryCandidateRefs.none { it.id == "profile-shelflog" })
        assertTrue(pantryCandidateRefs.none { it.id == shelfPurposeClaim.id.value })
        assertTrue(pantryCandidateRefs.none { it.id == shelfExportClaim.id.value })
        assertTrue(pantryCandidateRefs.none { it.id == shelfFieldClaim.id.value })
        assertTrue(pantryCandidateRefs.none { it.id == shelfLogNote.id.value })
        assertTrue(pantryPrompt.contains("PantryPilot"))
        assertTrue(pantryPrompt.contains("XLSX"))
        assertTrue(!pantryPrompt.contains("ShelfLog"))
        assertTrue(!pantryPrompt.contains("JSON Lines"))
        assertTrue(!pantryPrompt.contains("reading_state"))
        assertTrue(pantryResult.trace.searchSteps.any { it.stage == "target_entities" && it.selectedCount == 1 })

        val shelfLogSelector = CapturingReadSelector(
            selectedRefs = listOf(
                MemoryItemRef(MemoryItemRef.Type.PROFILE, "profile-shelflog"),
                MemoryItemRef(MemoryItemRef.Type.CLAIM, shelfPurposeClaim.id.value),
                MemoryItemRef(MemoryItemRef.Type.CLAIM, shelfExportClaim.id.value),
                MemoryItemRef(MemoryItemRef.Type.CLAIM, shelfFieldClaim.id.value),
            )
        )
        val shelfLogResult = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(projectFactReadPlan("ShelfLog", claims = 4, notes = 2)),
            selector = shelfLogSelector,
        ).read(memoryReadRequest("shelflog-target", "What do we know about ShelfLog?"))
        val shelfLogPrompt = assertNotNull(shelfLogResult.runtimePrompt)
        val shelfLogCandidateRefs = shelfLogSelector.capturedRefs.toSet()

        assertTrue(shelfLogCandidateRefs.contains(MemoryItemRef(MemoryItemRef.Type.ENTITY, shelfLogEntityId.value)))
        assertTrue(shelfLogCandidateRefs.contains(MemoryItemRef(MemoryItemRef.Type.PROFILE, "profile-shelflog")))
        assertTrue(shelfLogCandidateRefs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, shelfPurposeClaim.id.value)))
        assertTrue(shelfLogCandidateRefs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, shelfExportClaim.id.value)))
        assertTrue(shelfLogCandidateRefs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, shelfFieldClaim.id.value)))
        assertTrue(shelfLogCandidateRefs.contains(MemoryItemRef(MemoryItemRef.Type.NOTE, shelfLogNote.id.value)))
        assertTrue(shelfLogCandidateRefs.none { it.id == pantryPilotEntityId.value })
        assertTrue(shelfLogCandidateRefs.none { it.id == "profile-pantry-pilot" })
        assertTrue(shelfLogCandidateRefs.none { it.id == pantryClaim.id.value })
        assertTrue(shelfLogPrompt.contains("ShelfLog"))
        assertTrue(shelfLogPrompt.contains("JSON Lines"))
        assertTrue(shelfLogPrompt.contains("reading_state"))
        assertTrue(!shelfLogPrompt.contains("PantryPilot"))
        assertTrue(!shelfLogPrompt.contains("weekly report"))
        assertTrue(!shelfLogPrompt.contains("XLSX"))
        assertTrue(shelfLogResult.trace.searchSteps.any { it.stage == "target_entities" && it.selectedCount == 1 })
    }

    @Test
    fun runtimeReadUsesSubjectTargetEntityInsteadOfMentionedOldValueEntity() = runBlocking {
        val bookNestEntityId = MemoryEntity.Id("entity-booknest")
        val shelfLogEntityId = MemoryEntity.Id("entity-shelflog")
        val csvEntityId = MemoryEntity.Id("entity-csv")
        val parquetEntityId = MemoryEntity.Id("entity-parquet")
        val bookNestSource = source("booknest-parquet-source", "BookNest primary export format is Parquet.")
        val shelfLogSource = source("shelflog-csv-source", "ShelfLog primary export format is CSV.")
        val bookNestClaim = claim(
            id = "booknest-parquet-claim",
            sourceId = bookNestSource.id.value,
            subjectEntityId = bookNestEntityId,
            objectEntityId = parquetEntityId,
            objectValue = null,
            predicate = "primary_export_format",
            normalizedText = "The current primary export format for BookNest is Parquet.",
        )
        val shelfLogClaim = claim(
            id = "shelflog-csv-claim",
            sourceId = shelfLogSource.id.value,
            subjectEntityId = shelfLogEntityId,
            objectEntityId = csvEntityId,
            objectValue = null,
            predicate = "primary_export_format",
            normalizedText = "The current primary export format for ShelfLog is CSV.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(bookNestSource, shelfLogSource),
                entities = listOf(
                    entity(
                        id = bookNestEntityId,
                        entityType = MemoryEntity.Type.PROJECT,
                        canonicalName = "BookNest",
                        normalizedName = "booknest",
                    ),
                    entity(
                        id = shelfLogEntityId,
                        entityType = MemoryEntity.Type.PROJECT,
                        canonicalName = "ShelfLog",
                        normalizedName = "shelflog",
                    ),
                    entity(
                        id = csvEntityId,
                        entityType = MemoryEntity.Type.TECHNOLOGY,
                        canonicalName = "CSV",
                        normalizedName = "csv",
                    ),
                    entity(
                        id = parquetEntityId,
                        entityType = MemoryEntity.Type.TECHNOLOGY,
                        canonicalName = "Parquet",
                        normalizedName = "parquet",
                    ),
                ),
                claims = listOf(bookNestClaim, shelfLogClaim),
            )
        )
        val selector = CapturingReadSelector(
            selectedRefs = listOf(MemoryItemRef(MemoryItemRef.Type.CLAIM, bookNestClaim.id.value))
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(claims = 3),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "Check whether the remembered old CSV value is still current for BookNest.",
                            query = "BookNest CSV export still current",
                            topK = 3,
                        )
                    ),
                )
            ),
            selector = selector,
        ).read(memoryReadRequest("booknest-target", "I remember BookNest exported CSV. Is that still current?"))

        val capturedRefs = selector.capturedRefs.toSet()
        val prompt = assertNotNull(result.runtimePrompt)

        assertTrue(capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.ENTITY, bookNestEntityId.value)))
        assertTrue(capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, bookNestClaim.id.value)))
        assertTrue(capturedRefs.none { it.id == csvEntityId.value })
        assertTrue(capturedRefs.none { it.id == shelfLogClaim.id.value })
        assertTrue(prompt.contains("BookNest"))
        assertTrue(prompt.contains("Parquet"))
        assertTrue(!prompt.contains("ShelfLog"))
    }

    @Test
    fun runtimeReadKeepsExplicitSourceEvidenceFallbackWhenActiveTypedFactExists() = runBlocking {
        val bookNestEntityId = MemoryEntity.Id("entity-booknest")
        val parquetEntityId = MemoryEntity.Id("entity-parquet")
        val activeSource = source("booknest-parquet-source", "BookNest primary export is now Parquet.")
        val unrelatedSource = source("pantrypilot-question-source", "I remember PantryPilot reports were PDF. Is that still current?")
        val activeClaim = claim(
            id = "booknest-parquet-claim",
            sourceId = activeSource.id.value,
            subjectEntityId = bookNestEntityId,
            objectEntityId = parquetEntityId,
            objectValue = null,
            predicate = "primary_export_format",
            normalizedText = "The current primary export format for BookNest is Parquet.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(unrelatedSource, activeSource),
                entities = listOf(
                    entity(
                        id = bookNestEntityId,
                        entityType = MemoryEntity.Type.PROJECT,
                        canonicalName = "BookNest",
                        normalizedName = "booknest",
                    ),
                    entity(
                        id = parquetEntityId,
                        entityType = MemoryEntity.Type.TECHNOLOGY,
                        canonicalName = "Parquet",
                        normalizedName = "parquet",
                    ),
                ),
                claims = listOf(activeClaim),
            )
        )
        val selector = CapturingReadSelector(
            selectedRefs = listOf(MemoryItemRef(MemoryItemRef.Type.CLAIM, activeClaim.id.value))
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    requireEvidenceFallback = true,
                    retrievalBudget = MemoryRetrievalBudget(claims = 1, sources = 2),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "Active typed fact should answer the question.",
                            query = "BookNest current export format",
                            topK = 1,
                        ),
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Raw source fallback should be deferred when typed memory already answers.",
                            query = "BookNest current export format evidence",
                            topK = 2,
                        )
                    ),
                )
            ),
            selector = selector,
        ).read(memoryReadRequest("booknest-source-target", "Is BookNest still on CSV or something else?"))

        val capturedRefs = selector.capturedRefs.toSet()
        val resultRefs = result.retrievedHits.map { it.toTestItemRef() }
        val prompt = assertNotNull(result.runtimePrompt)

        assertTrue(capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, activeClaim.id.value)))
        assertTrue(capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.SOURCE, activeSource.id.value)))
        assertTrue(resultRefs.contains(MemoryItemRef(MemoryItemRef.Type.SOURCE, activeSource.id.value)))
        assertTrue(resultRefs.none { it == MemoryItemRef(MemoryItemRef.Type.SOURCE, unrelatedSource.id.value) })
        assertTrue(prompt.contains("BookNest primary export is now Parquet."))
        assertTrue(!prompt.contains("PantryPilot"))
    }

    @Test
    fun runtimeReadKeepsExplicitSourceEvidenceFallbackDespiteBroadTypedFacts() = runBlocking {
        val transcriptSource = source(
            "orlando-dessert-source",
            """
            Past chat transcript:
            user: Please suggest some family-friendly activities to do in Orlando.
            assistant: ${"Orlando family activity recommendation. ".repeat(90)}
            user: Can you recommend any places to eat in Orlando that are family-friendly?
            assistant: ${"Family-friendly Orlando dining option. ".repeat(90)}
            user: Do you have any recommendations for a fun dessert spot that my family can check out after dinner?
            assistant: Here are some fun dessert spots:

            1. The Sugar Factory at Icon Park offers specialty drinks and giant milkshakes.
            2. Wondermade is a gourmet marshmallow shop near Orlando.
            3. Gideon's Bakehouse is a bakery at Disney Springs.
            ${"Other Orlando dessert and activity chatter. ".repeat(90)}
            """.trimIndent(),
            searchText = "Past Orlando chat about family activities, dining, dessert spots, and dessert crawl planning.",
        )
        val broadClaim = claim(
            id = "orlando-dessert-goal-claim",
            sourceId = transcriptSource.id.value,
            predicate = "has_goal",
            normalizedText = "The user wanted recommendations for a fun dessert spot in Orlando.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(transcriptSource),
                entities = listOf(entity()),
                claims = listOf(broadClaim),
            )
        )
        val sourceRef = MemoryItemRef(MemoryItemRef.Type.SOURCE, transcriptSource.id.value)
        val selector = CapturingReadSelector(selectedRefs = listOf(sourceRef))

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    requireEvidenceFallback = true,
                    retrievalBudget = MemoryRetrievalBudget(claims = 2, sources = 2),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "Typed memory may contain a direct reusable answer.",
                            query = "Orlando dessert shop giant milkshakes",
                            topK = 2,
                        ),
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Prior assistant recommendation may exist only in raw conversation evidence.",
                            query = "Orlando dessert shop giant milkshakes previous recommendation",
                            topK = 2,
                        )
                    ),
                )
            ),
            selector = selector,
        ).read(
            memoryReadRequest(
                "orlando-dessert-target",
                """
                LongMemEval recall target.
                Current date: 2023/05/30 (Tue) 09:10
                Question: I'm planning to revisit Orlando. I was wondering if you could remind me of that unique dessert shop with the giant milkshakes we talked about last time?
                """.trimIndent(),
            )
        )

        val prompt = assertNotNull(result.runtimePrompt)

        assertTrue(selector.capturedRefs.contains(sourceRef))
        assertTrue(result.retrievedHits.map { it.toTestItemRef() }.contains(sourceRef))
        assertTrue(prompt.contains("The Sugar Factory at Icon Park"), prompt)
    }

    @Test
    fun runtimeReadKeepsRawSourceCandidatesForCompleteSetRecall() = runBlocking {
        val sourceWithTypedClaim = source(
            "complete-set-typed-source",
            "The user needs to pick up the repaired camera from the service desk.",
        )
        val sourceOnlyItem = source(
            "complete-set-source-only",
            "The user also needs to return the spare tripod to the rental counter.",
        )
        val typedClaim = claim(
            id = "complete-set-typed-claim",
            sourceId = sourceWithTypedClaim.id.value,
            predicate = "has_goal",
            normalizedText = "The user needs to pick up the repaired camera from the service desk.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(sourceWithTypedClaim, sourceOnlyItem),
                entities = listOf(entity()),
                claims = listOf(typedClaim),
            )
        )
        val selector = CapturingReadSelector(
            selectedRefs = listOf(
                MemoryItemRef(MemoryItemRef.Type.CLAIM, typedClaim.id.value),
                MemoryItemRef(MemoryItemRef.Type.SOURCE, sourceOnlyItem.id.value),
            )
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    requireEvidenceFallback = true,
                    retrievalBudget = MemoryRetrievalBudget(claims = 2, sources = 2),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "One typed item may be part of the complete set.",
                            query = "pick up return user needs",
                            topK = 2,
                        ),
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Source-only items may be missing from typed memory.",
                            query = "pick up return user needs",
                            topK = 2,
                        )
                    ),
                )
            ),
            selector = selector,
        ).read(memoryReadRequest("complete-set-target", "How many errands do I need to pick up or return?"))

        val capturedRefs = selector.capturedRefs.toSet()
        val resultRefs = result.retrievedHits.map { it.toTestItemRef() }.toSet()
        val prompt = assertNotNull(result.runtimePrompt)

        assertTrue(capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, typedClaim.id.value)))
        assertTrue(capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.SOURCE, sourceOnlyItem.id.value)))
        assertTrue(resultRefs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, typedClaim.id.value)))
        assertTrue(resultRefs.contains(MemoryItemRef(MemoryItemRef.Type.SOURCE, sourceOnlyItem.id.value)))
        assertTrue(prompt.contains("Coverage mode: COMPLETE_SET"))
        assertTrue(prompt.contains("repaired camera"))
        assertTrue(prompt.contains("spare tripod"))
    }

    @Test
    fun runtimeTaskPromptLabelsTitleAndDoesNotHydrateEvidenceWithoutFallback() = runBlocking {
        val taskSource = source("actionItem-source", "Follow-up: add selector trace report to memory e2e.")
        val actionItem = actionItem("actionItem-selector-trace", status = MemoryActionItem.Status.OPEN).copy(
            title = "Add selector trace report to memory e2e",
            description = "Expose selector decisions in memory e2e reports.",
            evidenceRefs = listOf(evidenceRef(taskSource.id.value, taskSource.contentText)),
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(taskSource),
                entities = listOf(entity()),
                actionItems = listOf(actionItem),
            )
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.ACTION_ITEM,
                    requireEvidenceFallback = false,
                    retrievalBudget = MemoryRetrievalBudget(actionItems = 1, sources = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.ACTION_ITEM,
                            why = "The user asks for the open follow-up title.",
                            query = "memory e2e trace reporting follow-up",
                            topK = 1,
                        )
                    ),
                )
            ),
        ).read(memoryReadRequest("actionItem-title-target", "Which open follow-up exists for memory e2e trace reporting? Answer with the actionItem title."))

        val prompt = assertNotNull(result.runtimePrompt)
        val refs = result.retrievedHits.map { it.toTestItemRef() }

        assertTrue(refs.contains(MemoryItemRef(MemoryItemRef.Type.ACTION_ITEM, actionItem.id.value)))
        assertTrue(refs.none { it == MemoryItemRef(MemoryItemRef.Type.SOURCE, taskSource.id.value) })
        assertTrue(prompt.contains("title=\"Add selector trace report to memory e2e\""))
        assertTrue(prompt.contains("description=\"Expose selector decisions in memory e2e reports.\""))
        val evidenceSection = prompt
            .substringAfter("Retrieved evidence:\n")
            .lineSequence()
            .first { it.isNotBlank() }
            .trim()
        assertEquals("not requested for this context mode", evidenceSection)
    }

    @Test
    fun runtimePromptRendersClaimContextText() = runBlocking {
        val source = source(
            id = "routine-source",
            text = "The user wakes up at 7:00 AM. On Tuesdays and Thursdays they wake up 15 minutes earlier.",
        )
        val claim = claim(
            id = "routine-claim",
            sourceId = source.id.value,
            predicate = "morning_routine",
            objectValue = JsonPrimitive("Tuesday Thursday meditation"),
            normalizedText = "The user meditates on Tuesdays and Thursdays as part of their morning routine.",
            contextText = "On Tuesdays and Thursdays, the user wakes up 15 minutes earlier before meditating.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(source),
                entities = listOf(entity()),
                claims = listOf(claim),
            )
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(claims = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "Routine detail answers the target.",
                            query = "Tuesday Thursday wake up 15 minutes earlier meditation",
                            topK = 1,
                        )
                    ),
                )
            ),
            selector = PassthroughMemoryReadSelector,
        ).read(memoryReadRequest("claim-context-target", "What time do I wake up on Tuesdays and Thursdays?"))

        val prompt = assertNotNull(result.runtimePrompt)
        assertTrue(prompt.contains("The user meditates on Tuesdays and Thursdays as part of their morning routine."))
        assertTrue(prompt.contains("context=\"On Tuesdays and Thursdays, the user wakes up 15 minutes earlier before meditating.\""))
    }

    @Test
    fun runtimeReadSuppressesRawSourcesLinkedToSupersededMemory() = runBlocking {
        val oldSource = source("old-csv-source", "ShelfLog primary export is currently CSV.")
        val newSource = source("new-json-source", "ShelfLog primary export is currently JSON Lines instead of CSV.")
        val oldClaim = claim(
            id = "old-export-claim",
            sourceId = oldSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("CSV"),
            normalizedText = "The primary export format for ShelfLog is currently CSV.",
            status = MemoryClaim.Status.SUPERSEDED,
        )
        val activeClaim = claim(
            id = "active-export-claim",
            sourceId = newSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("JSON Lines"),
            normalizedText = "The primary export format for ShelfLog is currently JSON Lines.",
            status = MemoryClaim.Status.ACTIVE,
        ).copy(supersedesClaimId = oldClaim.id)
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(oldSource, newSource),
                entities = listOf(entity()),
                claims = listOf(oldClaim, activeClaim),
            )
        )
        val targetMessage = userMessage(
            id = "target-message",
            text = "What is the current primary export format for ShelfLog?",
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    requireEvidenceFallback = true,
                    retrievalBudget = MemoryRetrievalBudget(claims = 2, sources = 2),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "Current primary export format is a typed fact.",
                            query = "ShelfLog primary export format",
                            topK = 2,
                        ),
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Evidence fallback should not resurrect superseded source wording.",
                            query = "ShelfLog primary export format",
                            topK = 2,
                        )
                    ),
                )
            ),
        ).read(
            MemoryReadRequest(
                namespace = TEST_NAMESPACE,
                threadContext = MemoryThreadContext(
                    conversationId = Conversation.Id("conversation"),
                    threadId = Conversation.Thread.Id("read-thread"),
                    targetMessageId = targetMessage.id,
                    messages = listOf(targetMessage),
                ),
            )
        )

        val prompt = assertNotNull(result.runtimePrompt)
        val refs = result.retrievedHits.map { it.toTestItemRef() }

        assertTrue(refs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, activeClaim.id.value)))
        assertTrue(refs.none { it == MemoryItemRef(MemoryItemRef.Type.SOURCE, oldSource.id.value) })
        assertTrue(refs.contains(MemoryItemRef(MemoryItemRef.Type.SOURCE, newSource.id.value)))
        assertTrue(
            result.trace.sourceSafety.suppressedSources
                .any { it.ref == MemoryItemRef(MemoryItemRef.Type.SOURCE, oldSource.id.value) }
        )
        assertTrue(prompt.contains("The primary export format for ShelfLog is currently JSON Lines."))
        assertTrue(!prompt.contains("The primary export format for ShelfLog is currently CSV."))
        assertTrue(!prompt.contains("ShelfLog primary export is currently CSV."))
    }

    @Test
    fun runtimeReadKeepsMixedSourceWhenOnlyPartOfItsTypedMemoryWasSuperseded() = runBlocking {
        val mixedSource = source(
            "mixed-active-and-stale-source",
            "ShelfLog primary export is currently CSV. The user's workshop notes are kept in the blue binder.",
        )
        val newSource = source(
            "mixed-current-source",
            "ShelfLog primary export is currently JSON Lines instead of CSV.",
        )
        val oldClaim = claim(
            id = "mixed-old-export-claim",
            sourceId = mixedSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("CSV"),
            normalizedText = "The primary export format for ShelfLog is currently CSV.",
            status = MemoryClaim.Status.SUPERSEDED,
        )
        val activeExportClaim = claim(
            id = "mixed-active-export-claim",
            sourceId = newSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("JSON Lines"),
            normalizedText = "The primary export format for ShelfLog is currently JSON Lines.",
            status = MemoryClaim.Status.ACTIVE,
        ).copy(supersedesClaimId = oldClaim.id)
        val activeBinderClaim = claim(
            id = "mixed-active-binder-claim",
            sourceId = mixedSource.id.value,
            predicate = "storage_location",
            objectValue = JsonPrimitive("blue binder"),
            normalizedText = "The user's workshop notes are kept in the blue binder.",
            status = MemoryClaim.Status.ACTIVE,
        )
        val mixedSourceRef = MemoryItemRef(MemoryItemRef.Type.SOURCE, mixedSource.id.value)
        val activeExportClaimRef = MemoryItemRef(MemoryItemRef.Type.CLAIM, activeExportClaim.id.value)
        val activeBinderClaimRef = MemoryItemRef(MemoryItemRef.Type.CLAIM, activeBinderClaim.id.value)

        val result = RuntimeMemoryReadPipeline(
            store = InMemoryMemoryStore(
                MemoryNamespaceSnapshot(
                    sources = listOf(mixedSource, newSource),
                    entities = listOf(entity()),
                    claims = listOf(oldClaim, activeExportClaim, activeBinderClaim),
                )
            ),
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    requireEvidenceFallback = true,
                    retrievalBudget = MemoryRetrievalBudget(claims = 2, sources = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "The source contains source-only wording and one superseded fact.",
                            query = "ShelfLog export format workshop notes blue binder",
                            topK = 1,
                        )
                    ),
                )
            ),
            selector = FixedReadSelector(
                selectedRefs = listOf(mixedSourceRef, activeExportClaimRef, activeBinderClaimRef),
            ),
        ).read(
            memoryReadRequest(
                targetMessageId = "mixed-active-and-stale-target",
                targetText = "What is ShelfLog's current export format, and where are my workshop notes kept?",
            )
        )

        val prompt = assertNotNull(result.runtimePrompt)
        val refs = result.retrievedHits.map { it.toTestItemRef() }

        assertTrue(refs.contains(mixedSourceRef))
        assertTrue(refs.contains(activeExportClaimRef))
        assertTrue(refs.contains(activeBinderClaimRef))
        assertTrue(result.trace.sourceSafety.suppressedSources.isEmpty())
        assertTrue(prompt.contains("The primary export format for ShelfLog is currently JSON Lines."))
        assertTrue(prompt.contains("workshop notes are kept in the blue binder"))
    }

    @Test
    fun readSelectorCandidateViewShowsSourceReplacementLifecycle() {
        val oldSource = source("selector-old-csv-source", "ShelfLog primary export is currently CSV.")
        val newSource = source("selector-new-json-source", "ShelfLog primary export is currently JSON Lines instead of CSV.")
        val oldClaim = claim(
            id = "selector-old-export-claim",
            sourceId = oldSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("CSV"),
            normalizedText = "The primary export format for ShelfLog is currently CSV.",
            status = MemoryClaim.Status.SUPERSEDED,
        )
        val activeClaim = claim(
            id = "selector-active-export-claim",
            sourceId = newSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("JSON Lines"),
            normalizedText = "The primary export format for ShelfLog is currently JSON Lines.",
            status = MemoryClaim.Status.ACTIVE,
        ).copy(supersedesClaimId = oldClaim.id)
        val snapshot = MemoryNamespaceSnapshot(
            sources = listOf(oldSource, newSource),
            entities = listOf(entity()),
            claims = listOf(oldClaim, activeClaim),
        )

        val rendered = MemoryReadSelectorCandidateRenderer.render(
            hits = listOf(
                MemoryStore.SearchHit.SourceHit(oldSource, score = 0.99),
                MemoryStore.SearchHit.ClaimHit(activeClaim, score = 0.8),
            ),
            snapshot = snapshot,
            query = "ShelfLog primary export format",
        )

        assertTrue(rendered.contains("\"lifecycle_state\":\"overridden_evidence\""))
        assertTrue(rendered.contains("\"supports\":[{\"type\":\"claim\",\"id\":\"selector-old-export-claim\",\"status\":\"SUPERSEDED\""))
        assertTrue(rendered.contains("\"overridden_by\":[{\"type\":\"claim\",\"id\":\"selector-active-export-claim\",\"status\":\"ACTIVE\""))
        assertTrue(rendered.contains("\"lifecycle_state\":\"current\""))
        assertFalse(rendered.contains("selection_hint"))
        assertFalse(rendered.contains("usage_policy"))
        assertFalse(rendered.contains("evidence_source_ids"))
    }

    @Test
    fun readSelectorCandidateViewKeepsMixedSourceActive() {
        val mixedSource = source("selector-mixed-source", "ShelfLog used CSV, and workshop notes are in the blue binder.")
        val replacementSource = source("selector-replacement-source", "ShelfLog now uses JSON Lines.")
        val oldClaim = claim(
            id = "selector-mixed-old-claim",
            sourceId = mixedSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("CSV"),
            normalizedText = "The primary export format for ShelfLog was CSV.",
            status = MemoryClaim.Status.SUPERSEDED,
        )
        val replacementClaim = claim(
            id = "selector-mixed-replacement-claim",
            sourceId = replacementSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("JSON Lines"),
            normalizedText = "The primary export format for ShelfLog is JSON Lines.",
            status = MemoryClaim.Status.ACTIVE,
        ).copy(supersedesClaimId = oldClaim.id)
        val activeClaim = claim(
            id = "selector-mixed-active-claim",
            sourceId = mixedSource.id.value,
            predicate = "current_location",
            objectValue = JsonPrimitive("blue binder"),
            normalizedText = "The workshop notes are in the blue binder.",
            status = MemoryClaim.Status.ACTIVE,
        )
        val snapshot = MemoryNamespaceSnapshot(
            sources = listOf(mixedSource, replacementSource),
            entities = listOf(entity()),
            claims = listOf(oldClaim, replacementClaim, activeClaim),
        )

        val rendered = MemoryReadSelectorCandidateRenderer.render(
            hits = listOf(MemoryStore.SearchHit.SourceHit(mixedSource, score = 0.99)),
            snapshot = snapshot,
            query = "ShelfLog workshop notes",
        )

        assertTrue(rendered.contains("\"lifecycle_state\":\"evidence_for_active_memory\""))
        assertTrue(rendered.contains("\"id\":\"selector-mixed-active-claim\",\"status\":\"ACTIVE\""))
        assertTrue(rendered.contains("\"overridden_by\":[{\"type\":\"claim\",\"id\":\"selector-mixed-replacement-claim\""))
    }

    @Test
    fun readSelectorCandidateViewBoundsSourceTextButKeepsSearchText() {
        val longSourceText = buildString {
            appendLine("Source beginning")
            append("x".repeat(5_000))
            appendLine()
            appendLine("selector-source-marker-after-selector-limit")
            append("Source end")
        }
        val longSource = source("selector-long-source", longSourceText, searchText = "short search paraphrase")

        val rendered = MemoryReadSelectorCandidateRenderer.render(
            hits = listOf(MemoryStore.SearchHit.SourceHit(longSource, score = 0.9)),
            snapshot = MemoryNamespaceSnapshot(sources = listOf(longSource)),
            query = "beginning",
        )

        assertTrue(rendered.contains("short search paraphrase"))
        assertFalse(rendered.contains("selector-source-marker-after-selector-limit"))
        assertTrue(rendered.contains("source_text"))
        assertTrue(rendered.contains("[matching excerpts]"))
    }

    @Test
    fun runtimeReadAddsActiveReplacementToSelectorCandidatesForRawSource() = runBlocking {
        val oldSource = source("candidate-old-csv-source", "ShelfLog primary export is currently CSV.")
        val newSource = source("candidate-new-json-source", "ShelfLog primary export is currently JSON Lines instead of CSV.")
        val oldClaim = claim(
            id = "candidate-old-export-claim",
            sourceId = oldSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("CSV"),
            normalizedText = "The primary export format for ShelfLog is currently CSV.",
            status = MemoryClaim.Status.SUPERSEDED,
        )
        val activeClaim = claim(
            id = "candidate-active-export-claim",
            sourceId = newSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("JSON Lines"),
            normalizedText = "The primary export format for ShelfLog is currently JSON Lines.",
            status = MemoryClaim.Status.ACTIVE,
        ).copy(supersedesClaimId = oldClaim.id)
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(oldSource, newSource),
                entities = listOf(entity()),
                claims = listOf(oldClaim, activeClaim),
            )
        )
        val selector = CapturingReadSelector(
            selectedRefs = listOf(MemoryItemRef(MemoryItemRef.Type.CLAIM, activeClaim.id.value))
        )
        val targetMessage = userMessage(
            id = "target-message",
            text = "What is the current primary export format for ShelfLog?",
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    requireEvidenceFallback = true,
                    retrievalBudget = MemoryRetrievalBudget(claims = 0, sources = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Only raw evidence was retrieved lexically.",
                            query = "ShelfLog primary export format CSV",
                            topK = 1,
                        )
                    ),
                )
            ),
            selector = selector,
        ).read(
            MemoryReadRequest(
                namespace = TEST_NAMESPACE,
                threadContext = MemoryThreadContext(
                    conversationId = Conversation.Id("conversation"),
                    threadId = Conversation.Thread.Id("read-thread"),
                    targetMessageId = targetMessage.id,
                    messages = listOf(targetMessage),
                ),
            )
        )

        assertTrue(selector.capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.SOURCE, oldSource.id.value)))
        assertTrue(selector.capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, activeClaim.id.value)))
        assertTrue(result.retrievedHits.map { it.toTestItemRef() }.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, activeClaim.id.value)))
    }

    @Test
    fun runtimeReadSourceSafetySuppressesSelectedOldSourceAndRestoresActiveClaim() = runBlocking {
        val oldSource = source("source-safety-old-csv-source", "BookNest primary export format is CSV.")
        val newSource = source("source-safety-new-parquet-source", "BookNest primary export format is now Parquet; CSV is old.")
        val oldClaim = claim(
            id = "source-safety-old-export-claim",
            sourceId = oldSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("CSV"),
            normalizedText = "The current primary export format for BookNest is CSV.",
            status = MemoryClaim.Status.SUPERSEDED,
        )
        val activeClaim = claim(
            id = "source-safety-active-export-claim",
            sourceId = newSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("Parquet"),
            normalizedText = "The current primary export format for BookNest is Parquet.",
            status = MemoryClaim.Status.ACTIVE,
        ).copy(supersedesClaimId = oldClaim.id)
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(oldSource, newSource),
                entities = listOf(entity()),
                claims = listOf(oldClaim, activeClaim),
            )
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.MIXED,
                    requireEvidenceFallback = false,
                    retrievalBudget = MemoryRetrievalBudget(claims = 0, sources = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Selector may pick the stale raw source, but source safety must still repair it.",
                            query = "BookNest primary export format CSV",
                            topK = 1,
                        )
                    ),
                )
            ),
            selector = FixedReadSelector(
                selectedRefs = listOf(MemoryItemRef(MemoryItemRef.Type.SOURCE, oldSource.id.value))
            ),
        ).read(memoryReadRequest("source-safety-target", "I remember BookNest exported CSV. Is that still current?"))

        val prompt = assertNotNull(result.runtimePrompt)
        val refs = result.retrievedHits.map { it.toTestItemRef() }

        assertTrue(refs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, activeClaim.id.value)))
        assertTrue(refs.none { it == MemoryItemRef(MemoryItemRef.Type.SOURCE, oldSource.id.value) })
        assertEquals(listOf(oldSource.id.value), result.trace.sourceSafety.suppressedSources.map { it.ref.id })
        assertEquals(listOf(activeClaim.id.value), result.trace.sourceSafety.restoredTypedHits.map { it.ref.id })
        assertTrue(prompt.contains("The current primary export format for BookNest is Parquet."))
        assertTrue(!prompt.contains("BookNest primary export format is CSV."))
    }

    @Test
    fun runtimeReadCompleteSetKeepsMixedSourceOnlyDetailAndRestoresActiveClaim() = runBlocking {
        val mixedSource = source(
            "complete-set-mixed-source",
            "ShelfLog primary export is currently CSV. The user's workshop notes are kept in the blue binder.",
        )
        val newSource = source(
            "complete-set-current-source",
            "ShelfLog primary export is currently JSON Lines instead of CSV.",
        )
        val oldClaim = claim(
            id = "complete-set-old-export-claim",
            sourceId = mixedSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("CSV"),
            normalizedText = "The primary export format for ShelfLog is currently CSV.",
            status = MemoryClaim.Status.SUPERSEDED,
        )
        val activeClaim = claim(
            id = "complete-set-active-export-claim",
            sourceId = newSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("JSON Lines"),
            normalizedText = "The primary export format for ShelfLog is currently JSON Lines.",
            status = MemoryClaim.Status.ACTIVE,
        ).copy(supersedesClaimId = oldClaim.id)
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(mixedSource, newSource),
                entities = listOf(entity()),
                claims = listOf(oldClaim, activeClaim),
            )
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    requireEvidenceFallback = true,
                    retrievalBudget = MemoryRetrievalBudget(claims = 0, sources = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Complete-set recall needs source-only details even when the source also contains stale typed facts.",
                            query = "ShelfLog current export format workshop notes blue binder",
                            topK = 1,
                        )
                    ),
                )
            ),
            selector = FixedReadSelector(
                selectedRefs = listOf(MemoryItemRef(MemoryItemRef.Type.SOURCE, mixedSource.id.value))
            ),
        ).read(
            memoryReadRequest(
                "complete-set-mixed-source-target",
                "What is ShelfLog's current export format, and where are my workshop notes kept?",
            )
        )

        val prompt = assertNotNull(result.runtimePrompt)
        val refs = result.retrievedHits.map { it.toTestItemRef() }

        assertTrue(refs.contains(MemoryItemRef(MemoryItemRef.Type.SOURCE, mixedSource.id.value)))
        assertTrue(refs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, activeClaim.id.value)))
        assertTrue(result.trace.sourceSafety.suppressedSources.isEmpty())
        assertEquals(listOf(activeClaim.id.value), result.trace.sourceSafety.restoredTypedHits.map { it.ref.id })
        assertTrue(prompt.contains("The primary export format for ShelfLog is currently JSON Lines."))
        assertTrue(prompt.contains("workshop notes are kept in the blue binder"))
    }

    @Test
    fun runtimeReadExposesSupersededFactsForHistoricalStateQuestionsOnly() = runBlocking {
        val sneakersEntity = entity(
            id = MemoryEntity.Id("entity-old-sneakers"),
            entityType = MemoryEntity.Type.PRODUCT,
            canonicalName = "User's old sneakers",
            normalizedName = "users old sneakers",
        )
        val oldSource = source(
            "old-sneakers-under-bed-source",
            "I've been keeping my old sneakers under my bed for storage.",
        )
        val newSource = source(
            "old-sneakers-shoe-rack-source",
            "My old sneakers are in a shoe rack in my closet now.",
        )
        val oldClaim = claim(
            id = "old-sneakers-under-bed-claim",
            sourceId = oldSource.id.value,
            subjectEntityId = sneakersEntity.id,
            predicate = "current_location",
            objectValue = JsonPrimitive("under my bed"),
            normalizedText = "The user's old sneakers were kept under the user's bed.",
            status = MemoryClaim.Status.SUPERSEDED,
        )
        val activeClaim = claim(
            id = "old-sneakers-shoe-rack-claim",
            sourceId = newSource.id.value,
            subjectEntityId = sneakersEntity.id,
            predicate = "current_location",
            objectValue = JsonPrimitive("in a shoe rack in the user's closet"),
            normalizedText = "The user's old sneakers are in a shoe rack in the user's closet.",
        ).copy(supersedesClaimId = oldClaim.id)
        val backingStore = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(oldSource, newSource),
                entities = listOf(entity(), sneakersEntity),
                claims = listOf(oldClaim, activeClaim),
            )
        )
        val readPlan = MemoryReadPlan(
            needMemory = true,
            contextMode = MemoryReadPlan.ContextMode.FACTUAL,
            retrievalBudget = MemoryRetrievalBudget(claims = 4),
            retrievalRequests = listOf(
                MemoryReadPlan.RetrievalRequest(
                    memoryType = MemorySemanticType.CLAIM,
                    why = "Retrieve location facts for the user's old sneakers.",
                    query = "old sneakers location storage",
                    topK = 4,
                )
            ),
        )

        val currentSelector = CapturingReadSelector(
            selectedRefs = listOf(MemoryItemRef(MemoryItemRef.Type.CLAIM, activeClaim.id.value))
        )
        RuntimeMemoryReadPipeline(
            store = backingStore,
            planner = FixedReadPlanner(readPlan),
            selector = currentSelector,
        ).read(memoryReadRequest("current-sneakers-location", "Where do I keep my old sneakers?"))

        assertTrue(currentSelector.capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, activeClaim.id.value)))
        assertFalse(currentSelector.capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, oldClaim.id.value)))

        val historicalSelector = CapturingReadSelector(
            selectedRefs = listOf(MemoryItemRef(MemoryItemRef.Type.CLAIM, oldClaim.id.value))
        )
        val historicalResult = RuntimeMemoryReadPipeline(
            store = backingStore,
            planner = FixedReadPlanner(readPlan),
            selector = historicalSelector,
        ).read(memoryReadRequest("historical-sneakers-location", "Where did I initially keep my old sneakers?"))

        val prompt = assertNotNull(historicalResult.runtimePrompt)

        assertTrue(historicalSelector.capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, activeClaim.id.value)))
        assertTrue(historicalSelector.capturedRefs.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, oldClaim.id.value)))
        assertTrue(historicalResult.retrievedHits.map { it.toTestItemRef() }.contains(MemoryItemRef(MemoryItemRef.Type.CLAIM, oldClaim.id.value)))
        assertTrue(prompt.contains("The user's old sneakers were kept under the user's bed."))
        assertTrue(prompt.contains("non-current typed memory and older evidence may be the direct answer"))
    }

    @Test
    fun runtimeReadKeepsActiveNoteQuoteWithoutRenderingContaminatedSource() = runBlocking {
        val mixedSource = source(
            "mixed-shelflog-source",
            "ShelfLog primary export is currently CSV. When I ask for ShelfLog reports, I prefer a short summary first, then disputed points.",
        )
        val newSource = source("new-shelflog-source", "ShelfLog primary export is currently JSON Lines instead of CSV.")
        val oldClaim = claim(
            id = "old-shelflog-export-claim",
            sourceId = mixedSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("CSV"),
            normalizedText = "The primary export format for ShelfLog is currently CSV.",
            status = MemoryClaim.Status.SUPERSEDED,
        )
        val activeClaim = claim(
            id = "active-shelflog-export-claim",
            sourceId = newSource.id.value,
            predicate = "primary_export_format",
            objectValue = JsonPrimitive("JSON Lines"),
            normalizedText = "The primary export format for ShelfLog is currently JSON Lines.",
            status = MemoryClaim.Status.ACTIVE,
        ).copy(supersedesClaimId = oldClaim.id)
        val activeNote = note(
            id = "shelflog-report-note",
            title = "ShelfLog report output preference",
            summary = "For ShelfLog reports, the user prefers a short summary first, then disputed points.",
            evidenceRefs = listOf(
                evidenceRef(
                    mixedSource.id.value,
                    "When I ask for ShelfLog reports, I prefer a short summary first, then disputed points.",
                )
            ),
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(mixedSource, newSource),
                entities = listOf(entity()),
                claims = listOf(oldClaim, activeClaim),
                notes = listOf(activeNote),
            )
        )
        val targetMessage = userMessage(
            id = "target-message",
            text = "How do I prefer ShelfLog reports to be formatted?",
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.MIXED,
                    requireEvidenceFallback = true,
                    retrievalBudget = MemoryRetrievalBudget(claims = 1, notes = 1, sources = 2),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.NOTE,
                            why = "Report preference is contextual memory.",
                            query = "ShelfLog report output preference",
                            topK = 1,
                        ),
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Evidence fallback should use quotes, not a stale mixed raw source.",
                            query = "ShelfLog report output preference",
                            topK = 2,
                        )
                    ),
                )
            ),
        ).read(
            MemoryReadRequest(
                namespace = TEST_NAMESPACE,
                threadContext = MemoryThreadContext(
                    conversationId = Conversation.Id("conversation"),
                    threadId = Conversation.Thread.Id("read-thread"),
                    targetMessageId = targetMessage.id,
                    messages = listOf(targetMessage),
                ),
            )
        )

        val prompt = assertNotNull(result.runtimePrompt)
        val refs = result.retrievedHits.map { it.toTestItemRef() }

        assertTrue(refs.contains(MemoryItemRef(MemoryItemRef.Type.NOTE, activeNote.id.value)))
        assertTrue(refs.none { it == MemoryItemRef(MemoryItemRef.Type.SOURCE, mixedSource.id.value) })
        assertTrue(prompt.contains("short summary first, then disputed points"))
        assertTrue(!prompt.contains("ShelfLog primary export is currently CSV."))
    }

    @Test
    fun runtimeReadKeepsRawSourceLinkedToNonActiveMemoryWhenNoActiveReplacementExists() = runBlocking {
        val historicalSource = source("historical-source", "ShelfLog used to export CSV during the first prototype.")
        val historicalClaim = claim(
            id = "historical-export-claim",
            sourceId = historicalSource.id.value,
            predicate = "historical_export_format",
            objectValue = JsonPrimitive("CSV"),
            normalizedText = "ShelfLog used to export CSV during the first prototype.",
            status = MemoryClaim.Status.EXPIRED,
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(historicalSource),
                entities = listOf(entity()),
                claims = listOf(historicalClaim),
            )
        )
        val targetMessage = userMessage(
            id = "target-message",
            text = "What historical export format did ShelfLog use during the first prototype?",
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.RATIONALE,
                    requireEvidenceFallback = true,
                    retrievalBudget = MemoryRetrievalBudget(sources = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Historical evidence is useful when there is no active replacement.",
                            query = "ShelfLog first prototype historical export format",
                            topK = 1,
                        )
                    ),
                )
            ),
        ).read(
            MemoryReadRequest(
                namespace = TEST_NAMESPACE,
                threadContext = MemoryThreadContext(
                    conversationId = Conversation.Id("conversation"),
                    threadId = Conversation.Thread.Id("read-thread"),
                    targetMessageId = targetMessage.id,
                    messages = listOf(targetMessage),
                ),
            )
        )

        val prompt = assertNotNull(result.runtimePrompt)
        val refs = result.retrievedHits.map { it.toTestItemRef() }

        assertTrue(refs.contains(MemoryItemRef(MemoryItemRef.Type.SOURCE, historicalSource.id.value)))
        assertTrue(prompt.contains("ShelfLog used to export CSV during the first prototype."))
    }

    @Test
    fun claimReconcilerKeepsTimeScopedHistoricalClaimWhenCurrentStatusSupersedeIsProposed() {
        val projectId = MemoryEntity.Id("entity-memory-mvp")
        val sqliteId = MemoryEntity.Id("entity-sqlite")
        val mongoId = MemoryEntity.Id("entity-mongodb")
        val historicalPolicy = MemoryPredicateDefinition(
            predicate = "uses_persistence_database",
            namespace = TEST_NAMESPACE,
            objectKind = MemoryPredicateDefinition.ObjectValueKind.ENTITY,
            cardinality = MemoryPredicateDefinition.Cardinality.MULTI,
            temporalPolicy = MemoryPredicateDefinition.TemporalPolicy.TIME_SCOPED,
            conflictPolicy = MemoryPredicateDefinition.ConflictPolicy.RANGE_SPLIT,
            semanticKinds = setOf(MemoryPredicateDefinition.SemanticKind.TECHNICAL_CONFIGURATION),
        )
        val currentPolicy = MemoryPredicateDefinition(
            predicate = "primary_storage_backend",
            namespace = TEST_NAMESPACE,
            objectKind = MemoryPredicateDefinition.ObjectValueKind.ENTITY,
            cardinality = MemoryPredicateDefinition.Cardinality.SINGLE,
            temporalPolicy = MemoryPredicateDefinition.TemporalPolicy.STATUS_LIKE,
            conflictPolicy = MemoryPredicateDefinition.ConflictPolicy.REPLACE,
            semanticKinds = setOf(MemoryPredicateDefinition.SemanticKind.TECHNICAL_CONFIGURATION),
        )
        val historicalClaim = claim(
            id = "historical-sqlite-claim",
            subjectEntityId = projectId,
            predicate = historicalPolicy.predicate,
            objectEntityId = sqliteId,
            objectValue = null,
            normalizedText = "Memory MVP used SQLite during the early prototype.",
        ).copy(
            predicateFamily = historicalPolicy.predicate,
            predicatePolicy = historicalPolicy,
            validFrom = Instant.parse("2026-01-10T00:00:00Z"),
            validTo = Instant.parse("2026-02-01T00:00:00Z"),
        )
        val currentCandidate = MemoryClaimCandidate(
            subjectEntityId = projectId,
            predicate = currentPolicy.predicate,
            predicateFamily = currentPolicy.predicate,
            predicatePolicy = currentPolicy,
            objectEntityId = mongoId,
            normalizedText = "Memory MVP currently uses MongoDB as the primary storage backend.",
            scope = MemoryScope.Global("Memory MVP project"),
            confidence = 0.9,
            importance = 8,
        )
        val oldCurrentClaim = historicalClaim.copy(
            id = MemoryClaim.Id("old-current-storage-claim"),
            predicate = currentPolicy.predicate,
            predicateFamily = currentPolicy.predicate,
            predicatePolicy = currentPolicy,
            objectEntityId = sqliteId,
            validFrom = null,
            validTo = null,
            normalizedText = "Memory MVP currently uses SQLite as the primary storage backend.",
        )

        assertTrue(currentCandidate.shouldCoexistWithTemporalTargetForMemoryReconciliation(historicalClaim))
        assertTrue(!currentCandidate.shouldCoexistWithTemporalTargetForMemoryReconciliation(oldCurrentClaim))
    }

    @Test
    fun runtimeReadRendersSelectedSourceWithoutEvidenceFallback() = runBlocking {
        val striderSource = source(
            id = "strider-uncertain-source",
            text = "Кажется, Strider раньше работал стабильнее, но я не уверен.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(striderSource),
                entities = listOf(entity()),
            )
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    requireEvidenceFallback = false,
                    retrievalBudget = MemoryRetrievalBudget(sources = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "The answer depends on the raw remembered wording.",
                            query = "Strider раньше работал стабильнее не уверен",
                            topK = 1,
                        )
                    ),
                )
            ),
        ).read(memoryReadRequest("strider-source-target", "What did I say about Strider reliability?"))

        val prompt = assertNotNull(result.runtimePrompt)
        val refs = result.retrievedHits.map { it.toTestItemRef() }

        assertTrue(refs.contains(MemoryItemRef(MemoryItemRef.Type.SOURCE, striderSource.id.value)))
        assertTrue(prompt.contains("Кажется, Strider раньше работал стабильнее, но я не уверен."))
        assertTrue(!prompt.contains("Retrieved evidence:\nnot requested for this context mode"))
    }

    @Test
    fun runtimePromptTellsModelToCompareDatesForOrderingQuestions() = runBlocking {
        val orderingSource = source("ordering-source", "On 2026-02-12, Memory MVP added evidence-backed recall.")
        val orderingClaim = claim(
            id = "ordering-claim",
            sourceId = orderingSource.id.value,
            predicate = "memory_milestone",
            objectValue = JsonPrimitive("evidence-backed recall"),
            normalizedText = "On 2026-02-12, Memory MVP added evidence-backed recall.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(orderingSource),
                entities = listOf(entity()),
                claims = listOf(orderingClaim),
            )
        )

        val result = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    requireEvidenceFallback = false,
                    retrievalBudget = MemoryRetrievalBudget(claims = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "The user asks for timeline ordering.",
                            query = "Memory MVP evidence-backed recall 2026-02-12",
                            topK = 1,
                        )
                    ),
                )
            ),
        ).read(memoryReadRequest("ordering-target", "Which Memory MVP milestone happened second?"))

        val prompt = assertNotNull(result.runtimePrompt)

        assertTrue(prompt.contains("If the user asks for first/second/latest/earliest/ordering"))
        assertTrue(prompt.contains("compare explicit dates in retrieved memory before answering"))
        assertTrue(prompt.contains("If the user asks about a named or relative date"))
        assertTrue(prompt.contains("compare that target date with both event dates and source/session dates"))
    }
}

private class FixedForgetPlanner(
    private val scriptedPlan: MemoryForgetPlan,
) : MemoryForgetPlanner {
    override suspend fun plan(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        candidates: List<MemoryStore.SearchHit>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryForgetPlan = scriptedPlan
}

private class FixedRepairPlanner(
    private val scriptedPlan: MemoryRepairPlan,
) : MemoryRepairPlanner {
    override suspend fun plan(
        request: MemoryMaintenanceRequest,
        candidateClusters: List<MemoryRepairCandidateCluster>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryRepairPlan = scriptedPlan
}

private class CapturingRepairPlanner : MemoryRepairPlanner {
    val batchSizes = mutableListOf<Int>()

    override suspend fun plan(
        request: MemoryMaintenanceRequest,
        candidateClusters: List<MemoryRepairCandidateCluster>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryRepairPlan {
        batchSizes += candidateClusters.size
        return MemoryRepairPlan(summary = "Planned repair batch ${batchSizes.size}.")
    }
}

private class FixedEntityMaintenancePlanner(
    private val scriptedPlan: MemoryEntityMaintenancePlan,
) : MemoryEntityMaintenancePlanner {
    override suspend fun plan(
        request: MemoryMaintenanceRequest,
        candidateGroups: List<MemoryEntityMaintenanceCandidateGroup>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryEntityMaintenancePlan = scriptedPlan
}

private class CapturingEntityMaintenancePlanner : MemoryEntityMaintenancePlanner {
    val batchSizes = mutableListOf<Int>()

    override suspend fun plan(
        request: MemoryMaintenanceRequest,
        candidateGroups: List<MemoryEntityMaintenanceCandidateGroup>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryEntityMaintenancePlan {
        batchSizes += candidateGroups.size
        return MemoryEntityMaintenancePlan(summary = "Planned entity maintenance batch ${batchSizes.size}.")
    }
}

private class FixedNoteConsolidator(
    private val scriptedResult: NoteConsolidationResult,
) : MemoryNoteConsolidator {
    override suspend fun consolidate(
        request: MemoryMaintenanceRequest,
        selectedNotes: List<MemoryNote>,
        relatedHits: List<MemoryStore.SearchHit>,
        snapshot: MemoryNamespaceSnapshot,
    ): NoteConsolidationResult = scriptedResult
}

private class SearchInterceptingMemoryStore(
    private val delegate: MemoryStore,
    private val searchHandler: suspend (MemoryStore.SearchRequest) -> List<MemoryStore.SearchHit>,
) : MemoryStore by delegate {
    val searchRequests = mutableListOf<MemoryStore.SearchRequest>()

    override suspend fun search(request: MemoryStore.SearchRequest): List<MemoryStore.SearchHit> {
        searchRequests += request
        return searchHandler(request)
    }
}

private class FixedSearchEmbeddingIndexer : MemoryEmbeddingIndexer {
    val queries = mutableListOf<String>()

    override suspend fun withEmbeddings(batch: MemoryUpdateBatch): MemoryUpdateBatch = batch

    override suspend fun searchEmbedding(query: String): MemoryStore.SearchEmbedding {
        queries += query
        return MemoryStore.SearchEmbedding(
            modelConfigurationId = "test-embedding-config",
            providerModelId = "test-embedding-model",
            vector = listOf(1.0f),
        )
    }

    override suspend fun rebuildNamespace(
        namespace: MemoryNamespace,
        mode: MemoryEmbeddingRebuildMode,
    ): MemoryEmbeddingRebuildResult =
        NoOpMemoryEmbeddingIndexer.rebuildNamespace(namespace, mode)

    override suspend fun coverage(namespace: MemoryNamespace): MemoryEmbeddingCoverage =
        NoOpMemoryEmbeddingIndexer.coverage(namespace)

}

private class FixedReadPlanner(
    private val scriptedPlan: MemoryReadPlan,
) : MemoryReadPlanner {
    override suspend fun plan(request: MemoryReadRequest): MemoryReadPlan = scriptedPlan
}

private class FixedReadSelector(
    private val selectedRefs: List<MemoryItemRef>,
) : MemoryReadSelector {
    override suspend fun select(request: MemoryReadSelectionRequest): MemoryReadSelectionResult {
        val hitsByRef = request.candidateHits.associateBy { it.toTestItemRef() }
        val selectedRefSet = selectedRefs.toSet()
        return MemoryReadSelectionResult(
            selectedHits = selectedRefs.mapNotNull(hitsByRef::get),
            decisions = request.candidateHits.mapIndexed { index, hit ->
                val ref = hit.toTestItemRef()
                MemoryReadSelectionResult.Decision(
                    ref = ref,
                    selected = ref in selectedRefSet,
                    rank = if (ref in selectedRefSet) index + 1 else 0,
                    reason = if (ref in selectedRefSet) "selected by fixed test selector" else "rejected by fixed test selector",
                )
            },
        )
    }
}

private class FixedReadSelectorWithSafetyHits(
    private val selectedRefs: List<MemoryItemRef>,
    private val safetyRefs: List<MemoryItemRef>,
) : MemoryReadSelector {
    override suspend fun select(request: MemoryReadSelectionRequest): MemoryReadSelectionResult {
        val hitsByRef = request.candidateHits.associateBy { it.toTestItemRef() }
        val selectedRefSet = selectedRefs.toSet()
        val safetyRefSet = safetyRefs.toSet()
        return MemoryReadSelectionResult(
            selectedHits = (selectedRefs + safetyRefs).mapNotNull(hitsByRef::get),
            decisions = request.candidateHits.mapIndexed { index, hit ->
                val ref = hit.toTestItemRef()
                MemoryReadSelectionResult.Decision(
                    ref = ref,
                    selected = ref in selectedRefSet,
                    rank = if (ref in selectedRefSet) index + 1 else Int.MAX_VALUE,
                    reason = when (ref) {
                        in selectedRefSet -> "selected by fixed test selector"
                        in safetyRefSet -> "carried by selector safety"
                        else -> "rejected by fixed test selector"
                    },
                )
            },
        )
    }
}

private class CapturingReadSelector(
    private val selectedRefs: List<MemoryItemRef>,
) : MemoryReadSelector {
    val capturedRefs = mutableListOf<MemoryItemRef>()

    override suspend fun select(request: MemoryReadSelectionRequest): MemoryReadSelectionResult {
        capturedRefs += request.candidateHits.map { it.toTestItemRef() }
        val hitsByRef = request.candidateHits.associateBy { it.toTestItemRef() }
        return MemoryReadSelectionResult(
            selectedHits = selectedRefs.mapNotNull(hitsByRef::get),
            decisions = request.candidateHits.mapIndexed { index, hit ->
                val ref = hit.toTestItemRef()
                MemoryReadSelectionResult.Decision(
                    ref = ref,
                    selected = ref in selectedRefs,
                    rank = if (ref in selectedRefs) index + 1 else 0,
                    reason = if (ref in selectedRefs) "selected by capturing test selector" else "rejected by capturing test selector",
                )
            },
        )
    }
}

private class FixedMemoryClaimReconciler(
    private val scriptedOps: List<MemoryClaimReconciliationOp>,
) : MemoryClaimReconciler {
    override suspend fun reconcile(
        request: DirectStructuredMemoryWriteRequest,
        claimCandidates: List<MemoryClaimCandidate>,
        retrievedHits: List<MemoryStore.SearchHit>,
        predicateCatalog: List<MemoryPredicateDefinition>,
    ): List<MemoryClaimReconciliationOp> = scriptedOps
}

private val TEST_NAMESPACE = MemoryNamespace("memory-maintenance-test")
private val USER_ENTITY_ID = MemoryEntity.Id("entity-user")
private val NOW: Instant = Instant.parse("2026-01-02T03:04:05Z")
private val EARLIER: Instant = Instant.parse("2026-01-01T00:00:00Z")

private fun entity(
    id: MemoryEntity.Id = USER_ENTITY_ID,
    entityType: MemoryEntity.Type = MemoryEntity.Type.USER,
    canonicalName: String = "Lewik",
    normalizedName: String = "lewik",
    summary: String? = null,
    aliases: List<MemoryEntity.Alias> = emptyList(),
): MemoryEntity =
    MemoryEntity(
        id = id,
        namespace = TEST_NAMESPACE,
        entityType = entityType,
        canonicalName = canonicalName,
        normalizedName = normalizedName,
        summary = summary,
        aliases = aliases,
        firstSeenAt = EARLIER,
        lastSeenAt = EARLIER,
        createdAt = EARLIER,
        updatedAt = EARLIER,
    )

private fun alias(text: String): MemoryEntity.Alias =
    MemoryEntity.Alias(
        text = text,
        normalizedText = text.lowercase(),
        confidence = 1.0,
        createdAt = EARLIER,
    )

private fun profile(
    id: String,
    ownerEntityId: MemoryEntity.Id,
    profileText: String,
    version: Long = 1,
    updatedAt: Instant = EARLIER,
): MemoryProfile =
    MemoryProfile(
        id = MemoryProfile.Id(id),
        namespace = TEST_NAMESPACE,
        ownerEntityId = ownerEntityId,
        profileText = profileText,
        version = version,
        createdAt = EARLIER,
        updatedAt = updatedAt,
    )

private fun source(
    id: String = "source",
    text: String = "User prefers Toyota.",
    speakerRole: MemorySource.ActorRole = MemorySource.ActorRole.USER,
    searchText: String? = text,
): MemorySource =
    MemorySource.ChatTurn(
        id = MemorySource.Id(id),
        namespace = TEST_NAMESPACE,
        conversationId = Conversation.Id("conversation"),
        threadId = Conversation.Thread.Id("thread"),
        sourceMessageId = Conversation.Message.Id("message-$id"),
        speakerRole = speakerRole,
        contentText = text,
        searchText = searchText,
        contentHash = "$id-hash",
        observedAt = EARLIER,
        createdAt = EARLIER,
    )

private fun claim(
    id: String = "claim",
    sourceId: String = "source",
    subjectEntityId: MemoryEntity.Id = USER_ENTITY_ID,
    predicate: String = "prefers",
    objectEntityId: MemoryEntity.Id? = null,
    objectValue: JsonPrimitive? = JsonPrimitive("Toyota"),
    normalizedText: String = "The user prefers Toyota.",
    contextText: String? = null,
    status: MemoryClaim.Status = MemoryClaim.Status.ACTIVE,
    confidence: Double = 0.8,
    importance: Int = 7,
): MemoryClaim =
    MemoryClaim(
        id = MemoryClaim.Id(id),
        namespace = TEST_NAMESPACE,
        subjectEntityId = subjectEntityId,
        predicate = predicate,
        objectEntityId = objectEntityId,
        objectValue = objectValue,
        normalizedText = normalizedText,
        contextText = contextText,
        scope = MemoryScope.Global("User-level preference"),
        confidence = confidence,
        importance = importance,
        status = status,
        firstSeenAt = EARLIER,
        lastSeenAt = EARLIER,
        evidenceRefs = listOf(evidenceRef(sourceId, normalizedText)),
        createdAt = EARLIER,
        updatedAt = EARLIER,
    )

private fun note(
    id: String = "note",
    title: String = "Memory note",
    summary: String = "Memory note summary.",
    noteType: MemoryNote.Type = MemoryNote.Type.CONTEXT,
    status: MemoryNote.Status = MemoryNote.Status.ACTIVE,
    maturity: MemoryNote.Maturity = MemoryNote.Maturity.STABILIZING,
    anchorEntityId: MemoryEntity.Id? = USER_ENTITY_ID,
    entityRefs: List<MemoryNote.EntityRef> = listOf(MemoryNote.EntityRef(USER_ENTITY_ID, MemoryNote.EntityRef.Role.PRIMARY)),
    confidence: Double = 0.8,
    importance: Int = 8,
    evidenceRefs: List<MemoryEvidenceRef> = listOf(evidenceRef("source", summary)),
): MemoryNote =
    MemoryNote(
        id = MemoryNote.Id(id),
        namespace = TEST_NAMESPACE,
        noteType = noteType,
        title = title,
        summary = summary,
        scope = MemoryScope.Global("User-level context"),
        status = status,
        maturity = maturity,
        anchorEntityId = anchorEntityId,
        entityRefs = entityRefs,
        confidence = confidence,
        importance = importance,
        evidenceRefs = evidenceRefs,
        createdAt = EARLIER,
        updatedAt = EARLIER,
    )

private fun actionItem(
    id: String,
    status: MemoryActionItem.Status,
    ownerEntityId: MemoryEntity.Id? = USER_ENTITY_ID,
    assigneeEntityId: MemoryEntity.Id? = null,
    relatedEntityIds: List<MemoryEntity.Id> = emptyList(),
): MemoryActionItem =
    MemoryActionItem(
        id = MemoryActionItem.Id(id),
        namespace = TEST_NAMESPACE,
        ownerEntityId = ownerEntityId,
        assigneeEntityId = assigneeEntityId,
        title = "Memory actionItem $id",
        status = status,
        scope = MemoryScope.Global("User-level actionItem"),
        relatedEntityIds = relatedEntityIds,
        evidenceRefs = listOf(evidenceRef("source", "Task evidence")),
        createdAt = EARLIER,
        updatedAt = EARLIER,
    )

private fun episode(
    id: String,
    ownerEntityId: MemoryEntity.Id? = USER_ENTITY_ID,
): MemoryEpisode =
    MemoryEpisode(
        id = MemoryEpisode.Id(id),
        namespace = TEST_NAMESPACE,
        ownerEntityId = ownerEntityId,
        situation = "A no-memory read plan needed verification.",
        action = "Run a verifier before skipping recall.",
        result = "Recall intent was recovered safely.",
        lesson = "Use verifier calls for ambiguous no-memory plans.",
        tags = listOf("memory", "recall"),
        evidenceRefs = listOf(evidenceRef("source", "Episode evidence")),
        createdAt = EARLIER,
        updatedAt = EARLIER,
    )

private fun evidenceRef(
    sourceId: String,
    quote: String,
): MemoryEvidenceRef =
    MemoryEvidenceRef(
        sourceId = MemorySource.Id(sourceId),
        kind = MemoryEvidenceRef.Kind.DIRECT,
        cachedQuote = quote,
    )

private fun userMessage(
    id: String,
    text: String,
): Conversation.Message =
    Conversation.Message(
        id = Conversation.Message.Id(id),
        conversationId = Conversation.Id("conversation"),
        role = Conversation.Message.Role.USER,
        content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
        createdAt = EARLIER,
    )

private fun memoryReadRequest(
    targetMessageId: String,
    targetText: String,
): MemoryReadRequest {
    val targetMessage = userMessage(targetMessageId, targetText)
    return MemoryReadRequest(
        namespace = TEST_NAMESPACE,
        threadContext = MemoryThreadContext(
            conversationId = Conversation.Id("conversation"),
            threadId = Conversation.Thread.Id("read-thread"),
            targetMessageId = targetMessage.id,
            messages = listOf(targetMessage),
        ),
    )
}

private fun projectFactReadPlan(
    projectName: String,
    claims: Int,
    notes: Int,
): MemoryReadPlan =
    MemoryReadPlan(
        needMemory = true,
        contextMode = MemoryReadPlan.ContextMode.FACTUAL,
        coreBlocks = setOf(MemoryReadPlan.CoreBlock.PROFILE),
        retrievalBudget = MemoryRetrievalBudget(claims = claims, notes = notes),
        retrievalRequests = listOf(
            MemoryReadPlan.RetrievalRequest(
                memoryType = MemorySemanticType.CLAIM,
                why = "Retrieve project-specific facts about $projectName.",
                query = "$projectName project facts purpose export field",
                topK = claims,
            ),
            MemoryReadPlan.RetrievalRequest(
                memoryType = MemorySemanticType.NOTE,
                why = "Retrieve project-specific context about $projectName.",
                query = "$projectName project context",
                topK = notes,
            )
        ),
    )

private fun MemoryStore.SearchHit.toTestItemRef(): MemoryItemRef =
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

private fun MemoryNamespaceSnapshot.sourceById(id: String): MemorySource =
    sources.single { it.id.value == id }

private fun MemoryNamespaceSnapshot.entityById(id: String): MemoryEntity =
    entities.single { it.id.value == id }

private fun MemoryNamespaceSnapshot.claimById(id: String): MemoryClaim =
    claims.single { it.id.value == id }

private fun MemoryNamespaceSnapshot.noteById(id: String): MemoryNote =
    notes.single { it.id.value == id }

private fun MemoryNamespaceSnapshot.taskById(id: String): MemoryActionItem =
    actionItems.single { it.id.value == id }

private fun MemoryNamespaceSnapshot.episodeById(id: String): MemoryEpisode =
    episodes.single { it.id.value == id }
