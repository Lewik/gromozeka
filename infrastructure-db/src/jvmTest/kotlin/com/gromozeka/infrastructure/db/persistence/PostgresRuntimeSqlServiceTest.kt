package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.LocalPasswordCredential
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserIdentity
import com.gromozeka.domain.service.RuntimeSqlAccessDeniedException
import com.gromozeka.domain.service.RuntimeSqlExecutionException
import com.gromozeka.domain.service.RuntimeSqlRequest
import com.gromozeka.domain.tool.ToolCancellationSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class PostgresRuntimeSqlServiceTest {
    @Test
    fun `full SQL supports DDL DML returning and VACUUM with output-only limits`() = test {
        val result = sql.execute(owner.id, RuntimeSqlRequest(
            "CREATE TABLE sql_probe(id int PRIMARY KEY, value text); " +
                "INSERT INTO sql_probe SELECT n, 'item' FROM generate_series(1,10) n; " +
                "UPDATE sql_probe SET value='updated' RETURNING id; SELECT NULL::text AS x, 1::bigint AS x", maxRows = 2))
        assertTrue(result.results.any { it.updateCount == 10L })
        val returned = result.results.first { it.rowsTruncated }
        assertEquals(2, returned.rows.size)
        assertEquals("10", scalar("SELECT count(*) FROM sql_probe WHERE value='updated'"))
        val last = result.results.last()
        assertEquals(listOf("x", "x"), last.columns.map { it.name })
        assertEquals(listOf(null, "1"), last.rows.single())
        assertTrue(result.outputTruncated)
        assertFalse(result.openTransactionRolledBack)
        sql.execute(owner.id, RuntimeSqlRequest("CREATE SEQUENCE sql_probe_counter; SELECT nextval('sql_probe_counter') FROM generate_series(1,10)", maxRows = 2))
        assertEquals("10", scalar("SELECT last_value FROM sql_probe_counter"))
        sql.execute(owner.id, RuntimeSqlRequest("VACUUM sql_probe"))
        sql.execute(owner.id, RuntimeSqlRequest("DROP TABLE sql_probe"))
    }

    @Test
    fun `counts login permission rather than observed identities and rechecks every call`() = test {
        val observed = user("observed", login = false, telegram = true)
        repository.createUser(observed)
        assertEquals("1", sql.execute(owner.id, RuntimeSqlRequest("SELECT 1")).results.single().rows.single().single())
        assertFailsWith<RuntimeSqlAccessDeniedException> { sql.execute(observed.id, RuntimeSqlRequest("SELECT 1")) }
        val second = user("second")
        repository.createUser(second, credential(second))
        assertFailsWith<RuntimeSqlAccessDeniedException> { sql.execute(owner.id, RuntimeSqlRequest("SELECT 1")) }
        repository.updateUser(second.copy(loginAllowed = false))
        sql.execute(owner.id, RuntimeSqlRequest("SELECT 1"))
        repository.updateUser(owner.copy(role = User.Role.MEMBER))
        assertFailsWith<RuntimeSqlAccessDeniedException> { sql.execute(owner.id, RuntimeSqlRequest("SELECT 1")) }
        repository.updateUser(owner.copy(loginAllowed = false))
        assertFailsWith<RuntimeSqlAccessDeniedException> { sql.execute(owner.id, RuntimeSqlRequest("SELECT 1")) }
    }

    @Test
    fun `new login waits for SQL even across explicit COMMIT in its script`() = test {
        coroutineScope {
            val running = async(Dispatchers.IO) { sql.execute(owner.id, RuntimeSqlRequest("BEGIN; COMMIT; SELECT pg_sleep(2) /* sql-gate-test */")) }
            awaitQuery("%sql-gate-test%")
            val second = user("second")
            val creating = async(Dispatchers.IO) { repository.createUser(second, credential(second)) }
            assertNull(withTimeoutOrNull(200) { creating.await() })
            running.await()
            creating.await()
            assertFailsWith<RuntimeSqlAccessDeniedException> { sql.execute(owner.id, RuntimeSqlRequest("SELECT 1")) }
        }
    }

    @Test
    fun `login activation waits for SQL and disabled accounts do not block it`() = test {
        val second = user("second", login = false)
        repository.createUser(second, credential(second))
        coroutineScope {
            val running = async(Dispatchers.IO) { sql.execute(owner.id, RuntimeSqlRequest("SELECT pg_sleep(2) /* sql-activation-test */")) }
            awaitQuery("%sql-activation-test%")
            val enabling = async(Dispatchers.IO) { repository.updateUser(second.copy(loginAllowed = true)) }
            assertNull(withTimeoutOrNull(200) { enabling.await() })
            running.await()
            enabling.await()
            assertFailsWith<RuntimeSqlAccessDeniedException> { sql.execute(owner.id, RuntimeSqlRequest("SELECT 1")) }
        }
    }

    @Test
    fun `an unfinished explicit transaction rolls back and sessions never leak settings`() = test {
        command("CREATE TABLE sql_probe(id int)")
        val result = sql.execute(owner.id, RuntimeSqlRequest("BEGIN; INSERT INTO sql_probe VALUES(1)"))
        assertTrue(result.openTransactionRolledBack)
        assertEquals("0", scalar("SELECT count(*) FROM sql_probe"))
        sql.execute(owner.id, RuntimeSqlRequest("SET search_path TO public; SET application_name='changed-by-sql'"))
        val fresh = sql.execute(owner.id, RuntimeSqlRequest("SELECT current_schema(), current_setting('application_name')"))
        assertEquals(listOf(schema, applicationName), fresh.results.single().rows.single())
    }

    @Test
    fun `errors preserve SQLSTATE and never pretend previously committed changes were rolled back`() = test {
        command("CREATE TABLE sql_probe(id int)")
        val error = assertFailsWith<RuntimeSqlExecutionException> {
            sql.execute(owner.id, RuntimeSqlRequest("BEGIN; INSERT INTO sql_probe VALUES(1); COMMIT; SELECT 1/0"))
        }
        assertEquals("22012", error.sqlState)
        assertTrue(error.message.orEmpty().contains("do not retry automatically"))
        assertEquals("1", scalar("SELECT count(*) FROM sql_probe"))
    }

    @Test
    fun `query cancellation releases session and its login guard`() = test {
        val cancelled = AtomicBoolean(false)
        coroutineScope {
            val running = async(Dispatchers.IO) {
                sql.execute(owner.id, RuntimeSqlRequest("SELECT pg_sleep(30) /* sql-cancel-test */"),
                    ToolCancellationSignal { if (cancelled.get()) throw CancellationException("Test cancellation") })
            }
            awaitQuery("%sql-cancel-test%")
            cancelled.set(true)
            withTimeout(5_000) { assertFailsWith<CancellationException> { running.await() } }
        }
        withTimeout(5_000) { repository.updateUser(owner.copy(displayName = "Still available")) }
        sql.execute(owner.id, RuntimeSqlRequest("SELECT 1"))
    }

    @Test
    fun `parent coroutine cancellation also interrupts JDBC without a tool cancellation signal`() = test {
        coroutineScope {
            val running = async(Dispatchers.IO) {
                sql.execute(owner.id, RuntimeSqlRequest("SELECT pg_sleep(30) /* sql-parent-cancel-test */"))
            }
            awaitQuery("%sql-parent-cancel-test%")
            running.cancel()
            withTimeout(5_000) { running.join() }
        }
        withTimeout(5_000) { repository.updateUser(owner.copy(displayName = "Available after parent cancellation")) }
    }

    @Test
    fun `missing authorization table fails closed instead of falling back to public users`() = test {
        command("ALTER TABLE users RENAME TO hidden_users")
        assertFailsWith<RuntimeSqlExecutionException> {
            sql.execute(owner.id, RuntimeSqlRequest("CREATE TABLE must_not_exist(id int)"))
        }
        assertNull(scalar("SELECT to_regclass('$schema.must_not_exist')"))
    }

    @Test
    fun `large cells report truncation and optional timeout is effective`() = test {
        val result = sql.execute(owner.id, RuntimeSqlRequest("SELECT repeat('x', 20000)"))
        assertTrue(result.outputTruncated)
        assertTrue(result.results.single().cellsTruncated)
        assertEquals(16_384, result.results.single().rows.single().single()?.length)
        val error = assertFailsWith<RuntimeSqlExecutionException> {
            sql.execute(owner.id, RuntimeSqlRequest("SELECT pg_sleep(10)", timeoutSeconds = 1))
        }
        assertEquals("57014", error.sqlState)
    }

    private fun test(block: suspend Fixture.() -> Unit) = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.block()
        } finally { fixture.close() }
    }

    private class Fixture {
        val schema = "runtime_sql_test_${UUID.randomUUID().toString().replace("-", "")}"
        val applicationName = "sql-test-$schema"
        val source = PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5434/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
            currentSchema = "$schema,public"
            applicationName = this@Fixture.applicationName
        }
        val sql = PostgresRuntimeSqlService(source, schema)
        val repository = ExposedIdentityRepository()
        val owner = user("owner").copy(role = User.Role.OWNER)
        suspend fun initialize() {
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/postgres").load().migrate()
            Database.connect(source)
            repository.createUser(owner, credential(owner))
        }
        fun user(id: String, login: Boolean = true, telegram: Boolean = false) = User(
            User.Id(id), listOf(if (telegram) UserIdentity.Telegram(1234, id) else UserIdentity.LocalLogin(id)),
            id, User.Status.ACTIVE, createdAt = Clock.System.now(), updatedAt = Clock.System.now(), loginAllowed = login,
        )
        fun credential(user: User) = LocalPasswordCredential(user.id, "test-hash", Clock.System.now())
        fun command(sql: String) { source.connection.use { it.createStatement().use { statement -> statement.execute(sql) } } }
        fun scalar(sql: String): String? = source.connection.use { it.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
        } }
        suspend fun awaitQuery(pattern: String) = withTimeout(5_000) {
            while (true) {
                val active = source.connection.use { connection ->
                    connection.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND state='active' AND query LIKE ? AND pid <> pg_backend_pid()").use {
                        it.setString(1, applicationName); it.setString(2, pattern)
                        it.executeQuery().use { rows -> rows.next(); rows.getInt(1) > 0 }
                    }
                }
                if (active) break
                delay(25)
            }
        }
        fun close() { command("DROP SCHEMA IF EXISTS $schema CASCADE") }
    }
}
