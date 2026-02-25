package com.deepansh.agent.observability;

import com.deepansh.agent.model.AgentResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Persists run traces and exposes analytics.
 *
 * Trace persistence is @Async — it never blocks the agent response.
 * Analytics queries are synchronous (called explicitly by the metrics endpoint).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TraceService {

    private final AgentRunTraceRepository traceRepository;
    private final ObjectMapper objectMapper;

    /**
     * Persist a completed run trace asynchronously.
     * Called at the end of every agent run from AgentLoop.
     */
    @Async("memoryTaskExecutor")
    public void persistTrace(String sessionId, String userId, String userInput,
                              AgentResponse response, RunContext runCtx, Throwable error) {
        try {
            AgentRunTrace.Status status;
            String errorMessage = null;

            if (error != null) {
                status = AgentRunTrace.Status.ERROR;
                errorMessage = error.getMessage();
            } else if (response.isMaxIterationsReached()) {
                status = AgentRunTrace.Status.MAX_ITERATIONS;
            } else {
                status = AgentRunTrace.Status.SUCCESS;
            }

            String toolCallsJson = serializeToolCalls(runCtx.getToolCallRecords());

            AgentRunTrace trace = AgentRunTrace.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .userInput(truncate(userInput, 4000))
                    .finalAnswer(truncate(response.getFinalAnswer(), 8000))
                    .status(status)
                    .iterationsUsed(response.getIterationsUsed())
                    .totalLatencyMs(runCtx.elapsedMs())
                    .promptTokens(runCtx.getPromptTokens())
                    .completionTokens(runCtx.getCompletionTokens())
                    .totalTokens(runCtx.totalTokens())
                    .toolCallsJson(toolCallsJson)
                    .errorMessage(errorMessage)
                    .build();

            traceRepository.save(trace);

            log.info("Trace persisted [session={}, status={}, latency={}ms, tokens={}]",
                    sessionId, status, runCtx.elapsedMs(), runCtx.totalTokens());

        } catch (Exception e) {
            // Trace persistence must never crash the app
            log.error("Failed to persist run trace for session={}", sessionId, e);
        }
    }

    public List<AgentRunTrace> getTracesForUser(String userId) {
        return traceRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<AgentRunTrace> getTracesForSession(String sessionId) {
        return traceRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    /**
     * Summary analytics for a user — avg latency, token usage last 24h, status breakdown.
     */
    public Map<String, Object> getAnalytics(String userId) {
        Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);

        Double avgLatency = traceRepository.avgLatencyForUser(userId);
        Long tokensLast24h = traceRepository.totalTokensUsedSince(userId, since24h);
        List<Object[]> statusRows = traceRepository.statusBreakdownForUser(userId);

        Map<String, Long> statusBreakdown = statusRows.stream()
                .collect(Collectors.toMap(
                        r -> r[0].toString(),
                        r -> ((Number) r[1]).longValue()
                ));

        return Map.of(
                "userId", userId,
                "avgLatencyMs", avgLatency != null ? Math.round(avgLatency) : 0,
                "totalTokensLast24h", tokensLast24h != null ? tokensLast24h : 0,
                "statusBreakdown", statusBreakdown
        );
    }

    private String serializeToolCalls(List<RunContext.ToolCallRecord> records) {
        if (records.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(records.stream()
                    .map(r -> Map.of(
                            "toolName", r.toolName(),
                            "latencyMs", r.latencyMs(),
                            "resultPreview", truncate(r.result(), 200)
                    ))
                    .toList());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}
