package com.deepansh.agent.memory;

import com.deepansh.agent.llm.LlmClient;
import com.deepansh.agent.model.LlmResponse;
import com.deepansh.agent.model.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Asynchronously extracts memorable facts from completed conversations.
 *
 * Key design decisions:
 *
 * 1. Uses @Qualifier("activeLlmClient") directly — bypasses ResilientLlmClient
 *    and its shared "llmClient" circuit breaker. Memory extraction failures
 *    must NEVER affect the main agent's availability.
 *
 * 2. Has its own @CircuitBreaker("memoryExtraction") — independent CB state.
 *    The main agent CB and memory extraction CB are completely isolated.
 *
 * 3. All failures are caught and logged — never propagated to the caller.
 *    This is background best-effort work. User experience is unaffected.
 *
 * 4. Three guards before JSON parsing prevent JsonParseException when
 *    the LLM returns prose instead of a JSON array (e.g. CB fallback text).
 */
@Service
@Slf4j
public class MemoryExtractionService {

    private final LlmClient llmClient;
    private final LongTermMemory longTermMemory;
    private final ObjectMapper objectMapper;

    public MemoryExtractionService(
            @Qualifier("activeLlmClient") LlmClient llmClient,
            LongTermMemory longTermMemory,
            ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.longTermMemory = longTermMemory;
        this.objectMapper = objectMapper;
    }

    @Async("memoryTaskExecutor")
    public void extractAndStore(String userId, String sessionId, List<Message> messages) {
        if (messages == null || messages.size() < 2) {
            return;
        }

        log.info("Starting async memory extraction for user={} session={} messages={}",
                userId, sessionId, messages.size());

        try {
            List<ExtractedFact> facts = extractFacts(messages);
            if (facts.isEmpty()) {
                log.debug("No memorable facts found in session={}", sessionId);
                return;
            }

            facts.forEach(fact -> longTermMemory.store(
                    userId, fact.content(), fact.tag(), sessionId));

            log.info("Extracted and stored {} facts for user={} from session={}",
                    facts.size(), userId, sessionId);

        } catch (Exception e) {
            log.error("Memory extraction failed for session={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Makes the LLM call with its own circuit breaker.
     * Falls back to empty list on any failure — never disrupts the main agent.
     */
    @CircuitBreaker(name = "memoryExtraction", fallbackMethod = "extractionCircuitBreakerFallback")
    protected LlmResponse callLlmForExtraction(List<Message> extractionMessages) {
        return llmClient.chat(extractionMessages, List.of());
    }

    protected LlmResponse extractionCircuitBreakerFallback(List<Message> messages, Exception ex) {
        log.warn("Memory extraction circuit breaker open — skipping extraction: {}", ex.getMessage());
        return LlmResponse.builder().toolCallRequired(false).content("[]").build();
    }

    private List<ExtractedFact> extractFacts(List<Message> messages) throws Exception {
        String conversationText = buildConversationText(messages);

        if (conversationText.isBlank()) {
            return List.of();
        }

        String extractionPrompt = """
                Analyze the following conversation and extract facts worth remembering about the user.

                Extract ONLY facts that would be useful in future conversations:
                - Personal/professional facts (role, company, projects, skills)
                - Stated preferences (communication style, tools, formats they like)
                - Ongoing tasks or goals they mentioned
                - Important context about their work or life

                DO NOT extract:
                - Temporary or one-off requests
                - Generic questions with no personal context
                - Error messages or system messages

                Respond with ONLY a JSON array. No explanation, no markdown, no prose. Example:
                [
                  {"content": "Works as a Java backend developer", "tag": "fact"},
                  {"content": "Prefers bullet-point summaries", "tag": "preference"}
                ]

                Valid tags: fact, preference, task, context
                If nothing is worth remembering, respond with exactly: []

                Conversation:
                """ + conversationText;

        Message systemMsg = Message.builder()
                .role(Message.Role.system)
                .content("You are a memory extraction assistant. Output only valid JSON arrays. Nothing else.")
                .build();

        Message userMsg = Message.builder()
                .role(Message.Role.user)
                .content(extractionPrompt)
                .build();

        LlmResponse response = callLlmForExtraction(List.of(systemMsg, userMsg));
        String raw = response.getContent();

        // Guard 1: null/blank
        if (raw == null || raw.isBlank()) {
            log.debug("Empty extraction response — skipping");
            return List.of();
        }

        // Strip markdown fences
        String cleaned = raw.strip()
                .replaceAll("(?s)^```json\\s*", "")
                .replaceAll("(?s)^```\\s*", "")
                .replaceAll("(?s)```\\s*$", "")
                .strip();

        // Guard 2: not a JSON array (CB fallback text, prose, etc.)
        if (!cleaned.startsWith("[")) {
            log.warn("Extraction response is not a JSON array — skipping. First 100 chars: '{}'",
                    cleaned.substring(0, Math.min(100, cleaned.length())));
            return List.of();
        }

        // Guard 3: parse defensively
        try {
            return objectMapper.readValue(cleaned, new TypeReference<List<ExtractedFact>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse extraction JSON — skipping. Error: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildConversationText(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        messages.forEach(m -> {
            if (m.getRole() == Message.Role.system) return;
            if (m.getRole() == Message.Role.tool) return;
            if (m.getContent() == null || m.getContent().isBlank()) return;
            String role = m.getRole() == Message.Role.user ? "User" : "Assistant";
            sb.append(role).append(": ").append(m.getContent()).append("\n");
        });
        return sb.toString();
    }

    record ExtractedFact(String content, String tag) {}
}
