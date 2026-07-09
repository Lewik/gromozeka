package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryPredicateCatalogDefaults
import com.gromozeka.domain.model.memory.MemorySource
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

class MemoryNamespaceRecallAccessGuardTest {
    @Test
    fun allowsRecallWhenStoreHasNoMemoryNamespaces() = runBlocking {
        MemoryNamespaceRecallAccessGuard(InMemoryMemoryStore())
            .ensureRecallSupported(MemoryNamespace("project:gromozeka"))
    }

    @Test
    fun allowsRecallWhenRequestedNamespaceIsTheOnlyMemoryNamespace() = runBlocking {
        val namespace = MemoryNamespace("project:gromozeka")
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(sources = listOf(source("source-a", namespace)))
        )

        MemoryNamespaceRecallAccessGuard(store).ensureRecallSupported(namespace)
    }

    @Test
    fun ignoresPredicateCatalogOnlyNamespaces() = runBlocking {
        val requestedNamespace = MemoryNamespace("project:gromozeka")
        val emptyTechnicalNamespace = MemoryNamespace("project:empty-probe")
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                predicateDefinitions = MemoryPredicateCatalogDefaults.forNamespace(emptyTechnicalNamespace)
            )
        )

        MemoryNamespaceRecallAccessGuard(store).ensureRecallSupported(requestedNamespace)
    }

    @Test
    fun blocksRecallWhenRequestedNamespaceWouldCreateASecondMemoryNamespace() = runBlocking {
        val existingNamespace = MemoryNamespace("project:gromozeka")
        val requestedNamespace = MemoryNamespace("user:lewik")
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(sources = listOf(source("source-a", existingNamespace)))
        )

        val error = assertFailsWith<MemoryNamespaceRecallAccessException> {
            MemoryNamespaceRecallAccessGuard(store).ensureRecallSupported(requestedNamespace)
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("requested_namespace=user:lewik"))
        assertTrue(message.contains("project:gromozeka"))
    }

    @Test
    fun blocksRecallWhenStoreAlreadyHasMultipleMemoryNamespaces() = runBlocking {
        val firstNamespace = MemoryNamespace("project:gromozeka")
        val secondNamespace = MemoryNamespace("work:hebrew")
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(
                    source("source-a", firstNamespace),
                    source("source-b", secondNamespace),
                )
            )
        )

        val error = assertFailsWith<MemoryNamespaceRecallAccessException> {
            MemoryNamespaceRecallAccessGuard(store).ensureRecallSupported(firstNamespace)
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("project:gromozeka"))
        assertTrue(message.contains("work:hebrew"))
    }

    private fun source(id: String, namespace: MemoryNamespace): MemorySource =
        MemorySource.ImportedNote(
            id = MemorySource.Id(id),
            namespace = namespace,
            contentText = "Test memory source $id",
            contentHash = "hash-$id",
            observedAt = NOW,
            createdAt = NOW,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
