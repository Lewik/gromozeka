package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.AiCallAdmission
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock

/** Adapter tests control admission independently of provider fixtures; queue policy has its own tests. */
class TestAiAdmission(limit: Int = 64) : AiCallAdmission() {
    private val permits = Semaphore(limit)
    val active = ConcurrentHashMap<String, AiAdmissionLease>()
    val admitted = CopyOnWriteArrayList<AiAdmissionOperation>()
    val completed = CopyOnWriteArrayList<Pair<AiAdmissionOperation, AiAdmissionOutcome>>()

    override suspend fun acquire(operation: AiAdmissionOperation): AiAdmissionLease {
        permits.acquire()
        val lease = AiAdmissionLease(operation, AiConcurrencyGroup.DEFAULT_ID, Clock.System.now())
        check(active.putIfAbsent(operation.id, lease) == null)
        admitted += operation
        return lease
    }

    override suspend fun finish(lease: AiAdmissionLease, outcome: AiAdmissionOutcome) {
        check(active.remove(lease.operation.id) != null)
        completed += lease.operation to outcome
        permits.release()
    }
}
