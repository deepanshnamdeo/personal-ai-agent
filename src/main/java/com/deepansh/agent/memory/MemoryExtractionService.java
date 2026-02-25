package com.deepansh.agent.memory;

import com.deepansh.agent.llm.LlmClient;
import com.deepansh.agent.model.LlmResponse;
import com.deepansh.agent.model.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Automatically extracts memorable facts from a completed conversation
 * and persists them to long-term memory.
 *
 * Runs @Async after every agent run — never blocks the user response.
 *
 * Key fix: validates that the LLM response is actually a JSON array
 * BEFORE attempting to parse it. If the LLM returned a fallback/error
 * message (e.g. circuit breaker fired), we skip extraction silently
 * instead of crashing with JsonParseException.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemoryExtractionService {

    private final LlmClient llmClient;
    private final LongTermMemory longTermMemory;
    private final ObjectMapper objectMapper;

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
            // Never let extraction failure propagate — it's best-effort background work
            log.error("Memory extraction failed for session={}: {}", sessionId, e.getMessage());
        }
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

        LlmResponse response = llmClient.chat(List.of(systemMsg, userMsg), List.of());
        String raw = response.getContent();

        // Guard 1: null or blank response
        if (raw == null || raw.isBlank()) {
            log.debug("LLM returned empty response during extraction — skipping");
            return List.of();
        }

        // Strip markdown fences if present
        String cleaned = raw.strip()
                .replaceAll("(?s)^```json\\s*", "")
                .replaceAll("(?s)^```\\s*", "")
                .replaceAll("(?s)```\\s*$", "")
                .strip();

        // Guard 2: response is not a JSON array (e.g. circuit breaker fallback text,
        // or LLM returned prose instead of JSON)
        if (!cleaned.startsWith("[")) {
            log.warn("LLM extraction response is not a JSON array — skipping. " +
                     "First 100 chars: '{}'", cleaned.substring(0, Math.min(100, cleaned.length())));
            return List.of();
        }

        // Guard 3: parse defensively — catch malformed JSON without crashing
        try {
            return objectMapper.readValue(cleaned, new TypeReference<List<ExtractedFact>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse extraction JSON — skipping. Response was: '{}'. Error: {}",
                    cleaned.substring(0, Math.min(200, cleaned.length())), e.getMessage());
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
