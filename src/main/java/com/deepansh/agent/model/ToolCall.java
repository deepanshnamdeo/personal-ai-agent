package com.deepansh.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {

    /** ID assigned by OpenAI — must be echoed back in the tool result message */
    private String id;

    private String toolName;

    private Map<String, Object> arguments;
}
