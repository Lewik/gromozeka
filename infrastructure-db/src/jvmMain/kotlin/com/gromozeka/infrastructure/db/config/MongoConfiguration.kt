package com.gromozeka.infrastructure.db.config

import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters
import com.mongodb.connection.ClusterSettings
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

@Configuration
class MongoConfiguration {
    @Bean
    fun mongoClient(
        @Value("\${gromozeka.mongodb.uri:mongodb://localhost:27017}") uri: String,
    ): MongoClient {
        val settings = MongoClientSettings.builder()
            .applyConnectionString(com.mongodb.ConnectionString(uri))
            .applyToClusterSettings { cluster: ClusterSettings.Builder ->
                cluster.serverSelectionTimeout(3, TimeUnit.SECONDS)
            }
            .build()

        return MongoClient.create(settings)
    }

    @Bean
    fun mongoDatabase(
        mongoClient: MongoClient,
        @Value("\${gromozeka.mongodb.database:gromozeka}") databaseName: String,
    ): MongoDatabase {
        val database = mongoClient.getDatabase(databaseName)

        runBlocking {
            database
                .getCollection<Document>("__startup_probe")
                .find(Filters.empty())
                .limit(1)
                .firstOrNull()
        }

        return database
    }
}
