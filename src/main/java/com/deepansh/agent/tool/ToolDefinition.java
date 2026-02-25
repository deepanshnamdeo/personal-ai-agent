package com.deepansh.agent.tool;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Immutable snapshot of a tool's schema sent to the LLM.
 * Decouples the LLM serialization format from the AgentTool implementation.
 */
@Data
@Builder
public class ToolDefinition {

    private String name;
    private String description;
    private Map<String, Object> inputSchema;

    public static ToolDefinition from(AgentTool tool) {
        return ToolDefinition.builder()
                .name(tool.getName())
                .description(tool.getDescription())
                .inputSchema(tool.getInputSchema())
                .build();
    }

    /**
     * Converts to OpenAI's expected tool format.
     * OpenAI expects: { "type": "function", "function": { "name", "description", "parameters" } }
     */
    public Map<String, Object> toOpenAiSchema() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", inputSchema
                )
        );
    }
}
