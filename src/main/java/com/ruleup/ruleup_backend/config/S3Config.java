package com.ruleup.ruleup_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * S3 클라이언트 — {@code app.upload.storage=s3} 일 때만 만든다.
 *
 * <p>자격증명은 <b>기본 provider 체인</b>을 그대로 쓴다. ECS 에서는 태스크 역할
 * ({@code ruleup-stg-ecs-task}) 이 자동으로 잡히므로 키를 환경변수에 넣지 않는다 —
 * 키를 심는 순간 그 값이 태스크 정의와 로그에 남는다.
 *
 * <p>리전도 체인에서 읽는다. ECS 는 {@code AWS_REGION} 을 넣어 주고, 로컬에서 S3 모드로
 * 띄울 때만 {@code app.upload.s3.region} 으로 지정한다.
 */
@Configuration
@ConditionalOnProperty(name = "app.upload.storage", havingValue = "s3")
public class S3Config {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(@Value("${app.upload.s3.region:}") String region,
                             @Value("${app.upload.s3.endpoint:}") String endpoint) {
        S3ClientBuilder builder = S3Client.builder().region(resolveRegion(region));
        applyEndpoint(endpoint, builder::endpointOverride, builder::serviceConfiguration);
        return builder.build();
    }

    /**
     * presigner 는 S3Client 와 별개 객체다. 같은 리전·자격증명을 쓰지만 서명만 하고
     * 네트워크를 타지 않으므로, 링크를 만들 때 S3 왕복이 발생하지 않는다.
     */
    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(@Value("${app.upload.s3.region:}") String region,
                                   @Value("${app.upload.s3.endpoint:}") String endpoint) {
        S3Presigner.Builder builder = S3Presigner.builder().region(resolveRegion(region));
        applyEndpoint(endpoint, builder::endpointOverride, builder::serviceConfiguration);
        return builder.build();
    }

    /**
     * 엔드포인트 재정의 — 비어 있으면 실제 AWS 다. LocalStack·MinIO 처럼 가상 호스트 방식
     * (bucket.host)을 지원하지 않는 구현을 쓸 때만 채우며, 그때는 <b>path-style 을 함께 켜야</b>
     * 한다. 안 켜면 SDK 가 {@code bucket.localhost} 로 붙으려다 이름을 못 찾는다.
     */
    private void applyEndpoint(String endpoint,
                               java.util.function.Consumer<URI> endpointSetter,
                               java.util.function.Consumer<S3Configuration> configSetter) {
        if (endpoint == null || endpoint.isBlank()) return;
        endpointSetter.accept(URI.create(endpoint.trim()));
        configSetter.accept(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    }

    private Region resolveRegion(String configured) {
        if (configured != null && !configured.isBlank()) return Region.of(configured.trim());
        return new DefaultAwsRegionProviderChain().getRegion();
    }
}
