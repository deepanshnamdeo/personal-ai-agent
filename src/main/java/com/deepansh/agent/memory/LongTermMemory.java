package com.deepansh.agent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * PostgreSQL-backed long-term memory — persists facts and context across sessions.
 *
 * Design decisions:
 * - Facts are stored as plain text (content) with a categorical tag
 * - Per-user cap enforced to avoid unbounded growth (evicts oldest entries)
 * - Keyword search via ILIKE — sufficient for V1; swap for pgvector similarity search in V2
 * - Write path is @Transactional to ensure cap enforcement + insert are atomic
 *
 * Future upgrade path: add an `embedding` column (vector type via pgvector) and
 * use cosine similarity search instead of keyword matching for better recall.
 */
@Component
@Slf4j
public class LongTermMemory {

    private final AgentMemoryRepository repository;

    @Value("${agent.memory.long-term.max-facts-per-user:500}")
    private int maxFactsPerUser;

    public LongTermMemory(AgentMemoryRepository repository) {
        this.repository = repository;
    }

    /**
     * Store a new memory fact for a user.
     * Enforces per-user cap by evicting oldest entries if needed.
     */
    @Transactional
    public AgentMemory store(String userId, String content, String tag, String sessionId) {
        enforceCapIfNeeded(userId);

        AgentMemory memory = AgentMemory.builder()
                .userId(userId)
                .content(content)
                .tag(tag)
                .sourceSessionId(sessionId)
                .build();

        AgentMemory saved = repository.save(memory);
        log.info("Stored long-term memory [id={}] for user: {} [tag={}]",
                saved.getId(), userId, tag);
        return saved;
    }

    /**
     * Retrieve all memories for a user (most recent first).
     */
    public List<AgentMemory> loadAll(String userId) {
        List<AgentMemory> memories = repository.findByUserIdOrderByCreatedAtDesc(userId);
        log.debug("Loaded {} long-term memories for user: {}", memories.size(), userId);
        return memories;
    }

    /**
     * Retrieve memories filtered by tag (e.g. "preference", "fact").
     */
    public List<AgentMemory> loadByTag(String userId, String tag) {
        return repository.findByUserIdAndTagOrderByCreatedAtDesc(userId, tag);
    }

    /**
     * Keyword search across a user's memories.
     * Case-insensitive substring match — good for V1.
     */
    public List<AgentMemory> search(String userId, String keyword) {
        List<AgentMemory> results = repository.searchByKeyword(userId, keyword);
        log.debug("Keyword search '{}' for user {} returned {} results",
                keyword, userId, results.size());
        return results;
    }

    /**
     * Format memories as a compact string block to inject into the system prompt.
     */
    public String formatForPrompt(String userId) {
        List<AgentMemory> memories = loadAll(userId);
        if (memories.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("## What I remember about you:\n");
        memories.forEach(m -> sb.append("- [")
                .append(m.getTag() != null ? m.getTag() : "general")
                .append("] ")
                .append(m.getContent())
                .append("\n"));
        return sb.toString();
    }

    public void delete(Long memoryId) {
        repository.deleteById(memoryId);
        log.info("Deleted long-term memory id: {}", memoryId);
    }

    public long countForUser(String userId) {
        return repository.countByUserId(userId);
    }

    /**
     * Enforces the per-user memory cap.
     * Evicts the oldest entries when the cap is about to be exceeded.
     */
    private void enforceCapIfNeeded(String userId) {
        long count = repository.countByUserId(userId);
        if (count >= maxFactsPerUser) {
            int toDelete = (int) (count - maxFactsPerUser + 1);
            List<AgentMemory> oldest = repository.findOldestByUserId(userId)
                    .stream()
                    .limit(toDelete)
                    .toList();
            repository.deleteAll(oldest);
            log.warn("Memory cap hit for user {}. Evicted {} oldest entries.", userId, toDelete);
        }
    }
}
