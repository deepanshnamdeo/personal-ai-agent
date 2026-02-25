package com.deepansh.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {

    private String finalAnswer;

    @Builder.Default
    private List<ToolCall> toolCallsExecuted = new ArrayList<>();

    private int iterationsUsed;
    private boolean maxIterationsReached;
    private String sessionId;
}
