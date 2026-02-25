package com.deepansh.agent.tool;

import com.deepansh.agent.model.ToolCall;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central registry for all AgentTool implementations.
 *
 * Spring auto-discovers every @Component that implements AgentTool
 * and injects them as a List<AgentTool>. We index them by name for O(1) dispatch.
 *
 * Tool execution errors are caught here and returned as observation strings
 * so the agent loop always continues — the LLM can decide what to do next.
 */
@Component
@Slf4j
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();

    public ToolRegistry(List<AgentTool> toolBeans) {
        toolBeans.forEach(tool -> {
            tools.put(tool.getName(), tool);
            log.info("Registered tool: [{}] — {}", tool.getName(), tool.getDescription());
        });
        log.info("Total tools registered: {}", tools.size());
    }

    public List<ToolDefinition> getAllDefinitions() {
        return tools.values().stream()
                .map(ToolDefinition::from)
                .collect(Collectors.toList());
    }

    /**
     * Dispatches a tool call and returns the observation string.
     * Never throws — all failures are returned as error strings for the LLM to handle.
     */
    public String execute(ToolCall toolCall) {
        AgentTool tool = tools.get(toolCall.getToolName());

        if (tool == null) {
            String msg = String.format(
                    "ERROR: Unknown tool '%s'. Available tools: %s",
                    toolCall.getToolName(), tools.keySet()
            );
            log.warn(msg);
            return msg;
        }

        log.info("Executing tool: [{}] with args: {}", toolCall.getToolName(), toolCall.getArguments());

        try {
            String result = tool.execute(toolCall.getArguments());
            log.debug("Tool [{}] returned: {}", toolCall.getToolName(), result);
            return result;
        } catch (Exception e) {
            // Defensive catch — tools should handle their own errors, but just in case
            log.error("Unexpected error in tool [{}]", toolCall.getToolName(), e);
            return "ERROR: Tool execution failed — " + e.getMessage();
        }
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    public int toolCount() {
        return tools.size();
    }
}
