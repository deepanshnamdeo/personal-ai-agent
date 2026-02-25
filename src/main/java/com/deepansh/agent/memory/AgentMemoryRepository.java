package com.deepansh.agent.memory;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentMemoryRepository extends MongoRepository<AgentMemory, String> {

    List<AgentMemory> findByUserIdOrderByCreatedAtDesc(String userId);

    List<AgentMemory> findByUserIdAndTagOrderByCreatedAtDesc(String userId, String tag);

    long countByUserId(String userId);

    /**
     * Case-insensitive regex search across content field.
     * MongoDB equivalent of ILIKE '%keyword%'.
     */
    @Query("{ 'userId': ?0, 'content': { $regex: ?1, $options: 'i' } }")
    List<AgentMemory> searchByKeyword(String userId, String keyword);

    /**
     * Oldest-first — used for cap enforcement eviction.
     */
    List<AgentMemory> findByUserIdOrderByCreatedAtAsc(String userId);

    /**
     * Find memories that have an embedding (for semantic search filtering).
     */
    @Query("{ 'userId': ?0, 'embedding': { $exists: true, $ne: null } }")
    List<AgentMemory> findByUserIdWithEmbedding(String userId);
}
