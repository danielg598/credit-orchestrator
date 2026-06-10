package co.lumen.credit.credit_orchestrator.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
/**
 * Declara dos RestClients separados, cada uno con su baseUrl, timeouts y headers.
 * Usamos Apache HttpComponents Client 5 como factory HTTP en lugar del
 * JDK HttpClient por dos motivos:
 *   1) El JDK Client tiene bugs reportados con POST body + Content-Type en
 *      Spring Boot 3.5.x (el body se envía vacío en algunos entornos).
 *   2) Apache HC5 es el estándar probado en entornos bancarios de producción
 *      (control granular de connection pool, retries, proxy, TLS).
 */
@Configuration
public class RestClientConfig {

    private static CloseableHttpClient buildHttpClient(int connectTimeoutSec, int readTimeoutSec) {
        RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(connectTimeoutSec))
                .setResponseTimeout(Timeout.ofSeconds(readTimeoutSec))
                .setConnectionRequestTimeout(Timeout.ofSeconds(connectTimeoutSec))
                .build();
        return HttpClients.custom()
                .setDefaultRequestConfig(cfg)
                .build();
    }

    /**
     * Cliente HTTP hacia el microservicio de IA (FastAPI Python).
     */
    @Bean("aiRestClient")
    RestClient aiRestClient(RestClient.Builder builder,
                            @Value("${app.ai.base-url}") String baseUrl) {
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(buildHttpClient(2, 3));
        return builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Cliente HTTP hacia Vault Core (Mock local o instancia real blx-demo).
     * IMPORTANTE: Thought Machine usa header 'X-Auth-Token' custom,
     *             NO es Bearer, NO es JWT. Si lo pones como Bearer, falla.
     */
    @Bean("vaultRestClient")
    RestClient vaultRestClient(
            RestClient.Builder builder,
            @Value("${app.vault.base-url}") String baseUrl,
            @Value("${app.vault.auth-token}") String authToken) {
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(buildHttpClient(3, 5));
        return builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Auth-Token", authToken)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
