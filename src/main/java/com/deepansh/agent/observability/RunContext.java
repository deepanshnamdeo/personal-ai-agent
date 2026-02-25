package com.deepansh.agent.observability;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable per-run context for collecting observability data.
 * Created at the start of each agent run, populated throughout,
 * then flushed to AgentRunTrace at the end.
 *
 * Kept separate from AgentContext (which holds conversation state)
 * so observability concerns don't bleed into the core loop.
 */
@Data
public class RunContext {

    private final long startTimeMs = System.currentTimeMillis();
    private final List<ToolCallRecord> toolCallRecords = new ArrayList<>();

    // Token usage — populated from OpenAI response headers/body
    private int promptTokens;
    private int completionTokens;

    public void recordToolCall(String toolName, Object args, long latencyMs, String result) {
        toolCallRecords.add(new ToolCallRecord(toolName, args, latencyMs, result));
    }

    public void addTokens(int prompt, int completion) {
        this.promptTokens += prompt;
        this.completionTokens += completion;
    }

    public long elapsedMs() {
        return System.currentTimeMillis() - startTimeMs;
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public record ToolCallRecord(
            String toolName,
            Object args,
            long latencyMs,
            String result
    ) {}
}
