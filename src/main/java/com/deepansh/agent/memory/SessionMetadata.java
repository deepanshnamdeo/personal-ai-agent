package com.deepansh.agent.memory;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(
    name = "agent_sessions",
    indexes = {
        @Index(name = "idx_session_user_id",    columnList = "user_id"),
        @Index(name = "idx_session_updated_at", columnList = "updated_at")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMetadata {

    @Id
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Builder.Default
    @Column(name = "turn_count")
    private int turnCount = 0;

    @Column(length = 1000)
    private String summary;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
