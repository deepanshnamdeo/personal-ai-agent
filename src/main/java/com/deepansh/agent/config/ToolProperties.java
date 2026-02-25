package com.deepansh.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Strongly-typed configuration for all tools.
 * Bound from application.yml under the "tools" prefix.
 */
@Component
@ConfigurationProperties(prefix = "tools")
@Data
public class ToolProperties {

    private WebSearch webSearch = new WebSearch();
    private RestApiCaller restApiCaller = new RestApiCaller();
    private Database database = new Database();

    @Data
    public static class WebSearch {
        private Brave brave = new Brave();

        @Data
        public static class Brave {
            private String apiKey = "";
            private String baseUrl = "https://api.search.brave.com/res/v1";
            private int maxResults = 5;
        }
    }

    @Data
    public static class RestApiCaller {
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 10000;
        /** Comma-separated allowlist — empty means allow all */
        private String allowedDomains = "";

        public List<String> getAllowedDomainList() {
            if (allowedDomains == null || allowedDomains.isBlank()) return List.of();
            return Arrays.stream(allowedDomains.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
    }

    @Data
    public static class Database {
        private String readableTables = "agent_memories,agent_sessions";
        private String writableTables = "";
        private int maxResultRows = 100;

        public List<String> getReadableTableList() {
            if (readableTables == null || readableTables.isBlank()) return List.of();
            return Arrays.stream(readableTables.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }

        public List<String> getWritableTableList() {
            if (writableTables == null || writableTables.isBlank()) return List.of();
            return Arrays.stream(writableTables.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
    }

    private FileOps fileOps = new FileOps();

    @Data
    public static class FileOps {
        private String baseDirectory = "./agent-files";
        private int maxFileSizeKb = 512;
        private String allowedExtensions = "txt,md,json,csv,yaml,yml,log";

        public List<String> getAllowedExtensionList() {
            return Arrays.stream(allowedExtensions.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
    }

}
