package com.deepansh.agent.core;

import com.deepansh.agent.model.Message;
import com.deepansh.agent.model.ToolCall;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class AgentContext {

    private String sessionId;
    private String userId;
    private String userInput;

    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    @Builder.Default
    private List<ToolCall> executedToolCalls = new ArrayList<>();

    private int currentIteration;
}
