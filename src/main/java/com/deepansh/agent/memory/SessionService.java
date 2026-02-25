package com.deepansh.agent.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Session lifecycle management backed by MongoDB.
 *
 * Uses MongoTemplate for the upsert (findAndModify with upsert=true)
 * which is more natural in MongoDB than JPA's merge semantics.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SessionService {

    private final SessionMetadataRepository sessionRepo;
    private final MongoTemplate mongoTemplate;

    public SessionMetadata upsertSession(String sessionId, String userId) {
        Query query = new Query(Criteria.where("_id").is(sessionId));
        Update update = new Update()
                .setOnInsert("userId", userId)
                .setOnInsert("turnCount", 0)
                .inc("turnCount", 1);

        mongoTemplate.upsert(query, update, SessionMetadata.class);

        return sessionRepo.findById(sessionId)
                .orElseGet(() -> {
                    log.warn("Session not found after upsert: {}", sessionId);
                    return SessionMetadata.builder()
                            .sessionId(sessionId)
                            .userId(userId)
                            .turnCount(1)
                            .build();
                });
    }

    public void updateSummary(String sessionId, String summary) {
        Query query = new Query(Criteria.where("_id").is(sessionId));
        Update update = new Update().set("summary", summary);
        mongoTemplate.updateFirst(query, update, SessionMetadata.class);
    }

    public List<SessionMetadata> getSessionsForUser(String userId) {
        return sessionRepo.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    public long countSessionsForUser(String userId) {
        return sessionRepo.countByUserId(userId);
    }
}
