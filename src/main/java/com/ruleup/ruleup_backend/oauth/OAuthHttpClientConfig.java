package com.ruleup.ruleup_backend.oauth;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

/**
 * OAuth(카카오/구글) 외부 호출 전용 RestClient.
 *
 * 기본 RestClient.create()는 매 요청마다 새 TCP+TLS 연결을 맺어 HTTPS 핸드셰이크 비용이 반복된다.
 * 여기서는 커넥션 풀(keep-alive)로 호스트별 연결을 재사용해 반복 로그인 시 지연을 줄인다.
 * 연결/응답 타임아웃도 함께 걸어 카카오가 느릴 때 무한정 매달리는 것을 막는다.
 */
@Configuration
public class OAuthHttpClientConfig {

    @Bean
    public RestClient oauthRestClient() {
        var connManager = new PoolingHttpClientConnectionManager();
        connManager.setMaxTotal(50);            // 전체 커넥션 상한
        connManager.setDefaultMaxPerRoute(20);  // 호스트(kauth/kapi 등)별 상한

        connManager.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(5, TimeUnit.SECONDS)
                .setSocketTimeout(5, TimeUnit.SECONDS)
                .build());

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connManager)
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();

        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
    }
}
