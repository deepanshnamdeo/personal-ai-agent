package com.deepansh.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Factory that creates the active LLM client based on LLM_PROVIDER env var.
 * Injects the SSL-trusting RestClient.Builder to avoid PKIX errors.
 */
@Configuration
@Slf4j
public class LlmClientConfig {

    @Value("${llm.provider:groq}")
    private String provider;

    // OpenAI
    @Value("${openai.api-key:}") private String openAiKey;
    @Value("${openai.base-url}") private String openAiBaseUrl;
    @Value("${openai.model}")    private String openAiModel;
    @Value("${openai.max-tokens}") private int openAiMaxTokens;
    @Value("${openai.temperature}") private double openAiTemp;

    // Groq
    @Value("${groq.api-key:}") private String groqKey;
    @Value("${groq.base-url}") private String groqBaseUrl;
    @Value("${groq.model}")    private String groqModel;
    @Value("${groq.max-tokens}") private int groqMaxTokens;
    @Value("${groq.temperature}") private double groqTemp;

    // Gemini
    @Value("${gemini.api-key:}") private String geminiKey;
    @Value("${gemini.base-url}") private String geminiBaseUrl;
    @Value("${gemini.model}")    private String geminiModel;
    @Value("${gemini.max-tokens}") private int geminiMaxTokens;
    @Value("${gemini.temperature}") private double geminiTemp;

    @PostConstruct
    public void logActiveProvider() {
        log.info("================================================================");
        log.info("  Active LLM Provider : {}", provider.toUpperCase());
        log.info("  Model               : {}", activeModel());
        log.info("================================================================");
    }

    /**
     * The active LLM client — selected by LLM_PROVIDER.
     * Wrapped by ResilientLlmClient with retry + circuit breaker.
     */
    @Bean("activeLlmClient")
    public LlmClient activeLlmClient(
            ObjectMapper objectMapper,
            @Qualifier("trustingRestClientBuilder") RestClient.Builder builder) {

        return switch (provider.toLowerCase()) {
            case "openai" -> {
                logKey("OPENAI", openAiKey, "OPENAI_API_KEY", "https://platform.openai.com/api-keys");
                yield new GenericLlmClient(openAiProps(), objectMapper, "openai", builder.clone());
            }
            case "gemini" -> {
                logKey("GEMINI", geminiKey, "GEMINI_API_KEY", "https://aistudio.google.com/app/apikey");
                yield new GenericLlmClient(geminiProps(), objectMapper, "gemini", builder.clone());
            }
            default -> { // groq
                logKey("GROQ", groqKey, "GROQ_API_KEY", "https://console.groq.com/keys");
                yield new GenericLlmClient(groqProps(), objectMapper, "groq", builder.clone());
            }
        };
    }

    /**
     * Kept for backward compat — ResilientLlmClient no longer uses this
     * directly, but having it avoids NoSuchBeanDefinitionException if
     * anything else references "openAiClient".
     */
    @Bean("openAiClient")
    public LlmClient openAiClient(
            ObjectMapper objectMapper,
            @Qualifier("trustingRestClientBuilder") RestClient.Builder builder) {
        return new GenericLlmClient(openAiProps(), objectMapper, "openai", builder.clone());
    }

    // ─── Props builders ───────────────────────────────────────────────────────

    private LlmProviderProperties openAiProps() {
        LlmProviderProperties p = new LlmProviderProperties();
        p.setApiKey(openAiKey); p.setBaseUrl(openAiBaseUrl); p.setModel(openAiModel);
        p.setMaxTokens(openAiMaxTokens); p.setTemperature(openAiTemp);
        return p;
    }

    private LlmProviderProperties groqProps() {
        LlmProviderProperties p = new LlmProviderProperties();
        p.setApiKey(groqKey); p.setBaseUrl(groqBaseUrl); p.setModel(groqModel);
        p.setMaxTokens(groqMaxTokens); p.setTemperature(groqTemp);
        return p;
    }

    private LlmProviderProperties geminiProps() {
        LlmProviderProperties p = new LlmProviderProperties();
        p.setApiKey(geminiKey); p.setBaseUrl(geminiBaseUrl); p.setModel(geminiModel);
        p.setMaxTokens(geminiMaxTokens); p.setTemperature(geminiTemp);
        return p;
    }

    private String activeModel() {
        return switch (provider.toLowerCase()) {
            case "openai" -> openAiModel;
            case "gemini" -> geminiModel;
            default -> groqModel;
        };
    }

    private void logKey(String name, String key, String envVar, String signupUrl) {
        if (key == null || key.isBlank()) {
            log.error("  {} API key not set! Set env var: {}={your-key}", name, envVar);
            log.error("  Get a free key at: {}", signupUrl);
        } else {
            log.info("  Key: {}...{}", key.substring(0, Math.min(8, key.length())),
                    key.length() > 8 ? key.substring(key.length() - 4) : "");
        }
    }
}
