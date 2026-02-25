package com.deepansh.agent.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentMemoryRepository extends JpaRepository<AgentMemory, Long> {

    List<AgentMemory> findByUserIdOrderByCreatedAtDesc(String userId);

    List<AgentMemory> findByUserIdAndTagOrderByCreatedAtDesc(String userId, String tag);

    long countByUserId(String userId);

    /**
     * Keyword search across memory content for a given user.
     * Simple ILIKE-based search — good enough before adding pgvector.
     */
    @Query("SELECT m FROM AgentMemory m WHERE m.userId = :userId " +
           "AND LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "ORDER BY m.createdAt DESC")
    List<AgentMemory> searchByKeyword(@Param("userId") String userId,
                                      @Param("keyword") String keyword);

    /**
     * Used to enforce the per-user memory cap — deletes oldest entries first.
     */
    @Query("SELECT m FROM AgentMemory m WHERE m.userId = :userId ORDER BY m.createdAt ASC")
    List<AgentMemory> findOldestByUserId(@Param("userId") String userId);
}
