package com.ruleup.ruleup_backend.observability.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import tools.jackson.databind.json.JsonMapper;

import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 운영 Slack {@code #ruleup-alert} 로 앱이 직접 보내는 알림의 공용 출구.
 *
 * <p>경로는 경보와 같다 — SNS {@code ruleup-<env>-alerts} 에 Amazon Q 커스텀 알림 형식으로 발행하면
 * Amazon Q(구성 ruleup-ops)가 채널에 올린다(infra/monitoring/alerting/README.md). 토픽 ARN 이 비면
 * 아무것도 하지 않는다(로컬·CI). 켜는 법은 infra/monitoring/alerting/signup_alert.py.
 *
 * <p>호출자는 요청을 흔들지 않도록 비동기로 부르고 실패는 로그만 남긴다 — 알림 한두 건 유실은 수용한다.
 */
@Component
public class OpsSlackChannel {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String topicArn;
    private final String env;
    private final SnsClient sns;

    public OpsSlackChannel(@Value("${app.ops-alert.topic-arn:}") String topicArn) {
        this.topicArn = topicArn.trim();
        this.env = this.topicArn.isEmpty() ? "" : envOf(this.topicArn);
        // 리전은 ARN 에 들어 있다(arn:aws:sns:<region>:<account>:<name>) — 따로 설정하면 둘이 어긋날 수 있다.
        this.sns = this.topicArn.isEmpty() ? null
                : SnsClient.builder().region(Region.of(this.topicArn.split(":")[3])).build();
    }

    @PreDestroy
    void close() {
        if (sns != null) sns.close();
    }

    public boolean enabled() {
        return sns != null;
    }

    /** 제목에 붙이는 환경 이름 — 같은 채널에 stg·prod 가 함께 온다. */
    public String env() {
        return env;
    }

    /** 발행한다. 꺼져 있으면 아무것도 하지 않는다. SNS 오류는 호출자에게 그대로 던진다. */
    public void post(String title, String description) {
        if (sns == null) return;
        sns.publish(r -> r.topicArn(topicArn).message(body(title, description)));
    }

    /** Amazon Q 커스텀 알림 본문 — 형식은 infra/monitoring/alerting/routing.py 의 템플릿과 같다. */
    static String body(String title, String description) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("textType", "client-markdown");
        content.put("title", title);
        content.put("description", description);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", "1.0");
        body.put("source", "custom");
        body.put("content", content);
        return JSON.writeValueAsString(body);
    }

    /** 토픽 이름 ruleup-<env>-alerts 에서 env 를 뽑는다. */
    static String envOf(String topicArn) {
        String name = topicArn.substring(topicArn.lastIndexOf(':') + 1);
        String[] parts = name.split("-");
        return parts.length >= 3 ? parts[1] : name;
    }
}
