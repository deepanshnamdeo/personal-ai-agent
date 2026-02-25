package com.deepansh.agent.memory;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(
    name = "agent_memories",
    indexes = {
        @Index(name = "idx_memory_user_id",  columnList = "user_id"),
        @Index(name = "idx_memory_user_tag",  columnList = "user_id, tag")
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

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(length = 50)
    private String tag;

    @Column(name = "source_session_id")
    private String sourceSessionId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
