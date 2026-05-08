package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.infrastructure.db.persistence.mongo.MongoIndexInitializer
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import klog.KLoggers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Service
import java.security.MessageDigest

@Service
class EmbeddingCacheService(
    database: MongoDatabase,
) {
    private val log = KLoggers.logger(this)
    private val embeddings: MongoCollection<Entry> = database.getCollection("embedding_cache")
    private val indexes = MongoIndexInitializer {
        embeddings.createIndex(
            Indexes.ascending("textHash", "model"),
            IndexOptions().unique(true),
        )
    }

    fun getCachedEmbedding(text: String, model: String): FloatArray? = runBlocking {
        val hash = computeHash(text)
        indexes.ensure()
        embeddings.find(Filters.and(Filters.eq("textHash", hash), Filters.eq("model", model)))
            .firstOrNull()
            ?.embeddingVector
            ?.toFloatArray()
    }

    fun cacheEmbedding(text: String, model: String, embedding: FloatArray) = runBlocking {
        val hash = computeHash(text)
        indexes.ensure()
        val exists = embeddings.countDocuments(
            Filters.and(Filters.eq("textHash", hash), Filters.eq("model", model)),
        ) > 0

        if (!exists) {
            embeddings.insertOne(
                Entry(
                    textHash = hash,
                    model = model,
                    embeddingVector = embedding.toList(),
                    createdAt = Clock.System.now(),
                ),
            )
        }
    }

    private fun computeHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(text.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    @Serializable
    private data class Entry(
        val textHash: String,
        val model: String,
        val embeddingVector: List<Float>,
        val createdAt: Instant,
    )
}
