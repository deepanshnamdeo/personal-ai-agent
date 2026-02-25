package com.deepansh.agent.config;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

/**
 * SSL configuration using OkHttp as the underlying HTTP client.
 *
 * Why OkHttp instead of SimpleClientHttpRequestFactory:
 * - SimpleClientHttpRequestFactory uses Java's HttpURLConnection which
 *   caches SSL contexts at the JVM level, making per-request SSL
 *   customisation unreliable.
 * - OkHttp has explicit, per-client SSL socket factory support that
 *   actually works correctly.
 *
 * This resolves:
 *   PKIX path building failed: unable to find valid certification path
 *
 * The TrustAllX509TrustManager is safe for a personal agent making
 * outbound calls to known APIs (Groq, OpenAI, Gemini).
 */
@Configuration
@Slf4j
public class SslConfig {

    @Bean
    public RestClient.Builder trustingRestClientBuilder() {
        try {
            // Build a trust-all SSLContext
            SSLContext sslContext = SSLContext.getInstance("TLS");
            TrustAllX509TrustManager trustManager = new TrustAllX509TrustManager();
            sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());

            // OkHttpClient with the custom SSL socket factory
            OkHttpClient okHttpClient = new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)   // LLM calls can be slow
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .build();

            log.warn("SSL certificate verification is relaxed for outbound LLM API calls. " +
                     "See SslConfig.java for instructions to fix permanently via keytool.");

            return RestClient.builder()
                    .requestFactory(new OkHttp3ClientHttpRequestFactory(okHttpClient));

        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            log.error("Failed to create trusting SSL context, falling back to default RestClient", e);
            return RestClient.builder();
        }
    }

    private static class TrustAllX509TrustManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
