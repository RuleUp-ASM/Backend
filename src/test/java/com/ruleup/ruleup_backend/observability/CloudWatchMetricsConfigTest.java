package com.ruleup.ruleup_backend.observability;

import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudWatchMetricsConfigTest {

    @Test
    @DisplayName("허용 목록에 있는 지표만 CloudWatch 레지스트리에 올라간다 — 나머지는 과금되지 않는다")
    void exportsOnlyAllowListedMeters() {
        CloudWatchMeterRegistry registry = new CloudWatchMetricsConfig()
                .cloudWatchMeterRegistry(mock(CloudWatchAsyncClient.class), "stg");
        try {
            Counter.builder("verification.sync.failed").register(registry).increment();
            Counter.builder("http.server.requests.fake").register(registry).increment();
            registry.gauge("jvm.memory.used.fake", 1.0);

            assertThat(registry.getMeters()).extracting(m -> m.getId().getName())
                    .containsExactly("verification.sync.failed");
        } finally {
            registry.stop();   // close() 는 가짜 클라이언트로 마지막 전송을 시도한다
        }
    }

    @Test
    @DisplayName("서비스 지표(biz.*)는 prod 에서만 CloudWatch 로 나간다 — stg 는 서버 지표만")
    void exportsBusinessMetersOnlyInProd() {
        for (String profile : new String[]{"stg", "prod"}) {
            CloudWatchMeterRegistry registry = new CloudWatchMetricsConfig()
                    .cloudWatchMeterRegistry(mock(CloudWatchAsyncClient.class), profile);
            try {
                Counter.builder("biz.signup").register(registry).increment();
                Counter.builder("verification.sync.failed").register(registry).increment();

                assertThat(registry.getMeters()).extracting(m -> m.getId().getName()).as(profile)
                        .containsExactlyInAnyOrderElementsOf("prod".equals(profile)
                                ? List.of("biz.signup", "verification.sync.failed")
                                : List.of("verification.sync.failed"));
            } finally {
                registry.stop();
            }
        }
    }

    @Test
    @DisplayName("stg·prod 에서는 기본 레지스트리와 CloudWatch 를 묶은 레지스트리가 주입된다 — 로컬 조회는 그대로다")
    void injectsCompositeOfSimpleAndCloudWatch() {
        CloudWatchAsyncClient client = mock(CloudWatchAsyncClient.class);
        when(client.putMetricData(any(PutMetricDataRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutMetricDataResponse.builder().build()));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class))
                .withBean(CloudWatchAsyncClient.class, () -> client)
                .withUserConfiguration(CloudWatchMetricsConfig.class)
                .withPropertyValues("spring.profiles.active=stg")
                .run(ctx -> {
                    MeterRegistry injected = ctx.getBean(MeterRegistry.class);
                    assertThat(injected).isInstanceOf(CompositeMeterRegistry.class);
                    injected.counter("jvm.fake.local").increment();
                    assertThat(ctx.getBean(SimpleMeterRegistry.class).find("jvm.fake.local").counter())
                            .as("허용 목록 밖이어도 /actuator/metrics 에서는 보여야 한다").isNotNull();
                    assertThat(ctx.getBean(CloudWatchMeterRegistry.class).find("jvm.fake.local").counter()).isNull();
                });
        new ApplicationContextRunner()
                .withUserConfiguration(CloudWatchMetricsConfig.class)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CloudWatchMeterRegistry.class));
    }

    @Test
    @DisplayName("허용 목록의 지표 이름은 코드가 실제로 만드는 이름과 같아야 한다(오타면 경보가 영영 비어 있다)")
    void allowListNamesAreProducedByTheApp() throws Exception {
        StringBuilder sources = new StringBuilder();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")
                    && !p.endsWith("CloudWatchMetricsConfig.java")).toList()) {
                sources.append(Files.readString(f));
            }
        }
        assertThat(CloudWatchMetricsConfig.EXPORTED).allSatisfy(name ->
                assertThat(sources).as("지표 %s 를 만드는 코드", name).contains("\"" + name + "\""));
    }
}
