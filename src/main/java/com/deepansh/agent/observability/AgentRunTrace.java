package com.deepansh.agent.observability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Agent run trace document.
 * Collection: agent_run_traces
 *
 * MongoDB advantage: toolCallsJson was a jsonb string in PostgreSQL.
 * Here toolCalls is a native List<Map> — stored as BSON array,
 * fully queryable with MongoDB's dot-notation and $elemMatch.
 *
 * E.g: db.agent_run_traces.find({"toolCalls.toolName": "web_search"})
 */
@Document(collection = "agent_run_traces")
@CompoundIndexes({
    @CompoundIndex(name = "idx_trace_user_date", def = "{'userId': 1, 'createdAt': -1}")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunTrace {

    public enum Status { SUCCESS, MAX_ITERATIONS, ERROR }

    @Id
    private String id;

    @Indexed
    private String sessionId;

    @Indexed
    private String userId;

    private String userInput;
    private String finalAnswer;
    private Status status;
    private int iterationsUsed;
    private long totalLatencyMs;

    // Token usage
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

    /**
     * Native BSON array — no JSON serialization needed.
     * Each entry: {toolName, latencyMs, resultPreview}
     */
    private List<Map<String, Object>> toolCalls;

    private String errorMessage;

    @CreatedDate
    private Instant createdAt;
}
