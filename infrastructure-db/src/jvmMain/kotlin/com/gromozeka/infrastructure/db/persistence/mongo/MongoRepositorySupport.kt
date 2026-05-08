package com.gromozeka.infrastructure.db.persistence.mongo

import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MongoIndexInitializer(
    private val createIndexes: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private var ready = false

    suspend fun ensure() {
        if (ready) return

        mutex.withLock {
            if (!ready) {
                createIndexes()
                ready = true
            }
        }
    }
}

internal suspend fun <T : Any> MongoCollection<T>.findByDomainId(id: String): T? =
    find(Filters.eq("id", id)).firstOrNull()

internal suspend fun <T : Any> MongoCollection<T>.insertNewByDomainId(
    id: String,
    document: T,
) {
    check(findByDomainId(id) == null) { "Document already exists: $id" }
    insertOne(document)
}

internal suspend fun <T : Any> MongoCollection<T>.upsertByDomainId(
    id: String,
    document: T,
) {
    replaceOne(
        Filters.eq("id", id),
        document,
        ReplaceOptions().upsert(true),
    )
}
