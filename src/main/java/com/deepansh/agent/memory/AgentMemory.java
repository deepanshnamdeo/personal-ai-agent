package com.deepansh.agent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Long-term memory document stored in MongoDB.
 *
 * Collection: agent_memories
 *
 * MongoDB advantages over PostgreSQL here:
 * - Embedding vector stored as a native List<Double> (no extension needed)
 * - Atlas Vector Search uses the same collection — no separate index table
 * - Schema-flexible: different memory types can carry different metadata
 * - Natural document fit: a memory is self-contained, not relational
 *
 * Indexes:
 * - userId (single): fast per-user lookups
 * - (userId, tag) compound: tag-filtered queries
 * - createdAt (TTL optional): could auto-expire old memories
 */
@Document(collection = "agent_memories")
@CompoundIndexes({
    @CompoundIndex(name = "idx_user_tag",  def = "{'userId': 1, 'tag': 1}"),
    @CompoundIndex(name = "idx_user_date", def = "{'userId': 1, 'createdAt': -1}")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMemory {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String content;

    /** fact | preference | task | context */
    private String tag;

    private String sourceSessionId;

    /**
     * Embedding vector for semantic search.
     * 1536 floats for text-embedding-3-small.
     * For Atlas Vector Search: create a search index on this field.
     * For local dev: use cosine similarity in application code.
     */
    private List<Double> embedding;

    @CreatedDate
    private Instant createdAt;
}
