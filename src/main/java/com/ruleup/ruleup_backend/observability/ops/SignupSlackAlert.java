package com.ruleup.ruleup_backend.observability.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import tools.jackson.databind.json.JsonMapper;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 새 가입을 운영 Slack {@code #ruleup-alert} 에 알린다.
 *
 * <p>경로는 경보와 같다 — SNS {@code ruleup-<env>-alerts} 에 Amazon Q 커스텀 알림 형식으로 발행하면
 * Amazon Q(구성 ruleup-ops)가 채널에 올린다(infra/monitoring/alerting/README.md). 토픽 ARN 이 비면
 * 아무것도 하지 않는다(로컬·CI). 켜는 법은 infra/monitoring/alerting/signup_alert.py.
 *
 * <p><b>가입을 흔들지 않는다.</b> 가입 트랜잭션이 끝난 뒤 비동기로 보내고, 실패는 로그만 남긴다 —
 * Slack 이 늦거나 SNS 가 막혀도 가입 응답은 그대로다. 알림 한두 건 유실은 수용한다(지표 biz.signup 이 원본).
 *
 * <p>개인정보는 싣지 않는다 — 닉네임·이메일 없이 소셜 종류·누적 회원 수·사용자 ID 만. ID 는 관리자 콘솔 조회용이다.
 */
@Component
public class SignupSlackAlert {

    private static final Logger log = LoggerFactory.getLogger(SignupSlackAlert.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter AT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final String topicArn;
    private final SnsClient sns;
    private final JdbcTemplate jdbc;

    public SignupSlackAlert(@Value("${app.ops-alert.topic-arn:}") String topicArn, JdbcTemplate jdbc) {
        this.topicArn = topicArn.trim();
        // 리전은 ARN 에 들어 있다(arn:aws:sns:<region>:<account>:<name>) — 따로 설정하면 둘이 어긋날 수 있다.
        this.sns = this.topicArn.isEmpty() ? null
                : SnsClient.builder().region(Region.of(this.topicArn.split(":")[3])).build();
        this.jdbc = jdbc;
    }

    @PreDestroy
    void close() {
        if (sns != null) sns.close();
    }

    /** 가입(또는 탈퇴 계정 복귀)이 커밋된 뒤 부른다. */
    @Async
    public void signedUp(String userId, String provider, boolean restored) {
        if (sns == null) return;
        try {
            // 관리자 대시보드의 회원 수와 같은 정의 — 운영자 콘솔 계정은 회원이 아니다(AdminDashboardService).
            Long members = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE status <> 'WITHDRAWN'"
                    + " AND deleted_at IS NULL AND role = 'MEMBER'", Long.class);
            sns.publish(r -> r.topicArn(topicArn)
                    .message(message(envOf(topicArn), provider, restored, members, Instant.now(), userId)));
        } catch (RuntimeException e) {
            log.warn("signup_slack_alert_failed restored={} cause={}", restored, e.toString());
        }
    }

    /** Amazon Q 커스텀 알림 본문 — 형식은 infra/monitoring/alerting/routing.py 의 템플릿과 같다. */
    static String message(String env, String provider, boolean restored, Long members, Instant at, String userId) {
        String title = restored ? ":leftwards_arrow_with_hook: [" + env + "] 탈퇴 계정 복귀" : ":tada: [" + env + "] 새 가입";
        String description = "%s 로그인 · 누적 회원 %s명\n%s KST · `%s`".formatted(
                providerLabel(provider), members == null ? "?" : "%,d".formatted(members), AT.format(at), userId);
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

    /** 토픽 이름 ruleup-<env>-alerts 에서 env 를 뽑는다 — 같은 채널에 stg·prod 가 함께 온다. */
    static String envOf(String topicArn) {
        String name = topicArn.substring(topicArn.lastIndexOf(':') + 1);
        String[] parts = name.split("-");
        return parts.length >= 3 ? parts[1] : name;
    }

    private static String providerLabel(String provider) {
        if (provider == null) return "알 수 없는";
        return switch (provider.toUpperCase()) {
            case "KAKAO" -> "카카오";
            case "NAVER" -> "네이버";
            case "GOOGLE" -> "구글";
            case "APPLE" -> "애플";
            default -> provider;
        };
    }
}
