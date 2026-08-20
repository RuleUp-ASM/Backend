package com.ruleup.ruleup_backend.oauth;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

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
    public RestClient oauthRestClient(MeterRegistry meterRegistry, OAuthHttpProperties properties) {
        var connManager = new PoolingHttpClientConnectionManager();
        connManager.setMaxTotal(50);            // 전체 커넥션 상한
        connManager.setDefaultMaxPerRoute(20);  // 호스트(kauth/kapi 등)별 상한

        connManager.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(properties.responseTimeout().toMillis()))
                .build());

        RequestConfig requestConfig = RequestConfig.custom()
                // 풀 고갈 시 로그인 요청이 기본 대기시간만큼 매달리지 않고 빠르게 502로 수렴한다.
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(
                        properties.connectionRequestTimeout().toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(properties.responseTimeout().toMillis()))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connManager)
                .setDefaultRequestConfig(requestConfig)
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();

        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .requestInterceptor((request, body, execution) -> {
                    Timer.Sample sample = Timer.start(meterRegistry);
                    String outcome = "IO_ERROR";
                    try {
                        var response = execution.execute(request, body);
                        outcome = Integer.toString(response.getStatusCode().value());
                        return response;
                    } finally {
                        // host는 kauth.kakao.com/kapi.kakao.com/oauth2.googleapis.com/www.googleapis.com
                        // 중 하나라 low-cardinality다. 토큰·인가코드·전체 URI는 metric에 넣지 않는다.
                        String host = request.getURI().getHost();
                        sample.stop(Timer.builder("oauth_http_client_duration")
                                .description("OAuth provider HTTP call duration")
                                .tag("host", host != null ? host : "unknown")
                                .tag("method", request.getMethod().name())
                                .tag("outcome", outcome)
                                .register(meterRegistry));
                    }
                })
                .build();
    }
}
