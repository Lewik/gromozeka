# Local Memory Runtime Fixture

This fixture records stable runtime-storage facts for document-ingestion E2E coverage.

## Active Storage Path

Gromozeka persists typed runtime memory in PostgreSQL through the `MemoryStore` domain interface.

The active PostgreSQL adapter is `PostgresMemoryStore` in the `infrastructure-db` module.

## Domain Boundary

Memory pipelines depend on `MemoryStore`, not directly on PostgreSQL tables or the database adapter.

This keeps the memory domain contract independent from its storage implementation.
