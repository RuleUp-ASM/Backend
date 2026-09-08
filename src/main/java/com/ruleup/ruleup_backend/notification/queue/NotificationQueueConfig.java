package com.ruleup.ruleup_backend.notification.queue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;

/**
 * 알림 큐 배선 — <b>{@code app.notification.queue.url} 에 값이 있을 때만</b> 만든다.
 *
 * <p>비어 있으면 {@link NotificationQueueFallbackConfig} 의 로그 폴백으로 떨어지고 푸시가
 * 나가지 않는다. 기동을 막지 않는 이유는 적재가 여전히 정상이기 때문이다 — 알림 센터와
 * 법적 고지는 큐 없이도 성립한다.
 *
 * <p>자격증명은 <b>기본 provider 체인</b>을 쓴다. ECS 에서는 태스크 역할이 자동으로 잡히므로
 * 키를 환경변수에 넣지 않는다. access-key 를 채우는 것은 LocalStack 으로 띄울 때뿐이다.
 */
@Configuration
// ⚠️ @ConditionalOnProperty 를 쓰면 안 된다. 그건 「속성이 존재하고 false 가 아니면」 참이라
//    yaml 의 ${NOTIFICATION_QUEUE_URL:} 기본값인 **빈 문자열도 존재로 본다**. 그러면 큐를 쓰지
//    않는 환경(CI·로컬)에서도 SqsClient 를 만들려다 리전 해석에 실패해 컨텍스트가 통째로 죽는다.
@ConditionalOnExpression("!'${app.notification.queue.url:}'.trim().isEmpty()")
public class NotificationQueueConfig {

    @Bean(destroyMethod = "close")
    public SqsClient notificationSqsClient(
            @Value("${app.notification.queue.region:}") String region,
            @Value("${app.notification.queue.endpoint:}") String endpoint,
            @Value("${app.notification.queue.access-key:}") String accessKey,
            @Value("${app.notification.queue.secret-key:}") String secretKey) {

        SqsClientBuilder builder = SqsClient.builder().region(resolveRegion(region));
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint.trim()));
        if (!accessKey.isBlank()) builder.credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey.trim(), secretKey.trim())));
        return builder.build();
    }

    @Bean
    public NotificationQueue sqsNotificationQueue(
            SqsClient notificationSqsClient,
            @Value("${app.notification.queue.url}") String queueUrl) {
        return new SqsNotificationQueue(notificationSqsClient, queueUrl.trim());
    }

    private Region resolveRegion(String configured) {
        if (!configured.isBlank()) return Region.of(configured.trim());
        return new DefaultAwsRegionProviderChain().getRegion();
    }
}
