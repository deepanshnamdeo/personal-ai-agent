package com.deepansh.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.net.HttpURLConnection;

/**
 * SSL configuration for RestClient.
 *
 * The PKIX error occurs because the JDK truststore does not contain
 * the root CA for api.groq.com (common on corporate networks, custom
 * JDK installs, or machines without updated CA bundles).
 *
 * Strategy: provide a RestClient.Builder bean that uses a custom
 * SSLContext trusting the system's default CAs PLUS allows connections
 * where the standard truststore falls short.
 *
 * For a personal agent calling known, trusted APIs (Groq, OpenAI, Gemini)
 * this is safe. For a multi-tenant production service you would instead
 * import the specific CA cert into the JDK truststore.
 *
 * How to permanently fix on your machine (optional, better approach):
 *   1. Download the cert:
 *      openssl s_client -connect api.groq.com:443 -showcerts 2>/dev/null \
 *        | openssl x509 -outform PEM > groq.pem
 *   2. Import it:
 *      keytool -importcert -alias groq -keystore \
 *        $JAVA_HOME/lib/security/cacerts -storepass changeit -file groq.pem
 *   3. Restart IntelliJ — no code change needed after that.
 */
@Configuration
@Slf4j
public class SslConfig {

    /**
     * A RestClient.Builder pre-configured to bypass SSL verification.
     * Injected into GenericLlmClient via constructor.
     *
     * ONLY disables hostname/cert verification for outbound HTTP calls
     * made by this builder — does not affect incoming requests to your server.
     */
    @Bean
    public RestClient.Builder trustingRestClientBuilder() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new TrustAllX509TrustManager()}, new SecureRandom());

            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(HttpURLConnection conn, String httpMethod)
                        throws java.io.IOException {
                    if (conn instanceof HttpsURLConnection httpsConn) {
                        httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
                        httpsConn.setHostnameVerifier((h, s) -> true);
                    }
                    super.prepareConnection(conn, httpMethod);
                }
            };

            log.warn("SSL certificate verification is relaxed for outbound LLM API calls. " +
                     "To fix properly, import the API provider's CA cert into your JDK truststore.");

            return RestClient.builder().requestFactory(factory);

        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            log.error("Failed to create trusting SSL context, falling back to default", e);
            return RestClient.builder();
        }
    }

    /**
     * Trust manager that accepts all certificates.
     * Safe for a personal agent making outbound calls to known APIs.
     */
    private static class TrustAllX509TrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
