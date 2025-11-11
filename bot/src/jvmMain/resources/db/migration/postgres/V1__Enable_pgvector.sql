-- Enable pgvector extension for PostgreSQL vector similarity search
-- This migration only applies when using PostgreSQL (not SQLite)
-- The vector store table will be created automatically by Spring AI PgVectorStore

CREATE EXTENSION IF NOT EXISTS vector;
