package com.deepansh.agent.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmResponse {

    /** Non-null when the LLM produces a final text answer */
    private String content;

    /** Non-null when the LLM wants to invoke a tool */
    private ToolCall toolCall;

    private boolean isToolCall;

    // Token usage — populated from OpenAI usage block
    @Builder.Default
    private int promptTokens = 0;

    @Builder.Default
    private int completionTokens = 0;
}
