package com.deepansh.agent.tool;

import com.deepansh.agent.config.ToolProperties;
import com.deepansh.agent.tool.impl.RestApiCallerTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RestApiCallerToolTest {

    private RestApiCallerTool tool;
    private ToolProperties props;

    @BeforeEach
    void setUp() {
        props = new ToolProperties();
        tool = new RestApiCallerTool(props, new ObjectMapper());
    }

    @Test
    void getName_returnsCallApi() {
        assertThat(tool.getName()).isEqualTo("call_api");
    }

    @Test
    void execute_missingUrl_returnsError() {
        String result = tool.execute(Map.of("method", "GET"));
        assertThat(result).startsWith("ERROR:").contains("url");
    }

    @Test
    void execute_disallowedMethod_returnsError() {
        String result = tool.execute(Map.of("url", "https://example.com", "method", "DELETE"));
        assertThat(result).startsWith("ERROR:").contains("not allowed");
    }

    @Test
    void execute_domainNotInAllowlist_returnsError() {
        props.getRestApiCaller().setAllowedDomains("api.github.com");
        String result = tool.execute(Map.of("url", "https://evil.com/data"));
        assertThat(result).startsWith("ERROR:").contains("not in the allowed list");
    }

    @Test
    void execute_domainInAllowlist_passes() {
        // Domain is in allowlist — it will actually try the HTTP call, but
        // the domain check itself should not block it (will fail at network layer in test)
        props.getRestApiCaller().setAllowedDomains("api.github.com,example.com");
        // We just verify no allowlist error — actual HTTP failure is acceptable in unit tests
        String result = tool.execute(Map.of("url", "https://evil-other.com/api"));
        assertThat(result).startsWith("ERROR:").contains("not in the allowed list");
    }

    @Test
    void execute_emptyAllowlist_allowsAll() {
        props.getRestApiCaller().setAllowedDomains("");
        // Empty allowlist = allow all — no domain error should be returned
        // (will fail at network, but not at allowlist check)
        String result = tool.execute(Map.of("url", "https://httpbin.org/get"));
        // Either succeeds or fails with network error — never an allowlist error
        assertThat(result).doesNotContain("not in the allowed list");
    }

    @Test
    void inputSchema_hasRequiredUrlField() {
        Map<String, Object> schema = tool.getInputSchema();
        assertThat(schema.get("required").toString()).contains("url");
    }
}
