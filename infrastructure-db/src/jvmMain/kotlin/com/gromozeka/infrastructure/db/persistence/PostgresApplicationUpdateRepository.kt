package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.ApplicationInstallation
import com.gromozeka.domain.repository.ApplicationUpdateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Repository
import javax.sql.DataSource

@Repository
class PostgresApplicationUpdateRepository(private val dataSource: DataSource) : ApplicationUpdateRepository {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun list(): List<ApplicationInstallation> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT document FROM application_installations ORDER BY id LIMIT 1000").use { statement ->
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(json.decodeFromString<ApplicationInstallation>(rows.getString(1)))
                } }
            }
        }
    }

    override suspend fun find(id: String): ApplicationInstallation? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT document FROM application_installations WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rows ->
                    if (rows.next()) json.decodeFromString<ApplicationInstallation>(rows.getString(1)) else null
                }
            }
        }
    }

    override suspend fun modify(id: String, initial: ApplicationInstallation?, transform: (ApplicationInstallation) -> ApplicationInstallation): ApplicationInstallation = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
                    it.setString(1, "application-update:$id")
                    it.execute()
                }
                val current = connection.prepareStatement("SELECT document FROM application_installations WHERE id = ? FOR UPDATE").use { statement ->
                    statement.setString(1, id)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) json.decodeFromString<ApplicationInstallation>(rows.getString(1)) else initial
                    }
                } ?: throw IllegalArgumentException("Installation not found")
                val updated = transform(current)
                check(updated.id == id)
                connection.prepareStatement("INSERT INTO application_installations(id, document) VALUES (?, ?::jsonb) ON CONFLICT (id) DO UPDATE SET document = EXCLUDED.document").use {
                    it.setString(1, id)
                    it.setString(2, json.encodeToString(updated))
                    it.executeUpdate()
                }
                connection.commit()
                updated
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }
}
