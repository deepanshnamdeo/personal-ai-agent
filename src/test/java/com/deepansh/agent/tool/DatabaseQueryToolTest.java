package com.deepansh.agent.tool;

import com.deepansh.agent.config.ToolProperties;
import com.deepansh.agent.tool.impl.DatabaseQueryTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DatabaseQueryToolTest {

    private DatabaseQueryTool tool;
    private ToolProperties props;

    @BeforeEach
    void setUp() {
        props = new ToolProperties();
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        tool = new DatabaseQueryTool(mongoTemplate, props, new ObjectMapper());
    }

    @Test
    void getName_returnsQueryDatabase() {
        assertThat(tool.getName()).isEqualTo("query_database");
    }

    @Test
    void execute_missingOperation_returnsError() {
        String result = tool.execute(Map.of("collection", "agent_memories"));
        assertThat(result).startsWith("ERROR:").contains("operation");
    }

    @Test
    void execute_missingCollection_returnsError() {
        String result = tool.execute(Map.of("operation", "find"));
        assertThat(result).startsWith("ERROR:").contains("collection");
    }

    @ParameterizedTest
    @ValueSource(strings = {"drop", "dropDatabase", "dropCollection", "deleteMany", "deleteOne"})
    void execute_blockedOperations_returnsError(String op) {
        String result = tool.execute(Map.of("operation", op, "collection", "agent_memories"));
        assertThat(result).startsWith("ERROR:").contains("not permitted");
    }

    @Test
    void execute_findFromNonReadableCollection_returnsError() {
        // Default readable: agent_memories, agent_sessions
        String result = tool.execute(Map.of("operation", "find", "collection", "secret_stuff"));
        assertThat(result).startsWith("ERROR:").contains("not in the readable list");
    }

    @Test
    void execute_insertIntoNonWritableCollection_returnsError() {
        // Default writable: empty
        String result = tool.execute(Map.of("operation", "insertOne",
                "collection", "agent_memories",
                "document", "{\"content\": \"test\"}"));
        assertThat(result).startsWith("ERROR:").contains("not in the writable list");
    }

    @Test
    void execute_insertIntoWhitelistedCollection_passesAllowlistCheck() {
        props.getDatabase().setWritableCollections("notes");
        // Will fail at MongoTemplate (mock), but NOT at allowlist check
        String result = tool.execute(Map.of("operation", "insertOne",
                "collection", "notes",
                "document", "{\"content\": \"test\"}"));
        assertThat(result).doesNotContain("not in the writable list");
    }

    @Test
    void inputSchema_hasRequiredFields() {
        Map<String, Object> schema = tool.getInputSchema();
        assertThat(schema.get("required").toString()).contains("operation").contains("collection");
    }
}
