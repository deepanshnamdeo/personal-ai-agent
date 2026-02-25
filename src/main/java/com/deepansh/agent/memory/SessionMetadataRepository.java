package com.deepansh.agent.memory;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SessionMetadataRepository extends MongoRepository<SessionMetadata, String> {

    List<SessionMetadata> findByUserIdOrderByUpdatedAtDesc(String userId);

    long countByUserId(String userId);
}
