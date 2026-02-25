package com.deepansh.agent.llm;

import com.deepansh.agent.model.LlmResponse;
import com.deepansh.agent.model.Message;
import com.deepansh.agent.tool.ToolDefinition;

import java.util.List;

public interface LlmClient {

    /**
     * Send the full conversation history and available tool schemas to the LLM.
     *
     * @param messages  full conversation so far (system + user + assistant + tool results)
     * @param tools     tool definitions the LLM can choose to invoke
     * @return either a final text answer or a ToolCall decision
     */
    LlmResponse chat(List<Message> messages, List<ToolDefinition> tools);
}
