package com.deepansh.agent.llm;

import com.deepansh.agent.exception.AgentException;
import com.deepansh.agent.model.LlmResponse;
import com.deepansh.agent.model.Message;
import com.deepansh.agent.model.ToolCall;
import com.deepansh.agent.tool.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI-compatible LLM client — works with Groq, OpenAI, and Gemini.
 *
 * Groq quirks handled here:
 *
 * 1. tool_use_failed (400): Some Groq models (including llama-3.3-70b-versatile)
 *    sometimes emit tool calls in a broken XML format:
 *      <function=web_search({"query": "..."})</function>
 *    instead of the standard OpenAI JSON tool_calls array.
 *    Groq then returns a 400 with code=tool_use_failed and includes the
 *    broken generation in the error body.
 *    Fix: parse the failed_generation XML and extract the tool call manually.
 *
 * 2. Model recommendation: use llama3-groq-70b-8192-tool-use-preview
 *    (fine-tuned for tool use) instead of llama-3.3-70b-versatile.
 *    Set via GROQ_MODEL env var or application.yml.
 */
@Slf4j
public class GenericLlmClient implements LlmClient {

    // Matches Groq's broken XML tool call format:
    // <function=tool_name({"arg": "value"})</function>
    // <function=tool_name({"arg": "value"})>
    private static final Pattern GROQ_XML_TOOL_PATTERN =
            Pattern.compile("<function=(\\w+)\\((.+?)\\)(?:</function>|>)",
                    Pattern.DOTALL);

    private final LlmProviderProperties props;
    private final ObjectMapper objectMapper;
    private final String providerName;
    private final RestClient restClient;

    public GenericLlmClient(LlmProviderProperties props,
                             ObjectMapper objectMapper,
                             String providerName,
                             RestClient.Builder restClientBuilder) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.providerName = providerName;
        this.restClient = restClientBuilder
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public LlmResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        Map<String, Object> requestBody = buildRequestBody(messages, tools);

        log.debug("Sending {} messages to {} [model={}]",
                messages.size(), providerName, props.getModel());

        try {
            Map<String, Object> response = restClient.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        String body = new String(res.getBody().readAllBytes());
                        log.error("{} 4xx error [{}]: {}", providerName, res.getStatusCode(), body);

                        // Groq tool_use_failed — parse the failed generation and recover
                        if (body.contains("tool_use_failed")) {
                            throw new GroqToolUseFailedException(body);
                        }

                        // 401 = bad API key — not retryable
                        if (res.getStatusCode().value() == 401) {
                            throw new AgentException(
                                providerName + " API key is invalid. Check your environment variable.");
                        }

                        // Other 4xx — not retryable
                        throw new AgentException(
                            providerName + " client error [" + res.getStatusCode() + "]: " + body);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        String body = new String(res.getBody().readAllBytes());
                        log.error("{} 5xx error [{}]: {}", providerName, res.getStatusCode(), body);
                        // 5xx IS retryable — throw RuntimeException (not AgentException)
                        throw new RuntimeException(
                            providerName + " server error [" + res.getStatusCode() + "]: " + body);
                    })
                    .body(new ParameterizedTypeReference<>() {});

            return parseResponse(response);

        } catch (GroqToolUseFailedException e) {
            // Try to salvage the tool call from the failed generation
            return recoverFromGroqToolUseFailure(e.getErrorBody());
        }
    }

    /**
     * Groq's tool_use_failed error contains the broken generation in
     * the "failed_generation" field. We parse it to extract the intended
     * tool call and return it as if the call succeeded normally.
     *
     * Error body example:
     * {
     *   "error": {
     *     "code": "tool_use_failed",
     *     "failed_generation": "<function=web_search({\"query\": \"Spring Boot version\"})</function>"
     *   }
     * }
     */
    @SuppressWarnings("unchecked")
    private LlmResponse recoverFromGroqToolUseFailure(String errorBody) {
        try {
            Map<String, Object> errorMap = objectMapper.readValue(errorBody, new TypeReference<>() {});
            Map<String, Object> error = (Map<String, Object>) errorMap.get("error");
            String failedGeneration  = (String) error.get("failed_generation");

            if (failedGeneration == null || failedGeneration.isBlank()) {
                log.warn("Groq tool_use_failed but no failed_generation in error body");
                return errorResponse("The agent encountered a tool formatting issue. Please try again.");
            }

            log.debug("Attempting to recover from Groq tool_use_failed. Generation: {}", failedGeneration);

            Matcher matcher = GROQ_XML_TOOL_PATTERN.matcher(failedGeneration);
            if (!matcher.find()) {
                log.warn("Could not parse Groq XML tool call from: {}", failedGeneration);
                return errorResponse("The agent encountered a tool formatting issue. Please try again.");
            }

            String toolName   = matcher.group(1);
            String argsJson   = matcher.group(2);

            Map<String, Object> args = objectMapper.readValue(argsJson, new TypeReference<>() {});

            log.info("Recovered Groq tool call: tool={} args={}", toolName, args);

            return LlmResponse.builder()
                    .toolCallRequired(true)
                    .toolCall(ToolCall.builder()
                            .id("recovered-" + UUID.randomUUID().toString().substring(0, 8))
                            .toolName(toolName)
                            .arguments(args)
                            .build())
                    .build();

        } catch (Exception e) {
            log.error("Failed to recover from Groq tool_use_failed: {}", e.getMessage());
            return errorResponse("The agent encountered a tool formatting issue. Please try again.");
        }
    }

    private LlmResponse errorResponse(String message) {
        return LlmResponse.builder()
                .toolCallRequired(false)
                .content(message)
                .build();
    }

    private Map<String, Object> buildRequestBody(List<Message> messages, List<ToolDefinition> tools) {
        List<Map<String, Object>> formattedMessages = messages.stream()
                .map(this::formatMessage)
                .toList();

        Map<String, Object> body = new HashMap<>();
        body.put("model", props.getModel());
        body.put("max_tokens", props.getMaxTokens());
        body.put("temperature", props.getTemperature());
        body.put("messages", formattedMessages);

        if (!tools.isEmpty()) {
            body.put("tools", tools.stream().map(ToolDefinition::toOpenAiSchema).toList());
            body.put("tool_choice", "auto");
        }

        return body;
    }

    private Map<String, Object> formatMessage(Message msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", msg.getRole().name());

        if (msg.getRole() == Message.Role.tool) {
            m.put("tool_call_id", msg.getToolCallId());
            m.put("content", msg.getContent());
        } else if (msg.getRole() == Message.Role.assistant && msg.getContent() == null) {
            m.put("content", "");
        } else {
            m.put("content", msg.getContent() != null ? msg.getContent() : "");
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private LlmResponse parseResponse(Map<String, Object> response) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new AgentException(providerName + " returned no choices in response");
        }

        int promptTokens = 0, completionTokens = 0;
        Map<String, Object> usage = (Map<String, Object>) response.get("usage");
        if (usage != null) {
            promptTokens     = ((Number) usage.getOrDefault("prompt_tokens", 0)).intValue();
            completionTokens = ((Number) usage.getOrDefault("completion_tokens", 0)).intValue();
            log.debug("Token usage — prompt={} completion={}", promptTokens, completionTokens);
        }

        Map<String, Object> choice       = choices.get(0);
        Map<String, Object> message      = (Map<String, Object>) choice.get("message");
        String              finishReason = (String) choice.get("finish_reason");

        log.debug("{} finish_reason: {}", providerName, finishReason);

        if ("tool_calls".equals(finishReason)) {
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
            Map<String, Object> first    = toolCalls.get(0);
            Map<String, Object> function = (Map<String, Object>) first.get("function");

            Map<String, Object> args;
            try {
                args = objectMapper.readValue(
                        (String) function.get("arguments"), new TypeReference<>() {});
            } catch (JsonProcessingException e) {
                throw new AgentException("Failed to parse tool arguments", e);
            }

            return LlmResponse.builder()
                    .toolCallRequired(true)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .toolCall(ToolCall.builder()
                            .id((String) first.get("id"))
                            .toolName((String) function.get("name"))
                            .arguments(args)
                            .build())
                    .build();
        }

        return LlmResponse.builder()
                .toolCallRequired(false)
                .content((String) message.get("content"))
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .build();
    }

    /**
     * Thrown internally when Groq returns tool_use_failed (400).
     * Caught in the same chat() method to attempt XML recovery.
     * Not propagated outside GenericLlmClient.
     */
    private static class GroqToolUseFailedException extends RuntimeException {
        private final String errorBody;
        GroqToolUseFailedException(String errorBody) {
            super("Groq tool_use_failed");
            this.errorBody = errorBody;
        }
        String getErrorBody() { return errorBody; }
    }
}
