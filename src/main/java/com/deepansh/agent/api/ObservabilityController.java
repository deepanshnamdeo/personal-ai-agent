package com.deepansh.agent.api;

import com.deepansh.agent.observability.AgentRunTrace;
import com.deepansh.agent.observability.TraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for agent run traces and analytics.
 *
 * GET /api/v1/traces/{userId}              — all run traces for a user
 * GET /api/v1/traces/session/{sessionId}   — traces for a specific session
 * GET /api/v1/traces/{userId}/analytics    — aggregated stats (latency, tokens, status)
 */
@RestController
@RequestMapping("/api/v1/traces")
@RequiredArgsConstructor
public class ObservabilityController {

    private final TraceService traceService;

    @GetMapping("/{userId}")
    public ResponseEntity<List<AgentRunTrace>> getTraces(@PathVariable String userId) {
        return ResponseEntity.ok(traceService.getTracesForUser(userId));
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<AgentRunTrace>> getSessionTraces(@PathVariable String sessionId) {
        return ResponseEntity.ok(traceService.getTracesForSession(sessionId));
    }

    @GetMapping("/{userId}/analytics")
    public ResponseEntity<Map<String, Object>> getAnalytics(@PathVariable String userId) {
        return ResponseEntity.ok(traceService.getAnalytics(userId));
    }
}
