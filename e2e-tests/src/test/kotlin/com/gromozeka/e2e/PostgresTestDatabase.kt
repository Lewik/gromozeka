package com.gromozeka.e2e

import java.sql.DriverManager
import java.util.UUID

internal class PostgresTestDatabase private constructor(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    private val adminJdbcUrl: String,
    private val databaseName: String,
) : AutoCloseable {
    override fun close() {
        DriverManager.getConnection(adminJdbcUrl, username, password).use { connection ->
            connection.prepareStatement(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = ? AND pid <> pg_backend_pid()"
            ).use { statement ->
                statement.setString(1, databaseName)
                statement.execute()
            }
            connection.createStatement().use { statement ->
                statement.execute("DROP DATABASE IF EXISTS \"$databaseName\"")
            }
        }
    }

    companion object {
        fun create(): PostgresTestDatabase {
            val adminJdbcUrl = System.getenv("GROMOZEKA_E2E_POSTGRES_URL")
                ?: "jdbc:postgresql://127.0.0.1:5432/postgres"
            val username = System.getenv("GROMOZEKA_E2E_POSTGRES_USERNAME") ?: "gromozeka"
            val password = System.getenv("GROMOZEKA_E2E_POSTGRES_PASSWORD") ?: "gromozeka"
            val databaseName = "gromozeka_e2e_${UUID.randomUUID().toString().replace("-", "")}"

            DriverManager.getConnection(adminJdbcUrl, username, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE DATABASE \"$databaseName\"")
                }
            }

            return PostgresTestDatabase(
                jdbcUrl = adminJdbcUrl.withDatabase(databaseName),
                username = username,
                password = password,
                adminJdbcUrl = adminJdbcUrl,
                databaseName = databaseName,
            )
        }
    }
}

private fun String.withDatabase(databaseName: String): String {
    val queryStart = indexOf('?').takeIf { it >= 0 } ?: length
    val base = substring(0, queryStart)
    val query = substring(queryStart)
    val databaseSeparator = base.lastIndexOf('/')
    require(databaseSeparator >= "jdbc:postgresql://".length) {
        "GROMOZEKA_E2E_POSTGRES_URL must include an admin database name: $this"
    }
    return base.substring(0, databaseSeparator + 1) + databaseName + query
}
