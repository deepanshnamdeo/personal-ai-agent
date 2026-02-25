package com.deepansh.agent.memory;

import com.deepansh.agent.model.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis-backed short-term memory — stores the conversation window for a session.
 *
 * Design decisions:
 * - Key pattern: agent:session:{sessionId}:messages
 * - Stored as a single JSON array (atomic read/write for a session)
 * - TTL reset on every write — idle sessions expire automatically
 * - Sliding window: only the last N messages are kept to control token usage
 *
 * Trade-off: storing as a single JSON blob is simpler but not ideal for very
 * long sessions. For 20-message windows this is fine. If we need append-only
 * with trimming, switch to Redis LIST with LPUSH + LTRIM.
 */
@Component
@Slf4j
public class ShortTermMemory {

    private static final String KEY_PREFIX = "agent:session:";
    private static final String KEY_SUFFIX = ":messages";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${agent.memory.short-term.ttl-minutes:60}")
    private long ttlMinutes;

    @Value("${agent.memory.short-term.max-messages:20}")
    private int maxMessages;

    public ShortTermMemory(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Load conversation history for a session.
     * Returns empty list if session doesn't exist or has expired.
     */
    public List<Message> load(String sessionId) {
        String key = buildKey(sessionId);
        String json = redisTemplate.opsForValue().get(key);

        if (json == null) {
            log.debug("No short-term memory found for session: {}", sessionId);
            return new ArrayList<>();
        }

        try {
            List<Message> messages = objectMapper.readValue(json, new TypeReference<>() {});
            log.debug("Loaded {} messages from short-term memory for session: {}",
                    messages.size(), sessionId);
            return messages;
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize messages for session: {}. Returning empty.", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * Persist the full message list for a session.
     * Applies sliding window (keeps last maxMessages) and resets TTL.
     */
    public void save(String sessionId, List<Message> messages) {
        List<Message> windowed = applyWindow(messages);
        String key = buildKey(sessionId);

        try {
            String json = objectMapper.writeValueAsString(windowed);
            redisTemplate.opsForValue().set(key, json, Duration.ofMinutes(ttlMinutes));
            log.debug("Saved {} messages to short-term memory for session: {} (TTL: {}m)",
                    windowed.size(), sessionId, ttlMinutes);
        } catch (JsonProcessingException e) {
            // Log and continue — memory write failure shouldn't crash the agent
            log.error("Failed to serialize messages for session: {}", sessionId, e);
        }
    }

    /**
     * Explicitly delete a session (e.g. user requests clear conversation).
     */
    public void clear(String sessionId) {
        redisTemplate.delete(buildKey(sessionId));
        log.info("Cleared short-term memory for session: {}", sessionId);
    }

    public boolean exists(String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(sessionId)));
    }

    /**
     * Sliding window — always preserves the system message (index 0) and
     * keeps the last (maxMessages - 1) non-system messages.
     */
    private List<Message> applyWindow(List<Message> messages) {
        if (messages.size() <= maxMessages) {
            return messages;
        }

        List<Message> result = new ArrayList<>();

        // Always keep system message
        if (!messages.isEmpty() && messages.get(0).getRole() == Message.Role.system) {
            result.add(messages.get(0));
            List<Message> rest = messages.subList(1, messages.size());
            int from = Math.max(0, rest.size() - (maxMessages - 1));
            result.addAll(rest.subList(from, rest.size()));
        } else {
            int from = Math.max(0, messages.size() - maxMessages);
            result.addAll(messages.subList(from, messages.size()));
        }

        log.debug("Applied sliding window: {} → {} messages", messages.size(), result.size());
        return result;
    }

    private String buildKey(String sessionId) {
        return KEY_PREFIX + sessionId + KEY_SUFFIX;
    }
}
