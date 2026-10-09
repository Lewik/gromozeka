package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.PreloadedWorkspaceToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import com.gromozeka.domain.tool.filesystem.GrzExecuteCommandTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PostgresAiToolContractRepositoryTest {
    @Test
    fun `contracts keep stable model names while different definitions coexist`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") {
            return@runBlocking
        }

        val schema = "ai_tool_contract_test_${UUID.randomUUID().toString().replace("-", "")}"
        val adminDataSource = dataSource()
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") }
        }

        try {
            val repositoryDataSource = dataSource(schema)
            applyMigration(repositoryDataSource)
            Database.connect(repositoryDataSource)
            val repository = ExposedAiToolContractRepository(Json)
            val firstDescriptor = descriptor("First contract.")
            val secondDescriptor = descriptor("Second contract.")

            val first = repository.resolveAll(listOf(firstDescriptor)).single()
            val resolved = repository.resolveAll(listOf(secondDescriptor, firstDescriptor))
            val second = resolved.single { it.descriptor == secondDescriptor }
            val firstAgain = resolved.single { it.descriptor == firstDescriptor }

            assertEquals("shared_tool", first.modelName)
            assertEquals(first, firstAgain)
            assertEquals("shared_tool__v2", second.modelName)
            assertNotEquals(first.fingerprint, second.fingerprint)
            assertEquals(
                resolved.map { it.modelName }.toSet(),
                ExposedAiToolContractRepository(Json)
                    .resolveAll(listOf(firstDescriptor, secondDescriptor))
                    .map { it.modelName }
                    .toSet(),
            )

            val concurrent = coroutineScope {
                listOf(descriptor("Third contract."), descriptor("Fourth contract."))
                    .map { descriptor ->
                        async(Dispatchers.IO) { repository.resolveAll(listOf(descriptor)).single() }
                    }
                    .map { it.await() }
            }
            assertEquals(setOf(3, 4), concurrent.map { it.variant }.toSet())
            assertEquals(2, concurrent.map { it.modelName }.toSet().size)

            val command = object : GrzExecuteCommandTool {
                override fun execute(request: ExecuteCommandRequest, context: ToolExecutionContext?): List<AiToolResult> =
                    error("Contract registration must not execute commands")
            }
            val commandDescriptor = AiToolDescriptor(
                AiToolDefinition(command.name, command.description, "{}"),
                command.metadata,
            )
            val storedCommand = repository.resolveAll(
                listOf(commandDescriptor.copy(metadata = PreloadedWorkspaceToolMetadata))
            ).single()
            assertEquals(storedCommand, repository.resolveAll(listOf(commandDescriptor)).single())

            repositoryDataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE ai_tool_contracts SET payload_json = jsonb_set(payload_json::jsonb, " +
                        "'{descriptor,metadata,supportsSlotContext}', 'true')::text WHERE fingerprint = ?"
                ).use { statement ->
                    statement.setString(1, storedCommand.fingerprint)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            applyMigration(repositoryDataSource, "V6__conversation_runtime_workers.sql")
            applyMigration(repositoryDataSource, "V69__remove_slot_support_metadata.sql")
            assertEquals(
                storedCommand,
                ExposedAiToolContractRepository(Json).resolveAll(listOf(commandDescriptor)).single(),
            )
        } finally {
            adminDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }

    private fun descriptor(description: String): AiToolDescriptor =
        AiToolDescriptor(
            definition = AiToolDefinition(
                name = "shared_tool",
                description = description,
                inputSchema = """{"type":"object","properties":{"value":{"type":"string"}}}""",
                source = "test",
            ),
            metadata = AiToolMetadata(executionScope = AiToolExecutionScope.WORKER),
        )

    private fun dataSource(schema: String? = null): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
            currentSchema = schema
        }

    private fun applyMigration(dataSource: DataSource, name: String = "V36__ai_tool_contracts.sql") {
        val migration = checkNotNull(
            javaClass.classLoader.getResource("db/migration/postgres/$name")
        ).readText()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                migration
                    .split(';')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach(statement::execute)
            }
        }
    }
}
