package com.deepansh.agent.tool;

import com.deepansh.agent.config.ToolProperties;
import com.deepansh.agent.tool.impl.DatabaseQueryTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DatabaseQueryToolTest {

    private DatabaseQueryTool tool;
    private ToolProperties props;

    @BeforeEach
    void setUp() {
        props = new ToolProperties();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        tool = new DatabaseQueryTool(jdbcTemplate, props);
    }

    @Test
    void getName_returnsQueryDatabase() {
        assertThat(tool.getName()).isEqualTo("query_database");
    }

    @Test
    void execute_missingSql_returnsError() {
        String result = tool.execute(Map.of());
        assertThat(result).startsWith("ERROR:").contains("sql");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DROP TABLE agent_memories",
            "CREATE TABLE evil (id int)",
            "ALTER TABLE agent_memories ADD COLUMN hack text",
            "TRUNCATE agent_memories",
            "GRANT ALL ON agent_memories TO hacker",
            "-- comment; DROP TABLE agent_memories"
    })
    void execute_blockedStatements_returnError(String sql) {
        String result = tool.execute(Map.of("sql", sql));
        assertThat(result).startsWith("ERROR:").contains("not permitted");
    }

    @Test
    void execute_selectFromNonReadableTable_returnsError() {
        // Default readable tables: agent_memories, agent_sessions
        String result = tool.execute(Map.of("sql", "SELECT * FROM secret_table"));
        assertThat(result).startsWith("ERROR:").contains("not in the readable tables list");
    }

    @Test
    void execute_insertIntoNonWritableTable_returnsError() {
        // Default writable tables: empty
        String result = tool.execute(Map.of("sql", "INSERT INTO agent_memories (content) VALUES ('x')"));
        assertThat(result).startsWith("ERROR:").contains("not in the writable tables list");
    }

    @Test
    void execute_selectFromReadableTable_passesAllowlistCheck() {
        // agent_memories IS in the readable list — should pass the allowlist
        // (will fail at JdbcTemplate mock level, but NOT at allowlist)
        String result = tool.execute(Map.of(
                "sql", "SELECT content, tag FROM agent_memories WHERE user_id = 'test' LIMIT 5"
        ));
        // Should not return an allowlist error
        assertThat(result).doesNotContain("not in the readable tables list");
    }

    @Test
    void execute_insertIntoExplicitlyWritableTable_passesAllowlistCheck() {
        props.getDatabase().setWritableTables("notes");
        String result = tool.execute(Map.of(
                "sql", "INSERT INTO notes (content) VALUES ('test note')"
        ));
        assertThat(result).doesNotContain("not in the writable tables list");
    }

    @Test
    void inputSchema_hasRequiredSqlField() {
        assertThat(tool.getInputSchema().get("required").toString()).contains("sql");
    }
}
