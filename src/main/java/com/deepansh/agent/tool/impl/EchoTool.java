package com.deepansh.agent.tool.impl;

import com.deepansh.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Smoke-test tool to verify the tool system wires up correctly.
 * Safe to keep — can be used to test the agent loop end-to-end without real APIs.
 */
@Component
public class EchoTool implements AgentTool {

    @Override
    public String getName() {
        return "echo";
    }

    @Override
    public String getDescription() {
        return "Echoes back the provided message. Use this to test the tool system is working.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "message", Map.of(
                                "type", "string",
                                "description", "The message to echo back"
                        )
                ),
                "required", List.of("message")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object message = arguments.get("message");
        if (message == null) {
            return "ERROR: 'message' argument is required";
        }
        return "Echo: " + message;
    }
}
