package com.deepansh.agent.resilience;

import com.deepansh.agent.llm.LlmClient;
import com.deepansh.agent.model.LlmResponse;
import com.deepansh.agent.model.Message;
import com.deepansh.agent.tool.ToolDefinition;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resilience decorator around the active LLM client.
 *
 * Wraps whichever provider is active (groq / openai / gemini)
 * with retry + circuit breaker. Provider switching is transparent —
 * this class doesn't care which provider is underneath.
 */
@Component
@Primary
@Slf4j
public class ResilientLlmClient implements LlmClient {

    private final LlmClient delegate;

    public ResilientLlmClient(@Qualifier("activeLlmClient") LlmClient delegate) {
        this.delegate = delegate;
    }

    @Override
    @Retry(name = "llmClient", fallbackMethod = "retryFallback")
    @CircuitBreaker(name = "llmClient", fallbackMethod = "circuitBreakerFallback")
    public LlmResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        return delegate.chat(messages, tools);
    }

    public LlmResponse retryFallback(List<Message> messages,
                                      List<ToolDefinition> tools,
                                      Exception ex) {
        log.error("LLM call failed after all retries: {}", ex.getMessage());
        return LlmResponse.builder()
                .toolCallRequired(false)
                .content("I'm temporarily unable to reach the AI service. " +
                         "Please check your API key and try again.")
                .build();
    }

    public LlmResponse circuitBreakerFallback(List<Message> messages,
                                               List<ToolDefinition> tools,
                                               Exception ex) {
        log.error("LLM circuit breaker is OPEN: {}", ex.getMessage());
        return LlmResponse.builder()
                .toolCallRequired(false)
                .content("The AI service is currently unavailable. Please try again in 30 seconds.")
                .build();
    }
}
