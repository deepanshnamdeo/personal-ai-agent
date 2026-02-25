package com.deepansh.agent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Semantic memory search backed by MongoDB.
 *
 * Two modes depending on environment:
 *
 * 1. MongoDB Atlas Vector Search (production):
 *    - Create a search index on the 'embedding' field (cosine similarity, 1536 dims)
 *    - Use $vectorSearch aggregation stage
 *    - Zero infrastructure overhead — Atlas manages the index
 *
 * 2. In-process cosine similarity (local dev / Community MongoDB):
 *    - Loads all user memories with embeddings into memory
 *    - Computes cosine similarity in Java
 *    - Works with any MongoDB instance — no Atlas required
 *    - Fine for personal agent scale (<500 memories per user)
 *
 * Embedding generation is @Async — never blocks the memory write path.
 * Self-invocation goes through ApplicationContext proxy to honour @Async.
 */
@Service
@Slf4j
public class SemanticMemoryService implements ApplicationContextAware {

    private static final int DEFAULT_TOP_K = 5;
    private static final double SIMILARITY_THRESHOLD = 0.75; // cosine similarity > 0.75 = relevant

    private final MongoTemplate mongoTemplate;
    private final EmbeddingService embeddingService;
    private final AgentMemoryRepository memoryRepository;
    private ApplicationContext applicationContext;

    public SemanticMemoryService(MongoTemplate mongoTemplate,
                                  EmbeddingService embeddingService,
                                  AgentMemoryRepository memoryRepository) {
        this.mongoTemplate = mongoTemplate;
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
     * Semantic search using in-process cosine similarity.
     *
     * For Atlas Vector Search, replace this with:
     *   mongoTemplate.aggregate(Aggregation.newAggregation(
     *       Aggregation.stage("{ $vectorSearch: { index: 'embedding_index',
     *           path: 'embedding', queryVector: [...], numCandidates: 100, limit: topK } }"))
     *   , AgentMemory.class, AgentMemory.class).getMappedResults();
     */
    public List<AgentMemory> semanticSearch(String userId, String query, int topK) {
        float[] queryEmbedding = embeddingService.embed(query);

        // Load all memories with embeddings for this user
        List<AgentMemory> candidates = memoryRepository.findByUserIdWithEmbedding(userId);

        if (candidates.isEmpty()) {
            log.debug("No embedded memories found for userId={}, falling back to keyword search", userId);
            return memoryRepository.searchByKeyword(userId, query);
        }

        // Score by cosine similarity and filter by threshold
        return candidates.stream()
                .filter(m -> m.getEmbedding() != null && !m.getEmbedding().isEmpty())
                .map(m -> new ScoredMemory(m, cosineSimilarity(queryEmbedding, toFloatArray(m.getEmbedding()))))
                .filter(sm -> sm.score() >= SIMILARITY_THRESHOLD)
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
                .limit(topK)
                .map(ScoredMemory::memory)
                .toList();
    }

    public List<AgentMemory> semanticSearch(String userId, String query) {
        return semanticSearch(userId, query, DEFAULT_TOP_K);
    }

    /**
     * Generate and persist the embedding for a memory document.
     * Called async after every store() — does not block the write path.
     */
    @Async("memoryTaskExecutor")
    public void embedMemoryAsync(String memoryId, String content) {
        try {
            float[] embedding = embeddingService.embed(content);
            List<Double> embeddingList = toDoubleList(embedding);

            Query query = new Query(Criteria.where("_id").is(memoryId));
            Update update = new Update().set("embedding", embeddingList);
            mongoTemplate.updateFirst(query, update, AgentMemory.class);

            log.debug("Embedding stored for memory id={}", memoryId);
        } catch (Exception e) {
            log.error("Failed to embed memory id={}", memoryId, e);
        }
    }

    /**
     * Backfill embeddings for all memories without one.
     * Run once after enabling semantic search on existing data.
     */
    public int backfillEmbeddings(String userId) {
        Query query = new Query(
                Criteria.where("userId").is(userId)
                        .and("embedding").exists(false));
        List<AgentMemory> unembedded = mongoTemplate.find(query, AgentMemory.class);

        log.info("Backfilling {} embeddings for userId={}", unembedded.size(), userId);
        unembedded.forEach(m -> self().embedMemoryAsync(m.getId(), m.getContent()));
        return unembedded.size();
    }

    // ─── Similarity math ────────────────────────────────────────────────────

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (normA == 0 || normB == 0) ? 0.0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private float[] toFloatArray(List<Double> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i).floatValue();
        return arr;
    }

    private List<Double> toDoubleList(float[] arr) {
        Double[] result = new Double[arr.length];
        for (int i = 0; i < arr.length; i++) result[i] = (double) arr[i];
        return List.of(result);
    }

    private record ScoredMemory(AgentMemory memory, double score) {}
}
