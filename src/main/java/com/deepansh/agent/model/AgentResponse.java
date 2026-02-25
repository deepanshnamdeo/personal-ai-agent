package com.deepansh.agent.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AgentResponse {

    private String finalAnswer;
    private List<ToolCall> toolCallsExecuted;
    private int iterationsUsed;
    private boolean maxIterationsReached;

    /** Session ID — used to resume conversations via memory */
    private String sessionId;
}
