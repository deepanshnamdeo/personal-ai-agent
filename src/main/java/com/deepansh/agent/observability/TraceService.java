package com.deepansh.agent.observability;

import com.deepansh.agent.model.AgentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TraceService {

    private final AgentRunTraceRepository traceRepository;

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

            // Native BSON tool calls — no JSON serialization needed
            List<Map<String, Object>> toolCalls = runCtx.getToolCallRecords().stream()
                    .map(r -> Map.<String, Object>of(
                            "toolName",      r.toolName(),
                            "latencyMs",     r.latencyMs(),
                            "resultPreview", truncate(r.result(), 200)
                    ))
                    .collect(Collectors.toList());

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
                    .toolCalls(toolCalls)
                    .errorMessage(errorMessage)
                    .build();

            traceRepository.save(trace);

            log.info("Trace persisted [session={}, status={}, latency={}ms, tokens={}]",
                    sessionId, status, runCtx.elapsedMs(), runCtx.totalTokens());

        } catch (Exception e) {
            log.error("Failed to persist run trace for session={}", sessionId, e);
        }
    }

    public List<AgentRunTrace> getTracesForUser(String userId) {
        return traceRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<AgentRunTrace> getTracesForSession(String sessionId) {
        return traceRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    public Map<String, Object> getAnalytics(String userId) {
        Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);

        Double avgLatency   = traceRepository.avgLatencyForUser(userId);
        Long tokensLast24h  = traceRepository.totalTokensUsedSince(userId, since24h);

        Map<String, Long> statusBreakdown = traceRepository.statusBreakdownForUser(userId)
                .stream()
                .collect(Collectors.toMap(
                        AgentRunTraceRepository.StatusCount::id,
                        AgentRunTraceRepository.StatusCount::count
                ));

        return Map.of(
                "userId",             userId,
                "avgLatencyMs",       avgLatency != null ? Math.round(avgLatency) : 0,
                "totalTokensLast24h", tokensLast24h != null ? tokensLast24h : 0,
                "statusBreakdown",    statusBreakdown
        );
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}
