package com.gromozeka.worker.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface WorkerRequestSnapshotStore {
    suspend fun read(): String?
    suspend fun write(snapshot: String)
}

class SnapshotWorkerRequestJournal(
    private val store: WorkerRequestSnapshotStore,
    private val maxStoredBytes: Int = 8 * 1024 * 1024,
) : WorkerRequestJournal {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }

    init { require(maxStoredBytes > 0) }

    override suspend fun load(): List<WorkerRequestReceipt> = mutex.withLock { read() }

    override suspend fun save(receipt: WorkerRequestReceipt) = mutex.withLock {
        write(read().filterNot { it.id == receipt.id } + receipt)
    }

    override suspend fun delete(id: String) = mutex.withLock {
        write(read().filterNot { it.id == id })
    }

    private suspend fun read(): List<WorkerRequestReceipt> = store.read()?.let { snapshot ->
        require(snapshot.encodeToByteArray().size <= maxStoredBytes) { "Worker request journal exceeds its storage limit" }
        json.decodeFromString<List<WorkerRequestReceipt>>(snapshot).also { receipts ->
            require(receipts.map { it.id }.distinct().size == receipts.size) { "Worker journal contains duplicate request IDs" }
        }
    } ?: emptyList()

    private suspend fun write(receipts: List<WorkerRequestReceipt>) {
        val snapshot = json.encodeToString(receipts)
        check(snapshot.encodeToByteArray().size <= maxStoredBytes) { "Worker request journal is full" }
        store.write(snapshot)
        check(store.read() == snapshot) { "Worker request journal could not be persisted" }
    }
}
