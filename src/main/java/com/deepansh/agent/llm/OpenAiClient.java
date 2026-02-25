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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component("openAiClient")
@Slf4j
public class OpenAiClient implements LlmClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${openai.model}")
    private String model;

    @Value("${openai.max-tokens}")
    private int maxTokens;

    @Value("${openai.temperature}")
    private double temperature;

    public OpenAiClient(
            @Value("${openai.base-url}") String baseUrl,
            @Value("${openai.api-key}") String apiKey,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public LlmResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        Map<String, Object> requestBody = buildRequestBody(messages, tools);

        log.debug("Sending {} messages and {} tools to OpenAI [model={}]",
                messages.size(), tools.size(), model);

        Map<String, Object> response = restClient.post()
                .uri("/chat/completions")
                .body(requestBody)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        return parseResponse(response);
    }

    private Map<String, Object> buildRequestBody(List<Message> messages, List<ToolDefinition> tools) {
        List<Map<String, Object>> formattedMessages = messages.stream()
                .map(this::formatMessage)
                .toList();

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
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
            throw new AgentException("OpenAI returned no choices in response");
        }

        // Parse token usage
        int promptTokens = 0, completionTokens = 0;
        Map<String, Object> usage = (Map<String, Object>) response.get("usage");
        if (usage != null) {
            promptTokens    = ((Number) usage.getOrDefault("prompt_tokens", 0)).intValue();
            completionTokens = ((Number) usage.getOrDefault("completion_tokens", 0)).intValue();
            log.debug("Token usage — prompt={} completion={} total={}",
                    promptTokens, completionTokens, promptTokens + completionTokens);
        }

        Map<String, Object> choice = choices.get(0);
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        String finishReason = (String) choice.get("finish_reason");

        log.debug("OpenAI finish_reason: {}", finishReason);

        if ("tool_calls".equals(finishReason)) {
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
            Map<String, Object> first    = toolCalls.get(0);
            Map<String, Object> function = (Map<String, Object>) first.get("function");

            Map<String, Object> args;
            try {
                args = objectMapper.readValue(
                        (String) function.get("arguments"), new TypeReference<>() {});
            } catch (JsonProcessingException e) {
                throw new AgentException("Failed to parse tool arguments from OpenAI response", e);
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
