package com.gromozeka.bot.config

import com.gromozeka.bot.services.SettingsService
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import klog.KLoggers
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.nio.file.Path
import javax.sql.DataSource
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

    @Bean
    @Qualifier("postgresDataSource")
    fun postgresDataSource(settingsService: SettingsService): DataSource? {
        if (!settingsService.settings.vectorStorageEnabled) {
            log.info("Vector storage disabled, skipping PostgreSQL DataSource creation")
            return null
        }

        return try {
            val config = HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://localhost:45432/gromozeka"
                username = "postgres"
                password = "postgres"
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = 5
                minimumIdle = 1
                connectionTimeout = 10000
                validationTimeout = 5000
            }

            HikariDataSource(config).also {
                log.info("PostgreSQL DataSource created successfully for vector memory")
            }
        } catch (e: Exception) {
            log.warn("Failed to create PostgreSQL DataSource: ${e.message}. Vector memory will be unavailable.")
            null
        }
    }

    @Bean
    @Qualifier("postgresFlyway")
    fun postgresFlyway(@Qualifier("postgresDataSource") dataSource: DataSource?): Flyway? {
        if (dataSource == null) {
            log.debug("PostgreSQL DataSource not available, skipping Flyway migration")
            return null
        }

        return try {
            val flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/postgres")
                .baselineOnMigrate(true)
                .load()

            flyway.migrate()

            log.info("PostgreSQL Flyway migration completed successfully")
            flyway
        } catch (e: Exception) {
            log.warn("PostgreSQL Flyway migration failed: ${e.message}")
            null
        }
    }
}
