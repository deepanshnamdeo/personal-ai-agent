package com.deepansh.agent.core;

import com.deepansh.agent.llm.LlmClient;
import com.deepansh.agent.memory.LongTermMemory;
import com.deepansh.agent.memory.MemoryExtractionService;
import com.deepansh.agent.memory.SessionService;
import com.deepansh.agent.memory.ShortTermMemory;
import com.deepansh.agent.model.AgentRequest;
import com.deepansh.agent.model.AgentResponse;
import com.deepansh.agent.model.LlmResponse;
import com.deepansh.agent.model.Message;
import com.deepansh.agent.model.ToolCall;
import com.deepansh.agent.tool.ToolDefinition;
import com.deepansh.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The core ReAct (Reason → Act → Observe) agent loop.
 *
 * Flow per run:
 * 1. Load short-term memory (conversation window from Redis)
 * 2. Inject long-term memory into system prompt (facts from PostgreSQL)
 * 3. Append new user message
 * 4. Loop:
 *    a. Call LLM with current messages + tool schemas
 *    b. If final answer → persist messages to Redis, return response
 *    c. If tool call → execute tool, append observation, continue loop
 * 5. Circuit breaker: exit after max-iterations
 */
@Service
@Slf4j
public class AgentLoop {

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final MemoryExtractionService memoryExtractionService;
    private final SessionService sessionService;

    @Value("${agent.max-iterations:10}")
    private int maxIterations;

    public AgentLoop(LlmClient llmClient,
                     ToolRegistry toolRegistry,
                     ShortTermMemory shortTermMemory,
                     LongTermMemory longTermMemory,
                     MemoryExtractionService memoryExtractionService,
                     SessionService sessionService) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.memoryExtractionService = memoryExtractionService;
        this.sessionService = sessionService;
    }

    public AgentResponse run(AgentRequest request) {
        String sessionId = resolveSessionId(request.getSessionId());
        String userId = request.getUserId() != null ? request.getUserId() : "default";

        log.info("Agent run started [sessionId={}, userId={}, input='{}']",
                sessionId, userId, request.getInput());

        AgentContext context = AgentContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .userInput(request.getInput())
                .messages(new ArrayList<>())
                .executedToolCalls(new ArrayList<>())
                .currentIteration(0)
                .build();

        // 1. Track session in PostgreSQL (upsert — increments turn count)
        sessionService.upsertSession(sessionId, userId);

        // 2. Load existing conversation from Redis
        List<Message> history = shortTermMemory.load(sessionId);

        if (history.isEmpty()) {
            // New session — build system message with long-term memory injected
            context.getMessages().add(buildSystemMessage(userId));
        } else {
            // Existing session — reuse history (already has system message)
            context.getMessages().addAll(history);
        }

        // 2. Append the new user message
        context.getMessages().add(Message.builder()
                .role(Message.Role.user)
                .content(request.getInput())
                .build());

        List<ToolDefinition> tools = toolRegistry.getAllDefinitions();
        AgentResponse result = executeLoop(context, tools);

        // 3. Persist updated conversation window to Redis
        shortTermMemory.save(sessionId, context.getMessages());

        // 4. Async: extract memorable facts and persist to PostgreSQL (non-blocking)
        memoryExtractionService.extractAndStore(userId, sessionId, context.getMessages());

        log.info("Agent run complete [sessionId={}, iterations={}, maxReached={}]",
                sessionId, result.getIterationsUsed(), result.isMaxIterationsReached());

        return result;
    }

    private AgentResponse executeLoop(AgentContext context, List<ToolDefinition> tools) {
        for (int i = 0; i < maxIterations; i++) {
            context.setCurrentIteration(i + 1);
            log.info("Agent iteration {}/{} [session={}]",
                    i + 1, maxIterations, context.getSessionId());

            LlmResponse llmResponse = llmClient.chat(context.getMessages(), tools);

            if (!llmResponse.isToolCall()) {
                // LLM produced a final answer
                context.getMessages().add(Message.builder()
                        .role(Message.Role.assistant)
                        .content(llmResponse.getContent())
                        .build());

                return AgentResponse.builder()
                        .finalAnswer(llmResponse.getContent())
                        .toolCallsExecuted(context.getExecutedToolCalls())
                        .iterationsUsed(i + 1)
                        .maxIterationsReached(false)
                        .sessionId(context.getSessionId())
                        .build();
            }

            // LLM wants to call a tool
            ToolCall toolCall = llmResponse.getToolCall();
            context.getExecutedToolCalls().add(toolCall);

            log.info("LLM requested tool: [{}] [session={}]",
                    toolCall.getToolName(), context.getSessionId());

            // Append assistant's tool-call decision to history
            // Note: content is empty string here — OpenAI expects this format
            context.getMessages().add(Message.builder()
                    .role(Message.Role.assistant)
                    .content(null)
                    .build());

            // Execute tool
            String observation = toolRegistry.execute(toolCall);

            // Append tool result as observation
            context.getMessages().add(Message.builder()
                    .role(Message.Role.tool)
                    .toolCallId(toolCall.getId())
                    .name(toolCall.getToolName())
                    .content(observation)
                    .build());
        }

        // Circuit breaker hit
        log.warn("Agent hit max iterations ({}) [session={}]",
                maxIterations, context.getSessionId());

        return AgentResponse.builder()
                .finalAnswer("I was unable to complete the task within the allowed number of steps. " +
                             "Please try rephrasing or breaking the request into smaller parts.")
                .toolCallsExecuted(context.getExecutedToolCalls())
                .iterationsUsed(maxIterations)
                .maxIterationsReached(true)
                .sessionId(context.getSessionId())
                .build();
    }

    /**
     * Builds the system message, injecting any long-term memories for this user.
     */
    private Message buildSystemMessage(String userId) {
        String longTermContext = longTermMemory.formatForPrompt(userId);

        String systemContent = """
                You are a personal productivity assistant. You help with tasks, research, notes, and planning.
                
                When given a task:
                1. Think step-by-step about what information or actions you need
                2. Use available tools one at a time — wait for each result before proceeding
                3. When you have enough information, provide a clear, concise final answer
                
                Rules:
                - Never make up information — use tools to find it
                - If a tool fails, try an alternative approach or explain the limitation
                - Be concise and actionable in your responses
                """
                + (longTermContext.isEmpty() ? "" : "\n" + longTermContext);

        return Message.builder()
                .role(Message.Role.system)
                .content(systemContent)
                .build();
    }

    private String resolveSessionId(String provided) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return UUID.randomUUID().toString();
    }
}
