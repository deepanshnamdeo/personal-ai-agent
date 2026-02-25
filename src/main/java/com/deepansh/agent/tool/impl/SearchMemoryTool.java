package com.deepansh.agent.tool.impl;

import com.deepansh.agent.memory.AgentMemory;
import com.deepansh.agent.memory.LongTermMemory;
import com.deepansh.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Allows the agent to search its own long-term memory during a run.
 *
 * The agent uses this to answer questions like:
 * - "What's my preferred format for summaries?"
 * - "What projects am I working on?"
 * - "What do you know about me?"
 *
 * Search modes:
 * - keyword: ILIKE search across all memories (V1 — good enough for small sets)
 * - tag: filter by category (fact / preference / task / context)
 * - all: return everything (used when user asks "what do you know about me?")
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SearchMemoryTool implements AgentTool {

    private final LongTermMemory longTermMemory;

    @Override
    public String getName() {
        return "search_memory";
    }

    @Override
    public String getDescription() {
        return """
                Search your long-term memory about the user. Use this to recall stored facts,
                preferences, ongoing tasks, or context from past conversations.
                Use mode 'keyword' to find specific memories, 'tag' to filter by category,
                or 'all' to retrieve everything you remember about the user.
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
                        "mode", Map.of(
                                "type", "string",
                                "enum", List.of("keyword", "tag", "all"),
                                "description", "Search mode: 'keyword' for text search, 'tag' for category filter, 'all' for everything"
                        ),
                        "query", Map.of(
                                "type", "string",
                                "description", "Search keyword (for mode=keyword) or tag name (for mode=tag): fact | preference | task | context"
                        )
                ),
                "required", List.of("userId", "mode")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String userId = (String) arguments.get("userId");
        String mode   = (String) arguments.get("mode");
        String query  = (String) arguments.getOrDefault("query", "");

        if (userId == null || userId.isBlank()) {
            return "ERROR: userId is required";
        }

        try {
            List<AgentMemory> results = switch (mode) {
                case "keyword" -> {
                    if (query.isBlank()) yield List.of();
                    yield longTermMemory.search(userId, query);
                }
                case "tag"     -> longTermMemory.loadByTag(userId, query);
                case "all"     -> longTermMemory.loadAll(userId);
                default        -> List.of();
            };

            if (results.isEmpty()) {
                return "No memories found for userId=" + userId +
                       (query.isBlank() ? "" : " with query='" + query + "'");
            }

            return formatResults(results);

        } catch (Exception e) {
            log.error("Memory search failed for userId={}", userId, e);
            return "ERROR: Memory search failed — " + e.getMessage();
        }
    }

    private String formatResults(List<AgentMemory> memories) {
        return "Found " + memories.size() + " memories:\n" +
                memories.stream()
                        .map(m -> String.format("  [%s] %s (id=%d)",
                                m.getTag() != null ? m.getTag() : "general",
                                m.getContent(),
                                m.getId()))
                        .collect(Collectors.joining("\n"));
    }
}
