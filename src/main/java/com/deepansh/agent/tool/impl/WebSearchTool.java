package com.deepansh.agent.tool.impl;

import com.deepansh.agent.config.ToolProperties;
import com.deepansh.agent.tool.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Web search tool powered by the Brave Search API.
 *
 * Why Brave:
 * - Independent index (not just a Google wrapper)
 * - Free tier: 2,000 queries/month, no credit card required
 * - Sign up: https://api.search.brave.com/register
 *
 * Output format: numbered list of results with title, URL, and snippet.
 * Optimized for LLM consumption — concise, structured, no HTML.
 *
 * Fallback behavior:
 * - If BRAVE_API_KEY is not set → returns a clear error string (LLM can recover)
 * - If API call fails → returns error string, never throws
 */
@Component
@Slf4j
public class WebSearchTool implements AgentTool {

    private final ToolProperties toolProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public WebSearchTool(ToolProperties toolProperties, ObjectMapper objectMapper) {
        this.toolProperties = toolProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(toolProperties.getWebSearch().getBrave().getBaseUrl())
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Accept-Encoding", "gzip")
                .build();
    }

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return """
                Search the web for current information, news, articles, or any topic.
                Returns the top results with titles, URLs, and summaries.
                Use this when you need up-to-date information or facts you don't know.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "The search query. Be specific for better results. E.g: 'Spring Boot 3.2 virtual threads performance'"
                        ),
                        "count", Map.of(
                                "type", "integer",
                                "description", "Number of results to return (1-10). Default: 5",
                                "default", 5
                        )
                ),
                "required", List.of("query")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String apiKey = toolProperties.getWebSearch().getBrave().getApiKey();

        if (apiKey == null || apiKey.isBlank()) {
            return "ERROR: Brave Search API key not configured. " +
                   "Set BRAVE_API_KEY environment variable. " +
                   "Get a free key at https://api.search.brave.com/register";
        }

        String query = (String) arguments.get("query");
        if (query == null || query.isBlank()) {
            return "ERROR: 'query' is required for web_search";
        }

        int count = resolveCount(arguments.get("count"));

        log.info("Web search: query='{}' count={}", query, count);

        try {
            return performSearch(query, count, apiKey);
        } catch (Exception e) {
            log.error("Web search failed for query='{}'", query, e);
            return "ERROR: Web search failed — " + e.getMessage();
        }
    }

    private String performSearch(String query, int count, String apiKey) throws Exception {
        String url = UriComponentsBuilder.fromPath("/web/search")
                .queryParam("q", query)
                .queryParam("count", count)
                .queryParam("text_decorations", false)
                .queryParam("search_lang", "en")
                .build()
                .toUriString();

        String responseBody = restClient.get()
                .uri(url)
                .header("X-Subscription-Token", apiKey)
                .retrieve()
                .body(String.class);

        return parseResults(responseBody, query);
    }

    private String parseResults(String responseBody, String query) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode webResults = root.path("web").path("results");

        if (webResults.isMissingNode() || !webResults.isArray() || webResults.isEmpty()) {
            return "No web results found for: " + query;
        }

        List<String> formatted = new ArrayList<>();
        int index = 1;

        for (JsonNode result : webResults) {
            String title       = result.path("title").asText("No title");
            String url         = result.path("url").asText("");
            String description = result.path("description").asText("No description");

            formatted.add(String.format("""
                    [%d] %s
                         URL: %s
                         %s""", index++, title, url, description));
        }

        return "Search results for \"" + query + "\":\n\n" +
               String.join("\n\n", formatted);
    }

    private int resolveCount(Object raw) {
        if (raw == null) return toolProperties.getWebSearch().getBrave().getMaxResults();
        try {
            int val = Integer.parseInt(raw.toString());
            return Math.min(Math.max(val, 1), 10); // clamp 1-10
        } catch (NumberFormatException e) {
            return toolProperties.getWebSearch().getBrave().getMaxResults();
        }
    }
}
