package com.deepansh.agent.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SemanticMemoryServiceTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock EmbeddingService embeddingService;
    @Mock AgentMemoryRepository memoryRepository;

    @InjectMocks
    SemanticMemoryService service;

    @Test
    void embedMemoryAsync_callsEmbeddingAndUpdatesDb() {
        float[] fakeEmbedding = new float[1536];
        when(embeddingService.embed(anyString())).thenReturn(fakeEmbedding);

        service.embedMemoryAsync(42L, "User prefers bullet points");

        verify(embeddingService).embed("User prefers bullet points");
        verify(jdbcTemplate).update(contains("UPDATE agent_memories SET embedding"), any(), eq(42L));
    }

    @Test
    void embedMemoryAsync_embeddingFailure_doesNotThrow() {
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("API down"));
        // Should not throw — embedding failure is best-effort
        service.embedMemoryAsync(1L, "some content");
    }
}
