package com.deepansh.agent.tool;

import com.deepansh.agent.config.ToolProperties;
import com.deepansh.agent.tool.impl.WebSearchTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchToolTest {

    private WebSearchTool tool;
    private ToolProperties props;

    @BeforeEach
    void setUp() {
        props = new ToolProperties();
        tool = new WebSearchTool(props, new ObjectMapper());
    }

    @Test
    void getName_returnsWebSearch() {
        assertThat(tool.getName()).isEqualTo("web_search");
    }

    @Test
    void execute_missingApiKey_returnsErrorString() {
        props.getWebSearch().getBrave().setApiKey("");
        String result = tool.execute(Map.of("query", "Spring Boot tips"));
        assertThat(result).startsWith("ERROR:").contains("BRAVE_API_KEY");
    }

    @Test
    void execute_missingQuery_returnsErrorString() {
        props.getWebSearch().getBrave().setApiKey("fake-key");
        String result = tool.execute(Map.of());
        assertThat(result).startsWith("ERROR:").contains("query");
    }

    @Test
    void inputSchema_hasRequiredQueryField() {
        Map<String, Object> schema = tool.getInputSchema();
        assertThat(schema).containsKey("required");
        assertThat(schema.get("required").toString()).contains("query");
    }
}
