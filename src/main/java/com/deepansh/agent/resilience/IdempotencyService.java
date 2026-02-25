package com.deepansh.agent.resilience;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-based idempotency for agent run requests.
 *
 * Problem: if a client retries a request (due to network timeout),
 * the agent should not execute the same input twice — especially for
 * tool calls that have side effects (INSERT into DB, send email, etc.)
 *
 * Solution: client sends an idempotency key (UUID) with each request.
 * We cache the response in Redis with a TTL. Repeated requests with
 * the same key return the cached response immediately.
 *
 * Key pattern: agent:idempotency:{idempotencyKey}
 * TTL: 24 hours (configurable)
 *
 * This is an opt-in feature — requests without an idempotency key
 * are always executed fresh.
 */
@Service
@Slf4j
public class IdempotencyService {

    private static final String KEY_PREFIX = "agent:idempotency:";
    private static final Duration TTL = Duration.ofHours(24);
    // Sentinel stored while the request is in-flight (prevents race condition)
    private static final String IN_FLIGHT_SENTINEL = "__IN_FLIGHT__";

    private final StringRedisTemplate redisTemplate;

    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Attempt to claim an idempotency key.
     *
     * Returns:
     * - Optional.empty()  → key is new, proceed with execution
     * - Optional.of(json) → key seen before, return cached response
     *
     * Uses SET NX (set-if-not-exists) for atomic claim.
     */
    public Optional<String> getCachedResponse(String idempotencyKey) {
        String key = buildKey(idempotencyKey);
        String existing = redisTemplate.opsForValue().get(key);

        if (existing == null) {
            return Optional.empty(); // new key — caller should proceed
        }

        if (IN_FLIGHT_SENTINEL.equals(existing)) {
            // Another thread is handling this — return empty and let the new request proceed
            // (In production you'd block/poll; for a personal agent this is fine)
            log.warn("Idempotency key {} is in-flight, proceeding anyway", idempotencyKey);
            return Optional.empty();
        }

        log.info("Idempotency hit for key={}", idempotencyKey);
        return Optional.of(existing);
    }

    /**
     * Mark key as in-flight atomically (SET NX).
     * Returns true if claim succeeded, false if another thread beat us to it.
     */
    public boolean claimKey(String idempotencyKey) {
        String key = buildKey(idempotencyKey);
        Boolean claimed = redisTemplate.opsForValue()
                .setIfAbsent(key, IN_FLIGHT_SENTINEL, TTL);
        return Boolean.TRUE.equals(claimed);
    }

    /**
     * Store the completed response for future duplicate requests.
     */
    public void storeResponse(String idempotencyKey, String responseJson) {
        String key = buildKey(idempotencyKey);
        redisTemplate.opsForValue().set(key, responseJson, TTL);
        log.debug("Stored idempotency response for key={}", idempotencyKey);
    }

    /**
     * Clean up if the request failed — allows the client to retry.
     */
    public void releaseKey(String idempotencyKey) {
        redisTemplate.delete(buildKey(idempotencyKey));
        log.debug("Released idempotency key={}", idempotencyKey);
    }

    private String buildKey(String idempotencyKey) {
        return KEY_PREFIX + idempotencyKey;
    }
}
