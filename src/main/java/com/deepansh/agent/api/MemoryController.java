package com.deepansh.agent.api;

import com.deepansh.agent.memory.AgentMemory;
import com.deepansh.agent.memory.LongTermMemory;
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

/**
 * REST API for memory and session management.
 *
 * Endpoints:
 *   GET    /api/v1/memory/{userId}                 — list all long-term memories
 *   GET    /api/v1/memory/{userId}/search?q=       — keyword search memories
 *   GET    /api/v1/memory/{userId}/tag/{tag}        — filter by tag
 *   POST   /api/v1/memory/{userId}                 — manually store a memory
 *   DELETE /api/v1/memory/entry/{memoryId}          — delete a specific memory
 *
 *   GET    /api/v1/memory/{userId}/sessions         — list all sessions for a user
 *   DELETE /api/v1/memory/session/{sessionId}       — clear session from Redis
 */
@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
@Slf4j
public class MemoryController {

    private final LongTermMemory longTermMemory;
    private final ShortTermMemory shortTermMemory;
    private final SessionService sessionService;

    // ─── Long-term memory endpoints ──────────────────────────────────────────

    @GetMapping("/{userId}")
    public ResponseEntity<List<AgentMemory>> getAllMemories(@PathVariable String userId) {
        return ResponseEntity.ok(longTermMemory.loadAll(userId));
    }

    @GetMapping("/{userId}/search")
    public ResponseEntity<List<AgentMemory>> searchMemories(
            @PathVariable String userId,
            @RequestParam @NotBlank String q) {
        return ResponseEntity.ok(longTermMemory.search(userId, q));
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

        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        AgentMemory saved = longTermMemory.store(userId, content, tag, "manual-api");
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/entry/{memoryId}")
    public ResponseEntity<Map<String, String>> deleteMemory(@PathVariable Long memoryId) {
        longTermMemory.delete(memoryId);
        return ResponseEntity.ok(Map.of(
                "message", "Memory deleted",
                "id", String.valueOf(memoryId)
        ));
    }

    @GetMapping("/{userId}/count")
    public ResponseEntity<Map<String, Long>> getMemoryCount(@PathVariable String userId) {
        return ResponseEntity.ok(Map.of("count", longTermMemory.countForUser(userId)));
    }

    // ─── Session endpoints ────────────────────────────────────────────────────

    @GetMapping("/{userId}/sessions")
    public ResponseEntity<List<SessionMetadata>> getSessions(@PathVariable String userId) {
        return ResponseEntity.ok(sessionService.getSessionsForUser(userId));
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, String>> clearSession(@PathVariable String sessionId) {
        shortTermMemory.clear(sessionId);
        return ResponseEntity.ok(Map.of(
                "message", "Session cleared from Redis",
                "sessionId", sessionId
        ));
    }
}
