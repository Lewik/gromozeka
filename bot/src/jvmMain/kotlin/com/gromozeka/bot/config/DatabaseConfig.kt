package com.gromozeka.bot.config

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import kotlin.io.path.createDirectories

@Configuration
class DatabaseConfig {

    @Bean
    fun flyway(appDataPath: Path): Flyway {
        val dbPath = appDataPath.resolve("gromozeka.db")
        dbPath.parent.createDirectories()

        val flyway = Flyway.configure()
            .dataSource("jdbc:sqlite:$dbPath", "", "")
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()

        flyway.migrate()

        return flyway
    }

    @Bean
    fun database(appDataPath: Path, flyway: Flyway): Database {
        val dbPath = appDataPath.resolve("gromozeka.db")

        return Database.connect(
            url = "jdbc:sqlite:$dbPath",
            driver = "org.sqlite.JDBC"
        )
    }
}
