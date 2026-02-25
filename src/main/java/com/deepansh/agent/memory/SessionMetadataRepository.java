package com.deepansh.agent.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SessionMetadataRepository extends JpaRepository<SessionMetadata, String> {

    List<SessionMetadata> findByUserIdOrderByUpdatedAtDesc(String userId);

    long countByUserId(String userId);
}
