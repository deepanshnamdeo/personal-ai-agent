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
 * Agent tool to search long-term memory.
 *
 * Modes:
 * - semantic: pgvector cosine similarity — finds conceptually related memories
 *             even when phrasing differs. Best default for open-ended queries.
 * - keyword:  ILIKE substring match — use for exact term lookups
 * - tag:      filter by category (fact/preference/task/context)
 * - all:      return everything ("what do you know about me?")
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
                Search your long-term memory about the user.
                Use mode 'semantic' (default) for natural language queries — finds related memories
                even when phrasing is different. Use 'keyword' for exact term matching.
                Use 'tag' to filter by category. Use 'all' to retrieve everything.
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
                                "enum", List.of("semantic", "keyword", "tag", "all"),
                                "description", "Search mode. 'semantic' uses AI similarity (best for most queries). " +
                                               "'keyword' for exact terms. 'tag' for category filter. 'all' for everything."
                        ),
                        "query", Map.of(
                                "type", "string",
                                "description", "Query string for semantic/keyword search, or tag name for tag mode " +
                                               "(fact | preference | task | context)"
                        )
                ),
                "required", List.of("userId", "mode")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String userId = (String) arguments.get("userId");
        String mode   = (String) arguments.getOrDefault("mode", "semantic");
        String query  = (String) arguments.getOrDefault("query", "");

        if (userId == null || userId.isBlank()) {
            return "ERROR: userId is required";
        }

        try {
            List<AgentMemory> results = switch (mode) {
                case "semantic" -> {
                    if (query.isBlank()) yield longTermMemory.loadAll(userId);
                    yield longTermMemory.semanticSearch(userId, query);
                }
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
