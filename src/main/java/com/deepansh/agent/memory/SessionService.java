package com.deepansh.agent.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Session lifecycle management backed by MongoDB.
 *
 * Fix: MongoDB error code 40 (path conflict) occurred because the previous
 * upsert used both $setOnInsert and $inc on the same 'turnCount' field.
 * MongoDB does not allow two update operators to touch the same field path
 * in a single operation.
 *
 * Solution: $setOnInsert only sets fields that $inc never touches (userId).
 * $inc: {turnCount: 1} handles both cases correctly:
 *   - New document (insert): field doesn't exist → MongoDB initialises to 0, then adds 1 → result: 1
 *   - Existing document (update): increments existing value → result: n+1
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
                .setOnInsert("userId", userId)   // only set userId on first insert
                .inc("turnCount", 1);            // $inc alone: 0→1 on insert, n→n+1 on update
                                                 // NO $setOnInsert for turnCount — that was the conflict

        FindAndModifyOptions options = FindAndModifyOptions.options()
                .upsert(true)
                .returnNew(true);

        SessionMetadata result = mongoTemplate.findAndModify(
                query, update, options, SessionMetadata.class);

        if (result == null) {
            // Fallback — should not happen with returnNew(true) but be defensive
            log.warn("findAndModify returned null for session={}, building fallback", sessionId);
            return SessionMetadata.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .turnCount(1)
                    .build();
        }

        log.debug("Session upserted [sessionId={}, userId={}, turnCount={}]",
                sessionId, userId, result.getTurnCount());
        return result;
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
