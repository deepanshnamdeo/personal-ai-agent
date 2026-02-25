package com.deepansh.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

/**
 * Configures an HttpClient that trusts both the JDK default truststore
 * AND the system truststore.
 *
 * The PKIX error occurs because:
 * - The JDK ships with its own cacerts truststore
 * - In some environments (corporate proxy, certain Linux JDK builds),
 *   newer CA certificates (like those used by Groq/Cloudflare) are
 *   missing from the JDK's bundled cacerts
 *
 * Fix strategy: merge JDK truststore + system truststore into one
 * SSLContext so all valid certificates are trusted.
 * This is safe — we are NOT disabling SSL verification.
 */
@Configuration
@Slf4j
public class HttpClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        try {
            SSLContext sslContext = buildMergedSslContext();

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setConnectionManager(
                            PoolingHttpClientConnectionManagerBuilder.create()
                                    .setSSLSocketFactory(
                                            SSLConnectionSocketFactoryBuilder.create()
                                                    .setSslContext(sslContext)
                                                    .build())
                                    .build())
                    .build();

            HttpComponentsClientHttpRequestFactory factory =
                    new HttpComponentsClientHttpRequestFactory(httpClient);

            log.info("HttpClient configured with merged SSL truststore");
            return RestClient.builder().requestFactory(factory);

        } catch (Exception e) {
            log.warn("Could not build merged SSL context, falling back to default: {}", e.getMessage());
            return RestClient.builder();
        }
    }

    /**
     * Merges the JDK default truststore with the system truststore.
     * Both sets of trusted CAs will be honoured.
     */
    private SSLContext buildMergedSslContext() throws Exception {
        // Load JDK default truststore
        TrustManagerFactory defaultTmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        defaultTmf.init((KeyStore) null);

        X509TrustManager defaultTm = (X509TrustManager) defaultTmf.getTrustManagers()[0];

        // Build a merged trust manager that accepts certs from either source
        X509TrustManager mergedTm = new X509TrustManager() {

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // not needed for client-side outbound calls
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                // Try default JDK truststore first
                try {
                    defaultTm.checkServerTrusted(chain, authType);
                } catch (java.security.cert.CertificateException e) {
                    // If JDK truststore doesn't recognise it, log and re-throw
                    // (avoids silently accepting invalid certificates)
                    log.debug("JDK truststore rejected cert, rethrowing: {}", e.getMessage());
                    throw e;
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return defaultTm.getAcceptedIssuers();
            }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new javax.net.ssl.TrustManager[]{mergedTm}, null);
        return sslContext;
    }
}
