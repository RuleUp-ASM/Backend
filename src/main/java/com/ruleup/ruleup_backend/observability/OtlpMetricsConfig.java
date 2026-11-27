package com.ruleup.ruleup_backend.observability;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Micrometer 지표를 OTLP 로 내보낸다 — Grafana Cloud(직접 또는 같은 서버의 Alloy 경유).
 *
 * <p>{@link CloudWatchMetricsConfig} 와 나란히 켤 수 있다(옮기는 동안 양쪽에 쌓는다). 내보내는 이름은 CloudWatch 의
 * {@link CloudWatchMetricsConfig#EXPORTED 허용 목록}에 런타임 기본 지표({@link #RUNTIME_PREFIXES})를 더한 것이다.
 * Grafana 는 이름이 아니라 시계열 수로 한도를 세므로 JVM·풀·스레드는 보내도 되지만, {@code http.server.requests} 처럼
 * 경로·상태 조합으로 시계열이 불어나는 것은 보내지 않는다 — 요청 지표는 SLI 버퍼가 맡는다.
 *
 * <p>부트의 OTLP 자동 구성은 끈다({@code management.otlp.metrics.export.enabled=false}). 켜 두면 허용 목록 없이
 * 모든 지표를, 꺼 둔 환경에서도 localhost 로 보내려 든다.
 */
@Configuration
@ConditionalOnProperty(name = "app.observability.otlp.enabled", havingValue = "true")
public class OtlpMetricsConfig {

    static final List<String> RUNTIME_PREFIXES = List.of("jvm.", "process.", "system.", "hikaricp.", "tomcat.", "executor.");

    @Bean
    public OtlpMeterRegistry otlpMeterRegistry(@Value("${app.observability.otlp.endpoint}") String endpoint,
                                               @Value("${app.observability.otlp.authorization:}") String authorization,
                                               @Value("${spring.profiles.active:local}") String profile) {
        String env = profile.split(",")[0].trim();
        Map<String, String> props = new HashMap<>();
        props.put("otlp.url", endpoint.replaceAll("/+$", "") + "/v1/metrics");
        props.put("otlp.step", Duration.ofMinutes(1).toString());
        props.put("otlp.resourceAttributes",
                "service.name=ruleup-api,deployment.environment=" + env + ",service.instance.id=" + hostname());
        if (!authorization.isBlank()) props.put("otlp.headers", "Authorization=" + authorization);
        OtlpConfig config = props::get;
        OtlpMeterRegistry registry = new OtlpMeterRegistry(config, Clock.SYSTEM);
        registry.config().meterFilter(MeterFilter.denyUnless(id -> exported(id.getName(), env)));
        return registry;
    }

    /** CloudWatch 를 끈 뒤에도 {@code /actuator/metrics} 와 지표 조회가 비지 않게 — 켜져 있으면 그쪽 설정이 만든다. */
    @Bean
    @ConditionalOnProperty(name = "app.observability.cloudwatch.enabled", havingValue = "false")
    public SimpleMeterRegistry otlpOnlySimpleMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    static boolean exported(String name, String env) {
        if (RUNTIME_PREFIXES.stream().anyMatch(name::startsWith)) return true;
        return CloudWatchMetricsConfig.EXPORTED.contains(name)
                && ("prod".equals(env) || !name.startsWith(CloudWatchMetricsConfig.BUSINESS_PREFIX));
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
