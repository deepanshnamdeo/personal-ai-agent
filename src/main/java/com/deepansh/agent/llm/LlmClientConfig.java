package com.deepansh.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects and configures the active LLM provider bean.
 *
 * Switch providers with a single env var — no code changes:
 *   LLM_PROVIDER=groq    → Groq (free, llama-3.3-70b)   ← default
 *   LLM_PROVIDER=openai  → OpenAI (GPT-4o)
 *   LLM_PROVIDER=gemini  → Google Gemini (gemini-2.0-flash)
 *
 * All three providers expose an OpenAI-compatible REST API,
 * so the same GenericLlmClient handles all of them.
 *
 * The @Primary bean named "activeLlmClient" is what ResilientLlmClient
 * wraps — no other code needs to change when switching providers.
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
        log.info("  Active LLM Provider: {}  (set LLM_PROVIDER env var to change)", provider.toUpperCase());
        log.info("================================================================");
    }

    @Bean("openAiClient")
    public LlmClient openAiClient(ObjectMapper objectMapper) {
        LlmProviderProperties props = new LlmProviderProperties();
        props.setApiKey(openAiKey);
        props.setBaseUrl(openAiBaseUrl);
        props.setModel(openAiModel);
        props.setMaxTokens(openAiMaxTokens);
        props.setTemperature(openAiTemp);
        return new GenericLlmClient(props, objectMapper, "openai");
    }

    /**
     * The active client — picked based on LLM_PROVIDER.
     * ResilientLlmClient @Primary decorator wraps this bean.
     */
    @Bean("activeLlmClient")
    public LlmClient activeLlmClient(ObjectMapper objectMapper) {
        return switch (provider.toLowerCase()) {
            case "openai" -> {
                validateKey(openAiKey, "openai", "OPENAI_API_KEY", "https://platform.openai.com/api-keys");
                LlmProviderProperties props = new LlmProviderProperties();
                props.setApiKey(openAiKey);
                props.setBaseUrl(openAiBaseUrl);
                props.setModel(openAiModel);
                props.setMaxTokens(openAiMaxTokens);
                props.setTemperature(openAiTemp);
                yield new GenericLlmClient(props, objectMapper, "openai");
            }
            case "gemini" -> {
                validateKey(geminiKey, "gemini", "GEMINI_API_KEY", "https://aistudio.google.com/app/apikey");
                LlmProviderProperties props = new LlmProviderProperties();
                props.setApiKey(geminiKey);
                props.setBaseUrl(geminiBaseUrl);
                props.setModel(geminiModel);
                props.setMaxTokens(geminiMaxTokens);
                props.setTemperature(geminiTemp);
                yield new GenericLlmClient(props, objectMapper, "gemini");
            }
            default -> {  // groq
                validateKey(groqKey, "groq", "GROQ_API_KEY", "https://console.groq.com/keys");
                LlmProviderProperties props = new LlmProviderProperties();
                props.setApiKey(groqKey);
                props.setBaseUrl(groqBaseUrl);
                props.setModel(groqModel);
                props.setMaxTokens(groqMaxTokens);
                props.setTemperature(groqTemp);
                yield new GenericLlmClient(props, objectMapper, "groq");
            }
        };
    }

    private void validateKey(String key, String providerName, String envVar, String signupUrl) {
        if (key == null || key.isBlank()) {
            log.error("================================================================");
            log.error("  {} API key is not set!", providerName.toUpperCase());
            log.error("  Set env var: {}=your-key", envVar);
            log.error("  Get a free key at: {}", signupUrl);
            log.error("================================================================");
        } else {
            log.info("  Model : {}", switch(providerName) {
                case "openai" -> openAiModel;
                case "gemini" -> geminiModel;
                default -> groqModel;
            });
            log.info("  Key   : {}...{}", key.substring(0, Math.min(8, key.length())),
                    key.length() > 8 ? key.substring(key.length() - 4) : "");
        }
    }
}
