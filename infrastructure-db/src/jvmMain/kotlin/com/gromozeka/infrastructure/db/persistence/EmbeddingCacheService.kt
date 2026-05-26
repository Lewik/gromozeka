package com.gromozeka.infrastructure.db.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.security.MessageDigest
import javax.sql.DataSource

@Service
class EmbeddingCacheService(
    private val dataSource: DataSource,
) {
    suspend fun getCachedEmbedding(text: String, model: String, dimensions: Int): FloatArray? = withContext(Dispatchers.IO) {
        val hash = computeHash(text)
        val cacheModel = cacheModel(model, dimensions)
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT embedding_vector FROM embedding_cache WHERE text_hash = ? AND model = ?").use { statement ->
                statement.setString(1, hash)
                statement.setString(2, cacheModel)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) return@withContext null
                    resultSet.getString(1)
                        .split(',')
                        .filter { it.isNotBlank() }
                        .map { it.toFloat() }
                        .toFloatArray()
                }
            }
        }
    }

    suspend fun cacheEmbedding(text: String, model: String, dimensions: Int, embedding: FloatArray) = withContext(Dispatchers.IO) {
        val hash = computeHash(text)
        val cacheModel = cacheModel(model, dimensions)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO embedding_cache (text_hash, model, embedding_vector, created_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (text_hash, model) DO NOTHING
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, hash)
                statement.setString(2, cacheModel)
                statement.setString(3, embedding.joinToString(","))
                statement.executeUpdate()
            }
        }
    }

    private fun cacheModel(model: String, dimensions: Int): String =
        "$model#$dimensions"

    private fun computeHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(text.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
