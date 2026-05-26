package com.gromozeka.infrastructure.db.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import javax.sql.DataSource

@Configuration
class PostgresDatabaseConfiguration {
    @Bean
    @Primary
    fun postgresDataSource(
        @Value("\${gromozeka.postgres.jdbc-url:\${GROMOZEKA_POSTGRES_JDBC_URL:jdbc:postgresql://localhost:5432/gromozeka}}") jdbcUrl: String,
        @Value("\${gromozeka.postgres.username:\${GROMOZEKA_POSTGRES_USERNAME:gromozeka}}") username: String,
        @Value("\${gromozeka.postgres.password:\${GROMOZEKA_POSTGRES_PASSWORD:gromozeka}}") password: String,
        @Value("\${gromozeka.postgres.maximum-pool-size:10}") maximumPoolSize: Int,
    ): HikariDataSource {
        val config = HikariConfig()
        config.jdbcUrl = jdbcUrl
        config.username = username
        config.password = password
        config.maximumPoolSize = maximumPoolSize
        config.poolName = "gromozeka-postgres"
        return HikariDataSource(config)
    }

    @Bean
    @Primary
    fun postgresFlyway(
        dataSource: DataSource,
        @Value("\${gromozeka.postgres.schema:\${GROMOZEKA_POSTGRES_SCHEMA:public}}") schema: String,
    ): Flyway {
        require(schema.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid Postgres schema name: $schema" }

        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .locations("classpath:db/migration/postgres")
            .baselineOnMigrate(true)
            .load()

        flyway.migrate()

        return flyway
    }

    @Bean
    @Primary
    fun database(
        dataSource: DataSource,
        @Qualifier("postgresFlyway") flyway: Flyway,
    ): Database = Database.connect(dataSource)
}
