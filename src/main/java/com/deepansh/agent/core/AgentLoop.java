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
import com.deepansh.agent.observability.RunContext;
import com.deepansh.agent.observability.TraceService;
import com.deepansh.agent.tool.ToolDefinition;
import com.deepansh.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Core ReAct (Reason → Act → Observe) agent loop.
 *
 * Per-run flow:
 * 1. Upsert session metadata
 * 2. Load short-term memory (Redis)
 * 3. Inject long-term memory into system prompt
 * 4. ReAct loop: LLM → tool call → observation → repeat
 * 5. Persist conversation to Redis
 * 6. Async: extract memories + persist trace
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
    private final TraceService traceService;

    @Value("${agent.max-iterations:10}")
    private int maxIterations;

    public AgentLoop(LlmClient llmClient,
                     ToolRegistry toolRegistry,
                     ShortTermMemory shortTermMemory,
                     LongTermMemory longTermMemory,
                     MemoryExtractionService memoryExtractionService,
                     SessionService sessionService,
                     TraceService traceService) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.memoryExtractionService = memoryExtractionService;
        this.sessionService = sessionService;
        this.traceService = traceService;
    }

    public AgentResponse run(AgentRequest request) {
        String sessionId = resolveSessionId(request.getSessionId());
        String userId    = request.getUserId() != null ? request.getUserId() : "default";

        log.info("Agent run started [sessionId={}, userId={}, input='{}']",
                sessionId, userId, request.getInput());

        RunContext runCtx = new RunContext();
        AgentResponse response = null;
        Throwable error = null;

        try {
            sessionService.upsertSession(sessionId, userId);

            List<Message> history = shortTermMemory.load(sessionId);
            List<Message> messages = new ArrayList<>();

            if (history.isEmpty()) {
                messages.add(buildSystemMessage(userId));
            } else {
                messages.addAll(history);
            }

            messages.add(Message.builder()
                    .role(Message.Role.user)
                    .content(request.getInput())
                    .build());

            AgentContext context = AgentContext.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .userInput(request.getInput())
                    .messages(messages)
                    .executedToolCalls(new ArrayList<>())
                    .currentIteration(0)
                    .build();

            response = executeLoop(context, runCtx);
            shortTermMemory.save(sessionId, context.getMessages());
            memoryExtractionService.extractAndStore(userId, sessionId, context.getMessages());

        } catch (Exception e) {
            log.error("Agent run failed [sessionId={}]", sessionId, e);
            error = e;
            response = AgentResponse.builder()
                    .finalAnswer("An error occurred: " + e.getMessage())
                    .toolCallsExecuted(List.of())
                    .iterationsUsed(0)
                    .maxIterationsReached(false)
                    .sessionId(sessionId)
                    .build();
        } finally {
            // Always persist trace — even on error
            traceService.persistTrace(sessionId, userId, request.getInput(),
                    response != null ? response : buildErrorResponse(sessionId),
                    runCtx, error);
        }

        log.info("Agent run complete [sessionId={}, iterations={}, latency={}ms, tokens={}]",
                sessionId, response.getIterationsUsed(), runCtx.elapsedMs(), runCtx.totalTokens());

        return response;
    }

    private AgentResponse executeLoop(AgentContext context, RunContext runCtx) {
        List<ToolDefinition> tools = toolRegistry.getAllDefinitions();

        for (int i = 0; i < maxIterations; i++) {
            context.setCurrentIteration(i + 1);
            log.info("Agent iteration {}/{} [session={}]", i + 1, maxIterations, context.getSessionId());

            LlmResponse llmResponse = llmClient.chat(context.getMessages(), tools);

            // Accumulate token usage per iteration
            runCtx.addTokens(llmResponse.getPromptTokens(), llmResponse.getCompletionTokens());

            if (!llmResponse.isToolCallRequired()) {
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

            ToolCall toolCall = llmResponse.getToolCall();
            context.getExecutedToolCalls().add(toolCall);

            log.info("LLM requested tool: [{}] [session={}]", toolCall.getToolName(), context.getSessionId());

            long toolStart = System.currentTimeMillis();
            String observation = toolRegistry.execute(toolCall);
            long toolLatency = System.currentTimeMillis() - toolStart;

            runCtx.recordToolCall(toolCall.getToolName(), toolCall.getArguments(),
                    toolLatency, observation);

            // IMPORTANT: The assistant message MUST include tool_calls per the OpenAI spec.
            // Without it, the LLM receives a malformed conversation on the next iteration
            // and re-generates broken tool calls instead of processing the tool result.
            context.getMessages().add(Message.builder()
                    .role(Message.Role.assistant)
                    .content(null)
                    .toolCalls(List.of(toolCall))
                    .build());

            context.getMessages().add(Message.builder()
                    .role(Message.Role.tool)
                    .toolCallId(toolCall.getId())
                    .name(toolCall.getToolName())
                    .content(observation)
                    .build());
        }

        log.warn("Agent hit max iterations ({}) [session={}]", maxIterations, context.getSessionId());

        return AgentResponse.builder()
                .finalAnswer("I was unable to complete the task within the allowed steps.")
                .toolCallsExecuted(context.getExecutedToolCalls())
                .iterationsUsed(maxIterations)
                .maxIterationsReached(true)
                .sessionId(context.getSessionId())
                .build();
    }

    private Message buildSystemMessage(String userId) {
        String ltm = longTermMemory.formatForPrompt(userId);
        String systemContent = """
                You are a personal productivity assistant. You help with tasks, research, notes, and planning.

                When given a task:
                1. Think step-by-step about what you need
                2. Use tools one at a time — wait for each result before proceeding
                3. Provide a clear, concise final answer

                Rules:
                - For well-known facts (country capitals, historical dates, famous people, geography, etc.) \
                answer directly from your training knowledge — do NOT use web_search for these.
                - Use web_search only for current events, live data, or information that may have changed recently.
                - If a tool returns an ERROR, do NOT call the same tool again. \
                Instead, answer from your own knowledge or clearly explain the limitation.
                - Be concise and actionable.
                """
                + (ltm.isEmpty() ? "" : "\n" + ltm);

        return Message.builder().role(Message.Role.system).content(systemContent).build();
    }

    private String resolveSessionId(String provided) {
        return (provided != null && !provided.isBlank()) ? provided : UUID.randomUUID().toString();
    }

    private AgentResponse buildErrorResponse(String sessionId) {
        return AgentResponse.builder()
                .finalAnswer("Error")
                .toolCallsExecuted(List.of())
                .iterationsUsed(0)
                .maxIterationsReached(false)
                .sessionId(sessionId)
                .build();
    }
}
