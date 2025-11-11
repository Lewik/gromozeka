package com.gromozeka.bot.config

import klog.KLoggers
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.nio.file.Path
import kotlin.io.path.createDirectories

@Configuration
class DatabaseConfig {

    private val log = KLoggers.logger(this)

    @Bean
    @Primary
    fun sqliteFlyway(appDataPath: Path): Flyway {
        val dbPath = appDataPath.resolve("gromozeka.db")
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
    fun database(appDataPath: Path, @Qualifier("sqliteFlyway") flyway: Flyway): Database {
        val dbPath = appDataPath.resolve("gromozeka.db")

        return Database.connect(
            url = "jdbc:sqlite:$dbPath?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL",
            driver = "org.sqlite.JDBC"
        )
    }
}
