package com.deepansh.agent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Session metadata document.
 * Collection: agent_sessions
 */
@Document(collection = "agent_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMetadata {

    @Id
    private String sessionId;

    @Indexed
    private String userId;

    @Builder.Default
    private int turnCount = 0;

    private String summary;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
