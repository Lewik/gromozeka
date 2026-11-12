package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.AiAdmissionRepository
import com.gromozeka.domain.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

class ServerAiTpmAdmissionTest {
    @Test fun `feedback error fails only its call and duplicate completion preserves the error without another debit`() = runBlocking {
        val fixture = Fixture()
        try {
            val first = fixture.service.openHttpSession(fixture.model, fixture.request, "TEST", null)
            assertEquals(1, fixture.service.snapshot.active.size)
            val reservation = requireNotNull(fixture.service.snapshot.active.single().operation.tpm)
            val persisted = fixture.repository.states.single()
            val current = fixture.service.snapshot.tpmGroups.single()
            assertEquals(reservation.groupId, persisted.groupId)
            assertEquals(reservation.cumulativeBefore + reservation.estimatedUnits, persisted.cumulativeAdmitted)
            assertEquals(persisted.groupId, current.groupId)
            assertEquals(persisted.cumulativeAdmitted, current.cumulativeAdmitted)
            assertEquals(persisted.cooldownUntil, current.cooldownUntil)
            assertTrue(current.localRemaining >= persisted.localRemaining)
            assertTrue(current.observedRemaining >= persisted.observedRemaining)
            val bad = AiHttpQuotaFeedback(200, emptyMap())
            assertFailsWith<AiAdmissionException> { first.finish(AiAdmissionOutcome.SUCCEEDED, bad) }
            assertTrue(fixture.service.snapshot.active.isEmpty())
            val after = fixture.repository.states
            assertFailsWith<AiAdmissionException> { first.finish(AiAdmissionOutcome.SUCCEEDED, bad) }
            assertEquals(after, fixture.repository.states)
            val next = fixture.service.openHttpSession(fixture.model, fixture.request, "TEST", null)
            next.finish(AiAdmissionOutcome.SUCCEEDED, AiHttpQuotaFeedback(200, mapOf("quota" to listOf("800"))))
            assertTrue(fixture.service.snapshot.active.isEmpty())
            assertEquals(2, fixture.repository.completions.size)
        } finally { fixture.close() }
    }

    @Test fun `a configured TPM policy cannot be bypassed by a non HTTP adapter`() = runBlocking {
        val fixture = Fixture()
        try {
            assertFailsWith<AiAdmissionException> { fixture.service.execute(fixture.model) { error("Must not run") } }
            assertTrue(fixture.service.snapshot.active.isEmpty())
        } finally { fixture.close() }
    }

    private class Fixture {
        private val group = AiTpmGroup(AiTpmGroup.Id("quota-id"), "model-key", "Quota", 1000)
        private val seed = RuntimeCatalogTemplateApplicationService().createSeed().aiCatalog
        val model = seed.modelConfigurations.first { configuration -> seed.connections.any {
            it.id == configuration.connectionId && it.kind == AiConnection.Kind.OPENAI_API
        } }.copy(tpm = AiTpmPolicy(AiTpmGroupSelector.Fixed(group.id), AiQuotaCalculator.BuiltIn(),
            AiTpmFeedbackPolicy.Fields(AiQuotaValueSource.Header("quota"))))
        private val catalog = seed.copy(modelConfigurations = seed.modelConfigurations.map { if (it.id == model.id) model else it }, tpmGroups = listOf(group))
        private val configuration = object : AiConfigurationProvider {
            override val snapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(AiCatalogSnapshot(this@Fixture.catalog, 1))
            override val snapshot get() = requireNotNull(snapshotFlow.value)
            override fun resolveAiRuntime(selection: AiRuntimeSelection) = catalog.resolveRuntime(selection)
        }
        val repository = Repository()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = ServerAiCallAdmission(configuration, repository, scope, AiQuotaPolicyEvaluator()).also { it.initialize() }
        val request = Json.parseToJsonElement("""{"model":"provider-model","input":"hello"}""")
        fun close() { service.stop(); scope.cancel() }
    }

    private class Repository : AiAdmissionRepository {
        val leases = ConcurrentHashMap<String, AiAdmissionLease>()
        val completions = ConcurrentHashMap<String, Pair<String?, AiAdmissionCompletionResult>>()
        @Volatile var states = emptyList<AiTpmState>()
        override suspend fun restoreRemoteLeases() = leases.values.filter { it.workerId != null }
        override suspend fun findCompletion(id: String, workerId: String?): AiAdmissionCompletionResult? = completions[id]?.let {
            require(it.first == workerId); it.second
        }
        override suspend fun loadTpmStates() = states
        override suspend fun create(lease: AiAdmissionLease, tpmStates: List<AiTpmState>) { check(leases.putIfAbsent(lease.operation.id, lease) == null); states = tpmStates }
        override suspend fun accept(id: String, workerId: String) { leases.compute(id) { _, lease -> requireNotNull(lease).also { require(it.workerId == workerId) }.copy(accepted = true) } }
        override suspend fun finish(id: String, workerId: String?, tpmStates: List<AiTpmState>, feedbackError: String?) {
            leases.remove(id)?.let { require(it.workerId == workerId); completions[id] = workerId to AiAdmissionCompletionResult(feedbackError) }
            states = tpmStates
        }
        override suspend fun finishWorkerRequest(workerId: String, requestId: String) {
            leases.values.filter { it.workerId == workerId && it.workerRequestId == requestId && (it.operation.backgroundSessionId == null || !it.accepted) }
                .forEach { leases.remove(it.operation.id) }
        }
    }
}
