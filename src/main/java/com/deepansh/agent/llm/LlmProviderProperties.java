package com.deepansh.agent.llm;

import lombok.Data;

/**
 * Holds config for a single LLM provider.
 * Populated from application.yml for openai / groq / gemini.
 */
@Data
public class LlmProviderProperties {
    private String apiKey;
    private String baseUrl;
    private String model;
    private int maxTokens;
    private double temperature;
}
