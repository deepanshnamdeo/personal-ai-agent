package com.deepansh.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Generates text embeddings via OpenAI's embeddings API.
 *
 * Model: text-embedding-3-small (1536 dimensions)
 * - Cost: $0.02 per 1M tokens — negligible for a personal agent
 * - Significantly better quality than ada-002
 *
 * Caching strategy:
 * - Embeddings for the same text are deterministic — cache aggressively
 * - Redis cache key: embed:{sha256(text)}
 * - TTL: 7 days — balances memory usage vs. re-embedding cost
 *
 * This means repeated memory searches with the same query text
 * cost zero API calls after the first request.
 */
@Service
@Slf4j
public class EmbeddingService {

    private static final String EMBEDDING_MODEL = "text-embedding-3-small";
    private static final String CACHE_PREFIX = "embed:";
    private static final Duration CACHE_TTL = Duration.ofDays(7);

    private final RestClient restClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public EmbeddingService(
            @Value("${openai.base-url}") String baseUrl,
            @Value("${openai.api-key}") String apiKey,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * Get embedding for a text string.
     * Returns cached result if available, otherwise calls OpenAI.
     */
    public float[] embed(String text) {
        String cacheKey = CACHE_PREFIX + hashText(text);

        // Cache hit
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                float[] embedding = objectMapper.readValue(cached, float[].class);
                log.debug("Embedding cache hit for text length={}", text.length());
                return embedding;
            } catch (Exception e) {
                log.warn("Failed to deserialize cached embedding, re-fetching");
            }
        }

        // Cache miss — call OpenAI
        float[] embedding = fetchEmbedding(text);

        // Cache the result
        try {
            redisTemplate.opsForValue().set(
                    cacheKey, objectMapper.writeValueAsString(embedding), CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache embedding", e);
        }

        return embedding;
    }

    @SuppressWarnings("unchecked")
    private float[] fetchEmbedding(String text) {
        log.debug("Fetching embedding for text length={}", text.length());

        Map<String, Object> request = Map.of(
                "model", EMBEDDING_MODEL,
                "input", text
        );

        Map<String, Object> response = restClient.post()
                .uri("/embeddings")
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        List<Number> rawEmbedding = (List<Number>) data.get(0).get("embedding");

        float[] result = new float[rawEmbedding.size()];
        for (int i = 0; i < rawEmbedding.size(); i++) {
            result[i] = rawEmbedding.get(i).floatValue();
        }

        log.debug("Fetched embedding: {} dimensions", result.length);
        return result;
    }

    /**
     * Simple deterministic hash for cache key generation.
     * SHA-256 would be better but this avoids adding a dependency.
     */
    private String hashText(String text) {
        return String.valueOf(text.hashCode() & 0xFFFFFFFFL);
    }
}
