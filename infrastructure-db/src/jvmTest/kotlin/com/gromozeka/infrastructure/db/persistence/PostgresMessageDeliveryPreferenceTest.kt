package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserMessageDeliveryMode
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.sql.SQLException
import java.util.UUID
import kotlin.test.*

class PostgresMessageDeliveryPreferenceTest {
    @Test fun `upgrade stores preferences per user with durable upsert and cascade`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "delivery_test_${UUID.randomUUID().toString().replace("-", "")}"
        val source = PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5434/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
            currentSchema = "$schema,public"
        }
        try {
            fun flyway() = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/postgres")
            flyway().target("69").load().migrate()
            source.connection.use { c -> c.createStatement().use {
                it.execute("INSERT INTO users(id,display_name,status,role,created_at,updated_at) VALUES ('a','Alice','ACTIVE','MEMBER',now(),now()),('b','Bob','ACTIVE','MEMBER',now(),now())")
            } }
            flyway().load().migrate()
            Database.connect(source)
            val repo = ExposedUserMessageDeliveryPreferenceRepository()
            val a = User.Id("a"); val b = User.Id("b")
            assertNull(repo.find(a))
            repo.save(a, UserMessageDeliveryMode.AFTER_CURRENT_TURN)
            assertNull(repo.find(b))
            assertEquals(UserMessageDeliveryMode.AFTER_CURRENT_TURN, ExposedUserMessageDeliveryPreferenceRepository().find(a))
            repo.save(a, UserMessageDeliveryMode.STEER)
            repo.save(b, UserMessageDeliveryMode.AFTER_CURRENT_TURN)
            assertEquals(UserMessageDeliveryMode.STEER, repo.find(a))
            assertEquals(UserMessageDeliveryMode.AFTER_CURRENT_TURN, repo.find(b))
            assertFailsWith<SQLException> {
                source.connection.use { c -> c.createStatement().use {
                    it.execute("UPDATE user_message_delivery_preferences SET delivery_mode='UNKNOWN' WHERE user_id='a'")
                } }
            }
            source.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM users WHERE id='a'") } }
            assertNull(repo.find(a))
            assertEquals(UserMessageDeliveryMode.AFTER_CURRENT_TURN, repo.find(b))
        } finally {
            source.connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }
}
