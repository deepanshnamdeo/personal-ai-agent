package com.deepansh.agent.memory;

import com.deepansh.agent.llm.LlmClient;
import com.deepansh.agent.model.Message;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Automatically extracts memorable facts from a completed conversation
 * and persists them to long-term memory.
 *
 * Design decisions:
 * - Called @Async after each agent run — does NOT block the response to the user
 * - Uses a dedicated LLM call with a strict JSON-extraction prompt
 * - Only stores facts that are genuinely worth remembering (not every message)
 * - Idempotent-safe: duplicate facts are tolerable (similarity dedup is a V2 concern)
 *
 * Extraction categories (tags):
 *   "preference"  — user likes/dislikes, communication style
 *   "fact"        — stated personal/professional facts about the user
 *   "task"        — ongoing tasks or projects the user mentioned
 *   "context"     — background context that would help future sessions
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemoryExtractionService {

    private final LlmClient llmClient;
    private final LongTermMemory longTermMemory;
    private final ObjectMapper objectMapper;

    /**
     * Asynchronously extract and persist facts from a completed conversation.
     * Called after the agent run completes — non-blocking to the user.
     *
     * @param userId    user to store memories under
     * @param sessionId source session for traceability
     * @param messages  full conversation history (excluding system message)
     */
    @Async("memoryTaskExecutor")
    public void extractAndStore(String userId, String sessionId, List<Message> messages) {
        if (messages == null || messages.size() < 2) {
            // Nothing worth extracting from a single-message exchange
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
            // Never let extraction failure propagate — it's best-effort
            log.error("Memory extraction failed for session={}", sessionId, e);
        }
    }

    private List<ExtractedFact> extractFacts(List<Message> messages) throws Exception {
        String conversationText = buildConversationText(messages);

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
                - Things already obvious from context
                
                Respond with ONLY a JSON array. No explanation, no markdown. Example:
                [
                  {"content": "Works as a Java backend developer", "tag": "fact"},
                  {"content": "Prefers bullet-point summaries", "tag": "preference"},
                  {"content": "Currently building a microservices platform called Phoenix", "tag": "task"}
                ]
                
                Valid tags: fact, preference, task, context
                
                If there is nothing worth remembering, respond with an empty array: []
                
                Conversation:
                """ + conversationText;

        Message systemMsg = Message.builder()
                .role(Message.Role.system)
                .content("You are a memory extraction assistant. Output only valid JSON arrays.")
                .build();

        Message userMsg = Message.builder()
                .role(Message.Role.user)
                .content(extractionPrompt)
                .build();

        // No tools needed for extraction — pure text generation
        var response = llmClient.chat(List.of(systemMsg, userMsg), List.of());
        String raw = response.getContent();

        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        // Strip any accidental markdown fences
        String cleaned = raw.strip()
                .replaceAll("^```json", "")
                .replaceAll("^```", "")
                .replaceAll("```$", "")
                .strip();

        return objectMapper.readValue(cleaned, new TypeReference<List<ExtractedFact>>() {});
    }

    private String buildConversationText(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        messages.forEach(m -> {
            if (m.getRole() == Message.Role.system) return; // skip system
            if (m.getRole() == Message.Role.tool) return;   // skip raw tool outputs

            String role = m.getRole() == Message.Role.user ? "User" : "Assistant";
            if (m.getContent() != null && !m.getContent().isBlank()) {
                sb.append(role).append(": ").append(m.getContent()).append("\n");
            }
        });
        return sb.toString();
    }

    /**
     * Internal record for JSON deserialization of extracted facts.
     */
    record ExtractedFact(String content, String tag) {}
}
