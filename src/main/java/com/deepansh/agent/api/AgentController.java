package com.deepansh.agent.api;

import com.deepansh.agent.core.AgentLoop;
import com.deepansh.agent.model.AgentRequest;
import com.deepansh.agent.model.AgentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Primary agent endpoint.
 *
 * POST /api/v1/agent/run    — run the agent (new or resumed session)
 * GET  /api/v1/agent/health — liveness check
 *
 * Memory and session management live in MemoryController (/api/v1/memory).
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final AgentLoop agentLoop;

    /**
     * Run the agent.
     *
     * Request:
     * {
     *   "input":     "What should I focus on today?",
     *   "sessionId": "abc-123",   // optional — resumes existing session
     *   "userId":    "deepansh"   // optional — defaults to "default"
     * }
     *
     * Response includes sessionId — pass it back for multi-turn conversations.
     */
    @PostMapping("/run")
    public ResponseEntity<AgentResponse> run(@Valid @RequestBody AgentRequest request) {
        log.info("Agent run request [sessionId={}, userId={}]",
                request.getSessionId(), request.getUserId());
        return ResponseEntity.ok(agentLoop.run(request));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
