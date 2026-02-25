-- Run this once on your PostgreSQL instance to enable pgvector
-- Requires PostgreSQL 12+ with the pgvector extension installed
-- Docker: use pgvector/pgvector:pg16 image instead of plain postgres:16

-- 1. Enable the extension
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Add embedding column to agent_memories
-- 1536 dimensions = OpenAI text-embedding-3-small
ALTER TABLE agent_memories
    ADD COLUMN IF NOT EXISTS embedding vector(1536);

-- 3. Create HNSW index for fast approximate nearest-neighbor search
-- HNSW is faster at query time than IVFFlat; builds slower but fine for personal use
CREATE INDEX IF NOT EXISTS idx_memory_embedding_hnsw
    ON agent_memories
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 4. Verify
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'agent_memories';
