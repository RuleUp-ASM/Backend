package com.ruleup.ruleup_backend.observability.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 새 가입을 운영 Slack {@code #ruleup-alert} 에 알린다. 경로는 {@link OpsSlackChannel}.
 *
 * <p><b>가입을 흔들지 않는다.</b> 가입 트랜잭션이 끝난 뒤 비동기로 보내고, 실패는 로그만 남긴다 —
 * Slack 이 늦거나 SNS 가 막혀도 가입 응답은 그대로다. 알림 한두 건 유실은 수용한다(지표 biz.signup 이 원본).
 *
 * <p>개인정보는 싣지 않는다 — 닉네임·이메일 없이 소셜 종류·누적 회원 수·사용자 ID 만. ID 는 관리자 콘솔 조회용이다.
 */
@Component
public class SignupSlackAlert {

    private static final Logger log = LoggerFactory.getLogger(SignupSlackAlert.class);
    private static final DateTimeFormatter AT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final OpsSlackChannel slack;
    private final JdbcTemplate jdbc;

    public SignupSlackAlert(OpsSlackChannel slack, JdbcTemplate jdbc) {
        this.slack = slack;
        this.jdbc = jdbc;
    }

    /** 가입(또는 탈퇴 계정 복귀)이 커밋된 뒤 부른다. */
    @Async
    public void signedUp(String userId, String provider, boolean restored) {
        if (!slack.enabled()) return;
        try {
            // 관리자 대시보드의 회원 수와 같은 정의 — 운영자 콘솔 계정은 회원이 아니다(AdminDashboardService).
            Long members = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE status <> 'WITHDRAWN'"
                    + " AND deleted_at IS NULL AND role = 'MEMBER'", Long.class);
            slack.post(title(slack.env(), restored), description(provider, members, Instant.now(), userId));
        } catch (RuntimeException e) {
            log.warn("signup_slack_alert_failed restored={} cause={}", restored, e.toString());
        }
    }

    static String title(String env, boolean restored) {
        return restored ? ":leftwards_arrow_with_hook: [" + env + "] 탈퇴 계정 복귀" : ":tada: [" + env + "] 새 가입";
    }

    static String description(String provider, Long members, Instant at, String userId) {
        return "%s 로그인 · 누적 회원 %s명\n%s KST · `%s`".formatted(
                providerLabel(provider), members == null ? "?" : "%,d".formatted(members), AT.format(at), userId);
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
