package com.deepansh.agent.memory;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Tracks metadata for each agent session in PostgreSQL.
 *
 * Useful for:
 * - Listing a user's recent sessions
 * - Knowing how many turns a session had (for debugging/analytics)
 * - Linking long-term memories back to their source session
 * - Future: session summaries (compress old sessions into one memory entry)
 */
@Entity
@Table(
    name = "agent_sessions",
    indexes = {
        @Index(name = "idx_session_user_id", columnList = "userId"),
        @Index(name = "idx_session_updated_at", columnList = "updatedAt")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMetadata {

    @Id
    private String sessionId;

    @Column(nullable = false)
    private String userId;

    @Builder.Default
    private int turnCount = 0;

    /** Short summary of what was discussed — populated post-session */
    @Column(length = 1000)
    private String summary;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
