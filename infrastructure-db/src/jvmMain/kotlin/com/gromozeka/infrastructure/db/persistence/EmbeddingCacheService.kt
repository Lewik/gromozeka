package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.infrastructure.db.persistence.tables.EmbeddingCache
import klog.KLoggers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Service
import java.security.MessageDigest

@Service
class EmbeddingCacheService {
    private val log = KLoggers.logger(this)

    fun getCachedEmbedding(text: String, model: String): FloatArray? = runBlocking {
        val hash = computeHash(text)

        dbQuery {
            EmbeddingCache
                .selectAll()
                .where { (EmbeddingCache.textHash eq hash) and (EmbeddingCache.model eq model) }
                .singleOrNull()
                ?.let { row ->
                    val vectorJson = row[EmbeddingCache.embeddingVector]
                    parseEmbeddingVector(vectorJson)
                }
        }
    }

    fun cacheEmbedding(text: String, model: String, embedding: FloatArray) = runBlocking {
        val hash = computeHash(text)
        val vectorJson = serializeEmbeddingVector(embedding)

        dbQuery {
            // Check if already exists
            val exists = EmbeddingCache
                .selectAll()
                .where { (EmbeddingCache.textHash eq hash) and (EmbeddingCache.model eq model) }
                .count() > 0

            if (!exists) {
                EmbeddingCache.insert {
                    it[textHash] = hash
                    it[embeddingVector] = vectorJson
                    it[EmbeddingCache.model] = model
                    it[createdAt] = Clock.System.now().toKotlin()
                }
            }
        }
    }

    private fun computeHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(text.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun serializeEmbeddingVector(vector: FloatArray): String {
        return vector.joinToString(",", "[", "]") { it.toString() }
    }

    private fun parseEmbeddingVector(json: String): FloatArray {
        return json
            .trim('[', ']')
            .split(',')
            .map { it.trim().toFloat() }
            .toFloatArray()
    }
}
