package com.gromozeka.infrastructure.db.config

import klog.KLoggers
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div

@Configuration
class DatabaseConfiguration {

    private val log = KLoggers.logger(this)

    @Bean
    @Primary
    fun sqliteFlyway(@Value("\${GROMOZEKA_HOME:build/test-data/.gromozeka}") gromozekaHome: String): Flyway {
        val dbPath = Path.of(gromozekaHome) / "gromozeka.db"
        dbPath.parent.createDirectories()

        val flyway = Flyway.configure()
            .dataSource("jdbc:sqlite:$dbPath", "", "")
            .locations("classpath:db/migration/sqlite")
            .baselineOnMigrate(true)
            .load()

        flyway.migrate()

        return flyway
    }

    @Bean
    @Primary
    fun database(
        @Value("\${GROMOZEKA_HOME:build/test-data/.gromozeka}") gromozekaHome: String,
        @Qualifier("sqliteFlyway") flyway: Flyway
    ): Database {
        val dbPath = Path.of(gromozekaHome) / "gromozeka.db"

        return Database.connect(
            url = "jdbc:sqlite:$dbPath?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL",
            driver = "org.sqlite.JDBC"
        )
    }
}
