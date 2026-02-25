package com.deepansh.agent.memory;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Semantic (vector) memory search using pgvector cosine similarity.
 *
 * This upgrades LongTermMemory's keyword (ILIKE) search to embedding-based
 * similarity search — much better recall for paraphrased or conceptually
 * related queries.
 *
 * Example:
 *   Stored memory: "User prefers concise responses"
 *   Query: "how does the user like answers formatted?"
 *   ILIKE: no match (no shared keywords)
 *   pgvector: strong match (semantic similarity)
 *
 * Architecture:
 * - embeddings are generated async after each memory write (non-blocking)
 * - search requires embedding generation for the query (one API call, cached)
 * - falls back gracefully to keyword search if embedding column is empty
 *
 * SQL uses pgvector's <=> operator (cosine distance).
 * Lower distance = more similar. We return top-K by cosine similarity.
 */
@Service
@Slf4j
public class SemanticMemoryService implements ApplicationContextAware {

    private static final int DEFAULT_TOP_K = 5;
    private static final double SIMILARITY_THRESHOLD = 0.25;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final AgentMemoryRepository memoryRepository;
    private ApplicationContext applicationContext;

    public SemanticMemoryService(JdbcTemplate jdbcTemplate,
                                  EmbeddingService embeddingService,
                                  AgentMemoryRepository memoryRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.memoryRepository = memoryRepository;
    }

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.applicationContext = ctx;
    }

    private SemanticMemoryService self() {
        return applicationContext.getBean(SemanticMemoryService.class);
    }

    /**
     * Semantic search: find the top-K most relevant memories for a query.
     * Returns memories sorted by cosine similarity (most relevant first).
     */
    public List<AgentMemory> semanticSearch(String userId, String query, int topK) {
        float[] queryEmbedding = embeddingService.embed(query);
        String embeddingStr = toPostgresVector(queryEmbedding);

        log.debug("Semantic search [userId={}, query='{}', topK={}]", userId, query, topK);

        // pgvector cosine distance operator: <=>
        // Lower value = more similar. SIMILARITY_THRESHOLD filters noise.
        String sql = """
                SELECT id, user_id, content, tag, source_session_id, created_at,
                       (embedding <=> ?::vector) AS distance
                FROM agent_memories
                WHERE user_id = ?
                  AND embedding IS NOT NULL
                  AND (embedding <=> ?::vector) < ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql,
                embeddingStr, userId, embeddingStr, SIMILARITY_THRESHOLD, embeddingStr, topK);

        List<AgentMemory> results = rows.stream()
                .map(this::mapRowToMemory)
                .toList();

        log.info("Semantic search returned {} results for query='{}'", results.size(), query);
        return results;
    }

    /**
     * Convenience overload with default topK.
     */
    public List<AgentMemory> semanticSearch(String userId, String query) {
        return semanticSearch(userId, query, DEFAULT_TOP_K);
    }

    /**
     * Generate and store an embedding for a memory asynchronously.
     * Called after each memory write — does not block the write path.
     */
    @Async("memoryTaskExecutor")
    @Transactional
    public void embedMemoryAsync(Long memoryId, String content) {
        try {
            float[] embedding = embeddingService.embed(content);
            String embeddingStr = toPostgresVector(embedding);

            jdbcTemplate.update(
                    "UPDATE agent_memories SET embedding = ?::vector WHERE id = ?",
                    embeddingStr, memoryId
            );

            log.debug("Embedding stored for memory id={}", memoryId);
        } catch (Exception e) {
            // Embedding failure must never crash memory storage
            log.error("Failed to embed memory id={}", memoryId, e);
        }
    }

    /**
     * Batch re-embed all memories for a user that have no embedding yet.
     * Useful for backfilling after first pgvector setup.
     */
    public int backfillEmbeddings(String userId) {
        List<AgentMemory> unembedded = jdbcTemplate.query(
                "SELECT * FROM agent_memories WHERE user_id = ? AND embedding IS NULL",
                (rs, rowNum) -> {
                    AgentMemory m = new AgentMemory();
                    m.setId(rs.getLong("id"));
                    m.setContent(rs.getString("content"));
                    return m;
                },
                userId
        );

        log.info("Backfilling {} embeddings for userId={}", unembedded.size(), userId);

        unembedded.forEach(m -> self().embedMemoryAsync(m.getId(), m.getContent()));
        return unembedded.size();
    }

    /**
     * Converts float[] to PostgreSQL vector literal format.
     * E.g: [0.1, 0.2, 0.3] → "[0.1,0.2,0.3]"
     */
    private String toPostgresVector(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private AgentMemory mapRowToMemory(Map<String, Object> row) {
        AgentMemory m = new AgentMemory();
        m.setId(((Number) row.get("id")).longValue());
        m.setUserId((String) row.get("user_id"));
        m.setContent((String) row.get("content"));
        m.setTag((String) row.get("tag"));
        m.setSourceSessionId((String) row.get("source_session_id"));
        return m;
    }
}
