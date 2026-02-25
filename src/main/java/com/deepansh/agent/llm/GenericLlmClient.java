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

/**
 * OpenAI-compatible LLM client.
 *
 * Works for OpenAI, Groq, and Gemini — all three expose identical
 * REST API structure at /chat/completions. The only differences are
 * the base URL, API key, and model name — all passed via LlmProviderProperties.
 *
 * This replaces the old OpenAiClient as the single implementation.
 * The old OpenAiClient bean is kept only for backward compatibility
 * with the @Qualifier("openAiClient") in ResilientLlmClient.
 */
@Slf4j
public class GenericLlmClient implements LlmClient {

    private final LlmProviderProperties props;
    private final ObjectMapper objectMapper;
    private final String providerName;
    private final RestClient restClient;

    public GenericLlmClient(LlmProviderProperties props,
                             ObjectMapper objectMapper,
                             String providerName) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.providerName = providerName;
        this.restClient = RestClient.builder()
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

        Map<String, Object> response = restClient.post()
                .uri("/chat/completions")
                .body(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    String body = new String(res.getBody().readAllBytes());
                    log.error("{} 4xx error [{}]: {}", providerName, res.getStatusCode(), body);
                    if (res.getStatusCode().value() == 401) {
                        throw new AgentException(
                            providerName + " API key is invalid. Check your environment variable. " +
                            "Response: " + body);
                    }
                    throw new AgentException(providerName + " client error [" + res.getStatusCode() + "]: " + body);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    String body = new String(res.getBody().readAllBytes());
                    log.error("{} 5xx error [{}]: {}", providerName, res.getStatusCode(), body);
                    throw new RuntimeException(providerName + " server error [" + res.getStatusCode() + "]: " + body);
                })
                .body(new ParameterizedTypeReference<>() {});

        return parseResponse(response);
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

        Map<String, Object> choice      = choices.get(0);
        Map<String, Object> message     = (Map<String, Object>) choice.get("message");
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
}
