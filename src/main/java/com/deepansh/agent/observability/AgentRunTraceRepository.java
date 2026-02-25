package com.deepansh.agent.observability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AgentRunTraceRepository extends JpaRepository<AgentRunTrace, Long> {

    List<AgentRunTrace> findByUserIdOrderByCreatedAtDesc(String userId);

    List<AgentRunTrace> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    @Query("SELECT t FROM AgentRunTrace t WHERE t.userId = :userId " +
           "AND t.createdAt >= :since ORDER BY t.createdAt DESC")
    List<AgentRunTrace> findRecentByUser(@Param("userId") String userId,
                                          @Param("since") Instant since);

    @Query("SELECT AVG(t.totalLatencyMs) FROM AgentRunTrace t WHERE t.userId = :userId")
    Double avgLatencyForUser(@Param("userId") String userId);

    @Query("SELECT SUM(t.totalTokens) FROM AgentRunTrace t WHERE t.userId = :userId " +
           "AND t.createdAt >= :since")
    Long totalTokensUsedSince(@Param("userId") String userId, @Param("since") Instant since);

    @Query("SELECT t.status, COUNT(t) FROM AgentRunTrace t WHERE t.userId = :userId " +
           "GROUP BY t.status")
    List<Object[]> statusBreakdownForUser(@Param("userId") String userId);
}
