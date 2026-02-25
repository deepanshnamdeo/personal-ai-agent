package com.deepansh.agent.memory;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Persistent long-term memory stored in PostgreSQL.
 *
 * A "memory" is a discrete fact or summary the agent extracts from a conversation
 * and persists for future sessions. Examples:
 *   - "User prefers summaries in bullet points"
 *   - "User's main project is called Phoenix"
 *   - "User's timezone is IST"
 *
 * Indexed by userId for fast lookup. Tag allows categorical filtering
 * (e.g. "preference", "fact", "task", "context").
 */
@Entity
@Table(
    name = "agent_memories",
    indexes = {
        @Index(name = "idx_memory_user_id", columnList = "userId"),
        @Index(name = "idx_memory_user_tag", columnList = "userId, tag")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, length = 2000)
    private String content;

    /**
     * Categorical tag for filtering: "preference", "fact", "task", "context"
     */
    @Column(length = 50)
    private String tag;

    /**
     * Source session that produced this memory — useful for tracing/debugging
     */
    private String sourceSessionId;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
