package com.deepansh.agent.tool;

import java.util.Map;

/**
 * Contract every tool must implement.
 *
 * The {@link #getInputSchema()} return value is serialized as JSON Schema
 * and sent to the LLM so it knows exactly how to invoke the tool.
 *
 * Tool execution errors should NOT throw — return an error string instead.
 * This keeps the agent loop alive; the LLM can recover or try another approach.
 */
public interface AgentTool {

    /** Unique snake_case name the LLM uses to invoke this tool */
    String getName();

    /**
     * Human-readable description. This is the primary signal the LLM uses
     * to decide when to call this tool. Be specific and include example use-cases.
     */
    String getDescription();

    /**
     * JSON Schema (as a Map) describing the tool's input parameters.
     * Follow the JSON Schema spec: type, properties, required, descriptions.
     */
    Map<String, Object> getInputSchema();

    /**
     * Execute the tool and return a string observation fed back to the LLM.
     * Never throw — catch internally and return "ERROR: ..." strings.
     */
    String execute(Map<String, Object> arguments);
}
