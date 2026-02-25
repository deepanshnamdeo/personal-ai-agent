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
 * Error handling strategy:
 *
 * | Error                  | Action                                        |
 * |------------------------|-----------------------------------------------|
 * | 401 invalid_api_key    | AgentException (not retried, not CB failure)  |
 * | 400 model_decommissioned | AgentException with loud guidance message   |
 * | 400 tool_use_failed    | Recover from failed_generation XML — continue |
 * | 400 other              | AgentException (not retried, not CB failure)  |
 * | 5xx server error       | RuntimeException (retried, counts as failure) |
 * | network error          | ResourceAccessException (retried)             |
 */
@Slf4j
public class GenericLlmClient implements LlmClient {

    // Matches BOTH broken XML formats Groq emits:
    // Format 1 (with parens):    <function=web_search({"arg": "val"})</function>
    // Format 2 (without parens): <function=web_search{"arg": "val"}></function>
    // The key difference: parens around the JSON object are made optional via \(? and \)?
    // and the JSON body is anchored to { ... } so both variants are captured in group 2.
    private static final Pattern GROQ_XML_TOOL_PATTERN =
            Pattern.compile("<function=(\\w+)\\(?(\\{.+?\\})\\)?(?:</function>|>)", Pattern.DOTALL);

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
                        log.error("{} 4xx [{}]: {}", providerName, res.getStatusCode(), body);
                        handle4xxError(body, res.getStatusCode().value());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        String body = new String(res.getBody().readAllBytes());
                        log.error("{} 5xx [{}]: {}", providerName, res.getStatusCode(), body);
                        // 5xx IS retryable — RuntimeException (not AgentException)
                        throw new RuntimeException(
                            providerName + " server error [" + res.getStatusCode() + "]: " + body);
                    })
                    .body(new ParameterizedTypeReference<>() {});

            return parseResponse(response);

        } catch (GroqToolUseFailedException e) {
            return recoverFromGroqToolUseFailure(e.getErrorBody());
        }
    }

    /**
     * Central 4xx error handler — maps error codes to correct exception types
     * so the circuit breaker and retry behave correctly for each case.
     */
    private void handle4xxError(String body, int statusCode) {
        // model_decommissioned — configuration error, not a runtime failure.
        // Throw AgentException (CB ignore list) + log clear guidance.
        if (body.contains("model_decommissioned")) {
            log.error("================================================================");
            log.error("  MODEL DECOMMISSIONED: {} is no longer supported.", props.getModel());
            log.error("  Update your model in application.yml or set env var:");
            log.error("  GROQ_MODEL=llama-3.3-70b-versatile");
            log.error("  See https://console.groq.com/docs/deprecations for alternatives");
            log.error("================================================================");
            throw new AgentException(
                "Model '" + props.getModel() + "' is decommissioned. " +
                "Set GROQ_MODEL=llama-3.3-70b-versatile in your environment variables.");
        }

        // tool_use_failed — Groq emitted XML tool call instead of JSON.
        // Handled by recoverFromGroqToolUseFailure — don't count as failure.
        if (body.contains("tool_use_failed")) {
            throw new GroqToolUseFailedException(body);
        }

        // 401 invalid API key — AgentException (not retryable, not CB failure)
        if (statusCode == 401) {
            throw new AgentException(
                providerName + " API key is invalid. Check your " +
                providerName.toUpperCase() + "_API_KEY environment variable.");
        }

        // 429 rate limit — retryable RuntimeException (counts toward CB)
        if (statusCode == 429) {
            throw new RuntimeException(providerName + " rate limit exceeded. Will retry.");
        }

        // All other 4xx — AgentException (not retried, not CB failure)
        throw new AgentException(providerName + " client error [" + statusCode + "]: " + body);
    }

    /**
     * Groq's tool_use_failed error contains the broken generation in "failed_generation".
     * Parse the XML format and extract the tool call to continue the agent loop.
     *
     * Broken format:  <function=web_search({"query": "..."})</function>
     * What we return: a valid LlmResponse with ToolCall populated
     */
    @SuppressWarnings("unchecked")
    private LlmResponse recoverFromGroqToolUseFailure(String errorBody) {
        try {
            Map<String, Object> errorMap = objectMapper.readValue(errorBody, new TypeReference<>() {});
            Map<String, Object> error    = (Map<String, Object>) errorMap.get("error");
            String failedGeneration      = (String) error.get("failed_generation");

            if (failedGeneration == null || failedGeneration.isBlank()) {
                log.warn("Groq tool_use_failed with no failed_generation — cannot recover");
                return plainTextResponse("I encountered a tool formatting issue. Please rephrase your request.");
            }

            log.debug("Recovering from Groq tool_use_failed. Generation: {}", failedGeneration);

            Matcher matcher = GROQ_XML_TOOL_PATTERN.matcher(failedGeneration);
            if (!matcher.find()) {
                log.warn("Could not parse XML tool call from failed_generation: {}", failedGeneration);
                return plainTextResponse("I encountered a tool formatting issue. Please rephrase your request.");
            }

            String toolName = matcher.group(1);
            String argsJson = matcher.group(2);

            Map<String, Object> args = objectMapper.readValue(argsJson, new TypeReference<>() {});
            log.info("Recovered Groq tool call: tool={} args={}", toolName, args);

            return LlmResponse.builder()
                    .toolCallRequired(true)
                    .toolCall(ToolCall.builder()
                            .id("groq-recovered-" + UUID.randomUUID().toString().substring(0, 8))
                            .toolName(toolName)
                            .arguments(args)
                            .build())
                    .build();

        } catch (Exception e) {
            log.error("Failed to recover from Groq tool_use_failed: {}", e.getMessage());
            return plainTextResponse("I encountered a tool formatting issue. Please rephrase your request.");
        }
    }

    private LlmResponse plainTextResponse(String message) {
        return LlmResponse.builder().toolCallRequired(false).content(message).build();
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
        } else if (msg.getRole() == Message.Role.assistant) {
            // Per the OpenAI spec, an assistant message that made tool calls must include
            // the tool_calls array. Without it the LLM cannot correlate tool results to its
            // original requests and will generate malformed follow-up calls.
            m.put("content", msg.getContent()); // null is valid here per spec
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                m.put("tool_calls", msg.getToolCalls().stream()
                        .map(tc -> {
                            Map<String, Object> tcMap = new HashMap<>();
                            tcMap.put("id", tc.getId());
                            tcMap.put("type", "function");

                            Map<String, Object> fn = new HashMap<>();
                            fn.put("name", tc.getToolName());
                            try {
                                fn.put("arguments", objectMapper.writeValueAsString(tc.getArguments()));
                            } catch (JsonProcessingException e) {
                                fn.put("arguments", "{}");
                            }
                            tcMap.put("function", fn);
                            return tcMap;
                        })
                        .toList());
            }
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

    private static class GroqToolUseFailedException extends RuntimeException {
        private final String errorBody;
        GroqToolUseFailedException(String errorBody) {
            super("Groq tool_use_failed");
            this.errorBody = errorBody;
        }
        String getErrorBody() { return errorBody; }
    }
}
