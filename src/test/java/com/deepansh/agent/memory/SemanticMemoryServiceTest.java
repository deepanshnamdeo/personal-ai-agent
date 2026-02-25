package com.deepansh.agent.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SemanticMemoryServiceTest {

    @Mock MongoTemplate mongoTemplate;
    @Mock EmbeddingService embeddingService;
    @Mock AgentMemoryRepository memoryRepository;

    @InjectMocks
    SemanticMemoryService service;

    @Test
    void embedMemoryAsync_callsEmbeddingAndUpdatesDocument() {
        float[] fakeEmbedding = new float[1536];
        when(embeddingService.embed(anyString())).thenReturn(fakeEmbedding);

        service.embedMemoryAsync("memory-id-123", "User prefers bullet points");

        verify(embeddingService).embed("User prefers bullet points");
        verify(mongoTemplate).updateFirst(any(), any(), eq(AgentMemory.class));
    }

    @Test
    void embedMemoryAsync_embeddingFailure_doesNotThrow() {
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("API down"));
        // Should not throw — embedding failure is best-effort
        service.embedMemoryAsync("id-1", "some content");
    }

    @Test
    void semanticSearch_noEmbeddedMemories_fallsBackToKeywordSearch() {
        when(memoryRepository.findByUserIdWithEmbedding("user1")).thenReturn(List.of());
        when(memoryRepository.searchByKeyword("user1", "preferences")).thenReturn(List.of());
        when(embeddingService.embed(anyString())).thenReturn(new float[1536]);

        List<AgentMemory> result = service.semanticSearch("user1", "preferences");

        assertThat(result).isEmpty();
        verify(memoryRepository).searchByKeyword("user1", "preferences");
    }
}
