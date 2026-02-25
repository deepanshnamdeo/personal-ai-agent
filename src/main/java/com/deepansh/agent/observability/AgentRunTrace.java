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

@Entity
@Table(
    name = "agent_run_traces",
    indexes = {
        @Index(name = "idx_trace_session",    columnList = "session_id"),
        @Index(name = "idx_trace_user",       columnList = "user_id"),
        @Index(name = "idx_trace_created_at", columnList = "created_at"),
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

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "user_input", nullable = false, length = 4000)
    private String userInput;

    @Column(name = "final_answer", length = 8000)
    private String finalAnswer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "iterations_used")
    private int iterationsUsed;

    @Column(name = "total_latency_ms")
    private long totalLatencyMs;

    @Column(name = "prompt_tokens")
    private int promptTokens;

    @Column(name = "completion_tokens")
    private int completionTokens;

    @Column(name = "total_tokens")
    private int totalTokens;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls_json", columnDefinition = "jsonb")
    private String toolCallsJson;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
