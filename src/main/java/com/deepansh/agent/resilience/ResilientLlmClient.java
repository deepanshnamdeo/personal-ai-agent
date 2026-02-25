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
 * Decorator around OpenAiClient that adds retry + circuit breaker.
 *
 * Why a decorator over annotations on OpenAiClient directly:
 * - OpenAiClient stays pure — no resilience concerns mixed into HTTP logic
 * - @Primary ensures AgentLoop gets this bean, not the raw client
 * - Fallback is explicit and testable
 *
 * Retry config (in application.yml):
 * - 3 attempts, exponential backoff: 2s → 4s → 8s
 * - Retries on network errors and 5xx; skips retry on logic errors
 *
 * Circuit breaker config:
 * - Opens after 50% failure rate in sliding window of 10 calls
 * - Waits 30s before allowing probe calls (half-open state)
 * - Counts slow calls (>30s) as failures
 */
@Component
@Primary
@Slf4j
public class ResilientLlmClient implements LlmClient {

    private final LlmClient delegate;

    public ResilientLlmClient(@Qualifier("openAiClient") LlmClient delegate) {
        this.delegate = delegate;
    }

    @Override
    @Retry(name = "llmClient", fallbackMethod = "retryFallback")
    @CircuitBreaker(name = "llmClient", fallbackMethod = "circuitBreakerFallback")
    public LlmResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        return delegate.chat(messages, tools);
    }

    /**
     * Retry fallback — all retry attempts exhausted.
     * Returns a structured error response the agent loop can handle.
     */
    public LlmResponse retryFallback(List<Message> messages,
                                      List<ToolDefinition> tools,
                                      Exception ex) {
        log.error("LLM call failed after all retries: {}", ex.getMessage());
        return LlmResponse.builder()
                .isToolCall(false)
                .content("I'm temporarily unable to process your request due to a connection issue. " +
                         "Please try again in a moment.")
                .build();
    }

    /**
     * Circuit breaker fallback — circuit is open, requests are short-circuited.
     */
    public LlmResponse circuitBreakerFallback(List<Message> messages,
                                               List<ToolDefinition> tools,
                                               Exception ex) {
        log.error("LLM circuit breaker is OPEN — rejecting call: {}", ex.getMessage());
        return LlmResponse.builder()
                .isToolCall(false)
                .content("The AI service is currently experiencing issues. " +
                         "Please try again in about 30 seconds.")
                .build();
    }
}
