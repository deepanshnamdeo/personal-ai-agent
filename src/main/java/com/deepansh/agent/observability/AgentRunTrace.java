package com.deepansh.agent.observability;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Persists a full trace of every agent run to PostgreSQL.
 *
 * Captures:
 * - Input / output
 * - Total latency and per-tool latency (stored as JSONB)
 * - Token usage (prompt + completion)
 * - Tool execution sequence
 * - Failure details if the run errored
 *
 * JSONB columns (tool_calls_json, tool_latencies_json) give you
 * structured querying without needing a separate join table.
 * E.g: SELECT * FROM agent_run_traces WHERE tool_calls_json @> '[{"toolName":"web_search"}]'
 */
@Entity
@Table(
    name = "agent_run_traces",
    indexes = {
        @Index(name = "idx_trace_session",    columnList = "sessionId"),
        @Index(name = "idx_trace_user",       columnList = "userId"),
        @Index(name = "idx_trace_created_at", columnList = "createdAt"),
        @Index(name = "idx_trace_status",     columnList = "status")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunTrace {

    public enum Status { SUCCESS, MAX_ITERATIONS, ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sessionId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, length = 4000)
    private String userInput;

    @Column(length = 8000)
    private String finalAnswer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private int iterationsUsed;
    private long totalLatencyMs;

    // Token usage
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

    /**
     * JSON array of tool calls in execution order.
     * E.g: [{"toolName":"web_search","args":{"query":"..."},"latencyMs":340}]
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String toolCallsJson;

    /** Error message if status = ERROR */
    @Column(length = 2000)
    private String errorMessage;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
