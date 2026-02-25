package com.deepansh.agent.tool.impl;

import com.deepansh.agent.memory.LongTermMemory;
import com.deepansh.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Allows the agent to autonomously persist a fact to the user's long-term memory.
 *
 * The agent uses this when it recognizes something worth remembering for future
 * sessions — e.g. a stated preference, an ongoing project, a personal fact.
 *
 * The userId is injected into the tool at runtime via ToolExecutionContext.
 * For now it reads from the arguments map (the agent passes it explicitly).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SaveMemoryTool implements AgentTool {

    private final LongTermMemory longTermMemory;

    @Override
    public String getName() {
        return "save_memory";
    }

    @Override
    public String getDescription() {
        return """
                Save an important fact about the user to long-term memory for future sessions.
                Use this when the user shares something meaningful: a preference, a personal fact,
                an ongoing project, or context that would be helpful to remember next time.
                Do NOT use for temporary requests or one-off tasks.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "userId", Map.of(
                                "type", "string",
                                "description", "The user's ID"
                        ),
                        "content", Map.of(
                                "type", "string",
                                "description", "The fact to remember, written as a clear statement. E.g: 'Prefers concise bullet-point responses'"
                        ),
                        "tag", Map.of(
                                "type", "string",
                                "enum", List.of("fact", "preference", "task", "context"),
                                "description", "Category: fact (personal/professional info), preference (likes/dislikes), task (ongoing work), context (background info)"
                        )
                ),
                "required", List.of("userId", "content", "tag")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String userId  = (String) arguments.get("userId");
        String content = (String) arguments.get("content");
        String tag     = (String) arguments.getOrDefault("tag", "fact");

        if (userId == null || userId.isBlank()) {
            return "ERROR: userId is required to save memory";
        }
        if (content == null || content.isBlank()) {
            return "ERROR: content is required to save memory";
        }

        try {
            var saved = longTermMemory.store(userId, content, tag, "agent-tool");
            log.info("Agent saved memory [id={}, userId={}, tag={}]", saved.getId(), userId, tag);
            return String.format("Memory saved successfully [id=%d, tag=%s]: %s",
                    saved.getId(), tag, content);
        } catch (Exception e) {
            log.error("Failed to save memory for userId={}", userId, e);
            return "ERROR: Failed to save memory — " + e.getMessage();
        }
    }
}
