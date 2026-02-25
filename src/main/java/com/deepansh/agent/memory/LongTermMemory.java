package com.deepansh.agent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * PostgreSQL-backed long-term memory — persists facts and context across sessions.
 *
 * V2 upgrade: after each store(), triggers async embedding generation via
 * SemanticMemoryService so the memory is immediately available for
 * semantic search queries.
 *
 * @Lazy on SemanticMemoryService breaks the circular dependency:
 * LongTermMemory → SemanticMemoryService → EmbeddingService (fine)
 * SemanticMemoryService → AgentMemoryRepository ← LongTermMemory (circular)
 */
@Component
@Slf4j
public class LongTermMemory {

    private final AgentMemoryRepository repository;
    private final SemanticMemoryService semanticMemoryService;

    @Value("${agent.memory.long-term.max-facts-per-user:500}")
    private int maxFactsPerUser;

    public LongTermMemory(AgentMemoryRepository repository,
                          @Lazy SemanticMemoryService semanticMemoryService) {
        this.repository = repository;
        this.semanticMemoryService = semanticMemoryService;
    }

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

        // Async: generate and store embedding for semantic search
        semanticMemoryService.embedMemoryAsync(saved.getId(), saved.getContent());

        return saved;
    }

    public List<AgentMemory> loadAll(String userId) {
        List<AgentMemory> memories = repository.findByUserIdOrderByCreatedAtDesc(userId);
        log.debug("Loaded {} long-term memories for user: {}", memories.size(), userId);
        return memories;
    }

    public List<AgentMemory> loadByTag(String userId, String tag) {
        return repository.findByUserIdAndTagOrderByCreatedAtDesc(userId, tag);
    }

    /** Keyword search — kept as fallback for exact matches */
    public List<AgentMemory> search(String userId, String keyword) {
        List<AgentMemory> results = repository.searchByKeyword(userId, keyword);
        log.debug("Keyword search '{}' for user {} returned {} results",
                keyword, userId, results.size());
        return results;
    }

    /** Semantic search — uses pgvector cosine similarity */
    public List<AgentMemory> semanticSearch(String userId, String query) {
        return semanticMemoryService.semanticSearch(userId, query);
    }

    public String formatForPrompt(String userId) {
        List<AgentMemory> memories = loadAll(userId);
        if (memories.isEmpty()) return "";

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

    private void enforceCapIfNeeded(String userId) {
        long count = repository.countByUserId(userId);
        if (count >= maxFactsPerUser) {
            int toDelete = (int) (count - maxFactsPerUser + 1);
            List<AgentMemory> oldest = repository.findOldestByUserId(userId)
                    .stream().limit(toDelete).toList();
            repository.deleteAll(oldest);
            log.warn("Memory cap hit for user {}. Evicted {} oldest entries.", userId, toDelete);
        }
    }
}
