package com.deepansh.agent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MongoDB-backed long-term memory.
 *
 * Stores facts as AgentMemory documents in the agent_memories collection.
 * After each store(), triggers async embedding generation for semantic search.
 *
 * Cap enforcement: when the user's memory count hits the limit,
 * the oldest documents (by createdAt ASC) are deleted first.
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

    public AgentMemory store(String userId, String content, String tag, String sessionId) {
        enforceCapIfNeeded(userId);

        AgentMemory memory = AgentMemory.builder()
                .userId(userId)
                .content(content)
                .tag(tag)
                .sourceSessionId(sessionId)
                .build();

        AgentMemory saved = repository.save(memory);
        log.info("Stored memory [id={}] for user={} [tag={}]", saved.getId(), userId, tag);

        // Async: generate embedding for semantic search
        semanticMemoryService.embedMemoryAsync(saved.getId(), saved.getContent());

        return saved;
    }

    public List<AgentMemory> loadAll(String userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<AgentMemory> loadByTag(String userId, String tag) {
        return repository.findByUserIdAndTagOrderByCreatedAtDesc(userId, tag);
    }

    public List<AgentMemory> search(String userId, String keyword) {
        return repository.searchByKeyword(userId, keyword);
    }

    public List<AgentMemory> semanticSearch(String userId, String query) {
        return semanticMemoryService.semanticSearch(userId, query);
    }

    public String formatForPrompt(String userId) {
        List<AgentMemory> memories = loadAll(userId);
        if (memories.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("## What I remember about you:\n");
        memories.forEach(m -> sb.append("- [")
                .append(m.getTag() != null ? m.getTag() : "general")
                .append("] ").append(m.getContent()).append("\n"));
        return sb.toString();
    }

    public void delete(String memoryId) {
        repository.deleteById(memoryId);
        log.info("Deleted memory id={}", memoryId);
    }

    public long countForUser(String userId) {
        return repository.countByUserId(userId);
    }

    private void enforceCapIfNeeded(String userId) {
        long count = repository.countByUserId(userId);
        if (count >= maxFactsPerUser) {
            int toDelete = (int) (count - maxFactsPerUser + 1);
            List<AgentMemory> oldest = repository.findByUserIdOrderByCreatedAtAsc(userId)
                    .stream().limit(toDelete).toList();
            repository.deleteAll(oldest);
            log.warn("Memory cap hit for user={}. Evicted {} oldest entries.", userId, toDelete);
        }
    }
}
