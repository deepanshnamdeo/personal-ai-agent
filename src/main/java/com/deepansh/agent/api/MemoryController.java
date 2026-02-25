package com.deepansh.agent.api;

import com.deepansh.agent.memory.AgentMemory;
import com.deepansh.agent.memory.LongTermMemory;
import com.deepansh.agent.memory.SemanticMemoryService;
import com.deepansh.agent.memory.SessionMetadata;
import com.deepansh.agent.memory.SessionService;
import com.deepansh.agent.memory.ShortTermMemory;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
@Slf4j
public class MemoryController {

    private final LongTermMemory longTermMemory;
    private final ShortTermMemory shortTermMemory;
    private final SessionService sessionService;
    private final SemanticMemoryService semanticMemoryService;

    // ─── Long-term memory ────────────────────────────────────────────────────

    @GetMapping("/{userId}")
    public ResponseEntity<List<AgentMemory>> getAllMemories(@PathVariable String userId) {
        return ResponseEntity.ok(longTermMemory.loadAll(userId));
    }

    @GetMapping("/{userId}/search")
    public ResponseEntity<List<AgentMemory>> searchMemories(
            @PathVariable String userId,
            @RequestParam @NotBlank String q,
            @RequestParam(defaultValue = "semantic") String mode) {
        List<AgentMemory> results = "semantic".equals(mode)
                ? longTermMemory.semanticSearch(userId, q)
                : longTermMemory.search(userId, q);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/{userId}/tag/{tag}")
    public ResponseEntity<List<AgentMemory>> getMemoriesByTag(
            @PathVariable String userId,
            @PathVariable String tag) {
        return ResponseEntity.ok(longTermMemory.loadByTag(userId, tag));
    }

    @PostMapping("/{userId}")
    public ResponseEntity<AgentMemory> storeMemory(
            @PathVariable String userId,
            @RequestBody Map<String, String> body) {
        String content = body.get("content");
        String tag = body.getOrDefault("tag", "general");
        if (content == null || content.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(longTermMemory.store(userId, content, tag, "manual-api"));
    }

    @DeleteMapping("/entry/{memoryId}")
    public ResponseEntity<Map<String, String>> deleteMemory(@PathVariable String memoryId) {
        longTermMemory.delete(memoryId);
        return ResponseEntity.ok(Map.of("message", "Deleted", "id", String.valueOf(memoryId)));
    }

    @GetMapping("/{userId}/count")
    public ResponseEntity<Map<String, Long>> getMemoryCount(@PathVariable String userId) {
        return ResponseEntity.ok(Map.of("count", longTermMemory.countForUser(userId)));
    }

    /**
     * Backfill embeddings for all memories without one.
     * Run once after enabling pgvector on an existing database.
     * POST /api/v1/memory/{userId}/backfill-embeddings
     */
    @PostMapping("/{userId}/backfill-embeddings")
    public ResponseEntity<Map<String, Object>> backfillEmbeddings(@PathVariable String userId) {
        int count = semanticMemoryService.backfillEmbeddings(userId);
        return ResponseEntity.ok(Map.of(
                "message", "Backfill started asynchronously",
                "memoriesQueued", count
        ));
    }

    // ─── Sessions ────────────────────────────────────────────────────────────

    @GetMapping("/{userId}/sessions")
    public ResponseEntity<List<SessionMetadata>> getSessions(@PathVariable String userId) {
        return ResponseEntity.ok(sessionService.getSessionsForUser(userId));
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, String>> clearSession(@PathVariable String sessionId) {
        shortTermMemory.clear(sessionId);
        return ResponseEntity.ok(Map.of("message", "Session cleared", "sessionId", sessionId));
    }
}
