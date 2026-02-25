package com.deepansh.agent.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages session lifecycle — creation, turn tracking, and listing.
 *
 * Separating session concerns from memory concerns keeps both services
 * focused and testable independently.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SessionService {

    private final SessionMetadataRepository sessionRepo;

    /**
     * Called at the start of each agent run.
     * Creates the session entry if new, increments turn count if existing.
     */
    @Transactional
    public SessionMetadata upsertSession(String sessionId, String userId) {
        return sessionRepo.findById(sessionId)
                .map(existing -> {
                    existing.setTurnCount(existing.getTurnCount() + 1);
                    return sessionRepo.save(existing);
                })
                .orElseGet(() -> {
                    SessionMetadata newSession = SessionMetadata.builder()
                            .sessionId(sessionId)
                            .userId(userId)
                            .turnCount(1)
                            .build();
                    log.info("New session created [sessionId={}, userId={}]", sessionId, userId);
                    return sessionRepo.save(newSession);
                });
    }

    /**
     * Store a summary for the session (generated post-run by LLM if desired).
     */
    @Transactional
    public void updateSummary(String sessionId, String summary) {
        sessionRepo.findById(sessionId).ifPresent(session -> {
            session.setSummary(summary);
            sessionRepo.save(session);
            log.debug("Updated summary for session={}", sessionId);
        });
    }

    public List<SessionMetadata> getSessionsForUser(String userId) {
        return sessionRepo.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    public long countSessionsForUser(String userId) {
        return sessionRepo.countByUserId(userId);
    }
}
