package com.ruleup.ruleup_backend.challenge.moderation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${app.moderation.queue.url:}'.trim().isEmpty()")
public class ChallengeModerationQueueConfig {
    @Bean(destroyMethod = "close")
    public SqsClient challengeModerationSqsClient(
            @Value("${app.moderation.queue.region:ap-northeast-2}") String region,
            @Value("${app.moderation.queue.endpoint:}") String endpoint,
            @Value("${app.moderation.queue.access-key:}") String accessKey,
            @Value("${app.moderation.queue.secret-key:}") String secretKey) {
        var builder = SqsClient.builder().region(Region.of(region))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(10)));
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (!accessKey.isBlank()) builder.credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)));
        return builder.build();
    }
}
