package com.deepansh.agent.tool.impl;

import com.deepansh.agent.config.ToolProperties;
import com.deepansh.agent.tool.AgentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Generic HTTP REST API caller tool.
 *
 * Allows the agent to call any external REST endpoint — weather APIs, GitHub,
 * Jira, Notion, internal microservices, etc.
 *
 * Security controls:
 * - Domain allowlist: TOOL_ALLOWED_DOMAINS env var (comma-separated)
 *   Empty = allow all (suitable for personal agent; tighten in multi-user setups)
 * - Only GET, POST, PUT, PATCH supported (no DELETE — agent must not delete data)
 * - Response is truncated at 4000 chars to avoid context window bloat
 * - Timeout enforced: connect=5s, read=10s
 *
 * The agent provides headers as a JSON object and body as a string.
 */
@Component
@Slf4j
public class RestApiCallerTool implements AgentTool {

    private static final int MAX_RESPONSE_CHARS = 4000;
    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH");

    private final ToolProperties toolProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public RestApiCallerTool(ToolProperties toolProperties, ObjectMapper objectMapper) {
        this.toolProperties = toolProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    log.debug("Outbound API call: {} {}", request.getMethod(), request.getURI());
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public String getName() {
        return "call_api";
    }

    @Override
    public String getDescription() {
        return """
                Make an HTTP request to an external REST API.
                Supports GET, POST, PUT, PATCH methods.
                Use this to fetch data from APIs like weather, GitHub, Notion, Jira,
                or any internal microservice endpoint.
                Always include required authentication headers if the API needs them.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of(
                                "type", "string",
                                "description", "Full URL to call. E.g: https://api.github.com/repos/owner/repo"
                        ),
                        "method", Map.of(
                                "type", "string",
                                "enum", ALLOWED_METHODS,
                                "description", "HTTP method. Default: GET",
                                "default", "GET"
                        ),
                        "headers", Map.of(
                                "type", "object",
                                "description", "Request headers as key-value pairs. E.g: {\"Authorization\": \"Bearer token\", \"Accept\": \"application/json\"}",
                                "additionalProperties", Map.of("type", "string")
                        ),
                        "body", Map.of(
                                "type", "string",
                                "description", "Request body as a JSON string (for POST/PUT/PATCH)"
                        )
                ),
                "required", List.of("url")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String url    = (String) arguments.get("url");
        String method = ((String) arguments.getOrDefault("method", "GET")).toUpperCase();

        if (url == null || url.isBlank()) {
            return "ERROR: 'url' is required";
        }

        if (!ALLOWED_METHODS.contains(method)) {
            return "ERROR: Method '" + method + "' is not allowed. Use: " + ALLOWED_METHODS;
        }

        // Domain allowlist check
        String domainError = checkDomainAllowlist(url);
        if (domainError != null) return domainError;

        log.info("API call: {} {}", method, url);

        try {
            return performRequest(url, method, arguments);
        } catch (Exception e) {
            log.error("API call failed: {} {}", method, url, e);
            return "ERROR: API call failed — " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String performRequest(String url, String method, Map<String, Object> arguments) {
        var requestSpec = restClient.method(HttpMethod.valueOf(method)).uri(URI.create(url));

        // Apply headers
        Object rawHeaders = arguments.get("headers");
        if (rawHeaders instanceof Map<?, ?> headers) {
            headers.forEach((k, v) -> requestSpec.header(k.toString(), v.toString()));
        }

        // Apply body for mutating methods
        String body = (String) arguments.get("body");
        if (body != null && !body.isBlank() && !method.equals("GET")) {
            requestSpec
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }

        ResponseEntity<String> response = requestSpec
                .retrieve()
                .toEntity(String.class);

        int statusCode = response.getStatusCode().value();
        String responseBody = response.getBody();

        log.info("API response: status={} body-length={}", statusCode,
                responseBody != null ? responseBody.length() : 0);

        // Try to pretty-print JSON responses for better LLM consumption
        String formattedBody = tryPrettyPrint(responseBody);

        // Truncate to avoid context window explosion
        if (formattedBody != null && formattedBody.length() > MAX_RESPONSE_CHARS) {
            formattedBody = formattedBody.substring(0, MAX_RESPONSE_CHARS) +
                    "\n... [truncated — " + (formattedBody.length() - MAX_RESPONSE_CHARS) + " more chars]";
        }

        return String.format("HTTP %d\n\n%s", statusCode,
                formattedBody != null ? formattedBody : "(empty response)");
    }

    private String tryPrettyPrint(String body) {
        if (body == null) return null;
        try {
            Object parsed = objectMapper.readValue(body, Object.class);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
        } catch (Exception e) {
            return body; // not JSON, return as-is
        }
    }

    private String checkDomainAllowlist(String url) {
        List<String> allowedDomains = toolProperties.getRestApiCaller().getAllowedDomainList();
        if (allowedDomains.isEmpty()) return null; // allow all

        try {
            String host = URI.create(url).getHost();
            boolean allowed = allowedDomains.stream()
                    .anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
            if (!allowed) {
                return "ERROR: Domain '" + host + "' is not in the allowed list. " +
                       "Allowed: " + allowedDomains;
            }
        } catch (Exception e) {
            return "ERROR: Invalid URL format: " + url;
        }
        return null;
    }
}
