package com.deepansh.agent.observability;

import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AgentRunTraceRepository extends MongoRepository<AgentRunTrace, String> {

    List<AgentRunTrace> findByUserIdOrderByCreatedAtDesc(String userId);

    List<AgentRunTrace> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    @Query("{ 'userId': ?0, 'createdAt': { $gte: ?1 } }")
    List<AgentRunTrace> findRecentByUser(String userId, Instant since);

    @Aggregation(pipeline = {
        "{ $match: { 'userId': ?0 } }",
        "{ $group: { _id: null, avg: { $avg: '$totalLatencyMs' } } }"
    })
    Double avgLatencyForUser(String userId);

    @Aggregation(pipeline = {
        "{ $match: { 'userId': ?0, 'createdAt': { $gte: ?1 } } }",
        "{ $group: { _id: null, total: { $sum: '$totalTokens' } } }"
    })
    Long totalTokensUsedSince(String userId, Instant since);

    @Aggregation(pipeline = {
        "{ $match: { 'userId': ?0 } }",
        "{ $group: { _id: '$status', count: { $sum: 1 } } }"
    })
    List<StatusCount> statusBreakdownForUser(String userId);

    record StatusCount(String id, long count) {}
}
