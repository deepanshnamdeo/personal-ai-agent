package com.deepansh.agent.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AgentRequest {

    @NotBlank(message = "input must not be blank")
    private String input;

    /**
     * Optional — if provided, the agent resumes conversation from this session.
     * If null, a new session is created.
     */
    private String sessionId;

    /**
     * Optional — used to scope long-term memory lookups.
     * Defaults to "default" if not provided.
     */
    private String userId;
}
