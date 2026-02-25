package com.deepansh.agent.api;

import com.deepansh.agent.core.AgentLoop;
import com.deepansh.agent.model.AgentRequest;
import com.deepansh.agent.model.AgentResponse;
import com.deepansh.agent.resilience.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Primary agent endpoint with idempotency support.
 *
 * POST /api/v1/agent/run
 *   Optional header: Idempotency-Key: <uuid>
 *   If provided, duplicate requests within 24h return the cached response.
 *
 * GET /api/v1/agent/health
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final AgentLoop agentLoop;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @PostMapping("/run")
    public ResponseEntity<AgentResponse> run(
            @Valid @RequestBody AgentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        log.info("Agent run request [sessionId={}, userId={}, idempotencyKey={}]",
                request.getSessionId(), request.getUserId(), idempotencyKey);

        // Idempotency check — only if key provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var cached = idempotencyService.getCachedResponse(idempotencyKey);
            if (cached.isPresent()) {
                try {
                    AgentResponse cachedResponse = objectMapper.readValue(
                            cached.get(), AgentResponse.class);
                    log.info("Returning cached response for idempotency key={}", idempotencyKey);
                    return ResponseEntity.ok(cachedResponse);
                } catch (Exception e) {
                    log.warn("Failed to deserialize cached response, proceeding fresh", e);
                }
            }
            idempotencyService.claimKey(idempotencyKey);
        }

        AgentResponse response;
        try {
            response = agentLoop.run(request);

            // Cache successful response
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                try {
                    idempotencyService.storeResponse(
                            idempotencyKey, objectMapper.writeValueAsString(response));
                } catch (Exception e) {
                    log.warn("Failed to cache idempotency response", e);
                }
            }
        } catch (Exception e) {
            // On error, release the key so the client can retry
            if (idempotencyKey != null) {
                idempotencyService.releaseKey(idempotencyKey);
            }
            throw e;
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
