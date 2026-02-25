package com.deepansh.agent.api;

import com.deepansh.agent.core.AgentLoop;
import com.deepansh.agent.memory.AgentMemory;
import com.deepansh.agent.memory.LongTermMemory;
import com.deepansh.agent.memory.ShortTermMemory;
import com.deepansh.agent.model.AgentRequest;
import com.deepansh.agent.model.AgentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final AgentLoop agentLoop;
    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;

    /**
     * Main endpoint — run the agent with optional session continuity.
     *
     * POST /api/v1/agent/run
     * Body: { "input": "...", "sessionId": "optional", "userId": "optional" }
     */
    @PostMapping("/run")
    public ResponseEntity<AgentResponse> run(@Valid @RequestBody AgentRequest request) {
        log.info("Received agent request [sessionId={}, userId={}]",
                request.getSessionId(), request.getUserId());
        return ResponseEntity.ok(agentLoop.run(request));
    }

    /**
     * Clear a conversation session from Redis.
     * DELETE /api/v1/agent/session/{sessionId}
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, String>> clearSession(@PathVariable String sessionId) {
        shortTermMemory.clear(sessionId);
        return ResponseEntity.ok(Map.of("message", "Session cleared: " + sessionId));
    }

    /**
     * Retrieve all long-term memories for a user.
     * GET /api/v1/agent/memory/{userId}
     */
    @GetMapping("/memory/{userId}")
    public ResponseEntity<List<AgentMemory>> getMemories(@PathVariable String userId) {
        return ResponseEntity.ok(longTermMemory.loadAll(userId));
    }

    /**
     * Manually store a long-term memory fact.
     * POST /api/v1/agent/memory/{userId}
     * Body: { "content": "...", "tag": "preference" }
     */
    @PostMapping("/memory/{userId}")
    public ResponseEntity<AgentMemory> storeMemory(
            @PathVariable String userId,
            @RequestBody Map<String, String> body) {
        AgentMemory memory = longTermMemory.store(
                userId,
                body.get("content"),
                body.getOrDefault("tag", "general"),
                "manual"
        );
        return ResponseEntity.ok(memory);
    }

    /**
     * Delete a specific long-term memory entry.
     * DELETE /api/v1/agent/memory/entry/{memoryId}
     */
    @DeleteMapping("/memory/entry/{memoryId}")
    public ResponseEntity<Map<String, String>> deleteMemory(@PathVariable Long memoryId) {
        longTermMemory.delete(memoryId);
        return ResponseEntity.ok(Map.of("message", "Memory deleted: " + memoryId));
    }

    /**
     * Health check.
     * GET /api/v1/agent/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
