package com.deepansh.agent.core;

import com.deepansh.agent.model.Message;
import com.deepansh.agent.model.ToolCall;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Holds all mutable state for a single agent run.
 * Passed through the ReAct loop instead of scattered fields on AgentLoop.
 */
@Data
@Builder
public class AgentContext {

    private String sessionId;
    private String userId;
    private String userInput;
    private List<Message> messages;
    private List<ToolCall> executedToolCalls;
    private int currentIteration;
}
